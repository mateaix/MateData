const decimalPattern = /^[+-]?\d+(?:\.\d+)?$/;
export function isNumericCell(value: unknown): boolean {
  return (
    typeof value === "number" ||
    (typeof value === "string" && decimalPattern.test(value))
  );
}
function exceedsSafeMagnitude(value: string): boolean {
  const [integer = "", fraction = ""] = value.replace(/^[+-]/, "").split(".");
  const normalized = integer.replace(/^0+/, "") || "0";
  const limit = String(Number.MAX_SAFE_INTEGER);
  return (
    normalized.length > limit.length ||
    (normalized.length === limit.length &&
      (normalized > limit || (normalized === limit && /[1-9]/.test(fraction))))
  );
}
export function chartUnavailableReason(
  rows: Record<string, unknown>[],
  column: string | undefined,
): string {
  if (!column) return "";
  const values = rows.map((row) => row[column]);
  if (
    values.some(
      (value) => !isNumericCell(value) || !Number.isFinite(Number(value)),
    )
  ) {
    return "该列包含缺失或非数值内容，请查看表格中的原始结果。";
  }
  if (
    values.some((value) =>
      typeof value === "string"
        ? exceedsSafeMagnitude(value)
        : Math.abs(Number(value)) > Number.MAX_SAFE_INTEGER,
    )
  ) {
    return "数值超出图表的安全精度范围，已关闭图表。下方表格保留完整精确值。";
  }
  if (values.some((value) => Number(value) < 0)) {
    return "结果包含负值，已关闭仅适用于非负数的条形图。请查看下方表格中的带符号数值。";
  }
  if (
    values.some(
      (value) =>
        typeof value === "string" && Number(value) === 0 && /[1-9]/.test(value),
    )
  ) {
    return "数值小于图表的安全精度范围，请查看表格中的完整精确值。";
  }
  return "";
}
