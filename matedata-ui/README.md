# MateData UI

Chinese Vue workbench for MateData, with Element Plus components and a responsive layout.

```sh
npm ci
npm run dev -- --host 127.0.0.1
```

Open http://localhost:5173. Vite proxies `/api` to the backend at `http://127.0.0.1:8090`. Enter your manually provisioned credentials; the UI contains no default login or API keys. All backend requests use same-origin cookies and `X-MateData-Request: 1`.

## Verification

```sh
npm run typecheck
npm test
npm run build
```

Use Node 22.12+ (22 LTS), 24 LTS, or 26+. Runtime dependency versions are exact and recorded in `package-lock.json`.

TypeScript 7.0.2 is installed as the primary compiler. Current `vue-tsc` 3.3.11 requires the JavaScript compiler API that native TypeScript 7 no longer exports. `scripts/typecheck.cjs` therefore invokes its supported `run(tscPath)` API with the separately pinned `typescript-vue` alias for TypeScript 6.0.3. This checks all TypeScript and Vue templates in strict mode; it does not modify installed packages.

## Workbench

- Login and session restoration; explicit rule-based/Agent planning selection.
- Questions, dataset context, result bars and table, SQL, execution trace, and errors.
- Data connections: create, test, inspect tables. Update/delete are not exposed by API v1.
- Semantic models: metadata-backed source/table/column selection, editable metric and dimension rows, alias and identifier validation, and preservation of published optional fields.
- Query history/detail uses `/runs/page?offset=0&limit=50` and the server’s explicit `nextOffset`; a cursor stack supports previous pages even when authorization filtering produces short or empty pages.
- Evaluation runs/results, model settings.

Agent mode is disabled until the backend reports a configured model. Only the built-in `demo_sales` source is labeled as sample data. The API mode `demo` is shown as rule-based planning without a model call, and may query connected business data. Requests remain pending until the synchronous backend operation returns; no simulated token streams or fake results are shown.
