<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <section class="run-panel">
    <div class="panel-toolbar">
      <span class="panel-hint">本工作空间内数字员工的运行与汇报；查看逐步骤时间线请进入运行时任务页</span>
      <div class="panel-filters">
        <ElSelect
          v-model="query.employeeBindingId"
          clearable
          filterable
          :disabled="Boolean(employeeLoadError)"
          :placeholder="employeeLoadError || '全部数字员工'"
          class="filter-item"
          @change="handleSearch"
        >
          <ElOption
            v-for="item in employeeOptions"
            :key="item.id"
            :label="item.employeeName || '未命名数字员工'"
            :value="String(item.id ?? '')"
          />
        </ElSelect>
        <ElSelect v-model="query.state" clearable placeholder="全部状态" class="filter-item" @change="handleSearch">
          <ElOption v-for="item in stateOptions" :key="item.value" :label="item.label" :value="item.value" />
        </ElSelect>
        <ElSelect v-model="query.runMode" clearable placeholder="全部模式" class="filter-item" @change="handleSearch">
          <ElOption v-for="item in RUN_MODE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </ElSelect>
        <ElInput
          v-model="query.keyword"
          clearable
          placeholder="任务关键字"
          class="filter-item"
          @keyup.enter="handleSearch"
        />
        <ElButton :loading="loading" @click="handleSearch">
          <ElIcon><Search /></ElIcon>
          查询
        </ElButton>
        <ElButton :loading="loading" @click="loadRuns">
          <ElIcon><Refresh /></ElIcon>
          刷新
        </ElButton>
      </div>
    </div>

    <ElAlert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="load-error-alert">
      <ElButton link type="primary" :loading="loading" @click="loadRuns">重试</ElButton>
    </ElAlert>

    <ElTable
      v-loading="loading"
      :data="runs"
      border
      stripe
      row-key="id"
      :empty-text="loadError ? '加载失败，数据未获取到' : '该工作空间暂无运行记录'"
    >
      <ElTableColumn label="任务 / 问题" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">{{ row.query || '未记录任务描述' }}</template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="110" align="center">
        <template #default="{ row }">
          <ElTag :type="runStateTagType(row.state)" effect="light" size="small">{{ runStateLabel(row.state) }}</ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="运行模式" width="120" align="center">
        <template #default="{ row }">{{ runModeLabel(row.runMode) }}</template>
      </ElTableColumn>
      <ElTableColumn label="触发来源" width="110" align="center">
        <template #default="{ row }">{{ triggerSourceLabel(row.triggerSource) }}</template>
      </ElTableColumn>
      <ElTableColumn label="开始 / 结束" width="180">
        <template #default="{ row }">
          <div>{{ formatRunDateTime(row.startedAt) }}</div>
          <span class="cell-muted">{{ formatRunDateTime(row.finishedAt) }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="耗时" width="96" align="right">
        <template #default="{ row }">{{ formatRunDuration(row.startedAt, row.finishedAt, nowMs) }}</template>
      </ElTableColumn>
      <ElTableColumn label="结果" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.errorMessage || resultHint(row.state) }}</template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="190" fixed="right" align="center">
        <template #default="{ row }">
          <ElButton link type="primary" :disabled="!runtimeRunsRouteAvailable" @click="goRuntimeRun(row.id)">
            查看时间线
          </ElButton>
          <ElButton v-if="canManageEvaluation" link type="primary" @click="openEvalDialog(row)">加入评估集</ElButton>
        </template>
      </ElTableColumn>
    </ElTable>

    <div class="run-pagination">
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

    <AddRunToEvalSuiteDialog
      v-model:visible="evalDialogVisible"
      :run-id="evalTargetRunId"
      :run-query="evalTargetRunQuery"
    />
  </section>
</template>

<script setup lang="ts">
/**
 * 工作空间「运行与汇报」面板。
 *
 * 红线（方案第十七章）：列表只呈现任务描述、状态、进度与结果，不展示运行/智能体/产物等内部 ID。
 * 运行 ID 仅作为跳转参数透传给运行时任务页，不落到可见文案上。
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Refresh, Search } from '@element-plus/icons-vue';
import { haveAuth } from '@/mixins/userAuth.js';
import agentWorkspaceService from '@/views/ai-agent/services/workspace';
import type { WorkspaceEmployee } from '@/views/ai-agent/services/workspace';
import {
  RUNTIME_RUN_STATE_LABELS,
  type RuntimeRunResp,
  type RuntimeRunState
} from '@/views/ai-agent/services/runtimeRun';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import {
  RUN_MODE_OPTIONS,
  formatRunDateTime,
  formatRunDuration,
  runModeLabel,
  runStateLabel,
  runStateTagType
} from '@/views/ai-agent/runtime-runs/runtime-run-display';
import AddRunToEvalSuiteDialog from '@/views/ai-agent/runtime-runs/components/add-to-eval-suite-dialog.vue';

defineOptions({ name: 'WorkspaceRunPanel' });

const props = defineProps<{ workspaceId: string }>();

/** 与后端 AgentEvaluationPermissionService 的 manage 档一致，导入用例需要该权限 */
const EVALUATION_MANAGE_PERMISSION = 'agent:evaluation:manage';

