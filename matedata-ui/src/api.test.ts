import { afterEach, expect, it, vi } from "vitest";
import { request } from "./api";
afterEach(() => vi.unstubAllGlobals());
it("sends same-origin cookies and CSRF header for authenticated API calls", async () => {
  const fetcher = vi.fn(
    async (_url: string, _options: RequestInit) =>
      new Response(JSON.stringify({ ok: true }), { status: 200 }),
  );
  vi.stubGlobal("fetch", fetcher);
  expect(await request("/datasets")).toEqual({ ok: true });
  const [url, options] = fetcher.mock.calls[0]!;
  expect(url).toBe("/api/v1/datasets");
  expect(options.credentials).toBe("same-origin");
  expect(new Headers(options.headers).get("X-MateData-Request")).toBe("1");
});
it("preserves server error message and request identifier", async () => {
  vi.stubGlobal(
    "fetch",
    async () =>
      new Response(
        JSON.stringify({
          code: "BAD_REQUEST",
          message: "数据集不存在",
          requestId: "trace-7",
        }),
        { status: 400 },
      ),
  );
  await expect(request("/queries", { method: "POST" })).rejects.toThrow(
    "数据集不存在 · 请求 trace-7",
  );
});
it("handles non-JSON gateway failures without losing HTTP status", async () => {
  vi.stubGlobal(
    "fetch",
    async () => new Response("Bad gateway", { status: 502 }),
  );
  await expect(request("/runs")).rejects.toThrow("请求失败（502）");
});
it("accepts successful empty responses", async () => {
  vi.stubGlobal("fetch", async () => new Response(null, { status: 204 }));
  expect(await request("/auth/logout", { method: "POST" })).toBeUndefined();
});
