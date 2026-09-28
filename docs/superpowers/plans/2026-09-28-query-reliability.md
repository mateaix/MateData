# 首轮问数可靠性实施计划

目标：按已确认的首轮建议，完善会话一致性、回答证据检查和真实数据库持续回归。
继续使用单进程 DDD 模块化单体；本轮不引入微服务，也不包含优化表中的其余演进项。

## 设计与实施

1. QueryService 在同一用户、同一会话内串行执行。锁覆盖权限读取、Agent 状态读写与结果保存，等待最多一秒；等待超时返回 BUSY，释放引用后删除空闲锁。独立会话仍并行。
2. 查询 API 接收可选 Idempotency-Key，按用户隔离。以确定性运行 ID 持久化结果，不增加内存结果缓存；重复请求验证请求参数及当前权限后复用，参数冲突返回 CONFLICT。第一次请求未成功保存时不承诺 exactly-once。前端为每次提交生成键。
3. 增加领域层回答检查器。未执行查询时只展示保守的澄清/拒答；无证据的结论替换成平台澄清。执行后核对数值引用，不匹配则使用已有确定性摘要，保留结果行和检查步骤。明确权限范围、结果上限；此检查不声称验证全部自然语言语义或因果关系。
4. CI 提供独立 PostgreSQL 与 MySQL，初始化精度/类型/命名空间测试数据与只读账户，运行数据库合同及 PostgreSQL 取消测试。报告必须包含目标套件且不能跳过。

## 验证步骤

- [x] 先运行 QueryAdmissionTest 新增回归，确认现有实现失败。
- [x] 实现会话串行与幂等，验证并发、超时、失败释放、参数冲突、权限变更和用户隔离。
- [x] 实现回答校验，验证准确数字、格式化金额、未知数字、空结果及无查询结论。
- [x] 初始化真实数据库 fixture，运行外部数据库合同。
- [x] 执行 scripts/build.sh、单包 smoke.py，检查 Git diff。
- [x] 更新操作/API/测试说明，核验 mateaix 身份，准备提交并推送 main。

测试代码分别位于 QueryAdmissionTest、QueryIdempotencyTest、AnswerGroundingTest；
实现集中在 QueryService、QueryCoordinator、AnswerGrounding 与 QueryController；
CI fixture 放入 scripts/ci，数据库合同沿用 ExternalDatabaseContract。

## 实施审查记录

首批三个 QueryAdmissionTest 回归在旧实现上失败，修复后通过。
独立审查发现中文数量与无查询澄清的漏检，新增失败测试后修复；
另补 HTTP 非安全上下文中 randomUUID 不可用的前端失败回归，改用 getRandomValues。
对单位、英文数字词和非 ASCII 数字采用保守回退，可能放弃可接受的模型解读；
数据表与平台摘要始终保留。审查复核未发现剩余实质问题。

最终本地验收：2026-09-28，scripts/build.sh 完成 209 项后端与 85 项前端测试，
后端零失败、零跳过；真实 PostgreSQL/MySQL 合同、格式与类型检查通过。
smoke.py 完成首次启动、重启与停机备份异目录恢复测试。
本机未运行 Docker；Actions 中 PostgreSQL 18 / MySQL 8.4 的容器环境由远程流水线验证。
