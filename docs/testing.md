# 验证流程

## 默认检查

`./scripts/build.sh` 运行前端依赖锁定安装、组件和行为测试、Vue 类型检查、生产构建，以及后端 `clean verify`。需选择 JDK 25 和 `.nvmrc` 中的 Node 版本。

测试使用自己的 H2 库、临时加密密钥与本地 HTTP 模型协议服务，不会调用收费模型。与 SQL、授权范围、历史记录、准确金额相关的测试验证实际行为；Harness 用例验证真实 SDK 的工具请求、预算、异常与超时。

## 可选 PostgreSQL 测试

使用隔离 PostgreSQL 实例，**不要在业务生产数据库执行以下测试初始化**。在空测试库中作为测试管理员执行：

```sql
CREATE TABLE "Sales_Data" (
  "Region" VARCHAR(20), "Revenue" NUMERIC(24,2), "LargeId" BIGINT,
  "OrderDate" DATE, "OrderTime" TIMESTAMP, "Clock" TIME(6)
);
INSERT INTO "Sales_Data" VALUES
  ('华东',9007199254740993.01,9007199254740993,'2026-01-15','2026-01-15 13:14:15','12:34:56.123456'),
  ('华南',12.34,2,'2026-01-15','2026-01-15 13:14:15','12:34:56.123456');
CREATE TABLE "SalesXData" ("Unexpected" VARCHAR(20));
CREATE ROLE matedata_reader LOGIN PASSWORD 'local-test-only-password';
GRANT USAGE ON SCHEMA public TO matedata_reader;
GRANT SELECT ON "Sales_Data", "SalesXData" TO matedata_reader;
```

然后执行：

```bash
export MATEDATA_TEST_POSTGRES_URL='jdbc:postgresql://127.0.0.1:54329/postgres'
export MATEDATA_TEST_POSTGRES_USER='matedata_reader'
export MATEDATA_TEST_POSTGRES_PASSWORD='local-test-only-password'
./mvnw -B -ntp test -Dtest=ExternalPostgresTest -Dsurefire.failIfNoSpecifiedTests=false
```

未提供 URL 时该用例跳过。测试确认真实 PostgreSQL 连接、只读账户、字段精确归属、大小写规范映射、引用标识符与超大金额保持精度。测试不自动创建或删除业务表。

2026-09-24 本地使用独立 PostgreSQL 18.6 实例运行通过。该用例曾实际捕获 JDBC 元数据下划线通配符引起的跨表字段混入，修复后相同测试通过。

## 其他验证边界

- MySQL 26.7.0 与 PostgreSQL 18.6 已使用独立本地实例运行同一 JDBC 行为合同；包括金额、数字筛选、日期和微秒时间精度。
- Docker Compose 配置可解析，当前机器未启动 Docker daemon；不代表镜像构建与容器运行通过。
- 真实供应商模型质量依赖模型配置，用平台评测中心执行；本地模型协议测试不评判语义智能质量。
- GitHub Actions 工作流已写入，未推送仓库，因此没有远程 CI 运行结果。

## 单包重启烟测

```bash
python3 scripts/smoke.py
```

该脚本从临时目录启动自己的应用实例，验证同源页面与静态资源、匿名拒绝、登录、问数、评测、加密配置，然后重启实例检查数据仍可读取。使用临时随机凭证和独立数据文件，结束时停止自己创建的进程并删除测试数据。无需访问收费模型或业务数据库。


## 可选 MySQL 测试

在隔离 MySQL 测试实例中创建数据库 `matedata_test` 和相同的 `Sales_Data` / `SalesXData` 表。把上述 SQL 的双引号标识符换为反引号，`OrderTime` 使用 `DATETIME(6)`。为测试用户授予该库的 SELECT 权限，设置 `MATEDATA_TEST_MYSQL_URL`、`MATEDATA_TEST_MYSQL_USER`、`MATEDATA_TEST_MYSQL_PASSWORD`，运行：

```bash
./mvnw -B -ntp test -Dtest=ExternalMysqlTest -Dsurefire.failIfNoSpecifiedTests=false
```

数据库测试使用 `ExternalDatabaseContract` 复用相同的期望，不会自动建表或写入业务数据。TIME(6) 必须精确保留 `12:34:56.123456`，不得经过会丢失微秒的 `java.sql.Time` 转换。