const TRIGGER_SOURCE_LABELS: Record<string, string> = {
  API: '接口调用',
  CHAT: '对话',
  IM: '即时通讯',
  SCHEDULE: '定时任务',
  EVENT: '事件触发',
  LEGACY_ORCHESTRATION: '历史编排'
};

const stateOptions = (Object.keys(RUNTIME_RUN_STATE_LABELS) as RuntimeRunState[]).map(value => ({
  value,
  label: RUNTIME_RUN_STATE_LABELS[value]
}));

const router = useRouter();

const canManageEvaluation = computed(() => haveAuth(EVALUATION_MANAGE_PERMISSION));
/** runtime-runs 页路由（动态菜单/静态兜底）未注册时禁用跳转，避免点了没反应 */
const runtimeRunsRouteAvailable = computed(() => router.hasRoute('ai-agent_runtime-runs'));

const loading = ref(false);
/** 列表加载失败原因；非空时表格顶部常驻错误态，与「确实没有运行记录」区分开 */
const loadError = ref('');
const runs = ref<RuntimeRunResp[]>([]);
const nowMs = ref(Date.now());
/** 员工下拉选项；加载失败时禁用筛选并在占位符上说明原因，不静默当成「没有员工」 */
const employeeOptions = ref<WorkspaceEmployee[]>([]);
const employeeLoadError = ref('');
const query = reactive<{
  employeeBindingId: string;
  state: RuntimeRunState | '';
  runMode: string;
  keyword: string;
}>({
  employeeBindingId: '',
  state: '',
  runMode: '',
  keyword: ''
});
const page = reactive({ current: 1, size: 10, total: 0 });

const evalDialogVisible = ref(false);
const evalTargetRunId = ref('');
const evalTargetRunQuery = ref('');

let tickTimer: ReturnType<typeof setInterval> | null = null;

onMounted(() => {
  loadEmployees();
  loadRuns();
  // 进行中运行的耗时列按秒刷新
  tickTimer = setInterval(() => {
    nowMs.value = Date.now();
  }, 1000);
});

onBeforeUnmount(() => {
  if (tickTimer !== null) {
    clearInterval(tickTimer);
    tickTimer = null;
  }
});

/** 员工列表只用于筛选下拉，失败不影响运行列表本身，因此单独记错误、不写进 loadError */
async function loadEmployees() {
  if (!props.workspaceId) return;
  employeeLoadError.value = '';
  try {
    employeeOptions.value = await agentWorkspaceService.listEmployees(props.workspaceId);
  } catch (error) {
    employeeOptions.value = [];
    employeeLoadError.value = extractApiErrorMessage(error, '数字员工列表加载失败');
    console.error('加载工作空间数字员工失败, workspaceId=%s:', props.workspaceId, error);
  }
}

async function loadRuns() {
  if (!props.workspaceId) return;
  loading.value = true;
  loadError.value = '';
  try {
    const response = await agentWorkspaceService.queryRunPage(props.workspaceId, {
      current: page.current,
      size: page.size,
      // 后端 Wraps 自动跳空：不选员工时不下发该条件，列表保持整个工作空间口径
      employeeBindingId: query.employeeBindingId || undefined,
      state: query.state || undefined,
      runMode: query.runMode || undefined,
      keyword: query.keyword.trim() || undefined
    });
    runs.value = response.data;
    page.total = response.total;
    nowMs.value = Date.now();
  } catch (error) {
    loadError.value = extractApiErrorMessage(error, '加载运行记录失败');
    runs.value = [];
    page.total = 0;
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(loadError.value);
    }
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  page.current = 1;
  loadRuns();
}

function triggerSourceLabel(value?: string | null): string {
  return TRIGGER_SOURCE_LABELS[value || ''] || value || '-';
}

/** 终态无错误信息时给一句人话，避免整列留空让人以为数据缺失 */
function resultHint(state?: RuntimeRunState | string | null): string {
  if (state === 'SUCCEEDED') return '执行成功';
  if (state === 'CANCELLED') return '已取消';
  return '-';
}

function goRuntimeRun(runId: string) {
  router.push({ name: 'ai-agent_runtime-runs', query: { runId: String(runId) } });
}

function openEvalDialog(row: RuntimeRunResp) {
  evalTargetRunId.value = String(row.id);
  evalTargetRunQuery.value = row.query || '';
  evalDialogVisible.value = true;
}
</script>

<style scoped>
.run-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.panel-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.panel-filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-item {
  width: 150px;
}

.load-error-alert {
  align-items: center;
}

.cell-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.run-pagination {
  display: flex;
  justify-content: flex-end;
}
</style>
