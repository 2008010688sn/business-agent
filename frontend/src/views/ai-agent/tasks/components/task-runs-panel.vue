<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <section class="runs-panel">
    <div class="panel-toolbar">
      <div class="panel-filters">
        <ElSelect v-model="query.runStatus" clearable placeholder="运行状态" class="filter-item" @change="handleSearch">
          <ElOption v-for="item in RUN_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </ElSelect>
        <ElSelect
          v-model="query.triggerType"
          clearable
          placeholder="触发类型"
          class="filter-item"
          @change="handleSearch"
        >
          <ElOption v-for="item in TRIGGER_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </ElSelect>
      </div>
      <div class="panel-actions">
        <ElButton size="small" :loading="loading" @click="loadRuns">
          <ElIcon><Refresh /></ElIcon>
          刷新
        </ElButton>
        <ElTooltip :disabled="Boolean(preferredManualTrigger)" :content="manualTriggerHint" placement="top">
          <ElButton
            v-if="canTrigger"
            size="small"
            type="primary"
            :disabled="!preferredManualTrigger"
            :loading="triggering"
            @click="runOnce"
          >
            <ElIcon><VideoPlay /></ElIcon>
            立即执行一次
          </ElButton>
        </ElTooltip>
      </div>
    </div>

    <ElAlert
      v-if="activeRunId"
      class="occupy-alert"
      type="warning"
      show-icon
      :closable="false"
      title="当前有运行占用并发槽"
    >
      占用运行 {{ activeRunId }}（待执行/执行中）。手动触发会被跳过；该运行可能不在当前分页。
      <div class="occupy-actions">
        <ElButton size="small" type="primary" @click="openRun(activeRunId)">查看占用运行</ElButton>
        <ElButton v-if="canTrigger" size="small" type="danger" :loading="cancelling" @click="cancelOccupy(activeRunId)">
          取消占用
        </ElButton>
      </div>
    </ElAlert>

    <ElTable v-loading="loading" :data="runRows" border stripe row-key="id" empty-text="暂无运行记录">
      <ElTableColumn label="运行 ID" width="150">
        <template #default="{ row }">{{ row.id ?? '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="触发类型" width="100">
        <template #default="{ row }">
          <ElTag :type="triggerTagType(row.triggerType)" effect="light" size="small">
            {{ triggerTypeText(row.triggerType) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="90">
        <template #default="{ row }">
          <ElTag :type="runStatusTagType(row.runStatus)" effect="light" size="small">
            {{ runStatusText(row.runStatus) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="并发策略" width="100">
        <template #default="{ row }">
          <ElTag
            v-if="row.concurrencyPolicy"
            :type="row.concurrencyPolicy === 'FORBID' ? 'warning' : 'info'"
            effect="light"
            size="small"
          >
            {{ CONCURRENCY_POLICY_LABELS[row.concurrencyPolicy] || row.concurrencyPolicy }}
          </ElTag>
          <span v-else>-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="幂等键" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.idempotencyKey || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="计划/受理时刻" width="160">
        <template #default="{ row }">{{ formatDateTime(row.scheduledTime) }}</template>
      </ElTableColumn>
      <ElTableColumn label="开始" width="160">
        <template #default="{ row }">{{ formatDateTime(row.startedTime) }}</template>
      </ElTableColumn>
      <ElTableColumn label="结束" width="160">
        <template #default="{ row }">{{ formatDateTime(row.finishedTime) }}</template>
      </ElTableColumn>
      <ElTableColumn label="RuntimeRun" width="160">
        <template #default="{ row }">
          <template v-if="row.runtimeRunId">
            <ElLink v-if="runtimeRunsRouteAvailable" type="primary" @click="goRuntimeRun(row.runtimeRunId)">
              {{ row.runtimeRunId }}
            </ElLink>
            <span v-else>{{ row.runtimeRunId }}</span>
          </template>
          <span v-else>-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="错误信息 / 跳过原因" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <template v-if="isConcurrentSlotSkip(row)">
            <span>{{ concurrentSkipText(row) }}</span>
            <ElButton
              v-if="occupyingRunIdFromSkipError(row.errorMessage)"
              link
              type="warning"
              @click="openRun(occupyingRunIdFromSkipError(row.errorMessage))"
            >
              查看占用
            </ElButton>
          </template>
          <span v-else>{{ row.errorMessage || '-' }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="80" fixed="right" align="center">
        <template #default="{ row }">
          <ElButton link type="primary" :disabled="!row.id" @click="openResult(row)">结果</ElButton>
        </template>
      </ElTableColumn>
    </ElTable>

    <TaskRunResultDrawer ref="resultDrawerRef" @cancelled="handleCancelled" />

    <div class="runs-pagination">
      <ElPagination
        v-model:current-page="page.current"
        v-model:page-size="page.size"
        background
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50]"
        :total="page.total"
        @size-change="handleSearch"
        @current-change="loadRuns"
      />
    </div>

  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, VideoPlay } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import agentTaskService from '@/views/ai-agent/services/agentTask';
import type {
  AgentTaskId,
  AgentTaskRun,
  AgentTaskTrigger,
  TaskRunStatus,
  TaskTriggerType
} from '@/views/ai-agent/services/agentTask';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import TaskRunResultDrawer from './task-run-result-drawer.vue';
import {
  CONCURRENCY_POLICY_LABELS,
  RUN_STATUS_LABELS,
  RUN_STATUS_OPTIONS,
  RUN_STATUS_TAG_TYPES,
  TRIGGER_TYPE_LABELS,
  TRIGGER_TYPE_OPTIONS,
  TRIGGER_TYPE_TAG_TYPES,
  concurrentSkipToast,
  isConcurrentSlotSkip,
  isConsoleManualTriggerType,
  occupyingRunIdFromSkipError
} from '../task-support';

defineOptions({ name: 'TaskRunsPanel' });

const props = defineProps<{
  definitionId: AgentTaskId | '';
  triggers: AgentTaskTrigger[];
  /** 当前占用 FORBID 并发槽的运行 ID（任务定义镜像列） */
  activeRunId?: AgentTaskId | '';
  /** ai-agent:task:trigger —— 手动发起运行 */
  canTrigger: boolean;
}>();

const emit = defineEmits<{
  cancelled: [];
}>();

const router = useRouter();

const query = reactive<{ runStatus: TaskRunStatus | ''; triggerType: TaskTriggerType | '' }>({
  runStatus: '',
  triggerType: ''
});
const page = reactive({ current: 1, size: 10, total: 0 });
const runRows = ref<AgentTaskRun[]>([]);
const loading = ref(false);
const triggering = ref(false);
const cancelling = ref(false);
const resultDrawerRef = ref<{ open: (taskRunId: AgentTaskId) => void } | null>(null);

const preferredManualTrigger = computed(() => {
  const enabled = props.triggers.filter(
    item => isConsoleManualTriggerType(item.triggerType) && item.status === 'enabled' && item.id != null
  );
  return enabled.find(item => item.triggerType === 'SCHEDULE') || enabled[0] || null;
});

const manualTriggerHint = computed(() =>
  preferredManualTrigger.value ? '' : '请先创建并启用定时 / 事件 / API 触发器'
);

/**
 * runtime-runs 页路由（动态菜单/静态兜底）未注册时降级为纯文本。
 * 已核实 F1 页面（2026-08）：当前不消费任何路由 query 参数，详情抽屉仅支持行内点击打开；
 * 本侧沿用 runId 作为约定参数名，待 F1 支持「按 query 自动打开详情」后即自然生效。
 */
const runtimeRunsRouteAvailable = computed(() => router.hasRoute('ai-agent_runtime-runs'));

const triggerTypeText = (type?: string) => TRIGGER_TYPE_LABELS[type || ''] || type || '-';
const triggerTagType = (type?: string) => TRIGGER_TYPE_TAG_TYPES[type || ''] || 'info';
const runStatusText = (status?: string) => RUN_STATUS_LABELS[status || ''] || status || '-';
const runStatusTagType = (status?: string) => RUN_STATUS_TAG_TYPES[status || ''] || 'info';

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

/**
 * SKIPPED 台账行转可读文案；后端原文形如：
 * [CONCURRENT_SLOT_LOCKED] FORBID 并发槽被其他运行占用, 本次触发已跳过(activeRunId=...)
 */
const concurrentSkipText = (row: AgentTaskRun): string => {
  const occupyingId = occupyingRunIdFromSkipError(row.errorMessage);
  return occupyingId
    ? `FORBID 并发槽被其他运行占用，本次触发已跳过（活跃运行 ${occupyingId}）`
    : 'FORBID 并发槽被其他运行占用，本次触发已跳过';
};

async function loadRuns() {
  if (!props.definitionId) return;
  loading.value = true;
  try {
    const response = await agentTaskService.fetchRunPage(props.definitionId, {
      runStatus: query.runStatus,
      triggerType: query.triggerType,
      current: page.current,
      size: page.size
    });
    runRows.value = response.data;
    page.total = response.total;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '运行记录查询失败'));
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  page.current = 1;
  loadRuns();
}

function goRuntimeRun(runtimeRunId: AgentTaskId) {
  router.push({ name: 'ai-agent_runtime-runs', query: { runId: String(runtimeRunId) } });
}

function openResult(row: AgentTaskRun) {
  if (row.id == null) return;
  openRun(row.id);
}

function openRun(taskRunId: AgentTaskId | '' | undefined) {
  if (taskRunId == null || taskRunId === '') return;
  resultDrawerRef.value?.open(taskRunId);
}

async function cancelOccupy(taskRunId: AgentTaskId | '' | undefined) {
  if (taskRunId == null || taskRunId === '') return;
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
    await handleCancelled();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '取消占用失败'));
  } finally {
    cancelling.value = false;
  }
}

