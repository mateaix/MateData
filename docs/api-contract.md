# API contract v1

Base `/api/v1`, JSON, same-origin session cookie, error `{code,message,requestId}`. Lists are plain arrays except explicitly documented paged endpoints. Unknown / unavailable operations produce errors, never fake success.

- `POST /auth/login` `{username,password}` → `{username,displayName,role}`; `GET /auth/me`; `POST /auth/logout`.
- `GET /system` → `{name,version,javaVersion,agentScopeVersion,mode,modelConfigured}`.
- `GET /datasets` → array `{id,name,description,sourceId,tableName,dialect,scopeFingerprint,metrics:[{id,name,column,aggregation,aliases:string[]}],dimensions:[{id,name,column,aliases:string[],valueType?:string}]}`.
- `POST /datasets` same dataset body, admin; `PUT /datasets/{id}` same body.
- `GET /sources` → array `{id,name,type,jdbcUrl,username,status,createdAt}` (never password).
- `POST /sources` `{name,type,jdbcUrl,username,password}` → source, admin. Types POSTGRESQL/MYSQL; built-in DEMO is read-only.
- `POST /sources/{id}/test` → `{success,message}`; `GET /sources/{id}/tables` → array `{name,columns:[{name,type}]}`.
- `POST /queries` `{question,datasetId,mode:'demo'|'agent',conversationId?:string}` → run. Synchronous, display pending state. No simulated streaming. `conversationId` matches `[A-Za-z0-9-]{1,64}`; send the previous run's `conversationId` to ask an agent follow-up. Agent memory is bound to the user, the conversation and the current authorization scope: after a grant or semantic model change the next question starts without earlier context. A conversation accepts at most 8 agent questions; start a new one afterwards.
- Run shape `{id,conversationId,question,datasetId,scopeFingerprint,mode,status:'SUCCEEDED'|'FAILED'|'NEEDS_INPUT',sql,columns:string[],rows:object[],rowCount,durationMs,createdAt,answer,error:string|null,steps:[{name,status,detail,durationMs}]}`.
- In agent mode `answer` is the model's plain-text interpretation of the returned rows (rows remain authoritative); `NEEDS_INPUT` means the agent asked a clarifying question in `answer` and ran no query (empty `sql` and rows).
- `GET /runs/page?offset=0&limit=50` → `{items:Run[],nextOffset:number|null}`. Use the returned continuation even when the visible page is empty: revoked records can occupy the scanned page. Never infer continuation from visible item count.
- `GET /runs?offset=0&limit=50` → authorized own run summaries, newest first (max 100 per page); summaries have empty rows/SQL/steps and retain rowCount. `GET /runs/{id}` → full own run. Results become inaccessible when data authorization or semantic model changes; rerun the question.
- Decimal and arbitrary-size integer result cells are serialized as exact decimal strings so browsers do not round financial values. Ordinary safe integral cells remain numbers.
- `GET /settings/model` → `{provider,baseUrl,model,configured,maxSteps,timeoutSeconds}`. No key. `PUT /settings/model` `{provider:'OPENAI_COMPATIBLE'|'OLLAMA',baseUrl,model,apiKey,maxSteps:1–12,timeoutSeconds:5–300}` admin. OpenAI-compatible services require a key; blank apiKey retains the existing key of the same provider. Ollama (for example `http://127.0.0.1:11434`) takes no key and discards any stored one. A missing provider means OPENAI_COMPATIBLE.
- `GET /evaluations` → array `{id,name,question,datasetId,expectedMetric,expectedDimension}`.
- `POST /evaluations/run` `{mode:'demo'|'agent'}` → `{id,passed,total,durationMs,results:[{name,passed,message,runId}]}`.
- `GET /evaluations/reports` → own persisted reports, newest first; `GET /evaluations/reports/{id}` → own report.
- `GET /audit` → array `{id,username,action,resource,createdAt}` admin.
- `GET /users` → array `{username,displayName,role}` admin. `POST /users` `{username,displayName,role:'ADMIN'|'ANALYST'|'VIEWER',password}` → user. Password at least 12 characters and at most 72 UTF-8 bytes, no credential returned.
- `GET /permissions` → array `{username,datasetId,enabled,metrics:string[],dimensions:string[],rowFilters:Record<string,string>}` admin.
- `PUT /permissions/{username}/{datasetId}` `{enabled,metrics,dimensions,rowFilters}` → grant, admin. Enabled grants require at least one metric; rowFilters enforce exact values even if the agent tries to change them. External datasets default to admin-only. Built-in demo is shared unless explicitly disabled. VIEWER cannot execute queries.

