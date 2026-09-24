// @vitest-environment happy-dom
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { flushPromises, mount, type VueWrapper } from "@vue/test-utils";
import { uiComponents as ElementPlus } from "./uiComponents";
import App from "./App.vue";
import { ApiError, request } from "./api";
vi.mock("./api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("./api")>()),
  request: vi.fn(),
}));
const api = vi.mocked(request);
const account = { username: "alice", displayName: "Alice", role: "ADMIN" };
let wrapper: VueWrapper;
function state() {
  return (wrapper.vm as unknown as { $: { setupState: Record<string, any> } }).$
    .setupState;
}
beforeEach(() => {
  api.mockReset();
  api.mockImplementation(async (path) => {
    if (path === "/auth/me") return account;
    if (path === "/system") return { modelConfigured: true };
    if (path.startsWith("/runs/page?")) return { items: [], nextOffset: null };
    return [];
  });
});
afterEach(() => {
  wrapper?.unmount();
});
async function start() {
  wrapper = mount(App, { global: { plugins: [ElementPlus] } });
  await flushPromises();
}
it("renders bootstrap failure in the authenticated error banner", async () => {
  api.mockImplementation(async (path) => {
    if (path === "/auth/me") return account;
    throw new ApiError("数据库暂不可用", 503);
  });
  await start();
  expect(wrapper.text()).toContain("数据库暂不可用");
});
it("clears restored authentication when bootstrap returns unauthorized", async () => {
  api.mockImplementation(async (path) => {
    if (path === "/auth/me") return account;
    throw new ApiError("会话失效", 401);
  });
  await start();
  expect(wrapper.find(".login-layout").exists()).toBe(true);
});
it("discards deferred results and finalizers after logout and another login", async () => {
  await start();
  const vm = state();
  vm.question = "old question";
  vm.datasetId = "sales";
  let resolve!: (value: unknown) => void;
  api.mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const pending = vm.ask();
  expect(vm.querying).toBe(true);
  vm.clearSession();
  vm.user = { ...account, username: "bob" };
  vm.querying = true;
  resolve({
    id: "private-old-run",
    columns: [],
    rows: [],
    steps: [],
    question: "old",
    status: "SUCCEEDED",
    mode: "demo",
  });
  await pending;
  expect(vm.activeRun).toBeNull();
  expect(vm.querying).toBe(true);
  expect(vm.error).toBe("");
});
it("old unauthorized responses cannot log out a new account", async () => {
  await start();
  const vm = state();
  vm.question = "old";
  vm.datasetId = "sales";
  let reject!: (error: Error) => void;
  api.mockImplementationOnce(
    () =>
      new Promise((_, r) => {
        reject = r;
      }),
  );
  const pending = vm.ask();
  vm.clearSession();
  vm.user = { ...account, username: "bob" };
  reject(new ApiError("old expired", 401));
  await pending;
  expect(vm.user.username).toBe("bob");
  expect(vm.error).toBe("");
});
it("erases all session-owned forms and secrets on logout", async () => {
  await start();
  const vm = state();
  vm.sourceDraft.password = "database-secret";
  vm.editingDataset = { id: "private schema" };
  vm.tables = [{ name: "private" }];
  vm.tableSource = "private";
  vm.editingId = "private";
  vm.credentials.password = "login-secret";
  vm.loginError = "private";
  vm.error = "private";
  vm.testing = "private";
  vm.saving = true;
  vm.clearSession();
  expect(vm.sourceDraft.password).toBe("");
  expect(vm.editingDataset).toBeNull();
  expect(vm.tables).toEqual([]);
  expect(vm.tableSource).toBe("");
  expect(vm.editingId).toBe("");
  expect(vm.credentials.password).toBe("");
  expect(vm.error).toBe("");
  expect(vm.loginError).toBe("");
  expect(vm.testing).toBe("");
  expect(vm.saving).toBe(false);
});
it("clears the source password on dialog cancel", async () => {
  await start();
  const vm = state();
  vm.sourceDialog = true;
  await flushPromises();
  vm.sourceDraft.password = "secret";
  vm.sourceDialog = false;
  await flushPromises();
  expect(vm.sourceDraft.password).toBe("");
});

