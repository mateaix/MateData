<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ApiError } from "../api";
import { StaleSessionError } from "../session";
import type { Dataset, Field, Source } from "../types";
import AppIcon from "./AppIcon.vue";
type MetadataTable = {
  name: string;
  columns: { name: string; type: string }[];
};
type DraftField = Field & { aliasesText: string };
type Draft = Omit<Dataset, "metrics" | "dimensions"> & {
  metrics: DraftField[];
  dimensions: DraftField[];
};
const props = defineProps<{
  dataset: Dataset | null;
  request: <T>(path: string, init?: RequestInit) => Promise<T>;
  saving?: boolean;
}>();
const emit = defineEmits<{
  save: [dataset: Dataset];
  cancel: [];
  sessionExpired: [];
}>();
const newField = (metric: boolean): DraftField => ({
  id: "",
  name: "",
  column: "",
  aliases: [],
  aliasesText: "",
  ...(metric ? { aggregation: "SUM" } : {}),
});
function makeDraft(): Draft {
  const base = props.dataset
    ? JSON.parse(JSON.stringify(props.dataset))
    : {
        id: "",
        name: "",
        description: "",
        sourceId: "",
        tableName: "",
        metrics: [newField(true)],
        dimensions: [],
      };
  return {
    ...base,
    metrics: base.metrics.map((field: Field) => ({
      ...field,
      aliasesText: field.aliases.join(", "),
    })),
    dimensions: base.dimensions.map((field: Field) => ({
      ...field,
      aliasesText: field.aliases.join(", "),
    })),
  };
}
const draft = ref<Draft>(makeDraft()),
  sources = ref<Source[]>([]),
  metadataTables = ref<MetadataTable[]>([]),
  sourceLoading = ref(false),
  metadataLoading = ref(false),
  error = ref("");
const availableColumns = computed(
  () =>
    metadataTables.value.find((table) => table.name === draft.value.tableName)
      ?.columns || [],
);
const aggregations = ["SUM", "COUNT", "AVG", "MIN", "MAX"];
let active = true,
  metadataVersion = 0;
