# API contract v1

Base `/api/v1`, JSON, same-origin session cookie, error `{code,message,requestId}`. Lists are plain arrays. Unknown / unavailable operations produce errors, never fake success.

- `POST /auth/login` `{username,password}` → `{username,displayName,role}`; `GET /auth/me`; `POST /auth/logout`.
- `GET /system` → `{name,version,javaVersion,agentScopeVersion,mode,modelConfigured}`.
- `GET /datasets` → array `{id,name,description,sourceId,tableName,metrics:[{id,name,column,aggregation,aliases:string[]}],dimensions:[{id,name,column,aliases:string[]}]}`.
- `POST /datasets` same dataset body, admin; `PUT /datasets/{id}` same body.
- `GET /sources` → array `{id,name,type,jdbcUrl,username,status,createdAt}` (never password).
- `POST /sources` `{name,type,jdbcUrl,username,password}` → source, admin. Types POSTGRESQL/MYSQL; built-in DEMO is read-only.
- `POST /sources/{id}/test` → `{success,message}`; `GET /sources/{id}/tables` → array `{name,columns:[{name,type}]}`.
- `POST /queries` `{question,datasetId,mode:'demo'|'agent',conversationId?:string}` → run. Synchronous, display pending state. No simulated streaming.
- Run shape `{id,conversationId,question,datasetId,mode,status:'SUCCEEDED'|'FAILED',sql,columns:string[],rows:object[],rowCount,durationMs,createdAt,answer,error:string|null,steps:[{name,status,detail,durationMs}]}`.
- `GET /runs` → own runs newest first; `GET /runs/{id}` → own run.
- `GET /settings/model` → `{baseUrl,model,configured,maxSteps,timeoutSeconds}`. No key. `PUT /settings/model` `{baseUrl,model,apiKey,maxSteps,timeoutSeconds}` admin; blank apiKey retains existing key.
- `GET /evaluations` → array `{id,name,question,datasetId,expectedMetric,expectedDimension}`.
- `POST /evaluations/run` `{mode:'demo'|'agent'}` → `{id,passed,total,durationMs,results:[{name,passed,message,runId}]}`.
- `GET /audit` → array `{id,username,action,resource,createdAt}` admin.

Built-in dataset `sales`, source `demo-sales`; metrics `revenue` (销售额 SUM amount), `orders` (订单数 COUNT id), `profit` (利润 SUM profit); dimensions `region` (区域), `category` (品类), `month` (月份), `channel` (渠道). Suggested queries: 各区域销售额、各品类利润、每月销售额趋势、各渠道订单数。Unsupported demo questions fail clearly. Model configuration may enable agent mode.

Local starter account is provided via env `MATEDATA_ADMIN_PASSWORD`; startup generates and logs a temporary password if unset. Login UI must allow manual entry, no embedded credentials.

API mutations require `X-MateData-Request: 1` (CSRF guard); all requests should set this header. CORS not broadly enabled. Dev proxy `/api` → `http://127.0.0.1:8090`.
