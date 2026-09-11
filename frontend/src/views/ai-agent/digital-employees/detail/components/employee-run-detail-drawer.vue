<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDrawer v-model="visible" title="运行结果" size="640px" destroy-on-close @closed="detail = null">
    <div v-loading="loading">
      <ElAlert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <template v-else-if="detail">
        <ElDescriptions :column="2" border size="small">
          <ElDescriptionsItem label="问题 / 任务" :span="2">{{ detail.run?.query || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="状态">{{ detail.run?.state || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="触发">{{ detail.run?.triggerSource || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="模式">{{ detail.run?.runMode || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="执行主体">{{ detail.run?.executionPrincipalId || '-' }}</ElDescriptionsItem>
        </ElDescriptions>

        <section class="block">
          <header>最终回答</header>
          <pre v-if="detail.finalAnswer" class="answer">{{ detail.finalAnswer }}</pre>
          <ElEmpty v-else description="已完成（无产物）" :image-size="64" />
        </section>

        <section class="block">
          <header>本次结果评价</header>
          <div class="feedback-row">
            <ElButton :type="feedbackRating === 'UP' ? 'success' : 'default'" @click="submitFeedback('UP')">
              有用
            </ElButton>
            <ElButton :type="feedbackRating === 'DOWN' ? 'danger' : 'default'" @click="submitFeedback('DOWN')">
              不准
            </ElButton>
          </div>
          <ElInput
            v-model="feedbackComment"
            class="feedback-comment"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            placeholder="可选评语；点「不准」时评语会在导入评估时作为期望输出"
          />
        </section>

        <RuntimeStructuredArtifacts :artifacts="detail.artifacts" />

        <section v-if="rawArtifacts.length" class="block">
          <header>其它产物（{{ rawArtifacts.length }}）</header>
          <div v-for="item in rawArtifacts" :key="String(item.id)" class="artifact">
            <div class="artifact-meta">
              {{ item.schemaVersion || '-' }} · {{ item.stepKey || '-' }}
              <span v-if="item.sensitivity === 'SENSITIVE'">（敏感，已隐藏）</span>
            </div>
            <pre v-if="item.data" class="answer">{{ item.data }}</pre>
          </div>
        </section>

        <section v-if="(detail.steps || []).length" class="block">
          <header>步骤</header>
          <ElTimeline>
            <ElTimelineItem v-for="step in detail.steps" :key="String(step.id || step.stepKey)" :type="stepTone(step.state)">
              {{ step.stepName || step.stepKey }} · {{ step.state }}
              <div v-if="step.errorMessage" class="step-error">{{ step.errorMessage }}</div>
            </ElTimelineItem>
          </ElTimeline>
        </section>
      </template>
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage } from 'element-plus';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { EmployeeRunDetail } from '@/views/ai-agent/services/digitalEmployee';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import RuntimeStructuredArtifacts from '@/views/ai-agent/components/runtime-structured-artifacts.vue';

defineOptions({ name: 'EmployeeRunDetailDrawer' });

const visible = ref(false);
const loading = ref(false);
const loadError = ref('');
const detail = ref<EmployeeRunDetail | null>(null);
const employeeIdRef = ref('');
const runtimeRunIdRef = ref('');
const feedbackRating = ref('');
const feedbackComment = ref('');
const feedbackSaving = ref(false);

const STRUCTURED_SCHEMAS = new Set([
  'single-turn-answer/v1',
  'query-result/v1',
  'chart-candidate/v1',
  'analysis-report/v1'
]);

const rawArtifacts = computed(() =>
  (detail.value?.artifacts || []).filter(item => !STRUCTURED_SCHEMAS.has(item.schemaVersion || ''))
);

const stepTone = (state?: string): 'primary' | 'success' | 'danger' | 'warning' | 'info' => {
  if (state === 'SUCCEEDED') return 'success';
  if (state === 'FAILED') return 'danger';
  if (state === 'RUNNING') return 'primary';
  return 'info';
};

async function open(employeeId: string, runtimeRunId: string) {
  visible.value = true;
  loading.value = true;
  loadError.value = '';
  detail.value = null;
  employeeIdRef.value = employeeId;
  runtimeRunIdRef.value = runtimeRunId;
  feedbackRating.value = '';
  feedbackComment.value = '';
  try {
    detail.value = await digitalEmployeeService.fetchRunDetail(employeeId, runtimeRunId);
    feedbackRating.value = detail.value.feedback?.rating || '';
    feedbackComment.value = detail.value.feedback?.comment || '';
  } catch (error) {
    loadError.value = extractApiErrorMessage(error, '运行详情加载失败');
  } finally {
    loading.value = false;
  }
}

async function submitFeedback(rating: 'UP' | 'DOWN') {
  if (!employeeIdRef.value || !runtimeRunIdRef.value || feedbackSaving.value) return;
  feedbackSaving.value = true;
  try {
    const saved = await digitalEmployeeService.saveRunFeedback(employeeIdRef.value, runtimeRunIdRef.value, {
      rating,
      comment: feedbackComment.value || undefined
    });
    feedbackRating.value = saved.rating || rating;
    feedbackComment.value = saved.comment || feedbackComment.value;
    ElMessage.success(rating === 'UP' ? '已标记有用' : '已标记不准，导入评估时会带上评语');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '反馈提交失败'));
  } finally {
    feedbackSaving.value = false;
  }
}

defineExpose({ open });
</script>

<style scoped>
.block {
  margin-top: 16px;
}

.block header {
  margin-bottom: 8px;
  font-weight: 600;
}

.answer {
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  max-height: 280px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.6;
}

.artifact {
  margin-bottom: 10px;
}

.artifact-meta {
  margin-bottom: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.step-error {
  color: var(--el-color-danger);
  font-size: 12px;
}

.feedback-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.feedback-comment {
  width: 100%;
}
</style>