async function handleCancelled() {
  await loadRuns();
  emit('cancelled');
}

async function runOnce() {
  const trigger = preferredManualTrigger.value;
  if (!props.definitionId || !trigger?.id) return;
  try {
    await ElMessageBox.confirm(
      '立即按任务描述执行一次？不会改定时的下次执行时间。',
      '立即执行一次',
      { type: 'warning', confirmButtonText: '执行', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  triggering.value = true;
  try {
    const run = await agentTaskService.manualTrigger(props.definitionId, trigger.id);
    const skipped = concurrentSkipToast(run);
    if (skipped) {
      ElMessage.warning(skipped);
    } else {
      ElMessage.success(`已受理，运行 ID ${run.id ?? '-'}`);
    }
    await loadRuns();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '立即执行失败'));
  } finally {
    triggering.value = false;
  }
}

watch(
  () => props.definitionId,
  definitionId => {
    runRows.value = [];
    page.current = 1;
    page.total = 0;
    if (definitionId) {
      loadRuns();
    }
  },
  { immediate: true }
);

defineExpose({ loadRuns, openRun });
</script>

<style scoped>
.runs-panel {
  display: grid;
  gap: 10px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.panel-filters {
  display: flex;
  gap: 8px;
}

.filter-item {
  width: 150px;
}

.panel-actions {
  display: flex;
  gap: 8px;
}

.occupy-alert {
  align-items: flex-start;
}

.occupy-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.runs-pagination {
  display: flex;
  justify-content: flex-end;
}
</style>
