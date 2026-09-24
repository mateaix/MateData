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
