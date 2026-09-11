import type { ClarificationResponse } from '@/views/ai-agent/services/graph';

export type BusinessInteractionSource = 'business-clarify' | 'confirmation';
export type LegacySuggestedRepliesSource = 'query-clarify' | 'agent-runtime';
export type SuggestedRepliesSource = BusinessInteractionSource | LegacySuggestedRepliesSource;
export type BusinessInteractionSchema = 'business-clarify/v1' | 'confirm/v1';

export interface SuggestedReplyOption {
  optionId: string;
  label: string;
  value: string;
  summary?: string;
}

export interface SuggestedReplyGroup {
  groupId: string;
  title: string;
  required?: boolean;
  maxSelect: number;
  options: SuggestedReplyOption[];
}

export interface ConfirmationPlanStep {
  name: string;
  description?: string;
  riskLevel?: string;
}

export interface SuggestedReplies {
  schemaVersion: 'suggested-replies/v1';
  source: SuggestedRepliesSource;
  submitMode: 'confirm';
  groups: SuggestedReplyGroup[];
  title?: string;
  prompt?: string;
  clarificationId?: string;
  clarificationSchemaVersion?: BusinessInteractionSchema;
  allowFreeText?: boolean;
  expiresAt?: string;
  riskLevel?: string;
  summary?: string;
  planSteps?: ConfirmationPlanStep[];
  originalQuery?: string;
}

export interface SuggestedReplySubmission {
  value: string;
  displayText: string;
  clarificationResponse?: ClarificationResponse;
}

const textValue = (value: unknown, maxLength = 500) => {
  if (typeof value !== 'string') return '';
  return value.trim().slice(0, maxLength);
};

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
};

const isBusinessSource = (value: SuggestedRepliesSource): value is BusinessInteractionSource =>
  value === 'business-clarify' || value === 'confirmation';

const normalizeOption = (value: unknown): SuggestedReplyOption | null => {
  if (!isRecord(value)) return null;
  const optionId = textValue(value.id ?? value.optionId, 160);
  const label = textValue(value.label, 200);
  const description = textValue(value.description ?? value.summary, 500);
  if (!optionId || !label) return null;
  return {
    optionId,
    label,
    value: label,
    ...(description ? { summary: description } : {})
  };
};

const normalizeOptions = (value: unknown): SuggestedReplyOption[] => {
  if (!Array.isArray(value)) return [];
  const seen = new Set<string>();
  return value
    .slice(0, 20)
    .map(normalizeOption)
    .filter((option): option is SuggestedReplyOption => {
      if (!option || seen.has(option.optionId)) return false;
      seen.add(option.optionId);
      return true;
    });
};

const normalizeConfirmationPlanSteps = (value: unknown): ConfirmationPlanStep[] => {
  if (!Array.isArray(value)) return [];
  return value
    .slice(0, 8)
    .map(item => {
      if (!isRecord(item)) return null;
      const name = textValue(item.name, 200);
      if (!name) return null;
      const description = textValue(item.description, 500);
      const riskLevel = textValue(item.riskLevel, 40);
      return {
        name,
        ...(description ? { description } : {}),
        ...(riskLevel ? { riskLevel } : {})
      };
    })
    .filter((step): step is ConfirmationPlanStep => Boolean(step));
};

const normalizeLegacyOption = (value: unknown): SuggestedReplyOption | null => {
  if (!isRecord(value)) return null;
  const optionId = textValue(value.optionId, 160);
  const label = textValue(value.label, 200);
  const optionValue = textValue(value.value, 500);
  if (!optionId || !label || !optionValue) return null;
  const summary = textValue(value.summary, 500);
  return { optionId, label, value: optionValue, ...(summary ? { summary } : {}) };
};

const normalizeLegacyGroup = (value: unknown): SuggestedReplyGroup | null => {
  if (!isRecord(value) || !Array.isArray(value.options)) return null;
  const groupId = textValue(value.groupId, 160);
  const title = textValue(value.title, 200);
  if (!groupId || !title) return null;
  const seen = new Set<string>();
  const options = value.options
    .slice(0, 20)
    .map(normalizeLegacyOption)
    .filter((option): option is SuggestedReplyOption => {
      if (!option || seen.has(option.optionId)) return false;
      seen.add(option.optionId);
      return true;
    });
  if (!options.length) return null;
  const maxSelect =
    typeof value.maxSelect === 'number' && Number.isInteger(value.maxSelect) && value.maxSelect > 0
      ? Math.min(value.maxSelect, options.length)
      : 1;
  return { groupId, title, required: value.required === true, maxSelect, options };
};

const normalizeBusinessInteraction = (
  value: Record<string, unknown>,
  source: BusinessInteractionSource
): SuggestedReplies | null => {
  const schemaVersion = value.schemaVersion;
  const expectedSchema: BusinessInteractionSchema = source === 'confirmation' ? 'confirm/v1' : 'business-clarify/v1';
  if (schemaVersion !== expectedSchema) return null;
  const clarificationId = textValue(value.clarificationId, 200);
  const title = textValue(value.title, 200);
  const prompt = textValue(value.prompt, 1000);
  const allowFreeText = value.allowFreeText === true;
  const options = normalizeOptions(value.options);
  if (!clarificationId || (!options.length && !allowFreeText)) return null;
  const group: SuggestedReplyGroup = {
    groupId: 'business-interaction',
    title: title || (source === 'confirmation' ? '请确认本次操作' : '请补充业务信息'),
    required: !allowFreeText && options.length > 0,
    maxSelect: source === 'confirmation' ? 1 : Math.max(1, options.length),
    options
  };
  const riskLevel = textValue(value.riskLevel, 40);
  const summary = textValue(value.summary, source === 'confirmation' ? 2000 : 500);
  const expiresAt = textValue(value.expiresAt, 80);
  const planSteps = source === 'confirmation' ? normalizeConfirmationPlanSteps(value.planSteps) : [];
  return {
    schemaVersion: 'suggested-replies/v1',
    source,
    submitMode: 'confirm',
    groups: options.length ? [group] : [],
    title: title || undefined,
    prompt: prompt || undefined,
    clarificationId,
    clarificationSchemaVersion: expectedSchema,
    allowFreeText,
    expiresAt: expiresAt || undefined,
    riskLevel: riskLevel || undefined,
    summary: summary || undefined,
    ...(planSteps.length ? { planSteps } : {})
  };
};

