<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div v-if="compare" class="gate-panel">
    <ElDescriptions :column="2" border>
      <ElDescriptionsItem label="基线运行">{{ compare.baselineRunId || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="沙箱运行">{{ compare.sandboxRunId || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="基线分">{{ formatScore(compare.baselineScore) }}</ElDescriptionsItem>
      <ElDescriptionsItem label="候选分">{{ formatScore(compare.candidateScore) }}</ElDescriptionsItem>
      <ElDescriptionsItem label="分数差">{{ formatSigned(compare.scoreDelta) }}</ElDescriptionsItem>
      <ElDescriptionsItem label="通过率差">{{ formatPercent(compare.passRateDelta) }}</ElDescriptionsItem>
      <ElDescriptionsItem label="p90 耗时变化">{{ formatPercent(compare.durationDeltaPct) }}</ElDescriptionsItem>
      <ElDescriptionsItem label="Token 变化">{{ formatPercent(compare.tokenDeltaPct) }}</ElDescriptionsItem>
      <ElDescriptionsItem label="执行意图" :span="2">
        <ElTag :type="intentTagType" effect="light" size="small">
          {{ executionIntentLabel(compare.sandboxExecutionIntent) }}
        </ElTag>
        <span v-if="isDryRun" class="cell-muted ml-8">{{ DRY_RUN_DESCRIPTION }}</span>
        <span v-else class="gate-danger-text ml-8">离线评估必须为 DRY_RUN，当前运行不符合，硬门禁不通过</span>
      </ElDescriptionsItem>
    </ElDescriptions>

    <section class="gate-section">
      <h2>硬门禁判定（任一不达标即禁止进入发布审批）</h2>
      <div class="gate-hard-grid">
        <div class="gate-hard-item" :class="isDryRun ? 'is-pass' : 'is-fail'">
          <span class="gate-hard-label">执行意图 = DRY_RUN</span>
          <ElTag :type="isDryRun ? 'success' : 'danger'" effect="dark" size="small">
            {{ isDryRun ? '满足' : `不满足（${compare.sandboxExecutionIntent || '缺失'}）` }}
          </ElTag>
        </div>
        <div class="gate-hard-item" :class="writeViolations === 0 ? 'is-pass' : 'is-fail'">
          <span class="gate-hard-label">写副作用违规 = 0</span>
          <ElTag :type="writeViolations === 0 ? 'success' : 'danger'" effect="dark" size="small">
            {{ writeViolations === 0 ? '0 次' : `${writeViolations} 次违规` }}
          </ElTag>
        </div>
        <div class="gate-hard-item" :class="isolationViolations === 0 ? 'is-pass' : 'is-fail'">
          <span class="gate-hard-label">权限/租户隔离违规 = 0</span>
          <ElTag :type="isolationViolations === 0 ? 'success' : 'danger'" effect="dark" size="small">
            {{ isolationViolations === 0 ? '0 次' : `${isolationViolations} 次违规` }}
          </ElTag>
        </div>
      </div>
    </section>

    <section class="gate-section">
      <h2>分片评估（按租户 / 岗位 / 业务类型标签）</h2>
      <ElAlert
        v-if="degradedShard"
        type="info"
        :closable="false"
        show-icon
        title="分片评估已降级"
        :description="degradedShard.reason || '评估用例未标注分片维度标签，本次按整体指标评估，未执行分片对比'"
      />
      <ElTable v-else :data="shardRows" border stripe size="small" empty-text="暂无分片数据">
        <ElTableColumn prop="shardKey" label="分片" min-width="160" show-overflow-tooltip />
        <ElTableColumn label="基线（样本 / 通过率）" min-width="150" align="right">
          <template #default="{ row }">
            {{ row.baselineTotal ?? 0 }} / {{ formatPercent(row.baselinePassRate) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="候选（样本 / 通过率）" min-width="150" align="right">
          <template #default="{ row }">
            {{ row.candidateTotal ?? 0 }} / {{ formatPercent(row.candidatePassRate) }}
          </template>
        </ElTableColumn>
        <ElTableColumn label="分片判定" width="120" align="center">
          <template #default="{ row }">
            <ElTag :type="row.regressed ? 'danger' : 'success'" effect="light" size="small">
              {{ row.regressed ? '回归' : '通过' }}
            </ElTag>
          </template>
        </ElTableColumn>
      </ElTable>
    </section>

    <section class="gate-section">
      <div class="gate-title-row">
        <h2>门禁结论</h2>
        <ElTag :type="compare.passed ? 'success' : 'danger'" effect="dark">
          {{ compare.passed ? '通过' : '未通过' }}
        </ElTag>
      </div>
      <ElAlert
        v-if="compare.passed"
        type="success"
        :closable="false"
        show-icon
        title="全部门禁项通过，可进入人工发布审批"
      />
      <ul v-else class="gate-reason-list">
        <li v-for="(reason, index) in compare.gateReasons || []" :key="index">
          <ElTag type="danger" effect="light" size="small">命中</ElTag>
          <span>{{ reason }}</span>
        </li>
      </ul>
    </section>
  </div>
  <ElEmpty v-else description="暂无对比数据" />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import type { OptCandidateCompare, OptShardResult } from '@/views/ai-agent/services/optimization';
import {
  DRY_RUN_DESCRIPTION,
  EXECUTION_INTENT_DRY_RUN,
  executionIntentLabel
} from '@/views/ai-agent/constants/execution-intent';

defineOptions({ name: 'CandidateGatePanel' });

const props = defineProps<{
  /** 候选对比与门禁结果（GET /candidates/{id}/compare） */
  compare: OptCandidateCompare | null;
}>();

const isDryRun = computed(() => props.compare?.sandboxExecutionIntent === EXECUTION_INTENT_DRY_RUN);
const intentTagType = computed(() => (isDryRun.value ? 'success' : 'danger'));
const writeViolations = computed(() => props.compare?.writeViolationCount ?? 0);
const isolationViolations = computed(() => props.compare?.isolationViolationCount ?? 0);

/** 用例无分片标签时后端仅返回一行 degraded 记录，如实提示降级而非假装分片完成。 */
const degradedShard = computed<OptShardResult | null>(() => {
  const rows = props.compare?.shardResults || [];
  return rows.find(row => row.degraded === true) || null;
});

const shardRows = computed<OptShardResult[]>(() => {
  return (props.compare?.shardResults || []).filter(row => row.degraded !== true);
});

function formatScore(value?: number | string | null) {
  if (value === undefined || value === null || value === '') return '-';
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric.toFixed(2) : '-';
}

function formatPercent(value?: number | string | null) {
  if (value === undefined || value === null || value === '') return '-';
  const numeric = Number(value);
  return Number.isFinite(numeric) ? `${numeric.toFixed(2)}%` : '-';
}

function formatSigned(value?: number | string | null) {
  if (value === undefined || value === null || value === '') return '-';
  const numeric = Number(value);
  if (!Number.isFinite(numeric)) return '-';
  return `${numeric >= 0 ? '+' : ''}${numeric.toFixed(2)}`;
}
</script>

<style scoped>
.gate-panel {
  display: grid;
  gap: 16px;
}

.gate-section {
  display: grid;
  gap: 10px;
}

.gate-section h2 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  letter-spacing: 0;
}

.gate-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.gate-hard-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.gate-hard-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
}

.gate-hard-item.is-fail {
  border-color: var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
}

.gate-hard-item.is-pass {
  border-color: var(--el-color-success-light-5);
  background: var(--el-color-success-light-9);
}

.gate-hard-label {
  color: #111827;
  font-size: 13px;
  font-weight: 600;
}

.gate-reason-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.gate-reason-list li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--el-color-danger-light-7);
  border-radius: 6px;
  background: var(--el-color-danger-light-9);
  color: #111827;
  font-size: 13px;
  line-height: 1.6;
}

.cell-muted {
  color: #64748b;
  font-size: 12px;
}

.gate-danger-text {
  color: var(--el-color-danger);
  font-size: 12px;
}

.ml-8 {
  margin-left: 8px;
}

@media (max-width: 920px) {
  .gate-hard-grid {
    grid-template-columns: 1fr;
  }
}
</style>
