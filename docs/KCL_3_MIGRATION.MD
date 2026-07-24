# Migrating enrich-kinesis / enrich-eventbridge from KCL 2.x to KCL 3.5

This document is for maintainers operating `enrich-kinesis` / `enrich-eventbridge` deployments that
are moving from `amazon-kinesis-client` 2.7.3 to 3.5.0 (this repo skips the 3.0–3.4 line and jumps
straight to 3.5, which uses the **single table format** by default for new 2.x → 3.5 migrations).

It covers:
1. What changed in the codebase and config
2. The rollout procedure (application-side)
3. AWS-side prerequisites (IAM, EC2/ECS/EKS metadata requirements)
4. CloudWatch metrics/alarms worth watching during and after migration
5. A note on the `MetricsLevel` vs metric-dimensions distinction (and why this migration doesn't
   change either)

Reference: [AWS docs - Migrate from KCL 2.x to KCL 3.x](https://docs.aws.amazon.com/streams/latest/dev/kcl-migration-from-2-3.html),
[Single table format for KCL](https://docs.aws.amazon.com/streams/latest/dev/kcl-single-table-format.html),
[IAM permissions for KCL](https://docs.aws.amazon.com/streams/latest/dev/kcl-iam-permissions.html),
[Monitor KCL with CloudWatch](https://docs.aws.amazon.com/streams/latest/dev/monitoring-with-kcl.html).

---

## 1. What changed in this codebase

- `amazon-kinesis-client` bumped to `3.5.0` (`project/Dependencies.scala`).
- A new input config field, `clientVersionConfig`, was added to `Input.Kinesis`
  (`modules/common-fs2/.../config/io.scala`). It maps directly onto
  `CoordinatorConfig.ClientVersionConfig` and controls the 2.x/3.x compatibility mode:
  - `COMPATIBLE_WITH_2X_PHASE1` (default in `application.conf`) — behaves identically to KCL 2.x;
    migration does **not** start yet. Safe to deploy/rollback freely.
  - `COMPATIBLE_WITH_2X` — starts the actual migration to the KCL 3.x lease-balancing algorithm.
  - `3X` — full KCL 3.x mode. KCL switches to this automatically once migration completes; you
    should not normally need to set it explicitly.
- `Source.scala` (in `kinesis` and `eventbridge` modules) wires this into
  `configsBuilder.coordinatorConfig.clientVersionConfig(...)`.
- `MetricsConfig.metricsLevel` behaviour is unchanged from before this migration: `DETAILED` when
  `monitoring.metrics.cloudwatch = true`, `NONE` otherwise. See [section 5](#5-a-note-on-metricslevel-vs-metricsenableddimensions)
  for a discussion of a *separate*, optional CloudWatch-dimension change that is **not** part of
  this migration.
- Because this is a **direct** 2.x → 3.5 migration (not 2.x → 3.0-3.4 → 3.5), single table format is
  used from day one. `ConfigsBuilder` still uses `appName` as the lease table name, so your existing
  lease table is reused as-is; KCL layers worker-metrics/coordinator-state entries into that same
  table (distinguished by an `entityType` attribute) instead of creating separate tables. **No lease
  table schema migration is required and no separate lease-stealing/duplicate-processing risk is
  introduced by the table structure itself** — the risk is purely in the coordination protocol
  during the phase1 → phase2 switch below.

## 2. Rollout procedure

> **Critical**: all workers of a given KCL application must run the *same* `clientVersionConfig`
> mode at (almost) the same time. KCL 2.x and KCL 3.x use different lease-balancing algorithms;
> running a mix for an extended period causes them to fight over lease ownership, which is the
> actual source of duplicate/re-processed records during migration — not the DynamoDB table
> structure.

### Phase 0 — Deploy KCL 3.5 code, `COMPATIBLE_WITH_2X_PHASE1` (already the default)

- Deploy the new build (this repo already defaults `clientVersionConfig` to
  `COMPATIBLE_WITH_2X_PHASE1` in `application.conf`).
- Behaviour is functionally identical to KCL 2.7.3. This is safe to roll out gradually
  (canary/rolling deploy) and safe to roll back at any time.
- Confirm IAM permissions are updated first (see [section 3](#3-aws-side-prerequisites)) — the new
  code will fail on lease-table `UpdateTable`/GSI `Query` calls otherwise.

### Phase 1 — Bake and confirm

- Let all workers run on `COMPATIBLE_WITH_2X_PHASE1` for a while (at least one full deploy cycle
  across your whole fleet).
- Watch the `Migration` operation metrics in CloudWatch (namespace = your KCL `appName`):
  - `CurrentState:2xCompatibleWorker` (Sum) should equal your total worker count.
  - `Fault` should be at/near zero.
- Do **not** proceed to Phase 2 until every worker is confirmed running the new code.

### Phase 2 — Flip to `COMPATIBLE_WITH_2X` to start the migration

- Change `input.clientVersionConfig` to `COMPATIBLE_WITH_2X` and roll out to **all** workers.
- This is what actually triggers the switch to the KCL 3.x resource-utilization-based
  lease-balancing algorithm.
- A rolling deploy is fine, but complete it promptly — don't leave the fleet in a mixed state for
  a long time.

### Phase 3 — Watch migration complete

- Keep watching the `Migration` operation metrics:
  - `CurrentState:3xWorker` (Sum) rises as workers switch over.
  - When `CurrentState:3xWorker` equals your total worker count, migration is functionally
    complete on the coordination side.
  - Allow **at least 10 minutes** after that point before considering it fully settled — KCL needs
    this time to finish switching every worker to the new algorithm.
  - `GsiStatusReady` should show the lease table GSI is available (required for the new
    `LeaseDiscovery` operation).

### Phase 4 — Leave it at `COMPATIBLE_WITH_2X` (or optionally clean up to `3X`)

- You do not need to manually set `3X`; KCL detects completion and behaves like KCL 3.x internally
  regardless. Leaving the config at `COMPATIBLE_WITH_2X` is fine indefinitely.
- Optionally, once you're confident the fleet has fully migrated and you don't need the ability to
  roll back to 2.x-compatible behaviour, update the default in `application.conf` /
  `config/config.kinesis.extended.hocon` / `config/config.eventbridge.extended.hocon` from
  `COMPATIBLE_WITH_2X_PHASE1` to `COMPATIBLE_WITH_2X` so fresh environments skip phase 1.

### Rollback considerations

- **Phase 0/1** (`COMPATIBLE_WITH_2X_PHASE1`): safe to roll back to KCL 2.7.3 code at any time.
- **Phase 2** (`COMPATIBLE_WITH_2X`, migration in progress): you can revert the config back to
  `COMPATIBLE_WITH_2X_PHASE1` and workers will fall back to KCL 2.x-style balancing.
- **After full migration completes**: rollback to 2.x-compatible behaviour is no longer meaningful.

---

## 3. AWS-side prerequisites

### 3.1 IAM permissions

KCL 3.x needs a few more IAM actions than 2.x did. Update the IAM role/policy attached to your
enrich workers **before** deploying the new code. Replace `REGION`, `ACCOUNT_ID`, `STREAM_NAME` and
`KCL_APPLICATION_NAME` (this is `input.appName` in enrich's config, i.e. the DynamoDB lease table
name) with your actual values:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "kinesis:DescribeStream",
        "kinesis:DescribeStreamSummary",
        "kinesis:RegisterStreamConsumer",
        "kinesis:GetRecords",
        "kinesis:GetShardIterator",
        "kinesis:ListShards"
      ],
      "Resource": "arn:aws:kinesis:REGION:ACCOUNT_ID:stream/STREAM_NAME"
    },
    {
      "Effect": "Allow",
      "Action": [
        "kinesis:SubscribeToShard",
        "kinesis:DescribeStreamConsumer"
      ],
      "Resource": "arn:aws:kinesis:REGION:ACCOUNT_ID:stream/STREAM_NAME/consumer/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:CreateTable",
        "dynamodb:DescribeTable",
        "dynamodb:UpdateTable",
        "dynamodb:GetItem",
        "dynamodb:UpdateItem",
        "dynamodb:PutItem",
        "dynamodb:DeleteItem",
        "dynamodb:Scan"
      ],
      "Resource": [
        "arn:aws:dynamodb:REGION:ACCOUNT_ID:table/KCL_APPLICATION_NAME"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "dynamodb:Query"
      ],
      "Resource": [
        "arn:aws:dynamodb:REGION:ACCOUNT_ID:table/KCL_APPLICATION_NAME/index/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "cloudwatch:PutMetricData"
      ],
      "Resource": "*"
    }
  ]
}
```

Notes:
- The `dynamodb:UpdateTable` and GSI `dynamodb:Query` actions are **new requirements versus KCL
  2.x** — 2.x never needed to alter the lease table schema or query a GSI on it. Missing these is
  the most common cause of KCL 3.x workers failing to start.
- Because this repo migrates straight to **single table format**, you do **not** need the separate
  `KCL_APPLICATION_NAME-WorkerMetricStats` / `KCL_APPLICATION_NAME-CoordinatorState` table
  permissions that AWS's generic KCL 3.x docs mention for 3.0–3.4 style migrations — those tables
  are never created in a direct 2.x → 3.5 migration. If you ever do end up with those tables (e.g.
  a past 3.0–3.4 deployment before this migration), also grant `CreateTable`, `DescribeTable`,
  `Scan`, `GetItem`, `PutItem`, `UpdateItem`, `DeleteItem` and (for cleanup) `DeleteTable` on them.
- `cloudwatch:PutMetricData` is required regardless of `metricsLevel`/`monitoring.metrics.cloudwatch`
  settings if you ever enable CloudWatch metrics reporting.

### 3.2 Worker CPU-utilization metrics prerequisites

KCL 3.x's lease-balancing algorithm uses worker CPU utilization to balance load. If it can't
collect this, it silently falls back to balancing purely on lease/throughput count per worker
(still correct, just less optimal). Requirements per platform:

- **EC2**: Linux OS, [IMDSv2](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/configuring-instance-metadata-service.html) enabled.
- **ECS on EC2**: Linux OS, [ECS task metadata endpoint v4](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ec2-metadata.html) enabled, ECS agent >= 1.39.0.
- **ECS on Fargate**: [Fargate task metadata endpoint v4](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task-metadata-endpoint-v4-fargate.html) (default on platform >= 1.4.0).
- **EKS on EC2**: Linux OS.
- **EKS on Fargate**: platform >= 1.3.0.

Check your Terraform/deployment tooling enforces IMDSv2 and the relevant metadata endpoint version
before migrating.

---

## 4. Helpful CloudWatch metrics/alarms

### 4.1 Migration-specific (temporary, remove/ignore once migration is complete)

All under the `Migration` operation, `SUMMARY` level, namespace = your `appName`:

| Metric | What to alarm on |
| --- | --- |
| `CurrentState:2xCompatibleWorker` | Track during Phase 1 bake — should equal total worker count before proceeding to Phase 2. |
| `CurrentState:3xWorker` | Track during/after Phase 2 — should reach total worker count; this is your "migration complete" signal. |
| `Fault` | Alarm if `Sum > 0` sustained for several periods — indicates the migration state machine is hitting errors (often transient/self-retried, but a persistent non-zero value warrants investigation). |
| `GsiStatusReady` | Confirms the lease table's GSI is available; should be `1` throughout. |

### 4.2 Ongoing operational health (useful beyond migration)

| Metric (scope) | Suggested alarm |
| --- | --- |
| `ProcessTask.MillisBehindLatest` (per-shard/aggregate, `SUMMARY`) | Alarm on sustained high values — indicates the consumer is falling behind the stream. This is the single most important consumer-lag signal. |
| `LeaseAssignmentManager.TotalLeases` vs actual shard count | Sanity check — mismatch indicates leases aren't being created/synced correctly. |
| `LeaseAssignmentManager.LeaseSpillover` | Non-zero sustained values mean you don't have enough workers/capacity for the number of leases — consider scaling out. |
| `RenewAllLeases.LostLeases` | Sustained non-zero indicates workers are losing leases unexpectedly (crashes, GC pauses, network issues) — a leading indicator of potential reprocessing. |
| `LeaseAssignmentManager.NumWorkers` | Cross-check against your actual deployed worker count — mismatches can indicate zombie/leaked workers still holding leases. |
| DynamoDB `ThrottledRequests` / `ConsumedWriteCapacityUnits` on the lease table (native DynamoDB metric, not KCL) | Since single table format concentrates lease + worker-metrics + coordinator-state writes into one table, watch DynamoDB throttling more closely than before — consider on-demand billing mode or adequate provisioned capacity headroom during migration. |

Since `WorkerIdentifier` restarts with a new value unless you pin worker IDs, avoid alarming
directly on per-worker series unless your deployment assigns stable worker IDs across restarts.

---

## 5. Quick checklist

- [ ] AWS SDK for Java pinned to >= 2.28.0 (not 2.27.19–2.27.23).
- [ ] IAM policy updated with `UpdateTable`, GSI `Query`, and (if applicable) legacy
      WorkerMetricStats/CoordinatorState table permissions.
- [ ] IMDSv2 / ECS-EKS metadata endpoint v4 enabled where applicable.
- [ ] Deploy with `clientVersionConfig = COMPATIBLE_WITH_2X_PHASE1` (default) to 100% of fleet.
- [ ] Confirm `CurrentState:2xCompatibleWorker` == total worker count in CloudWatch.
- [ ] Flip to `clientVersionConfig = COMPATIBLE_WITH_2X`, deploy to 100% of fleet promptly.
- [ ] Confirm `CurrentState:3xWorker` == total worker count, wait >= 10 minutes.
- [ ] Confirm no unexpected DynamoDB throttling occurred on the lease table during migration.
- [ ] (Optional) Flip default config to `COMPATIBLE_WITH_2X` for future fresh deployments.
- [ ] (Team decision, optional, separate patch) Discuss and apply the standalone
      `shardLevelMetricsEnabled` patch if per-shard CloudWatch metric/alarm sprawl is a real
      problem; separately decide on `metricsLevel` (`DETAILED` vs `SUMMARY`) as a cost/verbosity
      trade-off.
