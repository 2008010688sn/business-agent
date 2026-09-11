/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

export const DEFAULT_RUNTIME_ERROR_MESSAGE = '运行智能体失败，请稍后重试。';

export interface RuntimeErrorMetadata {
  errorCode?: string;
  messageKey?: string;
  retryable?: boolean;
  runtimeRequestId?: string;
  discardPartialOutput?: boolean;
}

export type RuntimeStreamError = Error & { metadata?: RuntimeErrorMetadata };

const MESSAGE_BY_KEY: Record<string, string> = {
  agent_runtime_timeout: '运行超时，请稍后重试或简化问题。',
  agent_runtime_authentication_failed: '模型认证失败，请检查 API Key 或权限配置。',
  agent_runtime_access_denied: '模型服务拒绝访问，请检查模型权限和服务账号。',
  agent_runtime_quota_exhausted: '模型配额不足，请检查配额或 API Key。',
  agent_runtime_rate_limited: '模型服务请求过于频繁，请稍后重试。',
  agent_runtime_upstream_not_found: '模型服务配置无效，请检查模型名称和服务地址。',
  agent_runtime_upstream_unavailable: '模型服务暂时不可用，请稍后重试。',
  agent_runtime_legal_restricted: '模型服务拒绝了本次请求，请检查服务区域和合规配置。',
  agent_runtime_tool_failed: '工具调用失败，请稍后重试或调整问题。',
  agent_runtime_model_config_invalid: '当前模型配置不可用，请检查模型配置。',
  MODEL_PROTOCOL_ERROR: '模型返回格式异常，本次未保存未完成内容，请稍后重试。',
  agent_runtime_session_busy: '当前会话已有运行中的请求，请等待完成后再继续提问。',
  CLARIFICATION_EXPIRED: '澄清已过期或已失效，请重新提问。',
  CLARIFICATION_CONSUMED: '澄清已被使用，请重新提问。'
};

const CLARIFICATION_LOCK_RELEASE_CODES = new Set(['CLARIFICATION_EXPIRED', 'CLARIFICATION_CONSUMED']);

export const isClarificationLockReleaseError = (error: Error): boolean => {
  const metadata = (error as RuntimeStreamError).metadata;
  const key = metadata?.errorCode || metadata?.messageKey;
  return typeof key === 'string' && CLARIFICATION_LOCK_RELEASE_CODES.has(key);
};

const asNonBlankString = (value: unknown): string | undefined => {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
};

export const normalizeRuntimeErrorMetadata = (value: unknown): RuntimeErrorMetadata | undefined => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return undefined;
  }
  const source = value as Record<string, unknown>;
  const metadata: RuntimeErrorMetadata = {
    errorCode: asNonBlankString(source.errorCode),
    messageKey: asNonBlankString(source.messageKey),
    retryable: typeof source.retryable === 'boolean' ? source.retryable : undefined,
    runtimeRequestId: asNonBlankString(source.runtimeRequestId),
    discardPartialOutput: typeof source.discardPartialOutput === 'boolean' ? source.discardPartialOutput : undefined
  };
  return Object.values(metadata).some(item => item !== undefined) ? metadata : undefined;
};

export const createRuntimeStreamError = (message?: string, metadata?: RuntimeErrorMetadata): RuntimeStreamError => {
  const error = new Error(asNonBlankString(message) || DEFAULT_RUNTIME_ERROR_MESSAGE) as RuntimeStreamError;
  if (metadata) {
    error.metadata = metadata;
  }
  return error;
};

export const parseRuntimeErrorEvent = (eventData: string): RuntimeStreamError => {
  try {
    const response = JSON.parse(eventData) as { text?: unknown; metadata?: unknown };
    if (!response || typeof response !== 'object' || Array.isArray(response)) {
      return createRuntimeStreamError();
    }
    return createRuntimeStreamError(asNonBlankString(response.text), normalizeRuntimeErrorMetadata(response.metadata));
  } catch {
    return createRuntimeStreamError();
  }
};

export const normalizeRuntimeErrorMessage = (error: Error): string => {
  const runtimeError = error as RuntimeStreamError;
  const messageKey = runtimeError.metadata?.messageKey || runtimeError.metadata?.errorCode;
  if (messageKey && CLARIFICATION_LOCK_RELEASE_CODES.has(messageKey) && MESSAGE_BY_KEY[messageKey]) {
    return MESSAGE_BY_KEY[messageKey];
  }
  if (
    error.message &&
    !['Stream connection failed', 'Stream ended without completion', 'Failed to parse server response'].includes(
      error.message
    )
  ) {
    return error.message;
  }
  if (messageKey && MESSAGE_BY_KEY[messageKey]) {
    return MESSAGE_BY_KEY[messageKey];
  }
  if (error.message === 'Stream connection failed') {
    return '流式请求失败，请稍后重试。';
  }
  if (error.message === 'Stream ended without completion') {
    return '服务连接已结束，但没有收到完成事件，请稍后重试。';
  }
  return DEFAULT_RUNTIME_ERROR_MESSAGE;
};

export const shouldDiscardPartialOutput = (error: Error): boolean => {
  return (error as RuntimeStreamError).metadata?.discardPartialOutput !== false;
};

export const buildPersistedRuntimeErrorMetadata = (error: Error, runtimeRequestId?: string) => {
  const metadata = (error as RuntimeStreamError).metadata;
  return {
    error: true,
    contentFormat: 'plain',
    errorCode: metadata?.errorCode,
    messageKey: metadata?.messageKey,
    retryable: metadata?.retryable,
    runtimeRequestId: metadata?.runtimeRequestId || runtimeRequestId,
    discardPartialOutput: metadata?.discardPartialOutput ?? true
  };
};
