-- Only for an empty, disposable CI database.
USE matedata_test;
CREATE TABLE `Sales_Data` (
  `Region` VARCHAR(20), `Revenue` DECIMAL(24,2), `LargeId` BIGINT,
  `OrderDate` DATE, `OrderTime` DATETIME(6), `Clock` TIME(6)
);
INSERT INTO `Sales_Data` VALUES
  ('华东',9007199254740993.01,9007199254740993,'2026-01-15','2026-01-15 13:14:15','12:34:56.123456'),
  ('华南',12.34,2,'2026-01-15','2026-01-15 13:14:15','12:34:56.123456');
CREATE TABLE `SalesXData` (`Unexpected` VARCHAR(20));
CREATE TABLE tiny_alias_capacity_test (
  id INT, alias_value BOOLEAN, tiny_value TINYINT(1), real_bits BIT(8)
);
INSERT INTO tiny_alias_capacity_test VALUES (1,0,0,0),(2,1,1,1),(3,2,2,2);
CREATE USER 'matedata_reader'@'%' IDENTIFIED BY 'ci-reader-only';
GRANT SELECT ON matedata_test.* TO 'matedata_reader'@'%';
