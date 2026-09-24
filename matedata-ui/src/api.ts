export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function request<T>(
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set("X-MateData-Request", "1");
  if (init.body) headers.set("Content-Type", "application/json");
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      ...init,
      headers,
      credentials: "same-origin",
    });
  } catch {
    throw new ApiError("无法连接服务，请检查网络与后端服务后重试。", 0);
  }
  const text = await response.text();
  let data: unknown;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    if (response.ok)
      throw new ApiError("服务返回了无效的数据格式。", response.status);
  }
  if (!response.ok) {
    const body = data as { message?: string; requestId?: string } | undefined;
    throw new ApiError(
      `${body?.message || `请求失败（${response.status}）`}${body?.requestId ? ` · 请求 ${body.requestId}` : ""}`,
      response.status,
    );
  }
  return data as T;
}
export const json = (value: unknown) => JSON.stringify(value);
