<script setup lang="ts">
import { computed, ref } from "vue";
import { chartUnavailableReason, isNumericCell } from "../chart";
import { runMatchesDataset } from "../modelVersions";
import { runStatus } from "../runStatus";
import type { Run, Dataset } from "../types";
const props = defineProps<{ run: Run; dataset?: Dataset }>();
const tab = ref("result");
const matchingDataset = computed(() =>
  runMatchesDataset(props.run, props.dataset) ? props.dataset : undefined,
);
function columnName(id: string) {
  return (
    [
      ...(matchingDataset.value?.metrics || []),
      ...(matchingDataset.value?.dimensions || []),
    ].find((field) => field.id === id)?.name || id
  );
}
const numericColumn = computed(() =>
  props.run.columns.find((c) =>
    props.run.rows.some((r) => isNumericCell(r[c])),
  ),
);
const labelColumn = computed(
  () =>
    props.run.columns.find((c) => c !== numericColumn.value) ||
    props.run.columns[0],
);
const chartReason = computed(() =>
  chartUnavailableReason(props.run.rows, numericColumn.value),
);
const chartRows = computed(() => props.run.rows.slice(0, 12));
const maximum = computed(() =>
  Math.max(
    ...chartRows.value.map((r) => Number(r[numericColumn.value || ""]) || 0),
    1,
  ),
);
function format(value: unknown) {
  return typeof value === "number"
    ? new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 20 }).format(
        value,
      )
    : String(value ?? "—");
}
</script>
<template>
  <section class="result-card">
    <div class="result-heading">
      <div>
        <span class="eyebrow">ANALYSIS RESULT</span>
        <h2>{{ run.question }}</h2>
      </div>
      <el-tag :type="runStatus(run.status).type">{{
        runStatus(run.status).label
      }}</el-tag>
    </div>
    <div class="run-meta">
      <span>{{
        run.mode === "demo" ? "规则解析（无模型调用）" : "Agent 查询"
      }}</span
      ><span v-if="matchingDataset?.sourceId === 'demo_sales'"
        >内置示例数据</span
      ><span v-else-if="matchingDataset"
        >{{ matchingDataset.name }} · 已连接数据源</span
      ><span>{{ run.rowCount }} 条结果</span><span>{{ run.durationMs }} ms</span
      ><span>{{ run.id }}</span>
    </div>
    <el-alert
      v-if="run.error"
      :title="run.error"
      type="error"
      :closable="false"
      show-icon
    />
    <div v-if="run.answer" class="answer">
      <span v-if="run.mode === 'agent'" class="answer-label"
        >{{ run.status === "NEEDS_INPUT" ? "智能体需要确认" : "智能体解读"
        }}<small v-if="run.status === 'SUCCEEDED'"
          >由模型根据查询结果生成，数值以下方结果表为准</small
        ></span
      >
      <p>{{ run.answer }}</p>
    </div>
    <el-tabs v-model="tab"
      ><el-tab-pane label="分析结果" name="result">
        <el-alert
          v-if="chartReason"
          :title="chartReason"
          type="info"
          :closable="false"
          show-icon
        />
        <div
          v-if="numericColumn && chartRows.length && !chartReason"
          class="chart"
        >
          <div class="chart-title">
            {{ columnName(numericColumn) }}
            <span>按返回顺序 · 最多展示 12 项 · 条形长度为近似值</span>
          </div>
          <div v-for="(row, index) in chartRows" :key="index" class="bar-row">
            <span class="bar-label">{{ format(row[labelColumn || ""]) }}</span>
            <div class="bar-track">
              <div
                class="bar-fill"
                :style="{
                  width: `${((Number(row[numericColumn]) || 0) / maximum) * 100}%`,
                }"
              ></div>
            </div>
            <strong>{{ format(row[numericColumn]) }}</strong>
          </div>
        </div>
        <el-table
          :data="run.rows"
          stripe
          max-height="430"
          empty-text="查询执行完成，没有返回记录"
          ><el-table-column
            v-for="column in run.columns"
            :key="column"
            :prop="column"
            :label="columnName(column)"
            min-width="140"
            ><template #default="scope">{{
              format(scope.row[column])
            }}</template></el-table-column
          ></el-table
        > </el-tab-pane
      ><el-tab-pane label="SQL 查询" name="sql">
        <pre class="sql">{{ run.sql || "本次执行没有生成 SQL。" }}</pre>
        <p class="muted">
          执行 SQL 由服务端只读校验后运行；结果遵循数据集权限。
        </p></el-tab-pane
      ><el-tab-pane label="执行轨迹" name="trace"
        ><el-timeline
          ><el-timeline-item
            v-for="(step, index) in run.steps"
            :key="index"
            :type="step.status === 'FAILED' ? 'danger' : 'success'"
            :timestamp="`${step.durationMs} ms · ${step.status}`"
            ><strong>{{ step.name }}</strong>
            <p class="trace-detail">{{ step.detail }}</p></el-timeline-item
          ></el-timeline
        ><el-empty
          v-if="!run.steps.length"
          description="暂无执行轨迹" /></el-tab-pane
    ></el-tabs>
  </section>
</template>
