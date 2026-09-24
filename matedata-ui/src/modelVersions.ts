import type { Dataset, Run } from "./types";
function canonical(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object")
    return Object.fromEntries(
      Object.entries(value)
        .sort(([a], [b]) => a.localeCompare(b))
        .map(([key, item]) => [key, canonical(item)]),
    );
  return value;
}
/** Local revisions protect cached responses even if a model changes and later reverts. */
export function createModelVersions() {
  let signatures = new Map<string, string>();
  const revisions = new Map<string, number>();
  return {
    update(datasets: Dataset[]) {
      const next = new Map(
        datasets.map((model) => [model.id, JSON.stringify(canonical(model))]),
      );
      const changed = new Set<string>();
      for (const id of new Set([...signatures.keys(), ...next.keys()])) {
        if (signatures.get(id) !== next.get(id)) {
          changed.add(id);
          revisions.set(id, (revisions.get(id) || 0) + 1);
        }
      }
      signatures = next;
      return changed;
    },
    capture(id: string) {
      return signatures.has(id) ? revisions.get(id) : undefined;
    },
    snapshot() {
      return new Map(
        [...signatures.keys()].map((id) => [id, revisions.get(id)!]),
      );
    },
    matches(id: string, version: number | undefined) {
      return (
        version !== undefined &&
        signatures.has(id) &&
        revisions.get(id) === version
      );
    },
    clear() {
      signatures.clear();
      revisions.clear();
    },
  };
}

/** Optional for legacy servers; when either side sends a fingerprint, both must agree. */
export function runMatchesDataset(
  run: Run,
  dataset: Dataset | undefined,
): boolean {
  if (!dataset || dataset.id !== run.datasetId) return false;
  const runScope = run.scopeFingerprint || null;
  const datasetScope = dataset.scopeFingerprint || null;
  return runScope === datasetScope;
}
