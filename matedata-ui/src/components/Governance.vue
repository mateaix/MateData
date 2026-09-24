<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { ApiError, json } from "../api";
import { createSessionScope, StaleSessionError } from "../session";
import type { Dataset, User, PermissionGrant, AuditEntry } from "../types";
const props = defineProps<{
  request: <T>(path: string, init?: RequestInit) => Promise<T>;
  datasets: Dataset[];
}>();
const emit = defineEmits<{ sessionExpired: [] }>();
const scope = createSessionScope(),
  tab = ref("users"),
  loading = ref(false),
  saving = ref(false),
  error = ref("");
const users = ref<User[]>([]),
  grants = ref<PermissionGrant[]>([]),
  audit = ref<AuditEntry[]>([]),
  userDialog = ref(false);
const newUser = () => ({
  username: "",
  displayName: "",
  role: "ANALYST",
  password: "",
});
const userDraft = ref(newUser()),
  grantUser = ref(""),
  grantDataset = ref(""),
  rowFiltersText = ref("{}");
const emptyGrant = () => ({
  enabled: false,
  metrics: [] as string[],
  dimensions: [] as string[],
  rowFilters: {} as Record<string, string>,
});
const grant = ref(emptyGrant()),
  selectedDataset = computed(() =>
    props.datasets.find((d) => d.id === grantDataset.value),
  );
