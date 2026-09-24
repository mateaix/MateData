/** A stale request must never publish data, errors or finalizers into another login. */
export class StaleSessionError extends Error {}
export function createSessionScope() {
  let generation = 0;
  function capture() {
    const own = generation;
    return () => generation === own;
  }
  return {
    invalidate() {
      generation++;
    },
    capture,
    async request<T>(work: () => Promise<T>): Promise<T> {
      const current = capture();
      try {
        const result = await work();
        if (!current()) throw new StaleSessionError();
        return result;
      } catch (error) {
        if (!current()) throw new StaleSessionError();
        throw error;
      }
    },
  };
}
