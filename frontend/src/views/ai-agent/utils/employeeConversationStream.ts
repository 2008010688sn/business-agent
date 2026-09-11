import { parseAgentUi, type AgentUiMessage } from '@/views/ai-agent/utils/agentUi';

/** 员工 SSE message 节点解析结果（事件名仍是 message，不新增 SSE 类型） */
export interface EmployeeConversationParsedNode {
  text: string;
  agentUi: AgentUiMessage | null;
  runtimeRequestId?: string;
  sessionId?: string;
}

const asOptionalString = (value: unknown): string | undefined => {
  if (typeof value === 'string' && value.trim()) {
    return value.trim();
  }
  if (typeof value === 'number' && Number.isFinite(value)) {
    return String(value);
  }
  return undefined;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  Boolean(value && typeof value === 'object' && !Array.isArray(value));

/** 解析员工对话 SSE message 节点的 text / metadata.agentUi / runtimeRequestId。 */
export const parseEmployeeConversationMessage = (
  node: Record<string, unknown>
): EmployeeConversationParsedNode => {
  const metadata = isRecord(node.metadata) ? node.metadata : undefined;
  const agentUi = parseAgentUi(metadata);
  return {
    text: typeof node.text === 'string' ? node.text : '',
    agentUi,
    runtimeRequestId:
      asOptionalString(metadata?.runtimeRequestId) ||
      asOptionalString(agentUi?.runtimeRequestId) ||
      asOptionalString(node.runtimeRequestId),
    sessionId: asOptionalString(metadata?.sessionId) || asOptionalString(node.threadId)
  };
};
