import { expect, it } from "vitest";
import { createModelVersions, runMatchesDataset } from "./modelVersions";
import type { Dataset, Run } from "./types";
const model: Dataset = {
  id: "sales",
  name: "销售",
  description: "",
  sourceId: "demo_sales",
  tableName: "sales",
  metrics: [
    {
      id: "revenue",
      name: "销售额",
      column: "amount",
      aggregation: "SUM",
      aliases: [],
    },
  ],
  dimensions: [],
};
it("keeps unchanged content valid but invalidates same-ID edits, removal and reversion", () => {
  const versions = createModelVersions();
  versions.update([model]);
  const before = versions.capture("sales");
  expect(versions.update([{ ...model }]).size).toBe(0);
  expect(versions.matches("sales", before)).toBe(true);
  expect(
    versions.update([{ ...model, sourceId: "external" }]).has("sales"),
  ).toBe(true);
  expect(versions.matches("sales", before)).toBe(false);
  versions.update([model]);
  expect(versions.matches("sales", before)).toBe(false);
  versions.update([]);
  expect(versions.capture("sales")).toBeUndefined();
});
it("treats nested metric and scope changes as content changes independent of object-key order", () => {
  const versions = createModelVersions();
  versions.update([model]);
  expect(
    versions.update([
      Object.fromEntries(Object.entries(model).reverse()) as unknown as Dataset,
    ]).size,
  ).toBe(0);
  versions.update([model]);
  expect(versions.update([{ ...model, metrics: [] }]).has("sales")).toBe(true);
});

it("requires matching fingerprints whenever either the model or run supplies one", () => {
  const run = { datasetId: "sales", scopeFingerprint: "same" } as Run;
  expect(runMatchesDataset(run, { ...model, scopeFingerprint: "same" })).toBe(
    true,
  );
  expect(runMatchesDataset(run, { ...model, scopeFingerprint: "other" })).toBe(
    false,
  );
  expect(runMatchesDataset(run, model)).toBe(false);
  expect(
    runMatchesDataset({ datasetId: "sales" } as Run, {
      ...model,
      scopeFingerprint: "same",
    }),
  ).toBe(false);
});
