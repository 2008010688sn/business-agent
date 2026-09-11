/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Runtime Run 时间线页展示辅助：状态标签、步骤/事件时间线构建与 payload 业务摘要提取。
 * 时间线条目复用 utils/runtimeProgressTimeline 的 RuntimeProgressTimelineItem 渲染模式。
 *
 * 红线（方案第十七章）：不展示内部 Agent/Skill/Artifact ID 与模型原始计划，
 * payload 摘要仅按业务字段白名单提取，内部 ID 类字段一律过滤。
 */

import dayjs from 'dayjs';
import type { RuntimeProgressTimelineItem } from '@/views/ai-agent/utils/runtimeProgressTimeline';
import {
  RUNTIME_EVENT_TYPE_LABELS,
  RUNTIME_RUN_STATE_LABELS,
  RUNTIME_STEP_STATE_LABELS,
  type RuntimeEventResp,
  type RuntimeEventType,
  type RuntimeRunState,
  type RuntimeStepResp,
  type RuntimeStepState
} from '@/views/ai-agent/services/runtimeRun';

export interface RuntimeRunEventRow {
  key: string;
  seq: number;
  timeText: string;
  label: string;
  stepKey: string;
  summary: string;
  /** 视觉基调：审批/中断类事件高亮 */
  tone: 'default' | 'approval' | 'interruption' | 'success' | 'danger';
}

/**
 * 运行主体类型选项，对应后端 RuntimeRunResp.ownerType：
 * DIGITAL_EMPLOYEE-数字员工 / CALLER-调用人 / PLATFORM-平台。
 */
export const RUN_OWNER_TYPE_OPTIONS = [
  { label: '数字员工', value: 'DIGITAL_EMPLOYEE' },
  { label: '调用人', value: 'CALLER' },
  { label: '平台', value: 'PLATFORM' }
] as const;

/** 运行主体类型文案；后端新增枚举未识别时原样展示。 */
export const runOwnerTypeLabel = (value?: string | null): string =>
  RUN_OWNER_TYPE_OPTIONS.find(item => item.value === value)?.label || value || '-';

export const RUN_MODE_OPTIONS = [
  { label: '对话', value: 'CHAT' },
  { label: '直连', value: 'DIRECT' },
  { label: 'Agent 循环', value: 'AGENT_LOOP' },
  { label: '编排', value: 'ORCHESTRATION' },
  { label: '流程', value: 'FLOW' }
];

export const runModeLabel = (value?: string | null): string =>
  RUN_MODE_OPTIONS.find(item => item.value === value)?.label || value || '-';

export const runStateLabel = (state?: RuntimeRunState | string | null): string =>
  (state && RUNTIME_RUN_STATE_LABELS[state as RuntimeRunState]) || state || '-';

export const runStateTagType = (
  state?: RuntimeRunState | string | null
): 'success' | 'danger' | 'warning' | 'info' | 'primary' => {
  switch (state) {
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
    case 'TIMED_OUT':
      return 'danger';
    case 'WAITING_APPROVAL':
    case 'WAITING_INPUT':
    case 'CANCELLING':
      return 'warning';
    case 'CANCELLED':
      return 'info';
    default:
      return 'primary';
  }
};

export const formatRunDateTime = (value?: string | null): string => {
  if (!value) {
    return '-';
  }
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : String(value);
};

export const formatRunDuration = (startedAt?: string | null, finishedAt?: string | null, nowMs?: number): string => {
  if (!startedAt) {
    return '-';
  }
  const start = dayjs(startedAt);
  if (!start.isValid()) {
    return '-';
  }
  const end = finishedAt ? dayjs(finishedAt) : dayjs(nowMs ?? Date.now());
  const durationMs = Math.max(0, end.diff(start));
  if (durationMs < 1000) {
    return `${durationMs}ms`;
  }
  if (durationMs < 60_000) {
    return `${(durationMs / 1000).toFixed(1)}s`;
  }
  const minutes = Math.floor(durationMs / 60_000);
  const seconds = Math.round((durationMs % 60_000) / 1000);
  return `${minutes}m${seconds}s`;
};

const STEP_STATUS_KEYS: Record<RuntimeStepState, string> = {
  PENDING: 'pending',
  READY: 'ready',
  RUNNING: 'running',
  WAITING: 'waiting',
  SUCCEEDED: 'success',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
  TIMED_OUT: 'failed',
  SKIPPED: 'skipped'
};

/**
 * 将步骤清单映射为时间线条目：步骤名称、状态、进度（尝试次数）与耗时。
 * 不输出 capabilityHandle / outputArtifactId 等内部句柄与 ID（红线）。
 */
