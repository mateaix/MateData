# 运行与维护

## 单包运行

从仓库根目录执行 `./scripts/build.sh`，构建后的 `matedata-server/target/matedata-server-0.1.0-SNAPSHOT.jar` 包含前端资源。`./scripts/run.sh` 会切回仓库根目录并检查 Java 25，避免从 server 子目录启动时读到另一份数据库。

启动脚本为本次进程创建独立的临时 JAR 副本，正常退出时删除；开发期间重新构建 `target/` 不会破坏运行中 JVM 的延迟类加载。脚本会把停止信号转发给 Java，并等待其正常关闭数据库。若直接运行 `java -jar target/...`，应先停止应用再替换该文件。

独立复制 JAR 时，请先进入选定的工作目录，在该目录执行 `java -jar ...`，或为元数据库和数据目录指定绝对路径。元数据库默认在 `./data/matedata.mv.db`，自动生成的密钥在 `./data/secret.key`。

## 容器

需要可用的 Docker daemon 和 Compose：

```bash
export MATEDATA_ADMIN_PASSWORD='replace-with-your-own-password'
docker compose up --build -d
docker compose logs -f matedata
```

Compose 只将 `127.0.0.1:8090` 暴露到宿主机；应用在容器内监听 `0.0.0.0`。数据保存在持久化命名卷中。不要使用 `docker compose down -v`，除非有意删除所有平台元数据与密钥。

线上入口应由反向代理提供 HTTPS，并设置 `COOKIE_SECURE=true`。登录限流目前基于应用直接连接地址，代理后的用户可能共享一个预算；对大规模部署应先实现可信代理感知的限流策略。

健康检查入口为 `/actuator/health`。默认仅开放 health/info；其余管理端点未暴露。

API 修改请求的正文最多 256 KiB，超出返回 `413 PAYLOAD_TOO_LARGE`。限制同时检查声明长度与实际接收字节，分块传输不能绕过；合法 JSON 内容保留原始 UTF-8 字节。当前接口为同步 JSON 解析，不提供上传文件或非阻塞请求体接口。

执行器最多返回 1,000 行；字符与 CLOB 结果单元格最多 65,536 个 Java 字符单位，单次结果累计最多 1,048,576 个。超限会明确失败并关闭查询资源，不会静默截断字段或保存部分结果。这是应用接受的结果容量限制，不是单元格的 JVM 内存硬上限：驱动可能先解码整行或整个单元格，数据库和驱动仍需按实际数据规模配置内存与查询资源。发布数据集只允许明确支持的数值、布尔、字符、日期与时间类型；BYTEA/BLOB、JSON、ARRAY 等类型不能用作维度或返回值指标，COUNT 只统计非空值可使用这些列。执行器还会检查 JDBC 实际返回类型，拒绝不支持类型以防发布后源表结构变更。

为避免驱动默认缓存完整结果，PostgreSQL 使用关闭自动提交的只读连接、forward-only 结果集和每批 64 行的游标读取；MySQL 使用 forward-only/read-only 结果集与 `Integer.MIN_VALUE` fetch size 逐行读取。成功与异常路径都会关闭结果集、语句和专用连接；PostgreSQL 只读事务随连接关闭结束。保留 10 秒语句执行超时和 15 秒 socket 超时；这些驱动超时并非从查询开始到全部行读取结束的统一墙钟预算。依据：[pgJDBC 游标条件](https://jdbc.postgresql.org/documentation/query/#getting-results-based-on-a-cursor)、[Connector/J 流式读取](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-implementation-notes.html)。

本轮环境未运行 Docker daemon，容器构建和运行必须在实际目标环境补验。不能把 Compose 语法验证等同于容器验收。

登记及每次重开连接均拒绝 MySQL 系统数据库（mysql、information_schema、performance_schema、sys）；PostgreSQL 需要明确的非系统业务 schema。元数据只接受当前 catalog/schema 的表与列。PG 执行前核验表名实际解析到同一业务 schema 的普通或分区表，避免隐式 pg_catalog 命中、大小写折叠差异或 search_path 回退。数据库账号应固定业务 schema 和最小只读权限；变更其默认 schema 后应重新核验并发布模型。

MySQL 连接显式设置 `tinyInt1isBit=false`：`BOOLEAN`/`BOOL` 别名及 `TINYINT(1)` 按数值 `NUMBER` 发布并保留原始整数，包括 2；常规布尔条件使用 0/1，而不是 true/false。真正的 `BIT(n)` 仍作为不支持的位串拒绝。PostgreSQL 原生 `BOOL`/`BOOLEAN` 保留布尔语义。参见 [Connector/J 类型映射](https://dev.mysql.com/doc/connector-j/en/connector-j-reference-type-conversions.html)。

## 备份与恢复

当前 H2 文件库采用单进程模式。采用停机一致性备份：

1. 停止 MateData 进程或容器，确认文件不再被写入。
2. 一并备份元数据库文件与 `data/secret.key`。若使用 `MATEDATA_ENCRYPTION_KEY`，在独立的密钥管理系统中保留相同密钥。
3. 备份需按生产数据管理，可能包含用户问题、查询结果、账号摘要与加密凭证。
4. 恢复到新的工作目录或数据卷，恢复同一密钥后再启动同版本应用。
5. 登录并验证历史记录、数据源连通性与模型配置；再执行演示基线评测。

只备份数据库却丢失密钥会导致原凭证无法解密。不要在应用运行时直接复制 `.mv.db` 当作一致性备份。

## 迁移

Flyway 脚本位于 `matedata-server/src/main/resources/db/migration`，禁止编辑已经部署过的迁移。V1 为文档存储与索引，旧的开发库可从 baseline 0 迁移。迁移前先备份。

元数据库适配器目前使用 H2 的 `MERGE INTO`，不支持仅替换 URL 就迁移到 PostgreSQL/MySQL。未来应通过领域仓储端口实现新的适配器与数据迁移。

## 故障定位

| 现象 | 检查 |
|---|---|
| 启动报 Java 版本错误 | `JAVA_HOME` 是否指向 JDK 25 |
| 首次管理员登录失败 | 首次创建时的密码；后续环境变量不覆盖已有密码 |
| 模型问数失败 | 模型设置、Key、兼容的 chat/completions 工具调用能力与网络；运行轨迹中是否达到预算 |
| 历史结果显示无权限 | 语义模型或授权已改变，重新发起查询 |
| 数据源连通但查询失败 | 只读账号、字段类型、默认 schema、聚合函数与实际表结构 |
| 金额显示字符串 | 为避免精度损失的设计行为，原值未被四舍五入 |
| 429 登录受限 | 同一直接连接地址每分钟最多 10 次尝试；稍后重试 |

不要把生产模型 Key 或数据库密码粘贴到 issue、终端录屏或 UI 截图中。
