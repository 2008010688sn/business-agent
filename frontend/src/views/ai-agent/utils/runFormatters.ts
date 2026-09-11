import type { AnswerTraceExplain } from '@/views/ai-agent/services/chat';

export const formatTokenCount = (value?: number | null): string => {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    return '--';
  }
  if (value >= 1000000) {
    return `${(value / 1000000).toFixed(1)}m`;
  }
  if (value >= 1000) {
    return `${(value / 1000).toFixed(1)}k`;
  }
  return String(value);
};

export const formatExplainValue = (value: unknown) => {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    return String(value);
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch (error) {
    console.warn('格式化 explain 值失败:', error);
    return String(value);
  }
};

export const asExplainStringList = (value: unknown): string[] => {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
    .map(item => item.trim());
};

export const summarizeExplainExecution = (explain: AnswerTraceExplain | null) => {
  if (!explain) {
    return '当前还没有可展示的执行说明。';
  }
  const hasDatasourceEvidence =
    Boolean(explain.datasource || explain.sql) || explain.usedTables.length > 0 || explain.usedColumns.length > 0;
  const hasSemanticEvidence = explain.semanticHits.length > 0;
  const hasKnowledgeEvidence = explain.knowledgeHits.length > 0;
  if (hasDatasourceEvidence && hasSemanticEvidence && hasKnowledgeEvidence) {
    return '本轮回答同时使用了结构化数据源、语义模型召回和 RAG/知识召回，下面展示的是完整来源。';
  }
  if (hasDatasourceEvidence && hasSemanticEvidence) {
    return '本轮回答同时使用了结构化数据源和语义模型召回，下面展示的是 SQL 来源与语义来源。';
  }
  if (hasDatasourceEvidence && hasKnowledgeEvidence) {
    return '本轮回答同时使用了结构化数据源和 RAG/知识召回，下面展示的是 SQL 来源与知识来源。';
  }
  if (hasDatasourceEvidence) {
    return '本轮回答访问了结构化数据源，下面展示的是实际执行过的数据源步骤、SQL、使用表和使用字段。';
  }
  if (hasKnowledgeEvidence && hasSemanticEvidence) {
    return '本轮回答没有直接查库，但同时命中了语义模型和 RAG/知识召回，回答受这些来源共同影响。';
  }
  if (hasKnowledgeEvidence) {
    return '本轮回答没有直接查库，但命中了 RAG/知识召回结果，回答受这些知识片段影响。';
  }
  if (hasSemanticEvidence) {
    return '本轮回答没有直接查库，但命中了语义模型，用来帮助系统理解你的问题和业务字段。';
  }
  if (explain.toolSteps.length > 0 || (explain.clarify && Object.keys(explain.clarify).length > 0)) {
    return '本轮回答没有形成可展示的查库明细，但系统执行过澄清或其他工具步骤，详细过程可在下方查看。';
  }
  return '本轮回答没有访问数据库、知识库或其他可回放工具，当前结果主要来自模型直接生成。';
};

export const formatTraceDuration = (durationMs: number) => {
  if (durationMs < 1000) {
    return `${durationMs} ms`;
  }
  if (durationMs < 10000) {
    return `${(durationMs / 1000).toFixed(2)} s`;
  }
  return `${(durationMs / 1000).toFixed(1)} s`;
};

export const formatTraceTime = (epochMs: number) => {
  if (!epochMs) {
    return '--';
  }
  return new Date(epochMs).toLocaleString();
};

export const formatTraceOffset = (epochMs: number, startEpochMs?: number | null) => {
  if (!startEpochMs || !epochMs) {
    return '--';
  }
  const offset = Math.max(0, epochMs - startEpochMs);
  return `+${formatTraceDuration(offset)}`;
};

export const isStructuredTraceValue = (value: string) => {
  const normalizedValue = value?.trim();
  return Boolean(
    normalizedValue &&
      ((normalizedValue.startsWith('{') && normalizedValue.endsWith('}')) ||
        (normalizedValue.startsWith('[') && normalizedValue.endsWith(']')))
  );
};

export const formatStructuredTraceValue = (value: string) => {
  if (!isStructuredTraceValue(value)) {
    return value;
  }
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch (error) {
    return value;
  }
};