const existingGrant = computed(() =>
  grants.value.find(
    (g) => g.username === grantUser.value && g.datasetId === grantDataset.value,
  ),
);
watch(
  userDialog,
  (open) => {
    if (!open) userDraft.value = newUser();
  },
  { flush: "sync" },
);
function hydrateSelectedGrant() {
  const found = existingGrant.value;
  grant.value = found
    ? {
        enabled: found.enabled,
        metrics: [...found.metrics],
        dimensions: [...found.dimensions],
        rowFilters: { ...found.rowFilters },
      }
    : emptyGrant();
  rowFiltersText.value = JSON.stringify(grant.value.rowFilters, null, 2);
}
watch([grantUser, grantDataset], hydrateSelectedGrant, { flush: "sync" });
async function safely(work: (current: () => boolean) => Promise<void>) {
  const current = scope.capture();
  error.value = "";
  try {
    await work(current);
  } catch (e) {
    if (!current() || e instanceof StaleSessionError) return;
    if (e instanceof ApiError && e.status === 401) {
      emit("sessionExpired");
      return;
    }
    error.value = e instanceof Error ? e.message : "操作失败，请重试。";
  }
}
async function load() {
  const current = scope.capture();
  loading.value = true;
  await safely(async (valid) => {
    const result = await Promise.all([
      props.request<User[]>("/users"),
      props.request<PermissionGrant[]>("/permissions"),
      props.request<AuditEntry[]>("/audit"),
    ]);
    if (!valid()) return;
    [users.value, grants.value, audit.value] = result;
    hydrateSelectedGrant();
  });
  if (current()) loading.value = false;
}
async function createUser() {
  const current = scope.capture();
  const body = { ...userDraft.value };
  userDraft.value.password = "";
  if (
    !body.username ||
    !body.displayName ||
    body.password.length < 12 ||
    new TextEncoder().encode(body.password).length > 72
  ) {
    error.value =
      "请填写用户名、显示名称；密码至少 12 字符且 UTF-8 编码不超过 72 字节。";
    return;
  }
  saving.value = true;
  await safely(async (valid) => {
    await props.request("/users", { method: "POST", body: json(body) });
    if (!valid()) return;
    userDialog.value = false;
    await load();
    if (valid()) ElMessage.success("用户已创建");
  });
  if (current()) saving.value = false;
}
async function saveGrant() {
  if (!grantUser.value || !grantDataset.value) {
    error.value = "请选择用户和数据集。";
    return;
  }
  let filters: Record<string, string>;
  try {
    const value = JSON.parse(rowFiltersText.value);
    if (
      !value ||
      Array.isArray(value) ||
      typeof value !== "object" ||
      Object.values(value).some((v) => typeof v !== "string")
    )
      throw Error();
    filters = value;
  } catch {
    error.value = "行过滤必须是 JSON 对象，所有值必须为字符串。";
    return;
  }
  if (grant.value.enabled && !grant.value.metrics.length) {
    error.value = "启用授权时至少选择一个指标。";
    return;
  }
  const current = scope.capture();
  saving.value = true;
  const path = `/permissions/${encodeURIComponent(grantUser.value)}/${encodeURIComponent(grantDataset.value)}`;
  await safely(async (valid) => {
    await props.request(path, {
      method: "PUT",
      body: json({ ...grant.value, rowFilters: filters }),
    });
    if (!valid()) return;
    await load();
    if (valid()) ElMessage.success("数据集授权已保存");
  });
  if (current()) saving.value = false;
}
onMounted(load);
onBeforeUnmount(() => {
  scope.invalidate();
  userDraft.value = newUser();
  rowFiltersText.value = "{}";
  users.value = [];
  grants.value = [];
  audit.value = [];
});
</script>
<template>
  <section>
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      class="page-error"
      show-icon
    />
    <div class="section-heading">
      <span>统一管理成员身份、数据范围和操作记录。</span
      ><el-button :loading="loading" @click="load">刷新</el-button>
    </div>
    <el-tabs v-model="tab"
      ><el-tab-pane label="团队成员" name="users"
        ><div class="section-heading">
          <h2>成员与角色</h2>
          <el-button type="primary" @click="userDialog = true"
            >＋ 创建用户</el-button
          >
        </div>
        <p class="muted">
          管理员管理平台与授权；分析师执行查询；只读成员只能查看自己已有的查询与报告。
        </p>
        <el-skeleton v-if="loading" :rows="4" animated /><el-table
          v-else
          :data="users"
          empty-text="暂无成员"
          ><el-table-column prop="username" label="用户名" /><el-table-column
            prop="displayName"
            label="显示名称" /><el-table-column
            prop="role"
            label="角色" /></el-table></el-tab-pane
      ><el-tab-pane label="数据权限" name="permissions"
        ><div class="info-strip">
          外部数据集默认仅管理员可用；内置演示数据默认共享。保存关闭的授权会明确禁止该成员访问所选数据集。管理员权限不受成员授权限制。
        </div>
        <el-form
          label-position="top"
          class="governance-form"
          @submit.prevent="saveGrant"
          ><div class="form-columns">
            <el-form-item label="成员"
              ><el-select
                v-model="grantUser"
                placeholder="选择成员"
                style="width: 240px"
                ><el-option
                  v-for="member in users"
                  :key="member.username"
                  :label="`${member.displayName} (${member.username})`"
                  :value="member.username"
                  :disabled="
                    member.role === 'ADMIN'
                  " /></el-select></el-form-item
            ><el-form-item label="数据集"
              ><el-select
                v-model="grantDataset"
                placeholder="选择数据集"
                style="width: 240px"
                ><el-option
                  v-for="dataset in datasets"
                  :key="dataset.id"
                  :label="dataset.name"
                  :value="dataset.id" /></el-select
            ></el-form-item>
          </div>
          <p class="muted">
            {{
              existingGrant
                ? "正在编辑已有授权"
                : "尚无显式授权；下方为待保存的配置。"
            }}
          </p>
          <el-form-item label="允许访问"
            ><el-switch
              v-model="grant.enabled"
              active-text="已启用"
              inactive-text="明确禁用" /></el-form-item
          ><el-form-item label="允许查询的指标"
            ><el-select
              v-model="grant.metrics"
              multiple
              placeholder="启用时至少选择一个指标"
              :disabled="!grant.enabled"
              class="full"
              ><el-option
                v-for="field in selectedDataset?.metrics"
                :key="field.id"
                :label="field.name"
                :value="field.id" /></el-select></el-form-item
          ><el-form-item label="允许分组的维度"
            ><el-select
              v-model="grant.dimensions"
              multiple
              placeholder="选择可见维度"
              :disabled="!grant.enabled"
              class="full"
              ><el-option
                v-for="field in selectedDataset?.dimensions"
                :key="field.id"
                :label="field.name"
                :value="field.id" /></el-select></el-form-item
          ><el-form-item label="行过滤（维度 ID → 精确值）"
            ><el-input
              v-model="rowFiltersText"
              type="textarea"
              :rows="4"
              placeholder='{"region":"华东"}'
              class="json-editor"
              :disabled="!grant.enabled"
            /><span class="form-help"
              >值必须为字符串。服务器会强制应用这些精确过滤条件。</span
            ></el-form-item
          ><el-button
            type="primary"
            native-type="submit"
            :loading="saving"
            :disabled="!grantUser || !grantDataset"
            >保存数据权限</el-button
          ></el-form
        ></el-tab-pane
      ><el-tab-pane label="审计记录" name="audit"
        ><el-table :data="audit" empty-text="暂无审计记录"
          ><el-table-column
            prop="username"
            label="操作人"
            width="140"
          /><el-table-column
            prop="action"
            label="操作"
            min-width="160"
          /><el-table-column
            prop="resource"
            label="资源"
            min-width="220"
          /><el-table-column label="时间" width="190"
            ><template #default="scope">{{
              new Date(scope.row.createdAt).toLocaleString("zh-CN")
            }}</template></el-table-column
          ></el-table
        ></el-tab-pane
      ></el-tabs
    ><el-dialog
      v-model="userDialog"
      title="创建团队成员"
      width="min(520px,94vw)"
      ><el-alert
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        class="page-error"
      /><el-form label-position="top" @submit.prevent="createUser"
        ><el-form-item label="用户名"
          ><el-input
            v-model="userDraft.username"
            autocomplete="off" /></el-form-item
        ><el-form-item label="显示名称"
          ><el-input v-model="userDraft.displayName" /></el-form-item
        ><el-form-item label="角色"
          ><el-select v-model="userDraft.role"
            ><el-option label="分析师 — 可执行查询" value="ANALYST" /><el-option
              label="只读成员 — 不可执行查询"
              value="VIEWER" /><el-option
              label="管理员 — 管理平台与权限"
              value="ADMIN" /></el-select></el-form-item
        ><el-form-item label="初始密码（至少 12 字符，最多 72 UTF-8 字节）"
          ><el-input
            v-model="userDraft.password"
            type="password"
            show-password
            autocomplete="new-password"
            maxlength="72"
          /><span class="form-help"
            >提交后立即清空密码。请通过团队认可的安全方式向成员交付凭据。</span
          ></el-form-item
        ></el-form
      ><template #footer
        ><el-button @click="userDialog = false">取消</el-button
        ><el-button type="primary" :loading="saving" @click="createUser"
          >创建成员</el-button
        ></template
      ></el-dialog
    >
  </section>
</template>
<style scoped>
.governance-form {
  max-width: 620px;
  padding: 18px 0;
}
.section-heading > span {
  font-size: 12px;
}
</style>
