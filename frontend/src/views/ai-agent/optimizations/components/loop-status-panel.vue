<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <section class="loop-panel">
    <div class="loop-title-row">
      <h2>自进化 LOOP 状态</h2>
      <ElButton :loading="loading" size="small" @click="reload">
        <ElIcon><Refresh /></ElIcon>
        刷新
      </ElButton>
    </div>
    <div v-loading="loading" class="loop-body">
      <template v-if="loopStatus">
        <ElTimeline class="loop-timeline">
          <ElTimelineItem
            v-for="stage in loopStatus.stages || []"
            :key="stage.stage"
            :type="stageDotType(stage.status)"
            :hollow="stage.status === 'PENDING' || stage.status === 'NOT_IMPLEMENTED'"
          >
            <div class="loop-stage-head">
              <span class="loop-stage-name">{{ stageLabel(stage.stage) }}</span>
              <ElTag :type="stageTagType(stage.status)" effect="light" size="small">
                {{ stageStatusLabel(stage.status) }}
              </ElTag>
            </div>
            <div v-if="stageDetailText(stage)" class="loop-stage-detail">{{ stageDetailText(stage) }}</div>
            <div v-if="stageNote(stage)" class="loop-stage-note">{{ stageNote(stage) }}</div>
          </ElTimelineItem>
        </ElTimeline>
        <ElAlert
          v-if="loopStatus.nextAction"
          type="info"
          :closable="false"
          show-icon
          title="下一步人工操作"
          :description="loopStatus.nextAction"
        />
      </template>
      <ElEmpty v-else-if="!loading" description="LOOP 状态加载失败，请刷新重试" />
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import optimizationService from '@/views/ai-agent/services/optimization';
import type { OptId, OptLoopStage, OptLoopStatus } from '@/views/ai-agent/services/optimization';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';

defineOptions({ name: 'LoopStatusPanel' });

const props = defineProps<{
  /** 优化实验 ID（后端 Long 序列化为字符串，禁止 Number() 转换） */
  experimentId: OptId | '';
}>();

const loading = ref(false);
const loopStatus = ref<OptLoopStatus | null>(null);
const requestSeq = ref(0);

/** 与后端 AgentOptimizationService#getLoopStatus 的 stage 常量一一对应。 */
const STAGE_LABELS: Record<string, string> = {
  OBSERVE: '观察（Observe）',
  DIAGNOSE: '诊断（Diagnose）',
  GENERATE_CANDIDATE: '生成候选（Generate）',
  OFFLINE_EVAL_DRY_RUN: '离线评估（OfflineEval · DRY_RUN）',
  HUMAN_APPROVE: '人工审批（HumanApprove）',
  RELEASE_DRAFT: '发布草稿（历史阶段）',
  APPLY_AND_PUBLISH: '写入活体并发布',
  APPLY_STAGING: '激活沙箱（Apply SANDBOX）',
  APPLY_PROD: '提升生产（Apply PRODUCTION）',
  SHADOW: '影子流量（Shadow）',
  CANARY: '金丝雀（Canary）',
  MONITOR_ROLLBACK: '监控 / 回滚（Monitor / Rollback）'
};

/** NOT_IMPLEMENTED 为后端扩展点占位，如实展示为「未实现 / 暂不可用」，不伪装为已完成。 */
const STAGE_STATUS_LABELS: Record<string, string> = {
  DONE: '已完成',
  IN_PROGRESS: '进行中',
  PENDING: '待进行',
  NOT_IMPLEMENTED: '未实现 / 暂不可用',
  STANDBY: '待命',
  SKIPPED: '已跳过',
  ROLLED_BACK: '已回滚'
};

const STAGE_TAG_TYPES: Record<string, 'success' | 'warning' | 'info' | 'danger'> = {
  DONE: 'success',
  IN_PROGRESS: 'warning',
  PENDING: 'info',
  NOT_IMPLEMENTED: 'info',
  STANDBY: 'info',
  SKIPPED: 'info',
  ROLLED_BACK: 'danger'
};

function stageLabel(stage?: string) {
  return STAGE_LABELS[stage || ''] || stage || '-';
}

function stageStatusLabel(status?: string) {
  return STAGE_STATUS_LABELS[status || ''] || status || '-';
}

function stageTagType(status?: string) {
  return STAGE_TAG_TYPES[status || ''] || 'info';
}

function stageDotType(status?: string) {
  if (status === 'DONE') return 'success';
  if (status === 'IN_PROGRESS') return 'warning';
  if (status === 'ROLLED_BACK') return 'danger';
  return 'info';
}

/** 已知 detail 键的中文摘要；note 单独渲染。 */
function stageDetailText(stage: OptLoopStage) {
  const detail = stage.detail || {};
  const parts: string[] = [];
  if (detail.baselineRunId !== undefined && detail.baselineRunId !== null) {
    parts.push(`基线运行 ${String(detail.baselineRunId)}`);
  }
  if (detail.candidateCount !== undefined) {
    parts.push(`候选 ${String(detail.candidateCount)} 个`);
  }
  if (detail.gatePassed !== undefined) {
    parts.push(`门禁${detail.gatePassed === true ? '已通过' : '未通过'}`);
  }
  if (detail.approvalCount !== undefined) {
    parts.push(`审批记录 ${String(detail.approvalCount)} 条`);
  }
  if (detail.rollbackCount !== undefined) {
    parts.push(`回滚记录 ${String(detail.rollbackCount)} 条`);
  }
  return parts.join('，');
}

function stageNote(stage: OptLoopStage) {
  const note = stage.detail?.note;
  return typeof note === 'string' && note ? note : '';
}

async function reload() {
  if (!props.experimentId) {
    loopStatus.value = null;
    return;
  }
  loading.value = true;
  const seq = requestSeq.value + 1;
  requestSeq.value = seq;
  try {
    const response = await optimizationService.getLoopStatus(props.experimentId);
    if (seq !== requestSeq.value) return;
    loopStatus.value = response;
  } catch (error) {
    if (seq === requestSeq.value) {
      loopStatus.value = null;
      ElMessage.error(extractApiErrorMessage(error, 'LOOP 状态查询失败'));
    }
  } finally {
    if (seq === requestSeq.value) {
      loading.value = false;
    }
  }
}

watch(
  () => props.experimentId,
  () => {
    reload();
  }
);

onMounted(reload);

defineExpose({ reload });
</script>

<style scoped>
.loop-panel {
  display: grid;
  gap: 10px;
}

.loop-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.loop-title-row h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0;
}

.loop-body {
  display: grid;
  gap: 12px;
  min-height: 80px;
}

.loop-timeline {
  padding-left: 4px;
}

.loop-stage-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.loop-stage-name {
  color: #111827;
  font-size: 13px;
  font-weight: 600;
}

.loop-stage-detail {
  margin-top: 4px;
  color: #475569;
  font-size: 12px;
}

.loop-stage-note {
  margin-top: 2px;
  color: #94a3b8;
  font-size: 12px;
}
</style>