function failure(e: unknown) {
  if (!active || e instanceof StaleSessionError) return;
  if (e instanceof ApiError && e.status === 401) {
    emit("sessionExpired");
    return;
  }
  error.value = e instanceof Error ? e.message : "读取数据结构失败，请重试。";
}
function clearColumns() {
  for (const field of [...draft.value.metrics, ...draft.value.dimensions])
    field.column = "";
}
async function loadTables(sourceId: string) {
  const version = ++metadataVersion;
  metadataTables.value = [];
  metadataLoading.value = !!sourceId;
  error.value = "";
  if (!sourceId) return;
  try {
    const result = await props.request<MetadataTable[]>(
      `/sources/${encodeURIComponent(sourceId)}/tables`,
    );
    if (!active || version !== metadataVersion) return;
    metadataTables.value = result;
    if (
      draft.value.tableName &&
      !result.some((table) => table.name === draft.value.tableName)
    )
      draft.value.tableName = "";
  } catch (e) {
    if (version === metadataVersion) failure(e);
  } finally {
    if (active && version === metadataVersion) metadataLoading.value = false;
  }
}
watch(
  () => draft.value.sourceId,
  (sourceId) => {
    draft.value.tableName = "";
    clearColumns();
    void loadTables(sourceId);
  },
  { flush: "sync" },
);
watch(
  () => draft.value.tableName,
  () => clearColumns(),
  { flush: "sync" },
);
onMounted(async () => {
  sourceLoading.value = true;
  try {
    const result = await props.request<Source[]>("/sources");
    if (!active) return;
    sources.value = result;
    await loadTables(draft.value.sourceId);
  } catch (e) {
    failure(e);
  } finally {
    if (active) sourceLoading.value = false;
  }
});
onBeforeUnmount(() => {
  active = false;
  metadataVersion++;
  metadataTables.value = [];
  sources.value = [];
  draft.value = {
    id: "",
    name: "",
    description: "",
    sourceId: "",
    tableName: "",
    metrics: [],
    dimensions: [],
  };
});
function submit() {
  error.value = "";
  if (props.saving || sourceLoading.value || metadataLoading.value) return;
  const value = draft.value;
  const identifier = /^[A-Za-z][A-Za-z0-9_]{0,62}$/;
  if (!identifier.test(value.id) || !value.name.trim()) {
    error.value =
      "请填写模型名称及有效标识符：字母开头，最多 63 位字母、数字或下划线。";
    return;
  }
  if (
    !sources.value.some((source) => source.id === value.sourceId) ||
    !metadataTables.value.some((table) => table.name === value.tableName)
  ) {
    error.value = "请选择已注册的数据连接和可见数据表。";
    return;
  }
  if (!value.metrics.length) {
    error.value = "请添加至少一个指标。";
    return;
  }
  const fields = [...value.metrics, ...value.dimensions];
  if (
    fields.some((field) => !identifier.test(field.id) || !field.name.trim())
  ) {
    error.value = "每个指标与维度都需要名称及有效标识符。";
    return;
  }
  if (
    new Set(fields.map((field) => field.id.toLowerCase())).size !==
    fields.length
  ) {
    error.value = "指标与维度的标识符必须唯一（不区分大小写）。";
    return;
  }
  if (
    fields.some(
      (field) =>
        !availableColumns.value.some((column) => column.name === field.column),
    )
  ) {
    error.value = "请选择当前数据表中实际存在的字段。";
    return;
  }
  if (
    value.metrics.some(
      (field) => !aggregations.includes(field.aggregation || ""),
    )
  ) {
    error.value = "请选择有效的聚合方式。";
    return;
  }
  function published(field: DraftField): Field {
    const { aliasesText, ...rest } = field;
    return {
      ...rest,
      name: field.name.trim(),
      aliases: [
        ...new Set(
          aliasesText
            .split(/[,，]/)
            .map((alias) => alias.trim())
            .filter(Boolean),
        ),
      ],
    };
  }
  // Spread copied published fields so future server-owned options survive editing.
  // There is deliberately no editable valueType: the server derives it from metadata.
  const payload: Dataset = {
    ...value,
    id: props.dataset?.id || value.id,
    name: value.name.trim(),
    metrics: value.metrics.map(published),
    dimensions: value.dimensions.map(published),
  };
  emit("save", payload);
}
</script>
<template>
  <section class="dataset-designer">
    <el-alert
      v-if="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="page-error"
    />
    <p class="muted">
      先选择数据连接与物理表，再定义业务指标和维度。字段类型由服务端根据元数据确认。
    </p>
    <el-form label-position="top" @submit.prevent="submit"
      ><div class="designer-grid">
        <el-form-item label="模型标识符"
          ><el-input
            v-model="draft.id"
            :disabled="!!dataset"
            placeholder="例如：sales"
            maxlength="63"
          /><span class="form-help"
            >发布后标识符固定。字母开头，只含字母、数字和下划线。</span
          ></el-form-item
        ><el-form-item label="模型名称"
          ><el-input
            v-model="draft.name"
            placeholder="例如：销售分析" /></el-form-item
        ><el-form-item label="数据连接"
          ><el-select
            v-model="draft.sourceId"
            :loading="sourceLoading"
            placeholder="选择已注册的数据连接"
            class="full"
            ><el-option
              v-for="source in sources"
              :key="source.id"
              :value="source.id"
              :label="`${source.name} · ${source.type}`" /></el-select></el-form-item
        ><el-form-item label="物理表"
          ><el-select
            v-model="draft.tableName"
            :loading="metadataLoading"
            :disabled="!draft.sourceId || metadataLoading"
            placeholder="选择可见数据表"
            class="full"
            ><el-option
              v-for="table in metadataTables"
              :key="table.name"
              :label="table.name"
              :value="table.name" /></el-select
          ><span class="form-help"
            >{{ availableColumns.length }} 个可用字段
            <button
              v-if="draft.sourceId"
              type="button"
              class="metadata-retry"
              @click="loadTables(draft.sourceId)"
            >
              刷新结构
            </button></span
          ></el-form-item
        >
      </div>
      <el-form-item label="业务说明"
        ><el-input
          v-model="draft.description"
          type="textarea"
          :rows="2"
          placeholder="描述数据范围、口径与适用场景"
      /></el-form-item>
      <div class="section-heading">
        <h2>业务指标 <small>至少一个</small></h2>
        <el-button @click="draft.metrics.push(newField(true))"
          ><AppIcon name="add" class="el-icon--left" />添加指标</el-button
        >
      </div>
      <el-table :data="draft.metrics" empty-text="添加一个业务指标以继续"
        ><el-table-column label="标识符" min-width="130"
          ><template #default="scope"
            ><el-input
              v-model="scope.row.id"
              placeholder="revenue"
              aria-label="指标标识符" /></template></el-table-column
        ><el-table-column label="业务名称" min-width="130"
          ><template #default="scope"
            ><el-input
              v-model="scope.row.name"
              placeholder="销售额"
              aria-label="指标名称" /></template></el-table-column
        ><el-table-column label="物理字段" min-width="180"
          ><template #default="scope"
            ><el-select
              v-model="scope.row.column"
              :disabled="!draft.tableName"
              placeholder="选择字段"
              aria-label="指标物理字段"
              ><el-option
                v-for="column in availableColumns"
                :key="column.name"
                :value="column.name"
                :label="`${column.name} · ${column.type}`" /></el-select></template></el-table-column
        ><el-table-column label="聚合方式" min-width="115"
          ><template #default="scope"
            ><el-select v-model="scope.row.aggregation" aria-label="聚合方式"
              ><el-option
                v-for="aggregation in aggregations"
                :key="aggregation"
                :label="aggregation"
                :value="aggregation" /></el-select></template></el-table-column
        ><el-table-column label="业务别名（逗号分隔）" min-width="195"
          ><template #default="scope"
            ><el-input
              v-model="scope.row.aliasesText"
              placeholder="销售金额, 营收"
              aria-label="指标别名" /></template></el-table-column
        ><el-table-column width="70"
          ><template #default="scope"
            ><el-button
              text
              type="danger"
              @click="draft.metrics.splice(scope.$index, 1)"
              >移除</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <div class="section-heading">
        <h2>分析维度 <small>可选</small></h2>
        <el-button @click="draft.dimensions.push(newField(false))"
          ><AppIcon name="add" class="el-icon--left" />添加维度</el-button
        >
      </div>
      <el-table
        :data="draft.dimensions"
        empty-text="需要分组分析时，添加区域、品类或日期等维度"
        ><el-table-column label="标识符" min-width="135"
          ><template #default="scope"
            ><el-input
              v-model="scope.row.id"
              placeholder="region"
              aria-label="维度标识符" /></template></el-table-column
        ><el-table-column label="业务名称" min-width="135"
          ><template #default="scope"
            ><el-input
              v-model="scope.row.name"
              placeholder="区域"
              aria-label="维度名称" /></template></el-table-column
        ><el-table-column label="物理字段" min-width="200"
          ><template #default="scope"
            ><el-select
              v-model="scope.row.column"
              :disabled="!draft.tableName"
              placeholder="选择字段"
              aria-label="维度物理字段"
              ><el-option
                v-for="column in availableColumns"
                :key="column.name"
                :value="column.name"
                :label="`${column.name} · ${column.type}`" /></el-select></template></el-table-column
        ><el-table-column label="业务别名（逗号分隔）" min-width="205"
          ><template #default="scope"
            ><el-input
              v-model="scope.row.aliasesText"
              placeholder="地区, 大区"
              aria-label="维度别名" /></template></el-table-column
        ><el-table-column width="70"
          ><template #default="scope"
            ><el-button
              text
              type="danger"
              @click="draft.dimensions.splice(scope.$index, 1)"
              >移除</el-button
            ></template
          ></el-table-column
        ></el-table
      >
      <div class="designer-actions">
        <el-button @click="emit('cancel')">取消</el-button
        ><el-button
          type="primary"
          native-type="submit"
          :loading="saving"
          :disabled="sourceLoading || metadataLoading"
          >{{ dataset ? "保存模型" : "发布模型" }}</el-button
        >
      </div></el-form
    >
  </section>
</template>
<style scoped>
.designer-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 22px;
}
.designer-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding-top: 26px;
}
.section-heading small {
  font-size: 10px;
  color: #94a48c;
  margin-left: 8px;
}
.metadata-retry {
  border: 0;
  background: none;
  color: #176b59;
  font-size: 10px;
  margin-left: 10px;
  cursor: pointer;
}
@media (max-width: 650px) {
  .designer-grid {
    grid-template-columns: 1fr;
  }
}
</style>
