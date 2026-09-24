import { expect, it } from "vitest";
import { createSessionScope, StaleSessionError } from "./session";
it("rejects successful old-session requests before their values can update state", async () => {
  const scope = createSessionScope();
  let resolve!: (value: string) => void;
  const result = scope.request(
    () =>
      new Promise<string>((r) => {
        resolve = r;
      }),
  );
  scope.invalidate();
  resolve("private old result");
  await expect(result).rejects.toBeInstanceOf(StaleSessionError);
});
it("rejects old-session failures so they cannot clear a new login", async () => {
  const scope = createSessionScope();
  let reject!: (error: Error) => void;
  const result = scope.request(
    () =>
      new Promise<string>((_, r) => {
        reject = r;
      }),
  );
  scope.invalidate();
  reject(new Error("old unauthorized"));
  await expect(result).rejects.toBeInstanceOf(StaleSessionError);
});
it("invalidates captured guards for loading and finalizers but allows current operations", async () => {
  const scope = createSessionScope(),
    old = scope.capture();
  scope.invalidate();
  expect(old()).toBe(false);
  expect(scope.capture()()).toBe(true);
  expect(await scope.request(async () => 42)).toBe(42);
});
