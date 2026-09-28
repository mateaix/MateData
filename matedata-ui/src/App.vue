<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { ApiError, json, request as apiRequest } from "./api";
import type {
  Dataset,
  Evaluation,
  EvaluationResult,
  Model,
  ModelProvider,
  Run,
  RunPage,
  Source,
  System,
  User,
} from "./types";
import DatasetDesigner from "./components/DatasetDesigner.vue";
import Governance from "./components/Governance.vue";
import RunResult from "./components/RunResult.vue";
import AppIcon from "./components/AppIcon.vue";
import type { IconName } from "./icons";
import { createModelVersions, runMatchesDataset } from "./modelVersions";
import { runStatus } from "./runStatus";
import { createSessionScope, StaleSessionError } from "./session";
const session = createSessionScope();
const modelVersions = createModelVersions();
let queryVersion = 0;
let pendingQueryDataset = "";
let detailVersion = 0;
let catalogVersion = 0;
const changedScopeMessage =
  "模型或权限已更新，无法确认本次结果的有效范围。请刷新语义模型后重试。";
function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  return session.request(() => apiRequest<T>(path, init));
}
type Page =
  | "ask"
  | "sources"
  | "datasets"
  | "runs"
  | "evaluations"
  | "settings"
  | "governance";
const pages: { id: Page; name: string; icon: IconName; caption: string }[] = [
  { id: "ask", name: "智能问数", icon: "ask", caption: "从一个好问题开始" },
  {
    id: "sources",
    name: "数据连接",
    icon: "sources",
    caption: "连接业务的数据基础",
  },
  {
    id: "datasets",
    name: "语义模型",
    icon: "datasets",
    caption: "让业务语言与数据对齐",
  },
  {
    id: "runs",
    name: "查询记录",
    icon: "runs",
    caption: "每一次分析，都有迹可循",
  },
  {
    id: "evaluations",
    name: "质量评测",
    icon: "evaluations",
    caption: "以可复现的结果建立信任",
  },
  {
    id: "settings",
    name: "模型设置",
    icon: "settings",
    caption: "配置你的智能分析引擎",
  },
  {
    id: "governance",
    name: "团队治理",
    icon: "governance",
    caption: "让权限与责任清晰可见",
  },
];
const page = ref<Page>("ask"),
  user = ref<User | null>(null),
  system = ref<System | null>(null),
  booting = ref(true),
  busy = ref(false),
  error = ref(""),
  loginBusy = ref(false),
  loginError = ref("");
const credentials = ref({ username: "", password: "" });
const datasets = ref<Dataset[]>([]),
  sources = ref<Source[]>([]),
  runs = ref<Run[]>([]),
  evaluations = ref<Evaluation[]>([]),
  evaluationResult = ref<EvaluationResult | null>(null);
const runsOffset = ref(0);
const runPageSize = 50;
const runPageOffsets = ref<number[]>([0]);
const runsNextOffset = ref<number | null>(null);
const hasOlderRuns = computed(() => runsNextOffset.value !== null);
let runLoadVersion = 0;
const evaluationReports = ref<EvaluationResult[]>([]);
const reportLoading = ref(false);
const model = ref<Model>({
  provider: "OPENAI_COMPATIBLE",
  baseUrl: "",
  model: "",
  configured: false,
  maxSteps: 8,
  timeoutSeconds: 60,
  apiKey: "",
});
/** Agent follow-ups share a server-side conversation; demo mode never does. */
const conversationId = ref("");
const conversationTurns = ref<string[]>([]);
function newConversation() {
  conversationId.value = "";
  conversationTurns.value = [];
}
const question = ref(""),
  datasetId = ref(""),
  mode = ref<"demo" | "agent">("demo"),
  querying = ref(false),
  activeRun = ref<Run | null>(null),
  detail = ref<Run | null>(null),
  evaluating = ref(false);
const sourceDialog = ref(false),
  sourceDraft = ref({
    name: "",
    type: "POSTGRESQL",
    jdbcUrl: "",
    username: "",
    password: "",
  }),
  saving = ref(false),
  testing = ref("");
const tables = ref<
    { name: string; columns: { name: string; type: string }[] }[]
  >([]),
  tableDialog = ref(false),
  tableSource = ref("");
const datasetEditorVersion = ref(0);
const datasetSaving = ref(false);
const datasetError = ref("");
const datasetDialog = ref(false),
  editingDataset = ref<Dataset | null>(null),
  editingId = ref("");
