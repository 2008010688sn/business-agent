/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

/** Agent 任务页共享常量与工具：枚举文案、cron 基础校验、签名辅助。 */

export type TagType = 'success' | 'warning' | 'info' | 'primary' | 'danger';

/**
 * 任务权限码，与后端 task/enums/TaskConstants.java 三档划分一致：
 * query（分页/详情/触发器列表/运行记录）、manage（任务与触发器增删改、轮换密钥）、
 * trigger（手动/API 发起运行，单独拆档以便外部对接账号最小授权）。
 */
export const TASK_QUERY_PERMISSION = 'ai-agent:task:query';
export const TASK_MANAGE_PERMISSION = 'ai-agent:task:manage';
export const TASK_TRIGGER_PERMISSION = 'ai-agent:task:trigger';

export const TRIGGER_TYPE_LABELS: Record<string, string> = {
  CHAT: '会话触发',
  SCHEDULE: '定时触发',
  EVENT: '事件触发',
  API: 'API触发',
  IM: 'IM触发'
};

export const TRIGGER_TYPE_TAG_TYPES: Record<string, TagType> = {
  CHAT: 'info',
  SCHEDULE: 'primary',
  EVENT: 'warning',
  API: 'success',
  IM: 'info'
};

export const TRIGGER_TYPE_OPTIONS: Array<{ label: string; value: string; unimplemented?: boolean }> = [
  { label: '定时触发（SCHEDULE）', value: 'SCHEDULE' },
  { label: '事件触发（EVENT）', value: 'EVENT' },
  { label: 'API触发（API）', value: 'API' },
  { label: '会话触发（CHAT，未开通）', value: 'CHAT', unimplemented: true },
  { label: 'IM触发（IM，未开通）', value: 'IM', unimplemented: true }
];

/** 控制台「立即执行一次」支持的触发类型（CHAT/IM 本轮未开通）。 */
export const CONSOLE_MANUAL_TRIGGER_TYPES = ['SCHEDULE', 'EVENT', 'API'] as const;

export const isConsoleManualTriggerType = (type?: string): boolean =>
  CONSOLE_MANUAL_TRIGGER_TYPES.some(item => item === type);

export const TRIGGER_TYPE_FILTER_OPTIONS = [
  { label: '定时触发（SCHEDULE）', value: 'SCHEDULE' },
  { label: '事件触发（EVENT）', value: 'EVENT' },
  { label: 'API触发（API）', value: 'API' }
] as const;

export const AUTONOMY_LEVEL_LABELS: Record<string, string> = {
  AUTONOMOUS: '全自动',
  ASSISTED: '需审批'
};

export const AUTONOMY_LEVEL_OPTIONS = [
  { label: '全自动（AUTONOMOUS，仅只读/低风险任务）', value: 'AUTONOMOUS' },
  { label: '需审批（ASSISTED，关键写动作人工审批）', value: 'ASSISTED' }
] as const;

export const RUN_STATUS_LABELS: Record<string, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败',
  SKIPPED: '已跳过',
  CANCELLED: '已取消'
};

export const RUN_STATUS_TAG_TYPES: Record<string, TagType> = {
  PENDING: 'info',
  RUNNING: 'primary',
  SUCCESS: 'success',
  FAILED: 'danger',
  SKIPPED: 'warning',
  CANCELLED: 'info'
};

export const RUN_STATUS_OPTIONS = [
  { label: '待执行', value: 'PENDING' },
  { label: '执行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' },
  { label: '已跳过', value: 'SKIPPED' },
  { label: '已取消', value: 'CANCELLED' }
] as const;

export const MISFIRE_POLICY_LABELS: Record<string, string> = {
  SKIP: '错过跳过',
  FIRE_ONCE: '补偿一次'
};

export const MISFIRE_POLICY_OPTIONS = [
  { label: '错过跳过（SKIP，从当前时间重算下一次）', value: 'SKIP' },
  { label: '补偿一次（FIRE_ONCE，补最早错过的一次）', value: 'FIRE_ONCE' }
] as const;

export const CONCURRENCY_POLICY_LABELS: Record<string, string> = {
  ALLOW: '允许并发',
  FORBID: '禁止并发'
};

export const CONCURRENCY_POLICY_OPTIONS = [
  { label: '禁止并发（FORBID，未结束时跳过并落 SKIPPED）', value: 'FORBID' },
  { label: '允许并发（ALLOW）', value: 'ALLOW' }
] as const;

/** 后端 SKIPPED 台账专用错误前缀（TaskConstants.REASON_CONCURRENT_SLOT_LOCKED）。 */
export const CONCURRENT_SLOT_LOCKED_PREFIX = '[CONCURRENT_SLOT_LOCKED]';

