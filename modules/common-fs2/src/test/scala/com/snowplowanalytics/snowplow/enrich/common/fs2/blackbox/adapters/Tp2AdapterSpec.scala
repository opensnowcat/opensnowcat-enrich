/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.adapters

import org.specs2.mutable.Specification

import cats.effect.testing.specs2.CatsIO

import cats.effect.IO

import cats.implicits._

import com.snowplowanalytics.snowplow.enrich.common.fs2.Enrich

import com.snowplowanalytics.snowplow.enrich.common.fs2.EnrichSpec
import com.snowplowanalytics.snowplow.enrich.common.fs2.test.TestEnvironment
import com.snowplowanalytics.snowplow.enrich.common.fs2.blackbox.BlackBoxTesting
import com.snowplowanalytics.snowplow.enrich.common.fs2.SpecHelpers.createIgluClient

class Tp2AdapterSpec extends Specification with CatsIO {
  "enrichWith" should {
    "enrich with Tp2Adapter" in {
      val input = BlackBoxTesting.buildCollectorPayload(
        path = "/com.snowplowanalytics.snowplow/tp2",
        body = Tp2AdapterSpec.body.some,
        contentType = "application/json".some
      )
      implicit val c = TestEnvironment.http4sClient
      createIgluClient(List(TestEnvironment.embeddedRegistry)).flatMap { igluClient =>
        Enrich
          .enrichWith(
            TestEnvironment.enrichmentReg.pure[IO],
            TestEnvironment.adapterRegistry,
            igluClient,
            None,
            EnrichSpec.processor,
            EnrichSpec.featureFlags,
            IO.unit
          )(
            input
          )
          .map {
            case (l, _) if l.forall(_.isValid) => l must haveSize(10)
            case other => ko(s"there should be 10 enriched events, got $other")
          }
      }
    }
  }
}