const selectedDataset = computed(() =>
  datasets.value.find((d) => d.id === datasetId.value),
);
const currentPage = computed(() => pages.find((p) => p.id === page.value)!);
const admin = computed(() => user.value?.role === "ADMIN");
const canExecute = computed(() => !!user.value && user.value.role !== "VIEWER");
const adminPages: Page[] = ["sources", "settings", "governance"];
const visiblePages = computed(() =>
  pages.filter((p) => admin.value || !adminPages.includes(p.id)),
);
function sessionExpired() {
  clearSession();
  loginError.value = "会话已过期，请重新登录。";
}
const examples = computed(() => {
  const dataset = selectedDataset.value;
  if (!dataset?.metrics.length) return [];
  if (!dataset.dimensions.length)
    return dataset.metrics.slice(0, 4).map((metric) => ({
      title: metric.name,
      question: metric.name,
      icon: "trend" as IconName,
    }));
  return dataset.dimensions.slice(0, 4).map((dimension, index) => {
    const metric = dataset.metrics[index % dataset.metrics.length]!;
    return {
      title: `${dimension.name} · ${metric.name}`,
      question: `各${dimension.name}${metric.name}`,
      icon: (["trend", "share", "series", "distribution"] as const)[index]!,
    };
  });
});
function datasetForRun(run: Run) {
  return datasets.value.find((dataset) => dataset.id === run.datasetId);
}
function resetSourceDraft() {
  sourceDraft.value = {
    name: "",
    type: "POSTGRESQL",
    jdbcUrl: "",
    username: "",
    password: "",
  };
}
// A follow-up only makes sense against the same dataset in agent mode.
watch([datasetId, mode], () => newConversation());
const providerDefaults: Record<ModelProvider, string> = {
  OPENAI_COMPATIBLE: "",
  OLLAMA: "http://127.0.0.1:11434",
};
watch(
  () => model.value.provider,
  (next, previous) => {
    if (!previous || next === previous) return;
    if (
      !model.value.baseUrl ||
      model.value.baseUrl === providerDefaults[previous]
    )
      model.value.baseUrl = providerDefaults[next];
  },
);
watch(
  sourceDialog,
  (open) => {
    if (!open) resetSourceDraft();
  },
  { flush: "sync" },
);
watch(
  datasetDialog,
  (open) => {
    if (!open) {
      datasetEditorVersion.value++;
      datasetSaving.value = false;
      datasetError.value = "";
      editingDataset.value = null;
      editingId.value = "";
    }
  },
  { flush: "sync" },
);
function clearSession() {
  session.invalidate();
  modelVersions.clear();
  catalogVersion++;
  queryVersion++;
  pendingQueryDataset = "";
  detailVersion++;
  booting.value = false;
  resetSourceDraft();
  datasetEditorVersion.value++;
  datasetSaving.value = false;
  datasetError.value = "";
  editingDataset.value = null;
  editingId.value = "";
  tables.value = [];
  tableSource.value = "";
  credentials.value = { username: "", password: "" };
  error.value = "";
  loginError.value = "";
  busy.value = false;
  querying.value = false;
  evaluating.value = false;
  saving.value = false;
  testing.value = "";
  loginBusy.value = false;
  user.value = null;
  activeRun.value = null;
  detail.value = null;
  runs.value = [];
  runsOffset.value = 0;
  runPageOffsets.value = [0];
  runsNextOffset.value = null;
  runLoadVersion++;
  sources.value = [];
  datasets.value = [];
  evaluations.value = [];
  evaluationResult.value = null;
  evaluationReports.value = [];
  reportLoading.value = false;
  datasetId.value = "";
  question.value = "";
  newConversation();
  system.value = null;
  page.value = "ask";
  mode.value = "demo";
  sourceDialog.value = false;
  datasetDialog.value = false;
  tableDialog.value = false;
  model.value = {
    provider: "OPENAI_COMPATIBLE",
    baseUrl: "",
    model: "",
    configured: false,
    maxSteps: 8,
    timeoutSeconds: 60,
    apiKey: "",
  };
}
function message(e: unknown) {
  return e instanceof Error ? e.message : "操作失败，请稍后重试。";
}
async function handle<T>(
  work: () => Promise<T>,
  valid: () => boolean = () => true,
): Promise<T | undefined> {
  const current = session.capture();
  error.value = "";
  try {
    return await work();
  } catch (e) {
    if (!current() || !valid() || e instanceof StaleSessionError) return;
    if (e instanceof ApiError && e.status === 401) {
      clearSession();
      loginError.value = message(e);
    } else error.value = message(e);
  }
}
function replaceDatasets(value: Dataset[], preserveQueryVersion?: number) {
  const changed = modelVersions.update(value);
  if (changed.size) catalogVersion++;
  const stillValid = (run: Run) =>
    !changed.has(run.datasetId) &&
    runMatchesDataset(
      run,
      value.find((dataset) => dataset.id === run.datasetId),
    );
  if (activeRun.value && !stillValid(activeRun.value)) activeRun.value = null;
  if (detail.value && !stillValid(detail.value)) detail.value = null;
  runs.value = runs.value.filter(stillValid);
  if (
    pendingQueryDataset &&
    changed.has(pendingQueryDataset) &&
    queryVersion !== preserveQueryVersion
  ) {
    queryVersion++;
    pendingQueryDataset = "";
    querying.value = false;
  }
  datasets.value = value;
  if (!value.some((dataset) => dataset.id === datasetId.value))
    datasetId.value = value[0]?.id || "";
}
/** Recover a legitimate new-scope response without replaying the database/model query.
 * A response whose original local epoch was already invalidated is never eligible.
 */
