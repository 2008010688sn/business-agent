<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDrawer v-model="visible" title="任务运行结果" size="640px" destroy-on-close @closed="detail = null">
    <div v-loading="loading">
      <ElAlert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <template v-else-if="detail">
        <ElDescriptions :column="2" border size="small">
          <ElDescriptionsItem label="状态">{{ detail.runStatus || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="触发">{{ detail.triggerType || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="开始">{{ formatTime(detail.startedTime) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="结束">{{ formatTime(detail.finishedTime) }}</ElDescriptionsItem>
          <ElDescriptionsItem v-if="detail.errorMessage" label="错误" :span="2">
            {{ detail.errorMessage }}
          </ElDescriptionsItem>
        </ElDescriptions>

        <section class="block">
          <header>最终回答</header>
          <pre v-if="detail.finalAnswer" class="answer">{{ detail.finalAnswer }}</pre>
          <ElEmpty v-else description="尚无结论（排队中或无产物）" :image-size="64" />
        </section>

        <RuntimeStructuredArtifacts :artifacts="detail.artifacts" />

        <section v-if="rawArtifacts.length" class="block">
          <header>其它产物</header>
          <div v-for="item in rawArtifacts" :key="String(item.id)" class="artifact">
            <div class="artifact-meta">{{ item.schemaVersion || '-' }} · {{ item.stepKey || '-' }}</div>
            <pre v-if="item.data" class="answer">{{ item.data }}</pre>
          </div>
        </section>

        <section v-if="(detail.deliveries || []).length" class="block">
          <header>投递</header>
          <div v-for="item in detail.deliveries" :key="String(item.id)">
            {{ item.channel }} · {{ item.deliveryStatus }} · {{ item.target || '-' }}
          </div>
        </section>
      </template>
    </div>
    <template v-if="canCancel" #footer>
      <ElButton :loading="cancelling" type="danger" @click="cancelOccupy">取消占用</ElButton>
    </template>
  </ElDrawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import dayjs from 'dayjs';
import { ElMessage, ElMessageBox } from 'element-plus';
import agentTaskService from '@/views/ai-agent/services/agentTask';
import type { AgentTaskId, AgentTaskRunDetail } from '@/views/ai-agent/services/agentTask';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import RuntimeStructuredArtifacts from '@/views/ai-agent/components/runtime-structured-artifacts.vue';

defineOptions({ name: 'TaskRunResultDrawer' });

const emit = defineEmits<{
  cancelled: [AgentTaskId];
}>();

const visible = ref(false);
const loading = ref(false);
const cancelling = ref(false);
const loadError = ref('');
const detail = ref<AgentTaskRunDetail | null>(null);
const openedRunId = ref<AgentTaskId | null>(null);
const canCancel = computed(
  () => detail.value?.runStatus === 'PENDING' || detail.value?.runStatus === 'RUNNING'
);
const STRUCTURED_SCHEMAS = new Set([
  'single-turn-answer/v1',
  'query-result/v1',
  'chart-candidate/v1',
  'analysis-report/v1'
]);
const rawArtifacts = computed(() =>
  (detail.value?.artifacts || []).filter(item => !STRUCTURED_SCHEMAS.has(item.schemaVersion || ''))
);

const formatTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

async function open(taskRunId: AgentTaskId) {
  visible.value = true;
  openedRunId.value = taskRunId;
  loading.value = true;
  loadError.value = '';
  detail.value = null;
  try {
    detail.value = await agentTaskService.fetchRunDetail(taskRunId);
  } catch (error) {
    loadError.value = extractApiErrorMessage(error, '任务结果加载失败');
  } finally {
    loading.value = false;
  }
}

async function cancelOccupy() {
  const taskRunId = openedRunId.value;
  if (taskRunId == null) {
    return;
  }
  try {
    await ElMessageBox.confirm('取消后将释放并发槽，后续触发可以重新执行。确认取消这条占用中的运行？', '取消占用', {
      type: 'warning',
      confirmButtonText: '取消占用',
      cancelButtonText: '返回'
    });
  } catch {
    return;
  }
  cancelling.value = true;
  try {
    await agentTaskService.cancelRun(taskRunId);
    ElMessage.success('已取消占用，并发槽已释放');
    emit('cancelled', taskRunId);
    visible.value = false;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '取消占用失败'));
  } finally {
    cancelling.value = false;
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
</style>
