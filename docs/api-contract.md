# API contract v1

Base `/api/v1`, JSON, same-origin session cookie, error `{code,message,requestId}`. Lists are plain arrays. Unknown / unavailable operations produce errors, never fake success.

- `POST /auth/login` `{username,password}` → `{username,displayName,role}`; `GET /auth/me`; `POST /auth/logout`.
- `GET /system` → `{name,version,javaVersion,agentScopeVersion,mode,modelConfigured}`.
- `GET /datasets` → array `{id,name,description,sourceId,tableName,metrics:[{id,name,column,aggregation,aliases:string[]}],dimensions:[{id,name,column,aliases:string[],valueType?:string}]}`.
- `POST /datasets` same dataset body, admin; `PUT /datasets/{id}` same body.
- `GET /sources` → array `{id,name,type,jdbcUrl,username,status,createdAt}` (never password).
- `POST /sources` `{name,type,jdbcUrl,username,password}` → source, admin. Types POSTGRESQL/MYSQL; built-in DEMO is read-only.
- `POST /sources/{id}/test` → `{success,message}`; `GET /sources/{id}/tables` → array `{name,columns:[{name,type}]}`.
- `POST /queries` `{question,datasetId,mode:'demo'|'agent',conversationId?:string}` → run. Synchronous, display pending state. No simulated streaming.
- Run shape `{id,conversationId,question,datasetId,mode,status:'SUCCEEDED'|'FAILED',sql,columns:string[],rows:object[],rowCount,durationMs,createdAt,answer,error:string|null,steps:[{name,status,detail,durationMs}]}`.
- `GET /runs/page?offset=0&limit=50` → `{items:Run[],nextOffset:number|null}`. Use the returned continuation even when the visible page is empty: revoked records can occupy the scanned page. Never infer continuation from visible item count.
- `GET /runs?offset=0&limit=50` → authorized own run summaries, newest first (max 100 per page); summaries have empty rows/SQL/steps and retain rowCount. `GET /runs/{id}` → full own run. Results become inaccessible when data authorization or semantic model changes; rerun the question.
- Decimal and arbitrary-size integer result cells are serialized as exact decimal strings so browsers do not round financial values. Ordinary safe integral cells remain numbers.
- `GET /settings/model` → `{baseUrl,model,configured,maxSteps,timeoutSeconds}`. No key. `PUT /settings/model` `{baseUrl,model,apiKey,maxSteps,timeoutSeconds}` admin; blank apiKey retains existing key.
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

Published dimension `valueType` is inferred from database metadata (TEXT/NUMBER/BOOLEAN/DATE/TIME/TIMESTAMP/TIMESTAMP_WITH_ZONE), not trusted from incoming definitions. Filter values are strings at the planning boundary and are parsed into exact typed JDBC values; temporal input uses ISO notation. Temporal result cells are ISO strings, preserving fractional time precision.
