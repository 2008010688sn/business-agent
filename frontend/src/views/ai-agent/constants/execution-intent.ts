/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

/** 评估运行执行意图（与后端 ExecutionIntentDict 对齐）。 */

export type ExecutionIntentTagType = 'success' | 'warning' | 'info' | 'primary' | 'danger';

export const EXECUTION_INTENT_LIVE = 'LIVE';

export const EXECUTION_INTENT_DRY_RUN = 'DRY_RUN';

export const EXECUTION_INTENT_LABELS: Record<string, string> = {
  [EXECUTION_INTENT_LIVE]: '在线执行',
  [EXECUTION_INTENT_DRY_RUN]: '离线干跑（DRY_RUN）'
};

export const EXECUTION_INTENT_TAG_TYPES: Record<string, ExecutionIntentTagType> = {
  [EXECUTION_INTENT_LIVE]: 'info',
  [EXECUTION_INTENT_DRY_RUN]: 'warning'
};

export const EXECUTION_INTENT_OPTIONS = [
  { label: '在线执行（LIVE）', value: EXECUTION_INTENT_LIVE },
  { label: '离线干跑（DRY_RUN，禁写、禁外部副作用）', value: EXECUTION_INTENT_DRY_RUN }
] as const;

/** DRY_RUN 的统一说明文案：离线评估不产生写副作用/外部副作用。 */
export const DRY_RUN_DESCRIPTION =
  '离线干跑（DRY_RUN）不产生写副作用/外部副作用：写能力与外部副作用调用会被拦截并记为违规，计入发布门禁。';

export const executionIntentLabel = (value?: string | null): string => {
  if (!value) return '-';
  return EXECUTION_INTENT_LABELS[value] || value;
};

export const executionIntentTagType = (value?: string | null): ExecutionIntentTagType => {
  return EXECUTION_INTENT_TAG_TYPES[value || ''] || 'info';
};
