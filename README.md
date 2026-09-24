# MateData

面向企业数据分析的开源问数平台。使用业务指标与维度构建语义层，让自然语言问题经过授权、规划、SQL 校验和只读执行，返回可核验的数据结果与执行轨迹。

MateData 采用 **DDD 模块化单体**：一个 Java 服务、一个 Vue 界面、一份持久化元数据库。当前是可运行的早期版本，不承诺生产级 SLA。

## 已实现

- 中文问数工作台：数据表、图表、生成 SQL、执行步骤、历史记录。
- 内置真实 H2 销售样本；无需模型 Key 即可运行确定性规则解析，明确标示无模型调用。规则解析也能使用满足该语法的自定义语义模型。
- PostgreSQL / MySQL 业务数据源登记、凭证加密、连通性检查和表字段元数据读取。
- 语义模型管理：受控聚合指标、维度、别名，以及经元数据核验的物理字段映射。
- AgentScope Java Harness：OpenAI 兼容协议、唯一授权语义工具、最大步数和超时、模型调用计数与 token 用量轨迹。模型不可用会失败，不替换成模拟答案。
- 管理员 / 分析师 / 查看者角色；数据集、指标、维度与等值行过滤授权。模型或权限变更后原历史结果失效。
- 精确金额与大整数传输；查询结果不经过 JavaScript 浮点转换来显示金额。
- 固定数据基线评测、评测报告历史、审计日志与登录保护。

## 版本基线

2026-09-24 核验的稳定发行版，具体版本写入构建文件和锁文件，避免使用浮动 `latest`：

| 组件 | 版本 |
|---|---|
| Java | **25**（固定用户指定大版本） |
| Spring Boot | 4.1.1 |
| AgentScope Java | 2.0.3 |
| Maven Wrapper | 3.9.16 |
| Vue / Element Plus | 3.5.43 / 2.14.6 |
| Vite / TypeScript | 8.3.0 / 7.0.2 |
| Node.js | 26.10.0 |
| JSqlParser / Flyway | 5.4 / 13.7.0 |

`vue-tsc` 3.3.11 尚不能使用 TypeScript 7 的编译器内部接口。项目保留 TS 7 主依赖，使用明确命名的 `typescript-vue`（6.0.3）兼容依赖完成 Vue 类型检查，详见前端 README。不会声称类型检查已运行在 TS 7 上。

## 本地运行

准备 JDK 25 和 Node.js 26.10.0，确保 `JAVA_HOME` 指向 JDK 25，`node --version` 与 `.nvmrc` 一致：

```bash
./scripts/build.sh
# 首次运行设置自己的至少 12 字符密码；不要把密码提交到仓库。
export MATEDATA_ADMIN_PASSWORD='replace-with-your-own-password'
./scripts/run.sh
```

访问 <http://127.0.0.1:8090>，用户名 `admin`。若首次启动没有提供密码，后台会生成随机密码并打印到受控的本地日志。已有账户的密码不会被环境变量覆盖。

示例问题：`各区域销售额`、`各品类利润`、`每月销售额趋势`、`各渠道订单数`。选择「规则解析（无模型调用）」即可运行真实 SQL；在「模型设置」配置兼容的模型服务后可切换「Agent 查询」。

开发界面支持 Vite 热更新：

```bash
cd matedata-ui
npm ci
npm run dev -- --host 127.0.0.1
```

先在另一个终端从仓库根目录运行后端。Vite 的 `/api` 代理指向 `127.0.0.1:8090`。端口占用时指定 `--port 5178`，不要结束其他项目的进程。

## 接入业务数据库

1. 管理员在「数据连接」填写 PostgreSQL 或 MySQL JDBC 地址与只读账号，例如 `jdbc:postgresql://db.example:5432/analytics`。
2. 执行连接测试，读取表元数据。
3. 在「语义模型」登记指标、维度及对应物理字段。标识符支持字母、数字、下划线；当前仅支持默认 schema 中的单表。
4. 在「团队治理」为分析师授予指标、维度和可选的强制行过滤条件。
5. 配置模型并发起问数。模型仅接收已授权的语义结构，不接收数据库密码或完整查询结果。

