# 参考与版本来源

核验日期：2026-09-24。依赖固定稳定版本，不采用 milestone / RC / nightly。

| 参考 | 本地记录 | 使用方式 |
|---|---|---|
| matehive | `7295e984d2b04631332261a599afb43fab5ceae6` | 参考领域分层、适配器与工具治理思路；未复制商业源代码 |
| TencentMusic SuperSonic | `6919ac50be262d467007719d5a3d9763eab84a7c`，origin/master | 参考语义层和问数处理流程；未嵌入其旧版框架依赖 |
| AgentScope Java | Maven Central 2.0.3 | 直接依赖公开发布的 Harness 和 OpenAI 扩展，检查对应 source JAR API |

SuperSonic 已在本机 `/Users/mate/Codes/Open-Source/ai/supersonic` 获取远程引用，MateData 的 `.local/references/supersonic` 缓存了上述提交快照。参考缓存不提交到开源仓库。

## 官方版本核验入口

- [Spring Boot 项目](https://spring.io/projects/spring-boot)
- [Spring Boot Maven 元数据](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml)
- [AgentScope Java](https://github.com/agentscope-ai/agentscope-java)
- [AgentScope Harness Maven 元数据](https://repo.maven.apache.org/maven2/io/agentscope/agentscope-harness/maven-metadata.xml)
- [Vue npm registry](https://registry.npmjs.org/vue/latest)
- [Element Plus npm registry](https://registry.npmjs.org/element-plus/latest)
- [Vite npm registry](https://registry.npmjs.org/vite/latest)
- [TypeScript npm registry](https://registry.npmjs.org/typescript/latest)
- [Node.js 发布清单](https://nodejs.org/dist/index.json)
- [SuperSonic](https://github.com/tencentmusic/supersonic)

版本以仓库中的 POM 和 package-lock.json 为准。记录核验日后出现的新版本应通过兼容性测试再升级。