it.each(["sources", "datasets", "runs", "evaluations", "settings"])(
  "discards the old %s page load after the account changes",
  async (page) => {
    await start();
    const vm = state();
    let resolve!: (value: unknown) => void;
    api.mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r;
        }),
    );
    const pending = vm.navigate(page);
    vm.clearSession();
    vm.user = { ...account, username: "bob" };
    vm.busy = true;
    resolve(
      page === "settings"
        ? { model: "old-private-model" }
        : [{ id: "old-private-row" }],
    );
    await pending;
    expect(vm.sources).toEqual([]);
    expect(vm.datasets).toEqual([]);
    expect(vm.runs).toEqual([]);
    expect(vm.evaluations).toEqual([]);
    expect(vm.model.model).toBe("");
    expect(vm.busy).toBe(true);
  },
);
it("does not continue a successful old mutation with another account’s requests or UI updates", async () => {
  await start();
  const vm = state();
  vm.sourceDraft = {
    name: "Old private",
    type: "POSTGRESQL",
    jdbcUrl: "jdbc:postgresql://old/db",
    username: "old",
    password: "secret",
  };
  let resolve!: (value: unknown) => void;
  api.mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const pending = vm.createSource();
  const count = api.mock.calls.length;
  vm.clearSession();
  vm.user = { ...account, username: "bob" };
  vm.saving = true;
  vm.sourceDialog = true;
  resolve({ id: "old-private-source" });
  await pending;
  expect(api.mock.calls).toHaveLength(count);
  expect(vm.saving).toBe(true);
  expect(vm.sourceDialog).toBe(true);
  expect(vm.sources).toEqual([]);
});
it("invalidates work immediately on logout and blocks login until cookie-changing logout settles", async () => {
  await start();
  const vm = state();
  let resolve!: (value: unknown) => void;
  api.mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const pending = vm.logout();
  expect(vm.user).toBeNull();
  expect(vm.loginBusy).toBe(true);
  vm.credentials = { username: "bob", password: "another-secret" };
  const count = api.mock.calls.length;
  await vm.login();
  expect(api.mock.calls).toHaveLength(count);
  resolve(undefined);
  await pending;
  expect(vm.loginBusy).toBe(false);
});

