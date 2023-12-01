DROP TABLE IF EXISTS enrichment_test;

CREATE TABLE IF NOT EXISTS enrichment_test(
   service_name     VARCHAR(30)     NOT NULL
  ,current_status   VARCHAR(16)     NOT NULL
  ,last_status      VARCHAR(16)     NOT NULL
  ,pk               INTEGER         NOT NULL PRIMARY KEY
);

INSERT INTO enrichment_test(service_name,current_status,last_status,pk) VALUES ('sp-sql-request-enrichment','OK','OK',1);