Built-in dataset `sales`, source `demo_sales`; metrics `revenue` (销售额 SUM amount), `orders` (订单数 COUNT id), `profit` (利润 SUM profit); dimensions `region` (区域), `category` (品类), `month` (月份), `channel` (渠道). Suggested queries: 各区域销售额、各品类利润、每月销售额趋势、各渠道订单数。Unsupported demo questions fail clearly. Model configuration may enable agent mode.

Local starter account is provided via env `MATEDATA_ADMIN_PASSWORD`; startup generates and logs a temporary password if unset. Login UI must allow manual entry, no embedded credentials.

API mutations require `X-MateData-Request: 1` (CSRF guard); all requests should set this header. CORS not broadly enabled. Dev proxy `/api` → `http://127.0.0.1:8090`.

Mutation bodies are limited to 256 KiB, including chunked transfer; larger bodies return `413 PAYLOAD_TOO_LARGE`. Concurrent creation of the same username or dataset identifier permits only one success and returns `409 CONFLICT` for the other, without overwriting the winning credentials or published model. Explicit dataset updates retain their update semantics.

`scopeFingerprint` is an opaque 64-character keyed HMAC token covering the full semantic model and the current user's authorization. A purpose-specific signing key is derived from the persistent server secret, so clients cannot enumerate low-entropy hidden row-filter values from a public plain hash. Compare it with the result's fingerprint before attaching business labels or a source description. Clear cached results when the corresponding dataset or fingerprint changes, including changes to row filters that leave visible fields unchanged. This is a cache-consistency token, never an authorization credential; every server history read still checks current permissions.

Published dimension `valueType` is inferred from database metadata (TEXT/NUMBER/BOOLEAN/DATE/TIME/TIMESTAMP/TIMESTAMP_WITH_ZONE), not trusted from incoming definitions. Filter values are strings at the planning boundary and are parsed into exact typed JDBC values; temporal input uses ISO notation. Temporal result cells are ISO strings, preserving fractional time precision.

Publishing SUM/AVG metrics requires a numeric physical column. Metric/dimension names and supplied aliases cannot be null or blank. Successful create/update responses contain the canonical physical table/column names and dialect; refresh GET `/datasets` to obtain the current scope fingerprint.

## Query coordination and evidence checks

`POST /queries` accepts optional `Idempotency-Key` (1–128 ASCII letters, digits, `_` or `-`).
Reuse it only with the identical question, dataset, mode and conversationId. The key is scoped
by authenticated username. Once a run is saved, retries return that same run (including saved
failures), across restarts. Different payloads return `409 CONFLICT`; replay still checks current
authorization/model scope. Use a new key for an intentional new run. A crash before persistence
may execute the read-only query again; this is not an exactly-once transaction with the model.

Same-user, same-conversation turns execute serially within this single application process.
Waiting more than one second for the conversation or idempotency key returns `429 BUSY`;
clients may retry the identical request with the same key. Independent conversations retain
the existing global eight-query limit. Do not run multiple instances against the same state directory.

After a governed query, numeric references in the interpretation are compared with result cells
using exact decimal values, including comma formatting and 万/亿 scaling. Unsupported numeric
claims, percentages/comparisons and empty results use the platform summary instead. The run
keeps its authoritative rows and a 回答校验 step. This is a conservative numeric check, not proof
of correct label/value attribution, comparative language or causality. Replies without a query
are constrained to a short clarification or replaced with a platform clarification (`NEEDS_INPUT`).
Authorization is checked again after interpretation, before returning successful results.
