<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDrawer
    :model-value="visible"
    :title="detail?.definition?.taskName || '任务详情'"
    size="72%"
    destroy-on-close
    @update:model-value="handleVisibleChange"
  >
    <div v-loading="loading" class="task-detail-body">
      <ElDescriptions :column="3" border>
        <ElDescriptionsItem label="任务 ID">{{ detail?.definition?.id ?? '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="任务类型">{{ detail?.definition?.taskType || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          <ElTag :type="detail?.definition?.status === 'enabled' ? 'success' : 'info'" effect="light" size="small">
            {{ TASK_STATUS_LABELS[detail?.definition?.status || ''] || detail?.definition?.status || '-' }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="自治级别">
          {{ AUTONOMY_LEVEL_LABELS[detail?.definition?.defaultAutonomyLevel || ''] || '-' }}
          <ElTag v-if="detail?.definition?.highRiskWrite" type="danger" effect="light" size="small" class="ml-8">
            高风险写任务
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="所属数字员工">{{ detail?.definition?.digitalEmployeeId ?? '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="员工发布版本">{{ detail?.definition?.employeeReleaseId ?? '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="并发槽状态">
          <template v-if="detail?.definition?.activeRunId">
            <ElTooltip content="点击查看占用运行，可手动取消并释放并发槽" placement="top">
              <ElTag class="occupy-tag" type="warning" effect="light" size="small" @click="openOccupiedRun">占用中</ElTag>
            </ElTooltip>
            <ElButton class="ml-8" link type="primary" @click="openOccupiedRun">
              运行 {{ detail.definition.activeRunId }}
            </ElButton>
          </template>
          <ElTooltip v-else content="无活跃运行占用并发槽" placement="top">
            <ElTag type="info" effect="light" size="small">空闲</ElTag>
          </ElTooltip>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="执行身份">{{ detail?.definition?.servicePrincipal || '由所属数字员工 Principal 签发' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="最新版本">
          {{ detail?.latestVersion?.versionNo != null ? `v${detail.latestVersion.versionNo}` : '-' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="任务描述" :span="3">
          {{ detail?.definition?.taskDescription || '-' }}
        </ElDescriptionsItem>
      </ElDescriptions>

      <ElCollapse v-if="detail?.latestVersion" class="version-collapse">
        <ElCollapseItem title="最新版本快照（参数 / 提示词）" name="snapshots">
          <div class="snapshot-grid">
            <div>
              <h3>任务参数快照</h3>
              <pre class="snapshot-json">{{ prettyJson(detail.latestVersion.paramsSnapshot) }}</pre>
            </div>
            <div>
              <h3>提示词快照</h3>
              <pre class="snapshot-json">{{ prettyJson(detail.latestVersion.promptSnapshot) }}</pre>
            </div>
          </div>
        </ElCollapseItem>
      </ElCollapse>

      <ElTabs v-model="activeTab">
        <ElTabPane label="触发器管理" name="triggers">
          <TaskTriggerPanel
            :definition-id="definitionId"
            :can-manage="canManage"
            :can-trigger="canTrigger"
            @changed="handleTriggersChanged"
            @run-created="handleRunCreated"
          />
        </ElTabPane>
        <ElTabPane label="运行记录" name="runs">
          <TaskRunsPanel
            ref="runsPanelRef"
            :definition-id="definitionId"
            :triggers="detail?.triggers || []"
            :active-run-id="detail?.definition?.activeRunId"
            :can-trigger="canTrigger"
            @cancelled="handleSlotReleased"
          />
        </ElTabPane>
      </ElTabs>
    </div>
  </ElDrawer>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import agentTaskService from '@/views/ai-agent/services/agentTask';
import type { AgentTaskDefinition, AgentTaskDetail, AgentTaskId } from '@/views/ai-agent/services/agentTask';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { AUTONOMY_LEVEL_LABELS, TASK_STATUS_LABELS } from '../task-support';
import TaskTriggerPanel from './task-trigger-panel.vue';
import TaskRunsPanel from './task-runs-panel.vue';

defineOptions({ name: 'TaskDetailDrawer' });

const props = defineProps<{
  visible: boolean;
  task: AgentTaskDefinition | null;
  /** ai-agent:task:manage —— 任务/触发器增删改、轮换密钥 */
  canManage: boolean;
  /** ai-agent:task:trigger —— 手动发起运行（独立于 manage，最小授权） */
  canTrigger: boolean;
}>();

const emit = defineEmits<{
  'update:visible': [boolean];
  changed: [];
}>();

const loading = ref(false);
const detail = ref<AgentTaskDetail | null>(null);
const activeTab = ref('triggers');
const runsPanelRef = ref<{ loadRuns: () => void; openRun: (taskRunId: AgentTaskId) => void } | null>(null);
let requestSeq = 0;

const definitionId = computed<AgentTaskId | ''>(() => props.task?.id ?? '');

const prettyJson = (value?: Record<string, unknown>) => {
  if (!value || !Object.keys(value).length) return '-';
  return JSON.stringify(value, null, 2);
};

async function loadDetail() {
  if (!definitionId.value) return;
  const seq = ++requestSeq;
  loading.value = true;
  try {
    const response = await agentTaskService.fetchTaskDetail(definitionId.value);
    if (seq !== requestSeq) return;
    detail.value = response;
  } catch (error) {
    if (seq !== requestSeq) return;
    ElMessage.error(extractApiErrorMessage(error, '任务详情查询失败'));
  } finally {
    if (seq === requestSeq) {
      loading.value = false;
    }
  }
}

function handleTriggersChanged() {
  loadDetail();
  emit('changed');
}

function handleRunCreated() {
  runsPanelRef.value?.loadRuns();
  loadDetail();
}

function handleSlotReleased() {
  loadDetail();
  emit('changed');
}

async function openOccupiedRun() {
  const runId = detail.value?.definition?.activeRunId;
  if (runId == null) return;
  activeTab.value = 'runs';
  await nextTick();
  runsPanelRef.value?.openRun(runId);
}

function handleVisibleChange(value: boolean) {
  emit('update:visible', value);
}

watch(
  () => props.visible,
  visible => {
    if (visible) {
      activeTab.value = 'triggers';
      detail.value = null;
      loadDetail();
    }
  },
  { immediate: true }
);
</script>

<style scoped>
.task-detail-body {
  display: grid;
  gap: 16px;
}

.version-collapse :deep(.el-collapse-item__content) {
  padding-bottom: 12px;
}

.snapshot-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr 1fr;
}

.snapshot-grid h3 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
}

.snapshot-json {
  overflow: auto;
  max-height: 240px;
  margin: 0;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
  color: #111827;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.ml-8 {
  margin-left: 8px;
}

.occupy-tag {
  cursor: pointer;
}

@media (max-width: 920px) {
  .snapshot-grid {
    grid-template-columns: 1fr;
  }
}
</style>