export const normalizeSuggestedReplies = (metadata?: Record<string, unknown> | null): SuggestedReplies | null => {
  if (!isRecord(metadata)) return null;
  const businessClarification = metadata.businessClarification;
  if (isRecord(businessClarification)) {
    return normalizeBusinessInteraction(businessClarification, 'business-clarify');
  }
  const confirmation = metadata.confirmation;
  if (isRecord(confirmation)) {
    return normalizeBusinessInteraction(confirmation, 'confirmation');
  }
  const value = metadata.suggestedReplies;
  if (!isRecord(value) || value.schemaVersion !== 'suggested-replies/v1') return null;
  if (value.source !== 'query-clarify' && value.source !== 'agent-runtime') return null;
  if (value.submitMode !== 'confirm' || !Array.isArray(value.groups)) return null;
  const groups = value.groups.map(normalizeLegacyGroup).filter((group): group is SuggestedReplyGroup => Boolean(group));
  if (!groups.length) return null;
  const originalQuery = textValue(metadata.originalQuery, 2000);
  return {
    schemaVersion: 'suggested-replies/v1',
    source: value.source,
    submitMode: 'confirm',
    groups,
    ...(originalQuery ? { originalQuery } : {})
  };
};

export const BUSINESS_CLARIFICATION_INPUT_LOCK_HINT = '请先在上方卡片选择或补充';

export const isBusinessInteractionCard = (
  replies: SuggestedReplies | null | undefined
): replies is SuggestedReplies => {
  return Boolean(
    replies && (replies.source === 'business-clarify' || replies.source === 'confirmation') && replies.clarificationId
  );
};

const readMessageMetadata = (metadata: unknown): Record<string, unknown> | null => {
  if (isRecord(metadata)) return metadata;
  if (typeof metadata !== 'string' || !metadata.trim()) return null;
  try {
    const parsed = JSON.parse(metadata) as unknown;
    return isRecord(parsed) ? parsed : null;
  } catch {
    return null;
  }
};

const isSkippableAssistantForClarificationLock = (message: {
  role?: string;
  messageType?: string;
  metadata?: unknown;
}) => {
  const type = String(message.messageType || '').toLowerCase();
  if (type === 'thinking' || type === 'answer-explain') return true;
  const metadata = readMessageMetadata(message.metadata);
  return metadata?.error === true;
};

export const getUnconsumedBusinessClarification = (
  messages: Array<{ role?: string; messageType?: string; metadata?: unknown }> | null | undefined,
  releasedClarificationIds?: Iterable<string>
): SuggestedReplies | null => {
  if (!Array.isArray(messages) || messages.length === 0) return null;
  const released = new Set(
    Array.from(releasedClarificationIds || []).filter(id => typeof id === 'string' && id.trim())
  );
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (String(message?.role || '').toLowerCase() !== 'assistant') {
      continue;
    }
    if (isSkippableAssistantForClarificationLock(message)) {
      continue;
    }
    const replies = normalizeSuggestedReplies(readMessageMetadata(message.metadata));
    if (isBusinessInteractionCard(replies) && !released.has(replies.clarificationId as string)) {
      return replies;
    }
    return null;
  }
  return null;
};

export const shouldBlockBareAgentSend = (
  card: SuggestedReplies | null | undefined,
  clarificationResponse?: ClarificationResponse | null
): boolean => {
  if (!isBusinessInteractionCard(card)) return false;
  return clarificationResponse?.clarificationId !== card.clarificationId;
};

export const buildSuggestedReplySubmission = (
  suggestedReplies: SuggestedReplies,
  selectedOptions: SuggestedReplyOption[],
  freeText = ''
): SuggestedReplySubmission | null => {
  const normalizedFreeText = textValue(freeText, 2000);
  const labels = selectedOptions.map(option => option.label).filter(Boolean);
  const value = normalizedFreeText || labels.join('、') || suggestedReplies.originalQuery || '';
  if (!value.trim()) return null;
  if (isBusinessSource(suggestedReplies.source)) {
    if (!suggestedReplies.clarificationId || !suggestedReplies.clarificationSchemaVersion) return null;
    if (!selectedOptions.length && !normalizedFreeText && !suggestedReplies.allowFreeText) return null;
    const optionIds = selectedOptions.map(option => option.optionId).filter(Boolean);
    const clarificationResponse: ClarificationResponse = {
      schemaVersion: suggestedReplies.clarificationSchemaVersion,
      clarificationId: suggestedReplies.clarificationId,
      ...(optionIds.length ? { optionIds } : {}),
      ...(normalizedFreeText ? { freeText: normalizedFreeText } : {})
    };
    return { value: value.trim(), displayText: value.trim(), clarificationResponse };
  }
  return { value: value.trim(), displayText: value.trim() };
};
