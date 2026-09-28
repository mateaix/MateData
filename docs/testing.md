# 验证流程

## 默认检查

`./scripts/build.sh` 运行前端依赖锁定安装、组件和行为测试、Vue 类型检查、生产构建，以及后端 `clean verify`。需选择 JDK 25 和 `.nvmrc` 中的 Node 版本。

测试使用自己的 H2 库、临时加密密钥与本地 HTTP 模型协议服务，不会调用收费模型。与 SQL、授权范围、历史记录、准确金额相关的测试验证实际行为；Harness 用例验证真实 SDK 的工具请求、预算、异常与超时，以及 8 个实际并发 Harness 会话的提示与计划隔离。

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

### 查询取消预算

`PostgresQueryBudgetTest` 仅在显式设置 `MATEDATA_TEST_POSTGRES_ADMIN_URL` 时运行。它用测试管理员在上述隔离库的 `Sales_Data` 表持有独占锁，断言业务只读连接在约 10 秒收到 PostgreSQL `57014` 取消状态，然后释放锁并再次成功查询。不会修改表数据，但会短暂阻塞其他访问，因此只可用于专用测试库。

```bash
export MATEDATA_TEST_POSTGRES_ADMIN_URL="$MATEDATA_TEST_POSTGRES_URL"
export MATEDATA_TEST_POSTGRES_ADMIN_USER='matedata_test'
export MATEDATA_TEST_POSTGRES_ADMIN_PASSWORD='your-disposable-test-password'
./mvnw -B -ntp test -Dtest=PostgresQueryBudgetTest -Dsurefire.failIfNoSpecifiedTests=false
```

该测试已在本地 PostgreSQL 18.6 实际通过，执行耗时约 10.4 秒。它验证数据库取消与恢复，普通默认构建不会持有数据库锁。

## 其他验证边界

- MySQL 26.7.0 与 PostgreSQL 18.6 已使用独立本地实例运行同一 JDBC 行为合同；包括金额、数字筛选、日期和微秒时间精度。
- Docker Compose 配置可解析，当前机器未启动 Docker daemon；不代表镜像构建与容器运行通过。
- 真实供应商模型质量依赖模型配置，用平台评测中心执行；本地模型协议测试不评判语义智能质量。
- GitHub Actions 在独立 PostgreSQL 18 / MySQL 8.4 服务上初始化 `scripts/ci/*.sql` fixture，再运行完整构建。`scripts/ci/check_database_reports.py` 要求数据库合同、PostgreSQL 系统边界与取消测试全部执行成功，不能跳过。仅本地未配置数据库变量时允许跳过。

## 单包重启烟测

```bash
python3 scripts/smoke.py
```

该脚本从临时目录启动自己的应用实例，验证同源页面与静态资源、匿名拒绝、登录、8 个独立会话并发问数、评测、加密配置，然后重启实例检查数据仍可读取，最后停机复制元数据库与密钥到另一个目录，再启动恢复实例验证查询、评测、模型配置与授权版本。使用临时随机凭证和独立数据文件，结束时停止自己创建的进程并删除测试数据。无需访问收费模型或业务数据库。


## 可选 MySQL 测试

在隔离 MySQL 测试实例中创建数据库 `matedata_test` 和相同的 `Sales_Data` / `SalesXData` 表。把上述 SQL 的双引号标识符换为反引号，`OrderTime` 使用 `DATETIME(6)`。为测试用户授予该库的 SELECT 权限，设置 `MATEDATA_TEST_MYSQL_URL`、`MATEDATA_TEST_MYSQL_USER`、`MATEDATA_TEST_MYSQL_PASSWORD`，运行：

```bash
./mvnw -B -ntp test -Dtest=ExternalMysqlTest -Dsurefire.failIfNoSpecifiedTests=false
```

数据库测试使用 `ExternalDatabaseContract` 复用相同的期望，不会自动建表或写入业务数据。TIME(6) 必须精确保留 `12:34:56.123456`，不得经过会丢失微秒的 `java.sql.Time` 转换。


### MySQL 布尔别名与位串合同

在上述专用测试库中由测试管理员执行：

```sql
CREATE TABLE tiny_alias_capacity_test (
  id INT, alias_value BOOLEAN, tiny_value TINYINT(1), real_bits BIT(8)
);
INSERT INTO tiny_alias_capacity_test VALUES (1,0,0,0),(2,1,1,1),(3,2,2,2);
```

设置 `MATEDATA_TEST_MYSQL_TYPE_TABLE=tiny_alias_capacity_test` 后，`ExternalMysqlTest` 额外验证 BOOLEAN/TINYINT(1) 的 0/1/2 数值和等值筛选，真实 BIT(8) 在发布和执行时均被拒绝。测试仍只使用只读连接，不自行创建或删除 fixture；结束后由测试管理员清理。

## API 路径规范化回归

`ApiPathSecurityTest` 通过真实嵌入式 Tomcat 发送分号路径参数、百分号编码、重复斜线和点路径，验证规范化路由不能绕过认证、CSRF 请求头或 256 KiB 正文限制。过滤器与路由统一使用容器规范化后的 servlet path；不能只根据原始 request URI 的字符串前缀判断权限。


## 系统命名空间与 PostgreSQL 解析合同

`SecurityPolicyTest` 和 `SystemObjectBoundaryTest` 验证登记、连接重开与元数据过滤中的系统对象拒绝。`PostgresSystemBoundaryTest` 与查询取消测试共用显式 `MATEDATA_TEST_POSTGRES_ADMIN_URL` 开关；需要管理员具备在**专用隔离测试库**创建 schema 并向只读测试用户授权的能力。

该测试创建随机名称的两个临时 schema，验证隐式 pg_catalog 同名解析、旧模型未加引号名称折叠、合法业务同名表和删除后 search_path 回退。finally 清理自己创建的 schema。不能在生产库启用该测试。普通未指定管理员 URL 的构建会跳过它。

## 会话一致性与回答校验

`QueryAdmissionTest` 验证同会话串行、不同会话并发、回答数值回退与无查询结论拦截。
`QueryIdempotencyTest` 使用真实 H2 持久化验证重试复用、服务重建、用户隔离、冲突及授权撤销。
`QueryCoordinatorTest` 验证等待超时后原持有者与后续请求仍可正常运行；
`PlatformIntegrationTest` 通过 HTTP 验证幂等请求头。
`AnswerGroundingTest` 验证精确金额与单位换算、未知数字、百分比、中文数词和空结果。
数据库合同还关闭 JDBC 只读提示后尝试无匹配行的 DELETE，确认数据库账户本身没有写权限。

CI 中的 `ci-reader-only` 和 `ci-admin-only` 仅用于每次运行后销毁的测试服务，不是应用凭证。
不得在真实业务数据库执行这些初始化 SQL。
