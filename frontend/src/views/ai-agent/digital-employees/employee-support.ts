/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

/**
 * 数字员工页面共享常量：权限码、状态字典、Tag 颜色映射。
 * 权限码与后端 DigitalEmployeeController 三档 @SaCheckPermission 一致（失败关闭）。
 */

/** 只读：分页/详情/能力清单/发布分页/部署查询 */
export const EMPLOYEE_QUERY_PERMISSION = 'ai-agent:digital-employee:query';
/** 对话：POST /{id}/conversations 与 POST /{id}/conversations/stream */
export const EMPLOYEE_USE_PERMISSION = 'ai-agent:digital-employee:use';
/** 管理：创建/编辑/删除/启停/封存/能力绑定/部署激活回滚/发布生命周期 */
export const EMPLOYEE_MANAGE_PERMISSION = 'ai-agent:digital-employee:manage';

/** 员工状态标签（对应后端 DigitalEmployee.status：DRAFT/ENABLED/DISABLED/ARCHIVED） */
export const EMPLOYEE_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  ENABLED: '启用',
  DISABLED: '停用',
  ARCHIVED: '已封存'
};

/** 员工状态 Tag 颜色 */
export const EMPLOYEE_STATUS_TAG_TYPES: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'info',
  ENABLED: 'success',
  DISABLED: 'warning',
  ARCHIVED: 'danger'
};

/** Principal 开通状态标签（对应后端 principalStatus：PENDING/READY/FAILED/DISABLED） */
export const PRINCIPAL_STATUS_LABELS: Record<string, string> = {
  PENDING: '待开通',
  READY: '就绪',
  FAILED: '开通失败',
  DISABLED: '已停用'
};

/** Principal 开通状态 Tag 颜色 */
export const PRINCIPAL_STATUS_TAG_TYPES: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  PENDING: 'warning',
  READY: 'success',
  FAILED: 'danger',
  DISABLED: 'info'
};

/** 自治级别标签（后端默认 ASSISTED） */
export const AUTONOMY_LEVEL_OPTIONS = [
  { label: '需审批（ASSISTED）', value: 'ASSISTED' },
  { label: '全自动（AUTONOMOUS）', value: 'AUTONOMOUS' }
];

/** 发布状态标签（DRAFT → SEALED → PUBLISHED → RETIRED） */
export const EMPLOYEE_RELEASE_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  SEALED: '已封版',
  PUBLISHED: '已发布',
  RETIRED: '已退役'
};

export const EMPLOYEE_RELEASE_STATUS_TAG_TYPES: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'info',
  SEALED: 'warning',
  PUBLISHED: 'success',
  RETIRED: 'danger'
};

/** 部署环境选项（后端 DeploymentEnvironmentDict：SANDBOX/PRODUCTION） */
export const DEPLOYMENT_ENVIRONMENT_OPTIONS = [
  { label: '沙箱（SANDBOX）', value: 'SANDBOX' },
  { label: '生产（PRODUCTION）', value: 'PRODUCTION' }
];

export const DEPLOYMENT_ENVIRONMENT_LABELS: Record<string, string> = {
  SANDBOX: '沙箱',
  PRODUCTION: '生产'
};

/** 部署状态标签（INACTIVE/ACTIVE/ROLLING_BACK） */
export const DEPLOYMENT_STATUS_LABELS: Record<string, string> = {
  INACTIVE: '未激活',
  ACTIVE: '活跃',
  ROLLING_BACK: '回滚中'
};

export const DEPLOYMENT_STATUS_TAG_TYPES: Record<string, 'info' | 'success' | 'warning'> = {
  INACTIVE: 'info',
  ACTIVE: 'success',
  ROLLING_BACK: 'warning'
};

/** 对话执行模式说明（Facade 语义：rollout 未开启走 MODEL_ONLY，开启且 Principal READY 走 PRINCIPAL） */
export const EXECUTION_MODE_LABELS: Record<string, string> = {
  MODEL_ONLY: '纯模型',
  PRINCIPAL: '员工身份'
};

/** 技能执行模式（能力清单展示；方案中的 authMode 对应草稿绑定的 executionMode） */
export const SKILL_EXECUTION_MODE_LABELS: Record<string, string> = {
  KNOWLEDGE: '知识问答',
  DETERMINISTIC: '确定性',
  REACT: 'ReAct',
  FLOW: '流程'
};

/** 记忆范围（员工记忆 Tab；WORKSPACE 语义为数字员工共享记忆） */
export const MEMORY_SCOPE_OPTIONS = [
  { label: '员工共享', value: 'WORKSPACE' },
  { label: '员工与用户', value: 'EMPLOYEE_USER' },
  { label: '会话', value: 'SESSION' },
  { label: '任务情景', value: 'EPISODIC' }
];
