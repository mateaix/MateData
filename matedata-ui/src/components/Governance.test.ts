// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises, type VueWrapper } from "@vue/test-utils";
import { uiComponents as ElementPlus } from "../uiComponents";
import Governance from "./Governance.vue";
let wrapper: VueWrapper;
const datasets = [
  {
    id: "sales",
    name: "销售",
    description: "",
    sourceId: "demo_sales",
    tableName: "sales",
    metrics: [{ id: "profit", name: "利润", column: "profit", aliases: [] }],
    dimensions: [{ id: "region", name: "区域", column: "region", aliases: [] }],
  },
];
const state = () =>
  (wrapper.vm as unknown as { $: { setupState: Record<string, any> } }).$
    .setupState;
afterEach(() => wrapper?.unmount());
it("shows user loading failures without inventing users", async () => {
  wrapper = mount(Governance, {
    props: {
      request: async () => {
        throw Error("读取用户失败");
      },
      datasets,
    },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  expect(wrapper.text()).toContain("读取用户失败");
});
it("clears the submitted user password even when creation fails", async () => {
  const request = vi.fn(async (_path: string, init?: RequestInit) => {
    if (init?.method === "POST") throw Error("创建失败");
    return [];
  });
  wrapper = mount(Governance, {
    props: { request: request as any, datasets },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  const vm = state();
  expect(typeof vm.createUser).toBe("function");
  vm.userDraft = {
    username: "bob",
    displayName: "Bob",
    role: "ANALYST",
    password: "long-secret-1234",
  };
  await vm.createUser();
  expect(vm.userDraft.password).toBe("");
  expect(vm.error).toBe("创建失败");
});
it("submits explicit disabled grants and rejects non-string row filter values", async () => {
  const request = vi.fn(async () => []);
  wrapper = mount(Governance, {
    props: { request: request as any, datasets },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  const vm = state();
  expect(typeof vm.saveGrant).toBe("function");
  vm.grantUser = "bob";
  vm.grantDataset = "sales";
  vm.grant = { enabled: false, metrics: [], dimensions: [], rowFilters: {} };
  vm.rowFiltersText = '{"region":42}';
  await vm.saveGrant();
  expect(request.mock.calls.length).toBe(3);
  expect(vm.error).toContain("字符串");
  vm.rowFiltersText = "{}";
  await vm.saveGrant();
  expect(request).toHaveBeenCalledWith(
    "/permissions/bob/sales",
    expect.objectContaining({
      method: "PUT",
      body: expect.stringContaining('"enabled":false'),
    }),
  );
});

it("erases new-user secrets when the dialog closes and when the component leaves the session", async () => {
  wrapper = mount(Governance, {
    props: { request: async () => [] as any, datasets },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  const vm = state();
  vm.userDialog = true;
  vm.userDraft.password = "cancel-secret";
  vm.userDialog = false;
  expect(vm.userDraft.password).toBe("");
  vm.userDraft.password = "session-secret";
  wrapper.unmount();
  expect(vm.userDraft.password).toBe("");
});
it("does not publish a deferred governance load after leaving the session", async () => {
  let resolve!: (value: unknown) => void;
  const pending = new Promise((r) => {
    resolve = r;
  });
  wrapper = mount(Governance, {
    props: { request: async () => (await pending) as any, datasets },
    global: { plugins: [ElementPlus] },
  });
  const vm = state();
  wrapper.unmount();
  resolve([{ username: "old-secret" }]);
  await flushPromises();
  expect(vm.users).toEqual([]);
  expect(vm.grants).toEqual([]);
  expect(vm.audit).toEqual([]);
});
it("rehydrates the selected permission editor after an explicit refresh", async () => {
  let remote = {
    username: "bob",
    datasetId: "sales",
    enabled: true,
    metrics: ["profit"],
    dimensions: ["region"],
    rowFilters: { region: "华东" },
  };
  const request = vi.fn(async (path: string) =>
    path === "/permissions" ? [remote] : [],
  );
  wrapper = mount(Governance, {
    props: { request: request as any, datasets },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  const vm = state();
  vm.grantUser = "bob";
  vm.grantDataset = "sales";
  expect(vm.grant.enabled).toBe(true);
  expect(JSON.parse(vm.rowFiltersText)).toEqual({ region: "华东" });
  remote = {
    ...remote,
    enabled: false,
    metrics: [],
    dimensions: [],
    rowFilters: { region: "华北" },
  };
  await wrapper.find(".section-heading button").trigger("click");
  await flushPromises();
  expect(vm.grant.enabled).toBe(false);
  expect(vm.grant.metrics).toEqual([]);
  expect(vm.grant.dimensions).toEqual([]);
  expect(JSON.parse(vm.rowFiltersText)).toEqual({ region: "华北" });
});
it("rejects passwords exceeding 72 UTF-8 bytes before submitting and clears the secret", async () => {
  const request = vi.fn(async () => []);
  wrapper = mount(Governance, {
    props: { request: request as any, datasets },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  const vm = state();
  vm.userDraft = {
    username: "bob",
    displayName: "Bob",
    role: "ANALYST",
    password: "密".repeat(25),
  };
  await vm.createUser();
  expect(request.mock.calls).toHaveLength(3);
  expect(vm.error).toContain("72 字节");
  expect(vm.userDraft.password).toBe("");
});