export const buildRuntimeStepTimeline = (
  steps: RuntimeStepResp[] | null | undefined,
  nowMs: number
): RuntimeProgressTimelineItem[] => {
  return (steps ?? []).map(step => {
    const state = step.state;
    const attemptText =
      (step.attemptCount ?? 0) > 0 && (step.maxAttempts ?? 0) > 0
        ? `第 ${step.attemptCount}/${step.maxAttempts} 次`
        : '';
    const stateLabel = RUNTIME_STEP_STATE_LABELS[state] || String(state);
    return {
      key: `step-${step.stepKey}`,
      label: step.stepName || step.stepKey,
      status: STEP_STATUS_KEYS[state] || 'running',
      statusLabel: attemptText ? `${stateLabel} · ${attemptText}` : stateLabel,
      durationText: step.startedAt ? formatRunDuration(step.startedAt, step.finishedAt, nowMs) : '',
      depth: 0,
      kind: 'stage'
    };
  });
};

const APPROVAL_EVENT_TYPES = new Set<string>(['APPROVAL_REQUESTED', 'APPROVAL_DECIDED']);
const INTERRUPTION_EVENT_TYPES = new Set<string>(['RUN_CANCEL_REQUESTED', 'RUN_WAITING', 'STEP_WAITING']);
const SUCCESS_EVENT_TYPES = new Set<string>(['RUN_SUCCEEDED', 'STEP_SUCCEEDED']);
const DANGER_EVENT_TYPES = new Set<string>(['RUN_FAILED', 'RUN_TIMED_OUT', 'STEP_FAILED', 'STEP_TIMED_OUT']);

/** payload 业务摘要白名单：产物摘要与业务凭证类字段，仅展示原始值为字符串/数字/布尔的项 */
const PAYLOAD_SUMMARY_KEYS = [
  'summary',
  'message',
  'reason',
  'title',
  'stepName',
  'decision',
  'approver',
  'artifactSummary',
  'resultSummary',
  'businessKey',
  'businessRef',
  'voucher',
  'ticketNo',
  'orderNo',
  'errorMessage',
  'errorCode'
] as const;

/** 红线过滤：内部 ID / 计划类字段即便出现在 payload 中也不展示 */
const PAYLOAD_BLOCKED_KEY_PATTERN = /(agentid|skillid|artifactid|releaseid|workspaceid|plan|capability|prompt)/i;

const isDisplayablePrimitive = (value: unknown): value is string | number | boolean =>
  (typeof value === 'string' && value.trim().length > 0) || typeof value === 'number' || typeof value === 'boolean';

/**
 * 从事件 payload 中提取可展示的业务摘要（产物摘要、业务凭证、审批决定等）。
 * payload 契约对前端不透明，按白名单保守提取，最多 3 项。
 */
export const extractEventSummary = (payload: unknown): string => {
  if (payload === null || payload === undefined) {
    return '';
  }
  if (isDisplayablePrimitive(payload)) {
    return String(payload).slice(0, 120);
  }
  if (typeof payload !== 'object' || Array.isArray(payload)) {
    return '';
  }
  const record = payload as Record<string, unknown>;
  return PAYLOAD_SUMMARY_KEYS.filter(key => !PAYLOAD_BLOCKED_KEY_PATTERN.test(key))
    .map(key => ({ key, value: record[key] }))
    .filter(({ value }) => isDisplayablePrimitive(value))
    .slice(0, 3)
    .map(({ key, value }) => `${key}: ${String(value).slice(0, 80)}`)
    .join('；');
};

const eventTone = (eventType: string): RuntimeRunEventRow['tone'] => {
  if (APPROVAL_EVENT_TYPES.has(eventType)) {
    return 'approval';
  }
  if (INTERRUPTION_EVENT_TYPES.has(eventType)) {
    return 'interruption';
  }
  if (SUCCESS_EVENT_TYPES.has(eventType)) {
    return 'success';
  }
  if (DANGER_EVENT_TYPES.has(eventType)) {
    return 'danger';
  }
  return 'default';
};

/** 将持久事件序列映射为时间线行（按 seq 升序），审批与中断事件带高亮基调 */
export const buildRuntimeEventRows = (events: RuntimeEventResp[] | null | undefined): RuntimeRunEventRow[] => {
  return (events ?? [])
    .slice()
    .sort((left, right) => (left.seq ?? 0) - (right.seq ?? 0))
    .map(event => {
      const eventType = String(event.eventType);
      return {
        key: `event-${event.seq}-${eventType}`,
        seq: event.seq,
        timeText: formatRunDateTime(event.occurredAt),
        label: RUNTIME_EVENT_TYPE_LABELS[eventType as RuntimeEventType] || eventType,
        stepKey: event.stepKey || '',
        summary: extractEventSummary(event.payload),
        tone: eventTone(eventType)
      };
    });
};