async function reconcileRunModels(
  responseRuns: Run[],
  snapshot: Map<string, number>,
  current: () => boolean,
  preserveQueryVersion?: number,
): Promise<Map<string, number>> {
  const eligible = responseRuns.filter((run) =>
    modelVersions.matches(run.datasetId, snapshot.get(run.datasetId)),
  );
  if (!eligible.some((run) => !runMatchesDataset(run, datasetForRun(run))))
    return snapshot;
  const beforeRefresh = catalogVersion;
  const fresh = await request<Dataset[]>("/datasets");
  if (!current() || beforeRefresh !== catalogVersion) return snapshot;
  replaceDatasets(fresh, preserveQueryVersion);
  const updated = new Map(snapshot);
  for (const run of eligible) {
    const version = modelVersions.capture(run.datasetId);
    if (version === undefined) updated.delete(run.datasetId);
    else updated.set(run.datasetId, version);
  }
  return updated;
}
async function loadBase() {
  const current = session.capture();
  const result = await Promise.all([
    request<Dataset[]>("/datasets"),
    request<System>("/system"),
  ]);
  if (!current()) throw new StaleSessionError();
  replaceDatasets(result[0]);
  system.value = result[1];
  if (!datasetId.value) datasetId.value = datasets.value[0]?.id || "";
}
async function login() {
  if (loginBusy.value) return;
  loginError.value = "";
  if (!credentials.value.username || !credentials.value.password) {
    loginError.value = "请输入用户名和密码。";
    return;
  }
  session.invalidate();
  const current = session.capture();
  loginBusy.value = true;
  try {
    const loggedIn = await request<User>("/auth/login", {
      method: "POST",
      body: json(credentials.value),
    });
    if (!current()) return;
    user.value = loggedIn;
    credentials.value.password = "";
    await handle(loadBase);
  } catch (e) {
    if (current() && !(e instanceof StaleSessionError))
      loginError.value = message(e);
  } finally {
    if (current()) loginBusy.value = false;
  }
}
async function logout() {
  clearSession(); // Invalidate in-flight work before waiting for the server.
  const current = session.capture();
  loginBusy.value = true; // Serialize cookie-changing logout and login calls.
  try {
    await request("/auth/logout", { method: "POST" });
  } catch (e) {
    if (current() && !(e instanceof StaleSessionError))
      loginError.value = message(e);
  } finally {
    if (current()) loginBusy.value = false;
  }
}
async function navigate(target: Page) {
  if (adminPages.includes(target) && !admin.value) return;
  const current = session.capture();
  page.value = target;
  detail.value = null;
  if (target === "runs") {
    await loadRuns(0, [0]);
    return;
  }
  runLoadVersion++;
  busy.value = true;
  await handle(async () => {
    if (target === "ask") await loadBase();
    if (target === "sources") {
      const value = await request<Source[]>("/sources");
      if (current()) sources.value = value;
    }
    if (target === "datasets") {
      const value = await request<Dataset[]>("/datasets");
      if (current()) replaceDatasets(value);
    }
    if (target === "evaluations") {
      const [value, reports] = await Promise.all([
        request<Evaluation[]>("/evaluations"),
        request<EvaluationResult[]>("/evaluations/reports"),
      ]);
      if (current()) {
        evaluations.value = value;
        evaluationReports.value = reports;
      }
    }
    if (target === "settings") {
      const value = await request<Model>("/settings/model");
      if (current()) model.value = { ...value, apiKey: "" };
    }
  });
  if (current()) busy.value = false;
}
async function ask() {
  if (!canExecute.value || !question.value.trim() || !datasetId.value) return;
  const sessionCurrent = session.capture();
  const version = ++queryVersion;
  const requestedDataset = datasetId.value;
  const requestedMode = mode.value;
  const modelVersion = modelVersions.capture(requestedDataset);
  pendingQueryDataset = requestedDataset;
  const current = () => sessionCurrent() && queryVersion === version;
  const valid = () =>
    current() && modelVersions.matches(requestedDataset, modelVersion);
  querying.value = true;
  activeRun.value = null;
  await handle(async () => {
    const value = await request<Run>("/queries", {
      method: "POST",
      headers: {
        "Idempotency-Key": Array.from(
          crypto.getRandomValues(new Uint8Array(16)),
          (byte) => byte.toString(16).padStart(2, "0"),
        ).join(""),
      },
      body: json({
        question: question.value.trim(),
        datasetId: requestedDataset,
        mode: requestedMode,
        conversationId:
          requestedMode === "agent" && conversationId.value
            ? conversationId.value
            : undefined,
      }),
    });
    if (!current()) return;
    const initial = new Map<string, number>();
    if (modelVersion !== undefined) initial.set(requestedDataset, modelVersion);
    const reconciled = await reconcileRunModels(
      [value],
      initial,
      current,
      version,
    );
    if (
      current() &&
      value.datasetId === requestedDataset &&
      modelVersions.matches(
        requestedDataset,
        reconciled.get(requestedDataset),
      ) &&
      runMatchesDataset(value, datasetForRun(value))
    ) {
      activeRun.value = value;
      if (requestedMode === "agent" && value.conversationId) {
        conversationId.value = value.conversationId;
        conversationTurns.value = [...conversationTurns.value, value.question];
        question.value = "";
      }
    } else if (current()) error.value = changedScopeMessage;
  }, valid);
  if (current()) {
    querying.value = false;
    pendingQueryDataset = "";
  }
}
async function loadRuns(
  offset: number,
  cursorStack: number[] = offset === 0
    ? [0]
    : [...runPageOffsets.value, offset],
) {
  const sessionCurrent = session.capture();
  const version = ++runLoadVersion;
  const snapshot = modelVersions.snapshot();
  const current = () => sessionCurrent() && version === runLoadVersion;
  busy.value = true;
  detail.value = null;
  error.value = "";
  try {
    const value = await request<RunPage>(
      `/runs/page?offset=${Math.max(0, offset)}&limit=${runPageSize}`,
    );
    if (!current()) return;
    const reconciled = await reconcileRunModels(value.items, snapshot, current);
    if (!current()) return;
    runs.value = value.items.filter(
      (run) =>
        modelVersions.matches(run.datasetId, reconciled.get(run.datasetId)) &&
        runMatchesDataset(run, datasetForRun(run)),
    );
    if (runs.value.length < value.items.length)
      error.value = changedScopeMessage;
    runsOffset.value = offset;
    runsNextOffset.value = value.nextOffset;
    runPageOffsets.value = [...cursorStack];
  } catch (e) {
    if (!current() || e instanceof StaleSessionError) return;
    if (e instanceof ApiError && e.status === 401) sessionExpired();
    else error.value = message(e);
  } finally {
    if (current()) busy.value = false;
  }
}
async function loadOlderRuns() {
  if (busy.value || runsNextOffset.value === null) return;
  await loadRuns(runsNextOffset.value, [
    ...runPageOffsets.value,
    runsNextOffset.value,
  ]);
}
async function loadNewerRuns() {
  if (busy.value || runPageOffsets.value.length < 2) return;
  const stack = runPageOffsets.value.slice(0, -1);
  await loadRuns(stack[stack.length - 1]!, stack);
}
async function showRun(id: string) {
  const sessionCurrent = session.capture();
  const version = ++detailVersion;
  const current = () => sessionCurrent() && detailVersion === version;
  const snapshot = modelVersions.snapshot();
  busy.value = true;
  await handle(async () => {
    const value = await request<Run>(`/runs/${encodeURIComponent(id)}`);
    if (!current()) return;
    const reconciled = await reconcileRunModels([value], snapshot, current);
    if (
      current() &&
      modelVersions.matches(value.datasetId, reconciled.get(value.datasetId)) &&
      runMatchesDataset(value, datasetForRun(value))
    )
      detail.value = value;
    else if (current()) error.value = changedScopeMessage;
  }, current);
  if (current()) busy.value = false;
}
async function createSource() {
  const current = session.capture();
  if (!sourceDraft.value.name || !sourceDraft.value.jdbcUrl) {
    ElMessage.warning("请填写连接名称和 JDBC URL");
    return;
  }
  saving.value = true;
  await handle(async () => {
    await request("/sources", {
      method: "POST",
      body: json(sourceDraft.value),
    });
    if (!current()) return;
    sourceDialog.value = false;
    sourceDraft.value = {
      name: "",
      type: "POSTGRESQL",
      jdbcUrl: "",
      username: "",
      password: "",
    };
    const value = await request<Source[]>("/sources");
    if (!current()) return;
    sources.value = value;
    ElMessage.success("数据连接已创建");
  });
  if (current()) saving.value = false;
}
async function testSource(source: Source) {
  const current = session.capture();
  testing.value = source.id;
  await handle(async () => {
    const result = await request<{ success: boolean; message: string }>(
      `/sources/${encodeURIComponent(source.id)}/test`,
      { method: "POST" },
    );
    if (!current()) return;
    ElMessage({
      message: result.message,
      type: result.success ? "success" : "error",
    });
    const refreshed = await request<Source[]>("/sources");
    if (current()) sources.value = refreshed;
  });
  if (current()) testing.value = "";
}
async function showTables(source: Source) {
  const current = session.capture();
  tableSource.value = source.name;
  tables.value = [];
  busy.value = true;
  await handle(async () => {
    const value = await request<typeof tables.value>(
      `/sources/${encodeURIComponent(source.id)}/tables`,
    );
    if (!current()) return;
    tables.value = value;
    tableDialog.value = true;
  });
  if (current()) busy.value = false;
}
function editDataset(dataset?: Dataset) {
  if (!admin.value) return;
  datasetEditorVersion.value++;
  datasetSaving.value = false;
  datasetError.value = "";
  editingId.value = dataset?.id || "";
  editingDataset.value = dataset ? JSON.parse(JSON.stringify(dataset)) : null;
  datasetDialog.value = true;
}
async function saveDataset(body: Dataset) {
  if (!admin.value || datasetSaving.value || !datasetDialog.value) return;
  const sessionCurrent = session.capture();
  const editorVersion = datasetEditorVersion.value;
  const originalId = editingId.value;
  const current = () =>
    sessionCurrent() &&
    datasetDialog.value &&
    datasetEditorVersion.value === editorVersion;
  datasetSaving.value = true;
  datasetError.value = "";
  try {
    await request(
      originalId ? `/datasets/${encodeURIComponent(originalId)}` : "/datasets",
      { method: originalId ? "PUT" : "POST", body: json(body) },
    );
    if (!current()) return;
    const value = await request<Dataset[]>("/datasets");
    if (!current()) return;
    replaceDatasets(value);
    datasetDialog.value = false;
    ElMessage.success("语义模型已保存");
  } catch (e) {
    if (!current() || e instanceof StaleSessionError) return;
    if (e instanceof ApiError && e.status === 401) sessionExpired();
    else datasetError.value = message(e);
  } finally {
    if (current()) datasetSaving.value = false;
  }
}
async function saveModel() {
  const current = session.capture();
  if (!model.value.baseUrl || !model.value.model) {
    ElMessage.warning("请填写 API 地址和模型名称");
    return;
  }
  saving.value = true;
  await handle(async () => {
    await request("/settings/model", {
      method: "PUT",
      body: json(model.value),
    });
    if (!current()) return;
    const value = await request<Model>("/settings/model");
    if (!current()) return;
    model.value = { ...value, apiKey: "" };
    await loadBase();
    if (!current()) return;
    ElMessage.success("模型配置已保存");
  });
  if (current()) saving.value = false;
}
async function runEvaluation() {
  if (!canExecute.value) return;
  const current = session.capture();
  evaluating.value = true;
  evaluationResult.value = null;
  await handle(async () => {
    const value = await request<EvaluationResult>("/evaluations/run", {
      method: "POST",
      body: json({ mode: mode.value }),
    });
    if (!current()) return;
    evaluationResult.value = value;
    await refreshEvaluationReports();
  });
  if (current()) evaluating.value = false;
}
async function refreshEvaluationReports() {
  const current = session.capture();
  reportLoading.value = true;
  await handle(async () => {
    const reports = await request<EvaluationResult[]>("/evaluations/reports");
    if (current()) evaluationReports.value = reports;
  });
  if (current()) reportLoading.value = false;
}
async function openEvaluationReport(id: string) {
  const current = session.capture();
  reportLoading.value = true;
  await handle(async () => {
    const report = await request<EvaluationResult>(
      `/evaluations/reports/${encodeURIComponent(id)}`,
    );
    if (current()) evaluationResult.value = report;
  });
  if (current()) reportLoading.value = false;
}
function date(value: string) {
  return new Date(value).toLocaleString("zh-CN", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}
onMounted(async () => {
  const current = session.capture();
  try {
    const restored = await request<User>("/auth/me");
    if (!current()) return;
    user.value = restored;
    // Bootstrap errors must use the visible authenticated error channel.
    await handle(loadBase);
  } catch (e) {
    if (current() && !(e instanceof StaleSessionError)) {
      if (e instanceof ApiError && e.status === 401) clearSession();
      else loginError.value = message(e);
    }
  } finally {
    if (current()) booting.value = false;
  }
});
</script>
<template>
  <div v-if="booting" class="boot">
    <div class="brand-mark">m</div>
    <p>正在连接 MateData…</p>
  </div>
  <main v-else-if="!user" class="login-layout">
    <section class="login-story">
      <div class="brand">
        <span class="brand-mark">m</span> MateData <small>OPEN SOURCE</small>
      </div>
      <div>
        <span class="eyebrow">YOUR DATA. YOUR ANSWERS.</span>
        <h1>让每个好问题，<br />都有数据回答。</h1>
        <p>
          连接企业数据，用业务语言探索洞察。<br />从问题、SQL
          到结果，每一步清晰可追溯。
        </p>
        <div class="story-grid">
          <span>01 / 连接数据</span><span>02 / 定义语义</span
          ><span>03 / 开始探索</span>
        </div>
      </div>
      <span class="login-foot">为团队构建的开源智能问数工作台</span>
    </section>
    <section class="login-form">
      <div class="login-box">
        <span class="eyebrow">WELCOME BACK</span>
        <h2>登录你的工作空间</h2>
        <p class="muted">使用管理员分配的账户继续。</p>
        <el-alert
          v-if="loginError"
          :title="loginError"
          type="error"
          :closable="false"
          show-icon
        /><el-form label-position="top" @submit.prevent="login"
          ><el-form-item label="用户名"
            ><el-input
              v-model="credentials.username"
              autocomplete="username"
              placeholder="输入用户名"
              size="large" /></el-form-item
          ><el-form-item label="密码"
            ><el-input
              v-model="credentials.password"
              type="password"
              autocomplete="current-password"
              show-password
              placeholder="输入密码"
              size="large" /></el-form-item
          ><el-button
            native-type="submit"
            type="primary"
            size="large"
            :loading="loginBusy"
            class="full"
            >进入工作空间<AppIcon
              name="forward"
              class="el-icon--right" /></el-button
        ></el-form>
        <p class="login-hint">
          本地部署首次登录请使用环境变量配置的密码，或查看后端启动日志中的临时密码。
        </p>
      </div>
    </section>
  </main>
  <div v-else class="app-layout">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="navigate('ask')"
        ><span class="brand-mark">m</span>MateData</a
      >
      <div class="workspace-label">
        <span class="workspace-icon">M</span>
        <div>团队工作空间<small>Enterprise analytics</small></div>
        <span class="dot"></span>
      </div>
      <span class="nav-label">工作空间</span>
      <nav>
        <button
          v-for="item in visiblePages"
          :key="item.id"
          :class="{ active: page === item.id }"
          @click="navigate(item.id)"
        >
          <AppIcon class="nav-icon" :name="item.icon" />{{ item.name
          }}<span v-if="item.id === 'ask'" class="nav-badge">AI</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="engine-state">
          <span
            class="dot"
            :class="{ neutral: !system?.modelConfigured }"
          ></span
          >{{ system?.modelConfigured ? "Agent 引擎已配置" : "模型未配置" }}
        </div>
        <p>开源 · 可控 · 可追溯</p>
        <button class="user-button" @click="logout">
          <span class="avatar">{{
            user.displayName?.[0] || user.username[0]
          }}</span
          ><span
            >{{ user.displayName || user.username
            }}<small>{{ admin ? "管理员" : "成员" }}</small></span
          ><span class="logout">退出<AppIcon name="logout" /></span>
        </button>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <span>工作空间 <b>/</b> {{ currentPage.name }}</span>
        <div>
          <span class="version">{{
            system?.version ? "v" + system.version : "MateData"
          }}</span
          ><el-tag effect="plain" type="info">本地工作空间</el-tag>
        </div>
      </header>
      <main class="content">
        <el-alert
          v-if="error"
          :title="error"
          type="error"
          show-icon
          closable
          @close="error = ''"
          class="page-error"
        />
        <template v-if="page === 'ask'">
          <el-alert
            v-if="!canExecute"
            title="只读成员不能执行问数或评测；可以查看自己已有的查询记录与评测报告。"
            type="info"
            :closable="false"
            class="page-error"
          />
          <div class="page-heading">
            <div>
              <span class="eyebrow">ASK YOUR DATA</span>
              <h1>今天，想从数据中发现什么？</h1>
              <p>用自然语言提问，让业务洞察触手可及。</p>
            </div>
            <AppIcon class="heading-decoration" name="ask" />
          </div>
          <section class="composer">
            <div class="composer-top">
              <el-select
                v-model="datasetId"
                placeholder="选择数据集"
                :disabled="querying || !canExecute"
                aria-label="选择数据集"
                style="width: 230px"
                ><el-option
                  v-for="dataset in datasets"
                  :key="dataset.id"
                  :label="dataset.name"
                  :value="dataset.id" /></el-select
              ><el-radio-group
                v-model="mode"
                :disabled="querying || !canExecute"
                size="small"
                ><el-radio-button value="demo"
                  >规则解析（无模型调用）</el-radio-button
                ><el-radio-button
                  value="agent"
                  :disabled="!system?.modelConfigured"
                  >Agent 查询</el-radio-button
                ></el-radio-group
              >
            </div>
            <div
              v-if="mode === 'agent' && conversationTurns.length"
              class="conversation-strip"
            >
              <AppIcon name="conversation" /><span
                >追问模式 · 已进行
                {{ conversationTurns.length }} 轮，智能体会结合上文理解</span
              ><span class="conversation-turns">{{
                conversationTurns.join(" / ")
              }}</span
              ><el-button
                link
                type="primary"
                :disabled="querying"
                @click="newConversation"
                ><AppIcon name="add" class="el-icon--left" />新对话</el-button
              >
            </div>
            <el-input
              v-model="question"
              type="textarea"
              :rows="3"
              resize="none"
              maxlength="2000"
              :placeholder="
                mode === 'agent' && conversationTurns.length
                  ? '继续追问，例如：那利润呢？只看华东'
                  : examples[0]?.question
                    ? `例如：${examples[0].question}`
                    : '输入当前数据集中的业务问题'
              "
              aria-label="输入业务问题"
              :disabled="querying || !canExecute"
              @keydown.ctrl.enter.prevent="ask"
              @keydown.meta.enter.prevent="ask"
            />
            <div class="composer-bottom">
              <span
                ><span class="dot"></span
                >{{
                  selectedDataset?.sourceId === "demo_sales"
                    ? "内置示例数据，不代表真实业务"
                    : selectedDataset
                      ? "查询当前语义模型连接的数据源"
                      : "请先选择数据集"
                }}
                · ⌘ / Ctrl + Enter</span
              ><el-button
                type="primary"
                size="large"
                :disabled="!canExecute || !question.trim() || !datasetId"
                :loading="querying"
                @click="ask"
                >{{ querying ? "正在分析" : "开始分析" }}
                <AppIcon v-if="!querying" name="send" class="el-icon--right"
              /></el-button>
            </div>
          </section>
          <div v-if="!system?.modelConfigured" class="inline-note">
            Agent 查询尚未启用。{{
              admin
                ? "在模型设置中配置服务后，即可使用真实模型推理。"
                : "请联系管理员配置模型服务。"
            }}<button v-if="admin" @click="navigate('settings')">
              配置模型<AppIcon name="forward" />
            </button>
          </div>
          <div v-if="querying" class="pending">
            <AppIcon name="loading" spin />
            <div>
              <strong>正在理解问题并执行查询</strong>
              <p>完成后将一并展示结果、SQL 与执行轨迹，请稍候。</p>
            </div>
          </div>
          <RunResult
            v-if="activeRun"
            :run="activeRun"
            :dataset="datasetForRun(activeRun)"
          />
          <template v-if="!activeRun && !querying"
            ><div class="section-heading">
              <h2>从这些问题开始</h2>
              <span
                >{{ selectedDataset?.name || "当前模型" }} ·
                根据可用指标与维度生成</span
              >
            </div>
            <div class="example-grid">
              <button
                v-for="example in examples"
                :key="example.question"
                @click="question = example.question"
              >
                <span class="example-icon"
                  ><AppIcon :name="example.icon" /></span
                ><small>{{ example.title }}</small
                ><strong>{{ example.question }}</strong
                ><AppIcon class="example-arrow" name="open" />
              </button>
            </div>
            <section class="dataset-preview">
              <div class="dataset-summary">
                <span class="eyebrow">DATA CONTEXT</span>
                <h2>{{ selectedDataset?.name || "尚无可用数据集" }}</h2>
                <p>
                  {{
                    selectedDataset
                      ? selectedDataset.description ||
                        "暂无业务说明，可在语义模型中补充。"
                      : "请先创建数据连接与语义模型。"
                  }}
                </p>
                <button @click="navigate('datasets')">
                  查看语义模型<AppIcon name="forward" />
                </button>
              </div>
              <div class="field-summary">
                <div>
                  <small>可用指标</small>
                  <div>
                    <el-tag
                      v-for="field in selectedDataset?.metrics"
                      :key="field.id"
                      effect="plain"
                      >{{ field.name }}</el-tag
                    >
                  </div>
                </div>
                <div>
                  <small>分析维度</small>
                  <div>
                    <el-tag
                      v-for="field in selectedDataset?.dimensions"
                      :key="field.id"
                      effect="plain"
                      type="info"
                      >{{ field.name }}</el-tag
                    >
                  </div>
                </div>
              </div>
            </section>
            <div class="trust-row">
              <span><AppIcon name="readOnly" />只读查询保护</span
              ><span><AppIcon name="runs" />完整执行轨迹</span
              ><span><AppIcon name="datasets" />语义层统一口径</span>
            </div></template
          ></template
        >
        <template v-else
          ><div class="page-heading compact">
            <div>
              <span class="eyebrow">{{ page.toUpperCase() }}</span>
              <h1>{{ currentPage.name }}</h1>
              <p>{{ currentPage.caption }}</p>
            </div>
            <el-button
              v-if="page === 'sources' && admin"
              type="primary"
              @click="sourceDialog = true"
              ><AppIcon name="add" class="el-icon--left" />新建连接</el-button
            ><el-button
              v-if="page === 'datasets' && admin"
              type="primary"
              @click="editDataset()"
              ><AppIcon name="add" class="el-icon--left" />新建模型</el-button
            ><el-button v-if="page === 'runs'" @click="navigate('runs')"
              >刷新记录</el-button
            >
          </div>
          <el-skeleton v-if="busy" :rows="6" animated />
          <template v-else-if="page === 'sources'"
            ><div class="info-strip">
              连接 PostgreSQL 或 MySQL
              数据库。建议使用专用只读账户；密码在服务端加密保存，不会返回浏览器。
            </div>
            <div class="source-grid">
              <article
                v-for="source in sources"
                :key="source.id"
                class="source-card"
              >
                <div class="source-head">
                  <span class="source-icon"><AppIcon name="database" /></span
                  ><el-tag type="info" effect="plain">{{
                    source.type === "DEMO" ? "内置演示" : source.type
                  }}</el-tag>
                </div>
                <h2>{{ source.name }}</h2>
                <p class="source-url">{{ source.jdbcUrl || "内置销售数据" }}</p>
                <div class="source-meta">
                  <span>{{ source.username || "系统内置" }}</span
                  ><span>{{ source.status }}</span>
                </div>
                <div class="card-actions">
                  <el-button
                    :loading="testing === source.id"
                    @click="testSource(source)"
                    >测试连接</el-button
                  ><el-button text type="primary" @click="showTables(source)"
                    >浏览数据表<AppIcon name="forward" class="el-icon--right"
                  /></el-button>
                </div>
              </article>
            </div>
            <el-empty
              v-if="!sources.length"
              description="还没有数据连接，新建连接以开始探索。"
          /></template>
          <template v-else-if="page === 'datasets'"
            ><div class="info-strip">
              将业务指标、维度与物理字段关联，统一团队的分析口径。
            </div>
            <div class="model-grid">
              <article
                v-for="dataset in datasets"
                :key="dataset.id"
                class="model-card"
              >
                <div class="section-heading">
                  <div>
                    <span class="eyebrow"
                      >{{ dataset.sourceId }} / {{ dataset.tableName }}</span
                    >
                    <h2>{{ dataset.name }}</h2>
                  </div>
                  <el-button
                    v-if="admin"
                    text
                    type="primary"
                    @click="editDataset(dataset)"
                    ><AppIcon
                      name="edit"
                      class="el-icon--left"
                    />编辑模型</el-button
                  >
                </div>
                <p class="muted">{{ dataset.description }}</p>
                <div class="semantic-fields">
                  <div>
                    <h3>
                      指标 <small>{{ dataset.metrics.length }}</small>
                    </h3>
                    <div
                      v-for="field in dataset.metrics"
                      :key="field.id"
                      class="semantic-row"
                    >
                      <strong>{{ field.name }}</strong
                      ><code>{{ field.aggregation }}({{ field.column }})</code>
                    </div>
                  </div>
                  <div>
                    <h3>
                      维度 <small>{{ dataset.dimensions.length }}</small>
                    </h3>
                    <div
                      v-for="field in dataset.dimensions"
                      :key="field.id"
                      class="semantic-row"
                    >
                      <strong>{{ field.name }}</strong
                      ><code>{{ field.column }}</code>
                    </div>
                  </div>
                </div>
              </article>
            </div>
            <el-empty v-if="!datasets.length" description="暂无语义模型"
          /></template>
          <template v-else-if="page === 'runs'"
            ><div v-if="detail">
              <el-button text type="primary" @click="detail = null"
                ><AppIcon
                  name="back"
                  class="el-icon--left"
                />返回当前页</el-button
              ><RunResult :run="detail" :dataset="datasetForRun(detail)" />
            </div>
            <section v-else class="table-card">
              <el-table
                :data="runs"
                @row-click="(row: Run) => showRun(row.id)"
                row-class-name="clickable-row"
                empty-text="本页没有可见查询记录；若仍有较早记录，可继续翻页。"
                ><el-table-column
                  prop="question"
                  label="业务问题"
                  min-width="270"
                /><el-table-column label="模式" width="115"
                  ><template #default="scope"
                    ><el-tag type="info" effect="plain">{{
                      scope.row.mode === "demo" ? "规则解析" : "Agent 查询"
                    }}</el-tag></template
                  ></el-table-column
                ><el-table-column label="状态" width="110"
                  ><template #default="scope"
                    ><el-tag :type="runStatus(scope.row.status).type">{{
                      runStatus(scope.row.status).label
                    }}</el-tag></template
                  ></el-table-column
                ><el-table-column
                  prop="rowCount"
                  label="结果行数"
                  width="95"
                /><el-table-column label="耗时" width="100"
                  ><template #default="scope"
                    >{{ scope.row.durationMs }} ms</template
                  ></el-table-column
                ><el-table-column label="查询时间" width="160"
                  ><template #default="scope">{{
                    date(scope.row.createdAt)
                  }}</template></el-table-column
                ><el-table-column width="65"
                  ><template #default="scope"
                    ><el-button
                      text
                      type="primary"
                      @click.stop="showRun(scope.row.id)"
                      >查看</el-button
                    ></template
                  ></el-table-column
                ></el-table
              >
              <div class="section-heading">
                <span
                  >第 {{ runPageOffsets.length }} 页 · 本页 {{ runs.length }} 条
                  · 每页最多 {{ runPageSize }} 条</span
                >
                <div>
                  <el-button
                    :disabled="busy || runPageOffsets.length < 2"
                    @click="loadNewerRuns"
                    ><AppIcon
                      name="back"
                      class="el-icon--left"
                    />较新记录</el-button
                  ><el-button
                    :disabled="busy || !hasOlderRuns"
                    @click="loadOlderRuns"
                    >较早记录<AppIcon name="forward" class="el-icon--right"
                  /></el-button>
                </div>
              </div></section
          ></template>
          <template v-else-if="page === 'evaluations'">
            <el-alert
              v-if="!canExecute"
              title="只读成员不能运行评测，可以查看自己的历史评测报告。"
              type="info"
              :closable="false"
              class="page-error" />
            <section class="table-card">
              <div class="section-heading">
                <h2>历史评测报告</h2>
                <el-button
                  :loading="reportLoading"
                  @click="refreshEvaluationReports"
                  >刷新报告</el-button
                >
              </div>
              <el-table
                :data="evaluationReports"
                empty-text="暂无历史评测报告，运行评测后将自动保存。"
                ><el-table-column
                  prop="id"
                  label="报告编号"
                  min-width="220"
                /><el-table-column label="通过用例" width="130"
                  ><template #default="scope"
                    >{{ scope.row.passed }} / {{ scope.row.total }}</template
                  ></el-table-column
                ><el-table-column label="耗时" width="120"
                  ><template #default="scope"
                    >{{ scope.row.durationMs }} ms</template
                  ></el-table-column
                ><el-table-column label="详情" width="100"
                  ><template #default="scope"
                    ><el-button
                      text
                      type="primary"
                      :disabled="reportLoading"
                      @click="openEvaluationReport(scope.row.id)"
                      >查看报告</el-button
                    ></template
                  ></el-table-column
                ></el-table
              >
            </section>
            <div class="evaluation-banner">
              <div>
                <h2>每一次迭代，都有标准可依</h2>
                <p>
                  对预设业务问题执行查询，验证指标、维度与结果是否符合预期。
                </p>
              </div>
              <div class="evaluation-actions">
                <el-select v-model="mode" aria-label="评测模式"
                  ><el-option
                    label="规则解析（无模型调用）"
                    value="demo" /><el-option
                    label="Agent 查询"
                    value="agent"
                    :disabled="!system?.modelConfigured" /></el-select
                ><el-button
                  type="primary"
                  :loading="evaluating"
                  :disabled="!canExecute"
                  @click="runEvaluation"
                  >运行评测<AppIcon name="forward" class="el-icon--right"
                /></el-button>
              </div>
            </div>
            <section v-if="evaluationResult" class="result-card">
              <div class="section-heading">
                <h2>
                  评测结果 {{ evaluationResult.passed }} /
                  {{ evaluationResult.total }} 通过
                </h2>
                <span>{{ evaluationResult.durationMs }} ms</span>
              </div>
              <el-table :data="evaluationResult.results"
                ><el-table-column prop="name" label="用例" /><el-table-column
                  label="结果"
                  width="90"
                  ><template #default="scope"
                    ><el-tag :type="scope.row.passed ? 'success' : 'danger'">{{
                      scope.row.passed ? "通过" : "失败"
                    }}</el-tag></template
                  ></el-table-column
                ><el-table-column
                  prop="message"
                  label="说明"
                  min-width="240"
                /><el-table-column label="详情" width="90"
                  ><template #default="scope"
                    ><el-button
                      v-if="scope.row.runId"
                      text
                      @click="
                        page = 'runs';
                        showRun(scope.row.runId);
                      "
                      >查看执行</el-button
                    ></template
                  ></el-table-column
                ></el-table
              >
            </section>
            <section class="table-card">
              <el-table :data="evaluations" empty-text="暂无评测用例"
                ><el-table-column
                  prop="name"
                  label="评测用例" /><el-table-column
                  prop="question"
                  label="问题"
                  min-width="200" /><el-table-column
                  prop="expectedMetric"
                  label="预期指标" /><el-table-column
                  prop="expectedDimension"
                  label="预期维度"
              /></el-table></section
          ></template>
          <template v-else-if="page === 'governance' && admin"
            ><Governance
              :request="request"
              :datasets="datasets"
              @session-expired="sessionExpired" /></template
          ><template v-else-if="page === 'settings' && admin"
            ><section class="settings-card">
              <div class="section-heading">
                <div>
                  <h2>模型服务</h2>
                  <p class="muted">
                    问数智能体基于 AgentScope Java
                    运行，模型需支持工具调用（Function Calling）。
                  </p>
                </div>
                <el-tag :type="model.configured ? 'success' : 'info'">{{
                  model.configured ? "已配置" : "未配置"
                }}</el-tag>
              </div>
              <el-alert
                v-if="!admin"
                title="只有管理员可以修改模型配置。"
                type="info"
                :closable="false"
              /><el-form
                label-position="top"
                :disabled="!admin"
                @submit.prevent="saveModel"
                ><el-form-item label="服务类型"
                  ><el-radio-group v-model="model.provider"
                    ><el-radio-button value="OPENAI_COMPATIBLE"
                      >OpenAI 兼容</el-radio-button
                    ><el-radio-button value="OLLAMA"
                      >Ollama 本地模型</el-radio-button
                    ></el-radio-group
                  ><span class="form-help">{{
                    model.provider === "OLLAMA"
                      ? "连接本机或内网的 Ollama 服务，无需 API Key；请选择支持工具调用的模型，如 qwen2.5、gemma4。"
                      : "OpenAI、通义千问（DashScope 兼容模式）、DeepSeek 等提供 Chat Completions 接口的服务。"
                  }}</span></el-form-item
                ><el-form-item label="API Base URL"
                  ><el-input
                    v-model="model.baseUrl"
                    :placeholder="
                      model.provider === 'OLLAMA'
                        ? 'http://127.0.0.1:11434'
                        : 'https://your-model-provider.example/v1'
                    "
                  /><span class="form-help"
                    >由服务端访问的模型 API 地址。</span
                  ></el-form-item
                ><el-form-item label="模型名称"
                  ><el-input
                    v-model="model.model"
                    :placeholder="
                      model.provider === 'OLLAMA'
                        ? '例如 gemma4:latest，可用 ollama list 查看'
                        : '输入服务商提供的模型标识'
                    " /></el-form-item
                ><el-form-item
                  v-if="model.provider !== 'OLLAMA'"
                  label="API Key"
                  ><el-input
                    v-model="model.apiKey"
                    type="password"
                    show-password
                    autocomplete="new-password"
                    :placeholder="
                      model.configured ? '留空以保留现有密钥' : '输入 API 密钥'
                    "
                  /><span class="form-help"
                    >密钥仅提交到服务端，已有密钥不会回传。</span
                  ></el-form-item
                >
                <div class="form-columns">
                  <el-form-item label="最大推理步数"
                    ><el-input-number
                      v-model="model.maxSteps"
                      :min="1"
                      :max="12" /></el-form-item
                  ><el-form-item label="单次问数超时（秒）"
                    ><el-input-number
                      v-model="model.timeoutSeconds"
                      :min="5"
                      :max="300"
                  /></el-form-item>
                </div>
                <el-button
                  v-if="admin"
                  type="primary"
                  native-type="submit"
                  :loading="saving"
                  >保存模型配置</el-button
                ></el-form
              >
            </section></template
          ></template
        >
        <footer class="content-footer">
          <span>MateData / 开源智能问数平台</span
          ><span>从数据到决策，保持清晰。</span>
        </footer>
      </main>
    </div>
    <el-dialog
      v-model="sourceDialog"
      title="新建数据连接"
      width="min(560px, 94vw)"
      ><el-alert
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        class="page-error"
      /><el-form label-position="top" @submit.prevent="createSource"
        ><el-form-item label="连接名称"
          ><el-input
            v-model="sourceDraft.name"
            placeholder="例如：业务分析数据库" /></el-form-item
        ><el-form-item label="数据库类型"
          ><el-select v-model="sourceDraft.type"
            ><el-option value="POSTGRESQL" label="PostgreSQL" /><el-option
              value="MYSQL"
              label="MySQL" /></el-select></el-form-item
        ><el-form-item label="JDBC URL"
          ><el-input
            v-model="sourceDraft.jdbcUrl"
            :placeholder="
              sourceDraft.type === 'POSTGRESQL'
                ? 'jdbc:postgresql://host:5432/database'
                : 'jdbc:mysql://host:3306/database'
            " /></el-form-item
        ><el-form-item label="用户名"
          ><el-input
            v-model="sourceDraft.username"
            autocomplete="off" /></el-form-item
        ><el-form-item label="密码"
          ><el-input
            v-model="sourceDraft.password"
            type="password"
            show-password
            autocomplete="new-password" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="sourceDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="createSource"
          >创建连接</el-button
        ></template
      ></el-dialog
    >
    <el-dialog
      v-model="tableDialog"
      :title="tableSource + ' · 数据表'"
      width="min(720px,94vw)"
      ><el-collapse
        ><el-collapse-item
          v-for="table in tables"
          :key="table.name"
          :title="table.name"
          :name="table.name"
          ><el-table :data="table.columns"
            ><el-table-column prop="name" label="字段" /><el-table-column
              prop="type"
              label="类型" /></el-table></el-collapse-item></el-collapse
      ><el-empty v-if="!tables.length" description="未发现可见数据表"
    /></el-dialog>
    <el-dialog
      v-model="datasetDialog"
      :title="editingId ? '编辑语义模型' : '新建语义模型'"
      width="min(1100px,96vw)"
      destroy-on-close
    >
      <el-alert
        v-if="datasetError"
        :title="datasetError"
        type="error"
        :closable="false"
        class="page-error"
      />
      <DatasetDesigner
        :key="datasetEditorVersion"
        v-if="datasetDialog && admin"
        :dataset="editingDataset"
        :request="request"
        :saving="datasetSaving"
        @save="saveDataset"
        @cancel="datasetDialog = false"
        @session-expired="sessionExpired"
      />
    </el-dialog>
  </div>
</template>
