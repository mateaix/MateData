export interface User {
  username: string;
  displayName: string;
  role: string;
}
export interface System {
  name: string;
  version: string;
  javaVersion: string;
  agentScopeVersion: string;
  mode: string;
  modelConfigured: boolean;
}
export interface Field {
  id: string;
  name: string;
  column: string;
  aggregation?: string;
  aliases: string[];
}
export interface Dataset {
  id: string;
  name: string;
  description: string;
  sourceId: string;
  tableName: string;
  metrics: Field[];
  dimensions: Field[];
}
export interface Source {
  id: string;
  name: string;
  type: string;
  jdbcUrl: string;
  username: string;
  status: string;
  createdAt: string;
}
export interface Run {
  id: string;
  conversationId: string;
  question: string;
  datasetId: string;
  mode: string;
  status: string;
  sql: string;
  columns: string[];
  rows: Record<string, unknown>[];
  rowCount: number;
  durationMs: number;
  createdAt: string;
  answer: string;
  error: string | null;
  steps: { name: string; status: string; detail: string; durationMs: number }[];
}
export interface Model {
  baseUrl: string;
  model: string;
  configured: boolean;
  maxSteps: number;
  timeoutSeconds: number;
  apiKey?: string;
}
export interface Evaluation {
  id: string;
  name: string;
  question: string;
  datasetId: string;
  expectedMetric: string;
  expectedDimension: string;
}
export interface EvaluationResult {
  id: string;
  passed: number;
  total: number;
  durationMs: number;
  results: { name: string; passed: boolean; message: string; runId: string }[];
}
export interface PermissionGrant {
  username: string;
  datasetId: string;
  enabled: boolean;
  metrics: string[];
  dimensions: string[];
  rowFilters: Record<string, string>;
}
export interface AuditEntry {
  id: string;
  username: string;
  action: string;
  resource: string;
  createdAt: string;
}

export interface RunPage {
  items: Run[];
  nextOffset: number | null;
}