it("hides admin destinations and rejects navigation for an analyst", async () => {
  await start();
  const vm = state();
  vm.user = { ...account, role: "ANALYST" };
  await flushPromises();
  expect(wrapper.find("nav").text()).not.toContain("数据连接");
  expect(wrapper.find("nav").text()).not.toContain("模型设置");
  expect(wrapper.find("nav").text()).not.toContain("团队治理");
  const count = api.mock.calls.length;
  await vm.navigate("sources");
  expect(api.mock.calls).toHaveLength(count);
  expect(vm.page).toBe("ask");
});
it("explains viewer restrictions and never executes questions or evaluations", async () => {
  await start();
  const vm = state();
  vm.user = { ...account, role: "VIEWER" };
  vm.question = "各区域销售额";
  vm.datasetId = "sales";
  await flushPromises();
  api.mockImplementation(async () => {
    throw new ApiError("禁止执行", 403);
  });
  const count = api.mock.calls.length;
  await vm.ask();
  await vm.runEvaluation();
  expect(api.mock.calls).toHaveLength(count);
  expect(wrapper.text()).toContain("只读成员");
});
it("loads persisted evaluation reports and can reopen a previous exact-result report", async () => {
  await start();
  const vm = state();
  const report = {
    id: "previous",
    passed: 1,
    total: 1,
    durationMs: 15,
    results: [
      { name: "case", passed: true, message: "精确值一致", runId: "r1" },
    ],
  };
  api.mockImplementation(async (path) =>
    path === "/evaluations/reports"
      ? [report]
      : path === "/evaluations/reports/previous"
        ? report
        : [],
  );
  await vm.navigate("evaluations");
  expect(vm.evaluationReports).toEqual([report]);
  await vm.openEvaluationReport("previous");
  expect(vm.evaluationResult).toEqual(report);
  await flushPromises();
  expect(wrapper.text()).toContain("精确值一致");
});
it("does not display a deferred report belonging to the previous session", async () => {
  await start();
  const vm = state();
  let resolve!: (value: unknown) => void;
  api.mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  expect(typeof vm.openEvaluationReport).toBe("function");
  const pending = vm.openEvaluationReport("old");
  vm.clearSession();
  vm.user = { ...account, username: "bob" };
  resolve({ id: "old" });
  await pending;
  expect(vm.evaluationResult).toBeNull();
});
it("uses explicit next cursors for empty and short authorized history pages", async () => {
  await start();
  const vm = state();
  api.mockImplementationOnce(async () => ({ items: [], nextOffset: 50 }));
  await vm.navigate("runs");
  expect(api).toHaveBeenLastCalledWith("/runs/page?offset=0&limit=50", {});
  expect(vm.hasOlderRuns).toBe(true);
  expect(vm.runs).toEqual([]);
  api.mockImplementationOnce(async () => ({
    items: [{ id: "authorized", createdAt: "2026-09-24T00:00:00Z" }],
    nextOffset: 137,
  }));
  await vm.loadOlderRuns();
  expect(api).toHaveBeenLastCalledWith("/runs/page?offset=50&limit=50", {});
  expect(vm.hasOlderRuns).toBe(true);
  api.mockImplementationOnce(async () => ({ items: [], nextOffset: null }));
  await vm.loadOlderRuns();
  expect(api).toHaveBeenLastCalledWith("/runs/page?offset=137&limit=50", {});
  expect(vm.hasOlderRuns).toBe(false);
  expect(vm.runPageOffsets).toEqual([0, 50, 137]);
  api.mockImplementationOnce(async () => ({ items: [], nextOffset: 137 }));
  await vm.loadNewerRuns();
  expect(api).toHaveBeenLastCalledWith("/runs/page?offset=50&limit=50", {});
  expect(vm.runPageOffsets).toEqual([0, 50]);
});
it("does not publish old paginated history or offset after logout", async () => {
  await start();
  const vm = state();
  expect(typeof vm.loadRuns).toBe("function");
  let resolve!: (value: unknown) => void;
  api.mockImplementationOnce(
    () =>
      new Promise((r) => {
        resolve = r;
      }),
  );
  const pending = vm.loadRuns(50);
  vm.clearSession();
  vm.user = { ...account, username: "bob" };
  vm.busy = true;
  resolve({ items: [{ id: "old" }], nextOffset: 100 });
  await pending;
  expect(vm.runs).toEqual([]);
  expect(vm.runsOffset).toBe(0);
  expect(vm.busy).toBe(true);
});
it.each(["success", "failure"])(
  "preserves editor B when the closed editor A save finishes with %s",
  async (outcome) => {
    await start();
    const vm = state();
    const model = (id: string) => ({
      id,
      name: id,
      description: "",
      sourceId: "source",
      tableName: "orders",
      metrics: [
        {
          id: "revenue",
          name: "Revenue",
          column: "amount",
          aggregation: "SUM",
          aliases: [],
        },
      ],
      dimensions: [],
    });
    let resolve!: (value: unknown) => void;
    let reject!: (error: Error) => void;
    api.mockImplementation(async (path) => {
      if (path === "/datasets/A")
        return await new Promise((yes, no) => {
          resolve = yes;
          reject = no;
        });
      if (path === "/sources")
        return [{ id: "source", name: "Source", type: "POSTGRESQL" }];
      if (path === "/sources/source/tables")
        return [
          { name: "orders", columns: [{ name: "amount", type: "DECIMAL" }] },
        ];
      return [];
    });
    vm.editDataset(model("A"));
    await flushPromises();
    const pending = vm.saveDataset(model("A"));
    vm.datasetDialog = false;
    await flushPromises();
    vm.editDataset(model("B"));
    await flushPromises();
    const editor = wrapper.findComponent({ name: "DatasetDesigner" });
    const draft = (editor.vm as any).$.setupState;
    draft.draft.name = "Unsaved B";
    draft.error = "B local validation";
    vm.datasetError = "B server error";
    if (outcome === "success") resolve(model("A"));
    else reject(new ApiError("A failed", 400));
    await pending;
    await flushPromises();
    expect(vm.datasetDialog).toBe(true);
    expect(vm.editingId).toBe("B");
    expect(vm.datasetError).toBe("B server error");
    expect(draft.draft.name).toBe("Unsaved B");
    expect(draft.error).toBe("B local validation");
    expect(vm.error).toBe("");
  },
);
it.each(["success", "failure"])(
  "ignores a superseded history response with %s",
  async (outcome) => {
    await start();
    const vm = state();
    let resolve!: (value: unknown) => void;
    let reject!: (error: Error) => void;
    api.mockImplementationOnce(
      () =>
        new Promise((yes, no) => {
          resolve = yes;
          reject = no;
        }),
    );
    const old = vm.loadRuns(0);
    api.mockImplementationOnce(async () => ({ items: [], nextOffset: 177 }));
    await vm.loadRuns(87, [0, 87]);
    if (outcome === "success")
      resolve({ items: [{ id: "old" }], nextOffset: 50 });
    else reject(new ApiError("obsolete page failed", 503));
    await old;
    expect(vm.runs).toEqual([]);
    expect(vm.runsOffset).toBe(87);
    expect(vm.runsNextOffset).toBe(177);
    expect(vm.runPageOffsets).toEqual([0, 87]);
    expect(vm.error).toBe("");
    expect(vm.busy).toBe(false);
  },
);
