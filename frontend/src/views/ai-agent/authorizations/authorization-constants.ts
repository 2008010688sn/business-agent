/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

/**
 * Agent 授权中心展示常量（与后端 authorization 枚举 name() 序列化口径对齐）。
 *
 * 后端枚举未使用 @JsonValue，Jackson 默认按 name() 序列化：
 * 如 AuthorizationOwnerType.DATA_AGENT → "DATA_AGENT"。
 */

export type TagType = 'success' | 'warning' | 'info' | 'primary' | 'danger';

/** 权限码（与后端 @SaCheckPermission 一致，失败关闭：未授权时按钮不出现） */
export const AUTHORIZATION_QUERY_PERMISSION = 'agent:authorization:query';
export const AUTHORIZATION_MANAGE_PERMISSION = 'agent:authorization:manage';

/** 策略状态 */
export const POLICY_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  RETIRED: '已停用'
};

export const POLICY_STATUS_TAG_TYPES: Record<string, TagType> = {
  DRAFT: 'info',
  PUBLISHED: 'success',
  RETIRED: 'warning'
};

export const POLICY_STATUS_OPTIONS = [
  { label: '草稿', value: 'DRAFT' },
  { label: '已发布', value: 'PUBLISHED' },
  { label: '已停用', value: 'RETIRED' }
] as const;

/** 绑定/授权主体类型（owner 维度） */
export const OWNER_TYPE_LABELS: Record<string, string> = {
  DATA_AGENT: '数据智能体',
  DIGITAL_EMPLOYEE: '数字员工'
};

export const OWNER_TYPE_OPTIONS = [
  { label: '数据智能体', value: 'DATA_AGENT' },
  { label: '数字员工', value: 'DIGITAL_EMPLOYEE' }
] as const;

/** 生效环境 */
export const ENVIRONMENT_LABELS: Record<string, string> = {
  SANDBOX: '沙箱',
  PRODUCTION: '生产'
};

export const ENVIRONMENT_OPTIONS = [
  { label: '沙箱', value: 'SANDBOX' },
  { label: '生产', value: 'PRODUCTION' }
] as const;

/** 授权主体类型（subject 维度） */
export const GRANT_SUBJECT_TYPE_LABELS: Record<string, string> = {
  USER: '用户',
  TEAM: '团队',
  PERMISSION: '权限码',
  TENANT: '租户'
};

export const GRANT_SUBJECT_TYPE_OPTIONS = [
  { label: '用户', value: 'USER' },
  { label: '团队', value: 'TEAM' },
  { label: '权限码', value: 'PERMISSION' },
  { label: '租户', value: 'TENANT' }
] as const;

/** 授权权限 */
export const GRANT_PERMISSION_LABELS: Record<string, string> = {
  DISCOVER: '目录可见',
  USE: '可使用'
};

export const GRANT_PERMISSION_OPTIONS = [
  { label: '目录可见', value: 'DISCOVER' },
  { label: '可使用', value: 'USE' }
] as const;

/** 授权记录状态 */
export const GRANT_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  REVOKED: '已撤销'
};

export const GRANT_STATUS_TAG_TYPES: Record<string, TagType> = {
  ACTIVE: 'success',
  REVOKED: 'info'
};

/** 授权来源 */
export const GRANT_SOURCE_TYPE_LABELS: Record<string, string> = {
  MANUAL: '手工',
  LEGACY: '存量迁移',
  SYSTEM: '系统初始化'
};

/** 决策模拟：请求主体类别 */
export const SUBJECT_KIND_LABELS: Record<string, string> = {
  CALLER: '调用者',
  DIGITAL_EMPLOYEE: '数字员工'
};

export const SUBJECT_KIND_OPTIONS = [
  { label: '调用者', value: 'CALLER' },
  { label: '数字员工', value: 'DIGITAL_EMPLOYEE' }
] as const;

/** 决策模拟：动作（AuthorizationAction 枚举全集） */
export const ACTION_LABELS: Record<string, string> = {
  READ: '读取',
  WRITE: '写入',
  EXECUTE: '执行',
  START_UNATTENDED: '启动无人值守',
  DISCOVER: '发现',
  USE: '使用',
  READ_KNOWLEDGE: '读知识库',
  READ_MEMORY: '读记忆',
  WRITE_MEMORY: '写记忆'
};

export const ACTION_OPTIONS = [
  { label: '读取', value: 'READ' },
  { label: '写入', value: 'WRITE' },
  { label: '执行', value: 'EXECUTE' },
  { label: '启动无人值守', value: 'START_UNATTENDED' },
  { label: '发现', value: 'DISCOVER' },
  { label: '使用', value: 'USE' },
  { label: '读知识库', value: 'READ_KNOWLEDGE' },
  { label: '读记忆', value: 'READ_MEMORY' },
  { label: '写记忆', value: 'WRITE_MEMORY' }
] as const;

/** PDP 决策原因码（v1 冻结契约 DecisionReasonCode） */
export const REASON_CODE_LABELS: Record<string, string> = {
  POLICY_ALLOWED: '策略规则显式允许',
  POLICY_DENIED: '策略规则显式拒绝或默认拒绝',
  MISSING_POLICY: '未绑定策略，默认拒绝',
  CAPABILITY_ALLOWED: '纯模型类动作放行',
  IAM_UNAVAILABLE_ALLOWED: 'IAM 不可用降级放行',
  IAM_UNAVAILABLE_DENIED: 'IAM 不可用被拒绝',
  CAPABILITY_VERSION_MISMATCH: '能力版本不一致',
  RELEASE_SNAPSHOT_MISMATCH: 'Release 快照不一致',
  AUTO_TOKEN_FORBIDDEN: '命中 X-Auto-Token 硬基线'
};

/** ENFORCE 门禁结论码（EnforceGateDecision） */
export const ENFORCE_GATE_DECISION_LABELS: Record<string, string> = {
  ADMITTED: '放行',
  REJECTED_GATE_DISABLED: '拒绝：门禁未启用',
  REJECTED_REPORT_MISSING: '拒绝：影子差异报告缺失',
  REJECTED_TENANT_MISSING: '拒绝：租户样本缺失',
  REJECTED_INSUFFICIENT_SAMPLES: '拒绝：可比样本不足',
  REJECTED_THRESHOLD_EXCEEDED: '拒绝：差异率超阈值'
};

export const ENFORCE_GATE_DECISION_TAG_TYPES: Record<string, TagType> = {
  ADMITTED: 'success',
  REJECTED_GATE_DISABLED: 'info',
  REJECTED_REPORT_MISSING: 'warning',
  REJECTED_TENANT_MISSING: 'warning',
  REJECTED_INSUFFICIENT_SAMPLES: 'warning',
  REJECTED_THRESHOLD_EXCEEDED: 'danger'
};

/** 比对状态 */
export const COMPARISON_STATUS_LABELS: Record<string, string> = {
  MATCHED: '判定一致',
  MISMATCHED: '判定不一致',
  ORIGINAL_ONLY: '现网结论缺失'
};

export const COMPARISON_STATUS_TAG_TYPES: Record<string, TagType> = {
  MATCHED: 'success',
  MISMATCHED: 'danger',
  ORIGINAL_ONLY: 'warning'
};
