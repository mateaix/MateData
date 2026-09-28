-- Only for an empty, disposable CI database.
CREATE TABLE "Sales_Data" (
  "Region" VARCHAR(20), "Revenue" NUMERIC(24,2), "LargeId" BIGINT,
  "OrderDate" DATE, "OrderTime" TIMESTAMP, "Clock" TIME(6)
);
INSERT INTO "Sales_Data" VALUES
  ('华东',9007199254740993.01,9007199254740993,'2026-01-15','2026-01-15 13:14:15','12:34:56.123456'),
  ('华南',12.34,2,'2026-01-15','2026-01-15 13:14:15','12:34:56.123456');
CREATE TABLE "SalesXData" ("Unexpected" VARCHAR(20));
CREATE ROLE matedata_reader LOGIN PASSWORD 'ci-reader-only';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO matedata_reader;
GRANT SELECT ON "Sales_Data", "SalesXData" TO matedata_reader;
