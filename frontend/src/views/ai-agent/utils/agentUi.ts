import type { ChatMessage } from '@/views/ai-agent/services/chat';
import type { AgentResponse, FlowAction } from '@/views/ai-agent/services/graph';

export const AGENT_UI_SCHEMA_VERSION = 'agent-ui/v2';

export interface AgentUiSource {
  flowInstanceId?: string;
}

export interface AgentUiOption {
  label?: string;
  value?: unknown;
  summary?: string;
  rawData?: Record<string, unknown>;
  [key: string]: unknown;
}

export interface AgentUiAction extends FlowAction {
  label?: string;
}

export interface AgentUiMissingSlot {
  path?: string;
  name?: string;
  prompt?: string;
}

export interface AgentUiStep {
  kind?: string;
  label?: string;
  status?: string;
  durationMs?: number | null;
}

export interface AgentUiMessage {
  schemaVersion: typeof AGENT_UI_SCHEMA_VERSION;
  kind: string;
  runtimeRequestId?: string;
  source?: AgentUiSource;
  content?: { format?: string; text?: string };
  payload?: { action?: string; values?: Record<string, unknown>; options?: AgentUiOption[] };
  actions?: AgentUiAction[];
  timing?: { stageCode?: string; durationMs?: number };
  steps?: AgentUiStep[];
}

export const ANALYSIS_RESULT_KIND = 'analysis-result';
export const SKILL_FLOW_KIND = 'skill-flow';
export const TOOL_CONFIRM_KIND = 'tool-confirm';
export const ANALYSIS_ACTION_TYPES = new Set(['DRILL', 'START_FLOW', 'ASK_WRITE', 'NONE']);
export const TOOL_CONFIRM_ACTION_TYPES = new Set(['APPROVE', 'DENY']);

export const FLOW_ACTION_TYPES = new Set([
  'SELECT',
  'SKIP',
  'COLLECT',
  'EXTRACT',
  'VALIDATION',
  'REVIEW',
  'CONFIRM',
  'EDIT',
  'CLEAR_REFERENCE',
  'CANCEL',
  'CANCEL_CONFIRM',
  'CONFIRM_CANCEL',
  'KEEP_FLOW',
  'SUBMIT',
  'REASK',
  'CHANGE_MODEL',
  'FALLBACK'
]);
// 非交互类 FLOW 卡片动作：actions 为空即合法等待/终态卡（正文展示，无按钮）。
// 与引擎动作词表对齐——引擎全部 terminal/waiting 动作均需归入交互或非交互集合，否则卡片被误判失效。
const FLOW_NON_INTERACTIVE_ACTION_TYPES = new Set([
  'PRESENT',
  'SUCCEEDED',
  'HANDOFF',
  'ERROR',
  'APPROVAL',
  'PROCESSING',
  'CANCELLED',
  'FAILED',
  'RESOLVER_RETRY'
]);
const FLOW_CHAT_WAITING_ACTION_TYPES = new Set(['REASK', 'CHANGE_MODEL']);

const isRecord = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object');

const MAX_AGENT_UI_STEPS = 30;

const nonEmptyString = (value: unknown): string | undefined =>
  typeof value === 'string' && value.trim() !== '' ? value : undefined;

// 仅接受有限数字；契约允许的 null 保留，其余视为缺失。
const stepDurationMs = (value: unknown): number | null | undefined => {
  if (value === null) return null;
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
};

const parseAgentUiSteps = (rawSteps: unknown): AgentUiStep[] | undefined => {
  if (!Array.isArray(rawSteps)) return undefined;
  return rawSteps
    .filter(
      (item): item is Record<string, unknown> =>
        isRecord(item) && Boolean(nonEmptyString(item.label) || nonEmptyString(item.kind))
    )
    .slice(0, MAX_AGENT_UI_STEPS)
    .map(item => ({
      kind: nonEmptyString(item.kind),
      label: nonEmptyString(item.label),
      status: nonEmptyString(item.status),
      durationMs: stepDurationMs(item.durationMs)
    }));
};

