// @vitest-environment happy-dom
import { expect, it } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import { uiComponents as ElementPlus } from "../uiComponents";
import RunResult from "./RunResult.vue";
import type { Run } from "../types";
function run(value: string): Run {
  return {
    id: "r1",
    conversationId: "c1",
    question: "利润",
    datasetId: "sales",
    mode: "demo",
    status: "SUCCEEDED",
    sql: "",
    columns: ["region", "profit"],
    rows: [{ region: "华东", profit: value }],
    rowCount: 1,
    durationMs: 1,
    createdAt: "",
    answer: "",
    error: null,
    steps: [],
  };
}
it("preserves every financial digit and decimal place in the table", async () => {
  const wrapper = mount(RunResult, {
    props: { run: run("9007199254740993.01") },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  expect(wrapper.find(".el-table").text()).toContain("9007199254740993.01");
  expect(wrapper.find(".chart").exists()).toBe(false);
  wrapper.unmount();
});
it("charts ordinary numeric-string results and discloses approximate visual scaling", async () => {
  const wrapper = mount(RunResult, {
    props: { run: run("1449000.00") },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  expect(wrapper.find(".chart").exists()).toBe(true);
  expect(wrapper.find(".chart").text()).toContain("近似");
  expect(wrapper.find(".el-table").text()).toContain("1449000.00");
  wrapper.unmount();
});
it("separates rule planning from sample data and uses matching semantic column names", async () => {
  const dataset = {
    id: "sales",
    name: "业务销售",
    description: "",
    sourceId: "production",
    tableName: "orders",
    metrics: [
      {
        id: "profit",
        name: "净利润",
        column: "profit",
        aggregation: "SUM",
        aliases: [],
      },
    ],
    dimensions: [
      { id: "region", name: "业务区域", column: "region", aliases: [] },
    ],
  };
  const wrapper = mount(RunResult, {
    props: { run: run("123.40"), dataset },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  expect(wrapper.text()).toContain("规则解析（无模型调用）");
  expect(wrapper.text()).not.toContain("内置演示数据");
  expect(wrapper.text()).not.toContain("内置示例数据");
  expect(wrapper.find(".el-table").text()).toContain("净利润");
  expect(wrapper.find(".el-table").text()).toContain("业务区域");
  expect(wrapper.find(".chart-title").text()).toContain("净利润");
  await wrapper.setProps({
    dataset: { ...dataset, id: "different", sourceId: "demo_sales" },
  });
  expect(wrapper.find(".chart-title").text()).toContain("profit");
  expect(wrapper.text()).not.toContain("内置示例数据");
  wrapper.unmount();
});
it("never applies new business labels or sample-source labels across authorization fingerprints", async () => {
  const dataset = {
    id: "sales",
    name: "New meaning",
    description: "",
    sourceId: "demo_sales",
    tableName: "sales",
    metrics: [
      {
        id: "profit",
        name: "New profit meaning",
        column: "new_profit",
        aggregation: "SUM",
        aliases: [],
      },
    ],
    dimensions: [],
    scopeFingerprint: "current",
  };
  const wrapper = mount(RunResult, {
    props: {
      run: { ...run("12.00"), scopeFingerprint: "old" } as Run,
      dataset,
    },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  expect(wrapper.find(".chart-title").text()).toContain("profit");
  expect(wrapper.text()).not.toContain("New profit meaning");
  expect(wrapper.text()).not.toContain("内置示例数据");
  wrapper.unmount();
});