object Tp2AdapterSpec {
  val body = """
    {"schema":"iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-0","data":[{"p":"pc","tv":"py-0.5.0","e":"pv","eid":"b603a8ee-3d0c-407e-8822-045b6e639493","url":"http://www.example.com","cx":"eyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY29udGV4dHMvanNvbnNjaGVtYS8xLTAtMCIsICJkYXRhIjogW3sic2NoZW1hIjogImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCAiZGF0YSI6IHsiYXBwbGVJZGZhIjogInNvbWVfYXBwbGVJZGZhIiwgIm9wZW5JZGZhIjogInNvbWVfSWRmYSIsICJhcHBsZUlkZnYiOiAic29tZV9hcHBsZUlkZnYiLCAib3NUeXBlIjogIk9TWCIsICJkZXZpY2VNYW51ZmFjdHVyZXIiOiAiQW1zdHJhZCIsICJhbmRyb2lkSWRmYSI6ICJzb21lX2FuZHJvaWRJZGZhIiwgImNhcnJpZXIiOiAic29tZV9jYXJyaWVyIiwgImRldmljZU1vZGVsIjogImxhcmdlIiwgIm9zVmVyc2lvbiI6ICIzLjAuMCJ9fSwgeyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZ2VvbG9jYXRpb25fY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwgImRhdGEiOiB7ImFsdGl0dWRlIjogMjAsICJzcGVlZCI6IDE2LCAiYmVhcmluZyI6IDUwLCAibG9uZ2l0dWRlIjogMTAsICJsYXRpdHVkZSI6IDcsICJsYXRpdHVkZUxvbmdpdHVkZUFjY3VyYWN5IjogMC41LCAiYWx0aXR1ZGVBY2N1cmFjeSI6IDAuM319XX0=","dtm":"1410278340704"},{"p":"pc","tv":"py-0.5.0","e":"se","se_ac":"my_action","se_ca":"my_category","eid":"a5935573-2120-4fa3-9537-98e243ad73ae","cx":"eyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY29udGV4dHMvanNvbnNjaGVtYS8xLTAtMCIsICJkYXRhIjogW3sic2NoZW1hIjogImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCAiZGF0YSI6IHsiYXBwbGVJZGZhIjogInNvbWVfYXBwbGVJZGZhIiwgIm9wZW5JZGZhIjogInNvbWVfSWRmYSIsICJhcHBsZUlkZnYiOiAic29tZV9hcHBsZUlkZnYiLCAib3NUeXBlIjogIk9TWCIsICJkZXZpY2VNYW51ZmFjdHVyZXIiOiAiQW1zdHJhZCIsICJhbmRyb2lkSWRmYSI6ICJzb21lX2FuZHJvaWRJZGZhIiwgImNhcnJpZXIiOiAic29tZV9jYXJyaWVyIiwgImRldmljZU1vZGVsIjogImxhcmdlIiwgIm9zVmVyc2lvbiI6ICIzLjAuMCJ9fSwgeyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZ2VvbG9jYXRpb25fY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwgImRhdGEiOiB7ImFsdGl0dWRlIjogMjAsICJzcGVlZCI6IDE2LCAiYmVhcmluZyI6IDUwLCAibG9uZ2l0dWRlIjogMTAsICJsYXRpdHVkZSI6IDcsICJsYXRpdHVkZUxvbmdpdHVkZUFjY3VyYWN5IjogMC41LCAiYWx0aXR1ZGVBY2N1cmFjeSI6IDAuM319XX0=","dtm":"1410278340705"},{"p":"pc","tv":"py-0.5.0","e":"se","se_ac":"another_action","se_ca":"another_category","eid":"41f38388-e87e-49ea-9766-a2c8ed916b43","cx":"eyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY29udGV4dHMvanNvbnNjaGVtYS8xLTAtMCIsICJkYXRhIjogW3sic2NoZW1hIjogImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCAiZGF0YSI6IHsiYXBwbGVJZGZhIjogInNvbWVfYXBwbGVJZGZhIiwgIm9wZW5JZGZhIjogInNvbWVfSWRmYSIsICJhcHBsZUlkZnYiOiAic29tZV9hcHBsZUlkZnYiLCAib3NUeXBlIjogIk9TWCIsICJkZXZpY2VNYW51ZmFjdHVyZXIiOiAiQW1zdHJhZCIsICJhbmRyb2lkSWRmYSI6ICJzb21lX2FuZHJvaWRJZGZhIiwgImNhcnJpZXIiOiAic29tZV9jYXJyaWVyIiwgImRldmljZU1vZGVsIjogImxhcmdlIiwgIm9zVmVyc2lvbiI6ICIzLjAuMCJ9fSwgeyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZ2VvbG9jYXRpb25fY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwgImRhdGEiOiB7ImFsdGl0dWRlIjogMjAsICJzcGVlZCI6IDE2LCAiYmVhcmluZyI6IDUwLCAibG9uZ2l0dWRlIjogMTAsICJsYXRpdHVkZSI6IDcsICJsYXRpdHVkZUxvbmdpdHVkZUFjY3VyYWN5IjogMC41LCAiYWx0aXR1ZGVBY2N1cmFjeSI6IDAuM319XX0=","dtm":"1410278340706"},{"p":"pc","tv":"py-0.5.0","e":"pv","eid":"ad960cd5-b68f-4724-b98d-1e4e5b419f09","url":"http://www.example.com","cx":"eyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY29udGV4dHMvanNvbnNjaGVtYS8xLTAtMCIsICJkYXRhIjogW3sic2NoZW1hIjogImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCAiZGF0YSI6IHsiYXBwbGVJZGZhIjogInNvbWVfYXBwbGVJZGZhIiwgIm9wZW5JZGZhIjogInNvbWVfSWRmYSIsICJhcHBsZUlkZnYiOiAic29tZV9hcHBsZUlkZnYiLCAib3NUeXBlIjogIk9TWCIsICJkZXZpY2VNYW51ZmFjdHVyZXIiOiAiQW1zdHJhZCIsICJhbmRyb2lkSWRmYSI6ICJzb21lX2FuZHJvaWRJZGZhIiwgImNhcnJpZXIiOiAic29tZV9jYXJyaWVyIiwgImRldmljZU1vZGVsIjogImxhcmdlIiwgIm9zVmVyc2lvbiI6ICIzLjAuMCJ9fSwgeyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZ2VvbG9jYXRpb25fY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwgImRhdGEiOiB7ImFsdGl0dWRlIjogMjAsICJzcGVlZCI6IDE2LCAiYmVhcmluZyI6IDUwLCAibG9uZ2l0dWRlIjogMTAsICJsYXRpdHVkZSI6IDcsICJsYXRpdHVkZUxvbmdpdHVkZUFjY3VyYWN5IjogMC41LCAiYWx0aXR1ZGVBY2N1cmFjeSI6IDAuM319XX0=","dtm":"1410278340707"},{"p":"pc","tv":"py-0.5.0","e":"se","se_ac":"my_action","se_ca":"my_category","eid":"674b3eaa-12da-41d0-b396-99abf3dd34fb","cx":"eyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY29udGV4dHMvanNvbnNjaGVtYS8xLTAtMCIsICJkYXRhIjogW3sic2NoZW1hIjogImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCAiZGF0YSI6IHsiYXBwbGVJZGZhIjogInNvbWVfYXBwbGVJZGZhIiwgIm9wZW5JZGZhIjogInNvbWVfSWRmYSIsICJhcHBsZUlkZnYiOiAic29tZV9hcHBsZUlkZnYiLCAib3NUeXBlIjogIk9TWCIsICJkZXZpY2VNYW51ZmFjdHVyZXIiOiAiQW1zdHJhZCIsICJhbmRyb2lkSWRmYSI6ICJzb21lX2FuZHJvaWRJZGZhIiwgImNhcnJpZXIiOiAic29tZV9jYXJyaWVyIiwgImRldmljZU1vZGVsIjogImxhcmdlIiwgIm9zVmVyc2lvbiI6ICIzLjAuMCJ9fSwgeyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZ2VvbG9jYXRpb25fY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwgImRhdGEiOiB7ImFsdGl0dWRlIjogMjAsICJzcGVlZCI6IDE2LCAiYmVhcmluZyI6IDUwLCAibG9uZ2l0dWRlIjogMTAsICJsYXRpdHVkZSI6IDcsICJsYXRpdHVkZUxvbmdpdHVkZUFjY3VyYWN5IjogMC41LCAiYWx0aXR1ZGVBY2N1cmFjeSI6IDAuM319XX0=","dtm":"1410278340708"},{"p":"pc","tv":"py-0.5.0","e":"se","se_ac":"another_action","se_ca":"another_category","eid":"3b3d0ad1-bb58-4b73-891b-5fd9085bd4fa","cx":"eyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY29udGV4dHMvanNvbnNjaGVtYS8xLTAtMCIsICJkYXRhIjogW3sic2NoZW1hIjogImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCAiZGF0YSI6IHsiYXBwbGVJZGZhIjogInNvbWVfYXBwbGVJZGZhIiwgIm9wZW5JZGZhIjogInNvbWVfSWRmYSIsICJhcHBsZUlkZnYiOiAic29tZV9hcHBsZUlkZnYiLCAib3NUeXBlIjogIk9TWCIsICJkZXZpY2VNYW51ZmFjdHVyZXIiOiAiQW1zdHJhZCIsICJhbmRyb2lkSWRmYSI6ICJzb21lX2FuZHJvaWRJZGZhIiwgImNhcnJpZXIiOiAic29tZV9jYXJyaWVyIiwgImRldmljZU1vZGVsIjogImxhcmdlIiwgIm9zVmVyc2lvbiI6ICIzLjAuMCJ9fSwgeyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZ2VvbG9jYXRpb25fY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwgImRhdGEiOiB7ImFsdGl0dWRlIjogMjAsICJzcGVlZCI6IDE2LCAiYmVhcmluZyI6IDUwLCAibG9uZ2l0dWRlIjogMTAsICJsYXRpdHVkZSI6IDcsICJsYXRpdHVkZUxvbmdpdHVkZUFjY3VyYWN5IjogMC41LCAiYWx0aXR1ZGVBY2N1cmFjeSI6IDAuM319XX0=","dtm":"1410278340710"},{"p":"pc","tv":"py-0.5.0","e":"pv","eid":"eb6e560a-fb5d-4e72-a52c-35d6f24733be","url":"http://www.example.com","cx":"eyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY29udGV4dHMvanNvbnNjaGVtYS8xLTAtMCIsICJkYXRhIjogW3sic2NoZW1hIjogImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCAiZGF0YSI6IHsiYXBwbGVJZGZhIjogInNvbWVfYXBwbGVJZGZhIiwgIm9wZW5JZGZhIjogInNvbWVfSWRmYSIsICJhcHBsZUlkZnYiOiAic29tZV9hcHBsZUlkZnYiLCAib3NUeXBlIjogIk9TWCIsICJkZXZpY2VNYW51ZmFjdHVyZXIiOiAiQW1zdHJhZCIsICJhbmRyb2lkSWRmYSI6ICJzb21lX2FuZHJvaWRJZGZhIiwgImNhcnJpZXIiOiAic29tZV9jYXJyaWVyIiwgImRldmljZU1vZGVsIjogImxhcmdlIiwgIm9zVmVyc2lvbiI6ICIzLjAuMCJ9fSwgeyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZ2VvbG9jYXRpb25fY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwgImRhdGEiOiB7ImFsdGl0dWRlIjogMjAsICJzcGVlZCI6IDE2LCAiYmVhcmluZyI6IDUwLCAibG9uZ2l0dWRlIjogMTAsICJsYXRpdHVkZSI6IDcsICJsYXRpdHVkZUxvbmdpdHVkZUFjY3VyYWN5IjogMC41LCAiYWx0aXR1ZGVBY2N1cmFjeSI6IDAuM319XX0=","dtm":"1410278340710"},{"p":"pc","tv":"py-0.5.0","e":"se","se_ac":"my_action","se_ca":"my_category","eid":"b0a42ed7-595e-451e-ad78-3e3fd7ea0ace","cx":"eyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY29udGV4dHMvanNvbnNjaGVtYS8xLTAtMCIsICJkYXRhIjogW3sic2NoZW1hIjogImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCAiZGF0YSI6IHsiYXBwbGVJZGZhIjogInNvbWVfYXBwbGVJZGZhIiwgIm9wZW5JZGZhIjogInNvbWVfSWRmYSIsICJhcHBsZUlkZnYiOiAic29tZV9hcHBsZUlkZnYiLCAib3NUeXBlIjogIk9TWCIsICJkZXZpY2VNYW51ZmFjdHVyZXIiOiAiQW1zdHJhZCIsICJhbmRyb2lkSWRmYSI6ICJzb21lX2FuZHJvaWRJZGZhIiwgImNhcnJpZXIiOiAic29tZV9jYXJyaWVyIiwgImRldmljZU1vZGVsIjogImxhcmdlIiwgIm9zVmVyc2lvbiI6ICIzLjAuMCJ9fSwgeyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZ2VvbG9jYXRpb25fY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwgImRhdGEiOiB7ImFsdGl0dWRlIjogMjAsICJzcGVlZCI6IDE2LCAiYmVhcmluZyI6IDUwLCAibG9uZ2l0dWRlIjogMTAsICJsYXRpdHVkZSI6IDcsICJsYXRpdHVkZUxvbmdpdHVkZUFjY3VyYWN5IjogMC41LCAiYWx0aXR1ZGVBY2N1cmFjeSI6IDAuM319XX0=","dtm":"1410278340711"},{"p":"pc","tv":"py-0.5.0","e":"se","se_ac":"another_action","se_ca":"another_category","eid":"dce5ca59-59f2-4652-aa83-d9fa3d0da596","cx":"eyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY29udGV4dHMvanNvbnNjaGVtYS8xLTAtMCIsICJkYXRhIjogW3sic2NoZW1hIjogImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCAiZGF0YSI6IHsiYXBwbGVJZGZhIjogInNvbWVfYXBwbGVJZGZhIiwgIm9wZW5JZGZhIjogInNvbWVfSWRmYSIsICJhcHBsZUlkZnYiOiAic29tZV9hcHBsZUlkZnYiLCAib3NUeXBlIjogIk9TWCIsICJkZXZpY2VNYW51ZmFjdHVyZXIiOiAiQW1zdHJhZCIsICJhbmRyb2lkSWRmYSI6ICJzb21lX2FuZHJvaWRJZGZhIiwgImNhcnJpZXIiOiAic29tZV9jYXJyaWVyIiwgImRldmljZU1vZGVsIjogImxhcmdlIiwgIm9zVmVyc2lvbiI6ICIzLjAuMCJ9fSwgeyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZ2VvbG9jYXRpb25fY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwgImRhdGEiOiB7ImFsdGl0dWRlIjogMjAsICJzcGVlZCI6IDE2LCAiYmVhcmluZyI6IDUwLCAibG9uZ2l0dWRlIjogMTAsICJsYXRpdHVkZSI6IDcsICJsYXRpdHVkZUxvbmdpdHVkZUFjY3VyYWN5IjogMC41LCAiYWx0aXR1ZGVBY2N1cmFjeSI6IDAuM319XX0=","dtm":"1410278340712"},{"p":"pc","tv":"py-0.5.0","e":"pv","eid":"a252552e-cb98-4da8-a49b-365dde1f573b","url":"http://www.example.com","cx":"eyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvY29udGV4dHMvanNvbnNjaGVtYS8xLTAtMCIsICJkYXRhIjogW3sic2NoZW1hIjogImlnbHU6Y29tLnNub3dwbG93YW5hbHl0aWNzLnNub3dwbG93L21vYmlsZV9jb250ZXh0L2pzb25zY2hlbWEvMS0wLTAiLCAiZGF0YSI6IHsiYXBwbGVJZGZhIjogInNvbWVfYXBwbGVJZGZhIiwgIm9wZW5JZGZhIjogInNvbWVfSWRmYSIsICJhcHBsZUlkZnYiOiAic29tZV9hcHBsZUlkZnYiLCAib3NUeXBlIjogIk9TWCIsICJkZXZpY2VNYW51ZmFjdHVyZXIiOiAiQW1zdHJhZCIsICJhbmRyb2lkSWRmYSI6ICJzb21lX2FuZHJvaWRJZGZhIiwgImNhcnJpZXIiOiAic29tZV9jYXJyaWVyIiwgImRldmljZU1vZGVsIjogImxhcmdlIiwgIm9zVmVyc2lvbiI6ICIzLjAuMCJ9fSwgeyJzY2hlbWEiOiAiaWdsdTpjb20uc25vd3Bsb3dhbmFseXRpY3Muc25vd3Bsb3cvZ2VvbG9jYXRpb25fY29udGV4dC9qc29uc2NoZW1hLzEtMC0wIiwgImRhdGEiOiB7ImFsdGl0dWRlIjogMjAsICJzcGVlZCI6IDE2LCAiYmVhcmluZyI6IDUwLCAibG9uZ2l0dWRlIjogMTAsICJsYXRpdHVkZSI6IDcsICJsYXRpdHVkZUxvbmdpdHVkZUFjY3VyYWN5IjogMC41LCAiYWx0aXR1ZGVBY2N1cmFjeSI6IDAuM319XX0=","dtm":"1410278340713"}]}
  """
}
