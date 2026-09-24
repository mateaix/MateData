// @vitest-environment happy-dom
import { expect, it } from "vitest";
import { createApp, defineComponent } from "vue";
import { mount } from "@vue/test-utils";
import { uiComponents } from "./uiComponents";
it("registers radio children even when ElementPlus exposes a no-op installer", () => {
  const app = createApp({});
  app.use(uiComponents);
  expect(app.component("ElRadioGroup")).toBeDefined();
  expect(app.component("ElRadioButton")).toBeDefined();
});
it("renders working mode buttons through the actual production registry", async () => {
  const wrapper = mount(
    defineComponent({
      data: () => ({ mode: "demo" }),
      template:
        '<el-radio-group v-model="mode"><el-radio-button value="demo">演示数据</el-radio-button><el-radio-button value="agent">Agent 查询</el-radio-button></el-radio-group>',
    }),
    { global: { plugins: [uiComponents] } },
  );
  expect(wrapper.findAll('input[type="radio"]')).toHaveLength(2);
  await wrapper.findAll('input[type="radio"]')[1]!.setValue();
  expect(wrapper.vm.mode).toBe("agent");
  wrapper.unmount();
});
