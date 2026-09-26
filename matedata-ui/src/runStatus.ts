/** Display of a run's status, shared by the result card and the history table. */
export function runStatus(status: string): {
  label: string;
  type: "success" | "warning" | "danger";
} {
  if (status === "SUCCEEDED") return { label: "已完成", type: "success" };
  if (status === "NEEDS_INPUT") return { label: "需要补充", type: "warning" };
  return { label: "执行失败", type: "danger" };
}