JDBC URL 不允许查询参数或脚本选项；数据库账号本身也应限制为必要表的只读权限。目前没有任意 SQL 编辑器或 SQL 执行 API。

## 架构

```text
matedata-domain                 纯 Java 领域模型与端口
  catalog                      数据源、元数据连接端口
  semantic                     业务词汇、语义计划、SQL 编译与校验
  conversation                 查询结果与执行端口
  harness                      模型配置、轨迹、评测、审计端口
  identity                     账户、授权范围与历史权限指纹
matedata-server                 Spring Boot 应用
  */application                用例编排，依赖领域端口
  */infrastructure             JDBC、加密、AgentScope 等适配器
  */interfaces                 HTTP 控制器、会话和响应适配
matedata-ui                     Vue + Element Plus 工作台
```

AgentScope 只负责从授权词汇生成结构化查询计划。平台编译器生成参数化 SQL，AST 守卫核对计划与 SQL，一致后交由 JDBC 执行器运行。行级权限在编译前强制合并；执行前后及读取历史时重新校验授权范围。领域模块不依赖 Spring 或 JDBC。

## 数据与配置

所有相对路径以仓库根目录或容器 `/app` 为基准：

| 变量 | 默认值 / 用途 |
|---|---|
| `PORT` / `BIND_ADDRESS` | `8090` / `127.0.0.1` |
| `MATEDATA_DATA_DIR` | `./data`，密钥和运行工作空间 |
| `MATEDATA_DATABASE_URL` | `jdbc:h2:file:./data/matedata;DB_CLOSE_ON_EXIT=FALSE` |
| `MATEDATA_DATABASE_PASSWORD` | 元数据库密码 |
| `MATEDATA_ADMIN_PASSWORD` | 首次初始化管理员密码 |
| `MATEDATA_ENCRYPTION_KEY` | 可选 Base64 编码 32 字节 AES 密钥；默认生成 `data/secret.key` |
| `COOKIE_SECURE` | 本地默认 `false`；HTTPS 部署设为 `true` |

元数据库当前使用 H2 文件模式及 Flyway 迁移。PostgreSQL/MySQL 指业务数据源支持，**不代表元数据库可直接切换到这两个数据库**。备份须同时保留元数据库和同一加密密钥，详见 [运维说明](docs/operations.md)。

## 验证与部署

```bash
./mvnw -B -ntp verify
cd matedata-ui
npm test
npm run build
```

完整构建可运行 `./scripts/build.sh`。容器部署见 [运维说明](docs/operations.md)。测试覆盖受控 SQL、实际 JDBC 查询、权限撤销、金额精度、真实 AgentScope 协议、前端交互及领域依赖方向。开发验收记录见 [演进记录](docs/evolution.md)。

## 当前边界

- 单表、单指标、至多一个分组维度、等值筛选；不支持跨表关联、同比环比、复杂时间语义或任意 SQL。演示解析只接受可完整解释的问题。
- 模型协议集成用本地测试服务验证。接入自己的真实模型后，应使用评测中心评估模型规划质量。
- 同步查询、每进程最多 8 个查询、SQL 10 秒超时、最多 1,000 行；不是大规模任务调度系统。
- 单进程会话和文件元数据库；没有 SSO、多租户隔离、分布式会话、高可用或备份服务。
- 当前角色与账户创建、授予权限已实现；密码重置、账户禁用、凭证轮换 UI 尚待演进。
- 当前评测使用固定演示数据基线，不等同于真实企业数据的模型效果评测。

## 开源与参考

本项目采用 [Apache License 2.0](LICENSE)。欢迎遵循 [贡献指南](CONTRIBUTING.md)提交改进，安全问题见 [SECURITY.md](SECURITY.md)。

架构参考 matehive 的上下文分层思路，问数流程参考 [TencentMusic SuperSonic](https://github.com/tencentmusic/supersonic) 的语义层与问数理念，Agent 实现使用 [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)。MateData 代码独立实现，没有复制 matehive 的商业源码，也没有把 SuperSonic 作为旧版依赖整体嵌入。参考版本与来源见 [参考记录](docs/references.md)。
