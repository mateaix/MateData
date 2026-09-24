import { expect, it } from "vitest";
import { chartUnavailableReason } from "./chart";
it("explains why a negative metric is not shown as a positive bar", () => {
  expect(
    chartUnavailableReason([{ profit: 20 }, { profit: -30 }], "profit"),
  ).toContain("负值");
});
it("checks all returned rows, including those outside the first 12 bars", () => {
  expect(
    chartUnavailableReason(
      [...Array.from({ length: 12 }, () => ({ profit: 1 })), { profit: -3 }],
      "profit",
    ),
  ).toContain("负值");
});
it("permits nonnegative bars", () => {
  expect(chartUnavailableReason([{ profit: 0 }, { profit: 9 }], "profit")).toBe(
    "",
  );
});
it("rejects financial decimal strings outside safe chart magnitude", () => {
  expect(
    chartUnavailableReason([{ profit: "9007199254740993.01" }], "profit"),
  ).toContain("安全");
  expect(
    chartUnavailableReason([{ profit: "9007199254740991.01" }], "profit"),
  ).toContain("安全");
});
it("allows ordinary financial decimal strings without losing their signs", () => {
  expect(chartUnavailableReason([{ profit: "1449000.00" }], "profit")).toBe("");
  expect(
    chartUnavailableReason([{ profit: "-1449000.00" }], "profit"),
  ).toContain("负值");
});