export const parseAgentUi = (metadata?: Record<string, unknown> | null): AgentUiMessage | null => {
  if (!metadata || metadata.uiSchemaVersion !== AGENT_UI_SCHEMA_VERSION || !isRecord(metadata.agentUi)) return null;
  const rawUi = metadata.agentUi;
  const kind =
    rawUi.kind === ANALYSIS_RESULT_KIND
      ? ANALYSIS_RESULT_KIND
      : rawUi.kind === TOOL_CONFIRM_KIND
        ? TOOL_CONFIRM_KIND
        : rawUi.kind === SKILL_FLOW_KIND
          ? SKILL_FLOW_KIND
          : null;
  if (rawUi.schemaVersion !== AGENT_UI_SCHEMA_VERSION || !kind) return null;
  const rawSource = isRecord(rawUi.source) ? rawUi.source : undefined;
  const source =
    typeof rawSource?.flowInstanceId === 'string' ? { flowInstanceId: rawSource.flowInstanceId } : undefined;
  // steps 为流程卡附带执行轨迹，可选字段向后兼容，仅 skill-flow 卡解析（老消息缺失时保持 undefined）。
  const steps = kind === SKILL_FLOW_KIND ? parseAgentUiSteps(rawUi.steps) : undefined;
  return {
    schemaVersion: AGENT_UI_SCHEMA_VERSION,
    kind,
    runtimeRequestId: typeof rawUi.runtimeRequestId === 'string' ? rawUi.runtimeRequestId : undefined,
    source,
    content: isRecord(rawUi.content) ? (rawUi.content as AgentUiMessage['content']) : undefined,
    payload: isRecord(rawUi.payload) ? (rawUi.payload as AgentUiMessage['payload']) : undefined,
    actions: Array.isArray(rawUi.actions) ? (rawUi.actions as AgentUiAction[]) : undefined,
    timing: isRecord(rawUi.timing) ? (rawUi.timing as AgentUiMessage['timing']) : undefined,
    steps
  };
};

export const isAnalysisResultUi = (ui: AgentUiMessage | null | undefined): ui is AgentUiMessage => {
  return Boolean(ui && ui.kind === ANALYSIS_RESULT_KIND && ui.schemaVersion === AGENT_UI_SCHEMA_VERSION);
};

export interface AnalysisFollowUp {
  type: string;
  label?: string;
  query?: string;
  value?: unknown;
  grain?: string;
  skillCode?: string;
  toolName?: string;
}

export const parseAnalysisFollowUps = (metadata: Record<string, unknown> | null | undefined): AnalysisFollowUp[] => {
  const raw = metadata?.analysisFollowUps;
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw
    .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === 'object'))
    .map(item => {
      const type = String(item.type || '').toUpperCase();
      return {
        type,
        label: typeof item.label === 'string' ? item.label : undefined,
        query: typeof item.query === 'string' ? item.query : undefined,
        value: item.value,
        grain: typeof item.grain === 'string' ? item.grain : undefined,
        skillCode: typeof item.skillCode === 'string' ? item.skillCode : undefined,
        toolName: typeof item.toolName === 'string' ? item.toolName : undefined
      };
    })
    .filter(item => ANALYSIS_ACTION_TYPES.has(item.type) && item.type !== 'NONE');
};

export const followUpDisplayText = (followUp: AnalysisFollowUp): string => {
  const query = String(followUp.query || followUp.value || '').trim();
  return query || String(followUp.label || '').trim();
};

export const isToolConfirmUi = (ui: AgentUiMessage | null | undefined): ui is AgentUiMessage => {
  return Boolean(ui && ui.kind === TOOL_CONFIRM_KIND && ui.schemaVersion === AGENT_UI_SCHEMA_VERSION);
};

