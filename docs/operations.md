# 运行与维护

## 单包运行

从仓库根目录执行 `./scripts/build.sh`，构建后的 `matedata-server/target/matedata-server-0.1.0-SNAPSHOT.jar` 包含前端资源。`./scripts/run.sh` 会切回仓库根目录并检查 Java 25，避免从 server 子目录启动时读到另一份数据库。

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

本轮环境未运行 Docker daemon，容器构建和运行必须在实际目标环境补验。不能把 Compose 语法验证等同于容器验收。

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