export const occupyingRunIdFromSkipError = (errorMessage?: string): string => {
  const matched = errorMessage?.match(/activeRunId=(\d+)/);
  return matched?.[1] ?? '';
};

export const isConcurrentSlotSkip = (row: { runStatus?: string; errorMessage?: string }): boolean =>
  row.runStatus === 'SKIPPED' && Boolean(row.errorMessage?.startsWith(CONCURRENT_SLOT_LOCKED_PREFIX));

/** 立即执行返回 SKIPPED 时的提示；非跳过返回 null。 */
export const concurrentSkipToast = (row: {
  runStatus?: string;
  errorMessage?: string;
  id?: string | number;
}): string | null => {
  if (row.runStatus !== 'SKIPPED') {
    return null;
  }
  if (!isConcurrentSlotSkip(row)) {
    return `本次已跳过${row.id != null ? `，运行 ${row.id}` : ''}`;
  }
  const occupyingId = occupyingRunIdFromSkipError(row.errorMessage);
  return occupyingId
    ? `并发槽被占用，本次已跳过。占用运行 ${occupyingId}，可查看并取消占用后再触发`
    : '并发槽被占用，本次已跳过。请查看占用中的运行并取消后再触发';
};

export const TASK_STATUS_LABELS: Record<string, string> = {
  enabled: '启用',
  disabled: '停用'
};

/** 常用 cron 模板（后端为 Spring 6 段 cron，首段为秒）。 */
export const CRON_TEMPLATES = [
  { label: '每分钟', value: '0 * * * * *' },
  { label: '每小时整点', value: '0 0 * * * *' },
  { label: '每天 09:00', value: '0 0 9 * * *' },
  { label: '每周一 09:00', value: '0 0 9 * * MON' },
  { label: '每月 1 日 00:00', value: '0 0 0 1 * *' }
] as const;

/** 常用 IANA 时区（选择器支持手动输入其他合法时区）。 */
export const TIMEZONE_OPTIONS = [
  'Asia/Shanghai',
  'Asia/Tokyo',
  'Asia/Singapore',
  'Asia/Kolkata',
  'Asia/Dubai',
  'Europe/London',
  'Europe/Paris',
  'America/New_York',
  'America/Los_Angeles',
  'UTC'
] as const;

/**
 * cron 基础校验：仅做五/六段格式检查（含 @hourly 等宏），语义合法性以后端
 * Spring CronExpression（6 段，首段为秒）校验为准。
 */
export const validateCronBasic = (cron: string): string => {
  const text = cron.trim();
  if (!text) {
    return 'cron 表达式不能为空';
  }
  if (text.startsWith('@')) {
    return '';
  }
  const segments = text.split(/\s+/);
  if (segments.length < 5 || segments.length > 6) {
    return `cron 表达式应为 5 或 6 段，当前 ${segments.length} 段`;
  }
  if (segments.length === 5) {
    return '后端按 Spring 6 段 cron（首段为秒）校验，5 段表达式会被拒绝，请补齐秒位';
  }
  return '';
};

/** 生成随机串（Idempotency-Key / X-Nonce）。 */
export const randomKey = (): string => {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
};

/**
 * 浏览器侧 HMAC-SHA256（小写 hex），用于控制台手动触发 API 任务时按后端协议签名：
 * 签名串 = timestamp + "\n" + nonce + "\n" + rawBody。
 * secret 仅在内存中参与本次计算，不落任何存储。
 */
export const hmacSha256Hex = async (secret: string, message: string): Promise<string> => {
  if (typeof crypto === 'undefined' || !crypto.subtle) {
    throw new Error('当前环境不支持 Web Crypto（需 HTTPS/localhost），无法本地计算签名');
  }
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey('raw', encoder.encode(secret), { name: 'HMAC', hash: 'SHA-256' }, false, [
    'sign'
  ]);
  const signature = await crypto.subtle.sign('HMAC', key, encoder.encode(message));
  return Array.from(new Uint8Array(signature))
    .map(byte => byte.toString(16).padStart(2, '0'))
    .join('');
};

/** 空值判断（null/undefined），用于兼容后端 Long 字段可能下发 null 的场景。 */
export const isNil = (value: unknown): value is null | undefined => value === null || value === undefined;

/** 摘要展示：超长文本截断加省略号。 */
export const truncateText = (value: string | undefined | null, max = 24): string => {
  const text = String(value ?? '').trim();
  if (!text) return '-';
  return text.length > max ? `${text.slice(0, max)}…` : text;
};