/** Valid FLOW `skill-flow` card only. Analysis-result / tool-confirm are routed separately. */
export const isValidSkillFlowUi = (ui: AgentUiMessage | null): ui is AgentUiMessage => {
  if (isAnalysisResultUi(ui) || isToolConfirmUi(ui)) return false;
  if (!ui || !ui.source?.flowInstanceId) return false;
  const actions = Array.isArray(ui.actions) ? ui.actions : [];
  if (actions.some(action => !FLOW_ACTION_TYPES.has(String(action.type || '').toUpperCase()))) return false;
  const actionType = String(ui.payload?.action || '').toUpperCase();
  if (
    actionType &&
    !FLOW_ACTION_TYPES.has(actionType) &&
    !(actions.length === 0
      && (FLOW_NON_INTERACTIVE_ACTION_TYPES.has(actionType) || FLOW_CHAT_WAITING_ACTION_TYPES.has(actionType)))
  ) {
    return false;
  }
  if (actionType === 'SELECT') {
    const hasSelectAction = actions.some(action => String(action.type || '').toUpperCase() === 'SELECT');
    const options = Array.isArray(ui.payload?.options) ? ui.payload.options : [];
    // 空选项 SELECT 是合法等待卡（等待用户输入名称/编码搜索或重试），仅校验非空选项的取值。
    return hasSelectAction && options.every(option => option.value !== undefined && option.value !== null);
  }
  return true;
};

/** @deprecated Flow-only predicate; use isValidSkillFlowUi. */
export const isValidAgentUi = isValidSkillFlowUi;

export const isWaitingAgentUi = (ui: AgentUiMessage | null) => {
  return Boolean(ui && ui.timing?.stageCode === 'FLOW_WAITING');
};

export const getAgentUiIdentity = (ui: AgentUiMessage): string => {
  if (isToolConfirmUi(ui)) {
    const values = ui.payload?.values || {};
    const toolCallId = typeof values.toolCallId === 'string' ? values.toolCallId : '';
    const fingerprint = typeof values.paramFingerprint === 'string' ? values.paramFingerprint : '';
    if (toolCallId && fingerprint) {
      return `${ui.runtimeRequestId || ''}:${toolCallId}:${fingerprint}`;
    }
  }
  const source = ui.source || {};
  if (ui.runtimeRequestId && source.flowInstanceId) {
    return `${ui.runtimeRequestId}:${source.flowInstanceId}`;
  }
  return getAgentUiFallbackIdentity(ui);
};

export const getAgentUiFallbackIdentity = (ui: AgentUiMessage): string => {
  const source = ui.source || {};
  const options = (ui.payload?.options || [])
    .map(option => `${String(option.value ?? '')}:${String(option.label ?? '')}`)
    .join('|');
  return `${source.flowInstanceId || ''}:${String(ui.payload?.action || '')}:${String(ui.content?.text || '')}:${options}`;
};

export const parseAgentUiFromMessage = (message: ChatMessage): AgentUiMessage | null => {
  if (!message.metadata || String(message.role).toLowerCase() !== 'assistant') return null;
  try {
    return parseAgentUi(JSON.parse(message.metadata) as Record<string, unknown>);
  } catch {
    return null;
  }
};

export const parseAgentUiFromResponses = (responses?: AgentResponse[] | null): AgentUiMessage | null => {
  for (const response of responses || []) {
    const ui = parseAgentUi(response.metadata as Record<string, unknown> | undefined);
    if (ui) return ui;
  }
  return null;
};

export interface AgentUiActionRequest {
  flowInstanceId?: string;
  flowAction: FlowAction;
  displayText: string;
}

export const hasEmptySlotFilter = (ui: AgentUiMessage | null | undefined): boolean => {
  const missing = ui?.payload?.values?.missing;
  return Array.isArray(missing) && missing.length > 0;
};

