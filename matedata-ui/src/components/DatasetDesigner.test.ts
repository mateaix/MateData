// @vitest-environment happy-dom
import { afterEach, expect, it, vi } from "vitest";
import { mount, flushPromises, type VueWrapper } from "@vue/test-utils";
import { uiComponents as ElementPlus } from "../uiComponents";
import DatasetDesigner from "./DatasetDesigner.vue";
let wrapper: VueWrapper;
const state = () =>
  (wrapper.vm as unknown as { $: { setupState: Record<string, any> } }).$
    .setupState;
const tables = [
  {
    name: "orders",
    columns: [
      { name: "amount", type: "DECIMAL" },
      { name: "region", type: "VARCHAR" },
    ],
  },
];
const sources = [
  { id: "one", name: "Primary", type: "POSTGRESQL" },
  { id: "two", name: "Secondary", type: "POSTGRESQL" },
];
const original = {
  id: "sales",
  name: "Sales",
  description: "Old",
  sourceId: "one",
  tableName: "orders",
  metrics: [
    {
      id: "revenue",
      name: "Revenue",
      column: "amount",
      aggregation: "SUM",
      aliases: ["销售额"],
      format: "currency",
    },
  ],
  dimensions: [
    {
      id: "region",
      name: "Region",
      column: "region",
      aliases: [],
      valueType: "STRING",
      futureOption: true,
    },
  ],
  futureDatasetOption: { enabled: true },
};
const request = vi.fn(async (path: string) =>
  path === "/sources" ? sources : tables,
);
afterEach(() => {
  wrapper?.unmount();
  request.mockReset();
  request.mockImplementation(async (path: string) =>
    path === "/sources" ? sources : tables,
  );
});
async function start(dataset: any = null) {
  wrapper = mount(DatasetDesigner, {
    props: { dataset, request: request as any },
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
}
it("loads registered source tables and clears selected columns when the source changes", async () => {
  await start(original);
  const vm = state();
  expect(vm.metadataTables).toEqual(tables);
  expect(vm.draft.tableName).toBe("orders");
  vm.draft.sourceId = "two";
  await flushPromises();
  expect(request).toHaveBeenCalledWith("/sources/two/tables");
  expect(vm.draft.tableName).toBe("");
  expect(vm.draft.metrics[0].column).toBe("");
  vm.draft.tableName = "orders";
  expect(vm.availableColumns.map((c: any) => c.name)).toEqual([
    "amount",
    "region",
  ]);
});
it("emits a usable payload with parsed aliases and retains published optional fields", async () => {
  await start(original);
  const vm = state();
  expect(typeof vm.submit).toBe("function");
  vm.draft.name = "Updated";
  vm.draft.metrics[0].aliasesText = "销售金额, 营收，收入";
  vm.submit();
  const payload = wrapper.emitted("save")?.[0]?.[0] as any;
  expect(payload.metrics[0].aliases).toEqual(["销售金额", "营收", "收入"]);
  expect(payload.metrics[0].format).toBe("currency");
  expect(payload.dimensions[0].valueType).toBe("STRING");
  expect(payload.dimensions[0].futureOption).toBe(true);
  expect(payload.futureDatasetOption).toEqual({ enabled: true });
  expect(payload.id).toBe("sales");
  expect(payload.metrics[0]).not.toHaveProperty("aliasesText");
});
it("rejects duplicate metric/dimension identifiers and missing metrics", async () => {
  await start(original);
  const vm = state();
  expect(typeof vm.submit).toBe("function");
  vm.draft.dimensions[0].id = "revenue";
  vm.submit();
  expect(vm.error).toContain("唯一");
  expect(wrapper.emitted("save")).toBeUndefined();
  vm.draft.metrics = [];
  vm.submit();
  expect(vm.error).toContain("至少一个指标");
});
it("rejects late metadata from the previous selected source", async () => {
  await start();
  const vm = state();
  expect(vm.draft).toBeDefined();
  let resolve!: (value: unknown) => void;
  request.mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }) as any,
  );
  vm.draft.sourceId = "one";
  await flushPromises();
  vm.draft.sourceId = "two";
  await flushPromises();
  resolve([{ name: "private_old", columns: [] }]);
  await flushPromises();
  expect(vm.metadataTables).toEqual(tables);
});
it("publishes a new model from registered table columns and locks an existing id", async () => {
  await start();
  const vm = state();
  vm.draft.id = "new_sales";
  vm.draft.name = "New sales";
  vm.draft.sourceId = "one";
  await flushPromises();
  vm.draft.tableName = "orders";
  Object.assign(vm.draft.metrics[0], {
    id: "revenue",
    name: "Revenue",
    column: "amount",
    aggregation: "AVG",
    aliasesText: "Sales",
  });
  vm.submit();
  expect(wrapper.emitted("save")?.[0]?.[0]).toMatchObject({
    id: "new_sales",
    sourceId: "one",
    tableName: "orders",
    metrics: [{ column: "amount", aggregation: "AVG", aliases: ["Sales"] }],
  });
  wrapper.unmount();
  await start(original);
  const edited = state();
  edited.draft.id = "different_id";
  edited.submit();
  expect(wrapper.emitted("save")?.[0]?.[0]).toMatchObject({ id: "sales" });
});
it("does not revive metadata after the designer leaves the session", async () => {
  await start();
  const vm = state();
  let resolve!: (value: unknown) => void;
  request.mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }) as any,
  );
  vm.draft.sourceId = "one";
  await flushPromises();
  wrapper.unmount();
  resolve([
    { name: "private_old", columns: [{ name: "secret", type: "VARCHAR" }] },
  ]);
  await flushPromises();
  expect(vm.metadataTables).toEqual([]);
  expect(vm.sources).toEqual([]);
  expect(vm.draft.sourceId).toBe("");
});