export const emptySlotFields = (ui: AgentUiMessage | null | undefined): AgentUiMissingSlot[] => {
  const missing = ui?.payload?.values?.missing;
  if (!hasEmptySlotFilter(ui) || !Array.isArray(missing)) return [];
  return missing
    .map(item => {
      if (typeof item === 'string') {
        const path = item;
        return { path, name: path.split('/').filter(Boolean).at(-1) };
      }
      if (!item || typeof item !== 'object') return null;
      const record = item as Record<string, unknown>;
      const path = typeof record.path === 'string' ? record.path : undefined;
      const name =
        typeof record.name === 'string' ? record.name : path?.split('/').filter(Boolean).at(-1);
      const prompt = typeof record.prompt === 'string' ? record.prompt : undefined;
      if (!path && !name && !prompt) return null;
      return { path, name, prompt };
    })
    .filter((item): item is AgentUiMissingSlot => item !== null);
};

export const toAgentUiActionRequest = (
  ui: AgentUiMessage,
  action: AgentUiAction,
  option?: AgentUiOption
): AgentUiActionRequest => ({
  flowInstanceId: ui.source?.flowInstanceId,
  flowAction: {
    actionId: action.actionId,
    type: action.type,
    value: option?.value ?? action.value,
    payload: option?.rawData ? { rawData: option.rawData } : action.payload || {}
  },
  displayText: option?.label || action.label || action.type
});

const slotPreview = (slots: Record<string, unknown>) =>
  Object.entries(slots)
    .filter(([, value]) => value !== undefined && value !== null && String(value).trim() !== '')
    .map(([key, value]) => `${key}=${String(value)}`)
    .join('，');

export const toAnalysisActionRequest = (ui: AgentUiMessage, action: AgentUiAction): AgentUiActionRequest => {
  const type = String(action.type || '').toUpperCase();
  const payload = isRecord(action.payload) ? action.payload : {};
  if (type === 'START_FLOW') {
    const skillCode = String(payload.skillCode || action.value || '').trim();
    const slots = isRecord(payload.slots) ? payload.slots : {};
    const preview = slotPreview(slots);
    const displayText = preview
      ? `请启动办理技能 ${skillCode}，预填：${preview}。请先确认，不要直接执行。`
      : `请启动办理技能 ${skillCode}。请先确认，不要直接执行。`;
    return {
      flowAction: {
        actionId: action.actionId,
        type,
        value: skillCode,
        payload: { skillCode, slots, confirm: true }
      },
      displayText
    };
  }
  if (type === 'ASK_WRITE') {
    const toolName = String(payload.toolName || action.value || '').trim();
    return {
      flowAction: {
        actionId: action.actionId,
        type,
        value: toolName,
        payload: { toolName, confirm: true }
      },
      displayText: `请对写工具 ${toolName} 发起确认后再执行，不要直接执行。`
    };
  }
  if (type === 'DRILL') {
    const query = String(payload.query || action.value || action.label || '').trim();
    return {
      flowAction: {
        actionId: action.actionId,
        type,
        value: query,
        payload: { query }
      },
      displayText: query
    };
  }
  return toAgentUiActionRequest(ui, action);
};

export interface ToolConfirmDecisionRequest {
  toolCallId: string;
  toolName?: string;
  paramFingerprint: string;
  replyId?: string;
  approvalId?: string;
  comment?: string;
  approved: boolean;
}

export const toolConfirmDecisionFromUi = (
  ui: AgentUiMessage,
  approved: boolean,
  comment?: string
): ToolConfirmDecisionRequest | null => {
  const values = ui.payload?.values || {};
  const toolCallId = typeof values.toolCallId === 'string' ? values.toolCallId.trim() : '';
  const paramFingerprint = typeof values.paramFingerprint === 'string' ? values.paramFingerprint.trim() : '';
  if (!toolCallId || !paramFingerprint) return null;
  const toolName = typeof values.toolName === 'string' ? values.toolName : undefined;
  const replyId = typeof values.replyId === 'string' ? values.replyId : undefined;
  const approvalId =
    values.approvalId === undefined || values.approvalId === null ? undefined : String(values.approvalId);
  return { toolCallId, toolName, paramFingerprint, replyId, approvalId, comment, approved };
};
