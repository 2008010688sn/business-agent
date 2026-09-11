<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="runtime-tab">
    <ElAlert
      class="mb-12"
      type="info"
      :closable="false"
      title="运行履历"
      description="该员工的对话与任务运行。详情走员工门面，不要求 runtime-run 权限；点开即可看到结论和产物。"
    />

    <section class="cost-block mb-12">
      <div class="cost-head">
        <strong>本员工运行成本</strong>
        <span class="cell-muted">近 7 天流水，不计费</span>
        <ElButton link type="primary" :loading="budgetLoading" @click="loadBudget">刷新</ElButton>
      </div>
      <div class="cost-cards">
        <article>
          <span>工具调用</span>
          <strong>{{ budgetToolCalls }}</strong>
        </article>
        <article>
          <span>耗时</span>
          <strong>{{ budgetDuration }}</strong>
        </article>
        <article>
          <span>有流水的运行</span>
          <strong>{{ budgetRunCount }}</strong>
        </article>
      </div>
    </section>

    <div class="filter-row mb-12">
      <ElSelect v-model="queryForm.state" class="filter-select" @change="handleSearch">
        <ElOption label="全部状态" value="" />
        <ElOption
          v-for="(label, value) in RUN_STATE_LABELS"
          :key="value"
          :label="label"
          :value="value"
        />
      </ElSelect>
      <ElInput
        v-model="queryForm.keyword"
        placeholder="问题关键字"
        clearable
        class="filter-input"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <ElButton type="primary" :loading="loading" @click="handleSearch">查询</ElButton>
      <ElButton :loading="loading" @click="loadRuns">刷新</ElButton>
    </div>

    <ElTable v-loading="loading" :data="rows" border stripe empty-text="该员工暂无运行记录">
      <ElTableColumn label="Run ID" width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.id || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="110">
        <template #default="{ row }">
          <ElTag :type="runStateTagType(row.state)" effect="light" size="small">
            {{ runStateLabel(row.state) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="触发来源" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.triggerSource || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="运行模式" width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.runMode || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="Release" width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.releaseId || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="发起人" width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.initiatorUserName || row.initiatorUserId || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="执行主体" width="170" show-overflow-tooltip>
        <template #default="{ row }">
          <div>{{ row.subjectKind || row.ownerType || '-' }}</div>
          <span v-if="row.executionPrincipalId" class="cell-muted">{{ row.executionPrincipalId }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="问题/任务描述" min-width="240" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.query">{{ row.query }}</span>
          <span v-else class="cell-muted">-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="错误信息" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span v-if="row.errorCode || row.errorMessage" class="cell-error">
            {{ row.errorCode ? `[${row.errorCode}] ` : '' }}{{ row.errorMessage || '-' }}
          </span>
          <span v-else class="cell-muted">-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="开始时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.startedAt || row.createTime) }}</template>
      </ElTableColumn>
      <ElTableColumn label="结束时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.finishedAt) }}</template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="90" fixed="right" align="center">
        <template #default="{ row }">
          <ElButton link type="primary" :disabled="!row.id" @click="openRunDetail(row)">结果</ElButton>
        </template>
      </ElTableColumn>
    </ElTable>

    <EmployeeRunDetailDrawer ref="detailDrawerRef" />

    <div class="pagination-row">
      <ElPagination
        v-model:current-page="queryForm.current"
        v-model:page-size="queryForm.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="loadRuns"
        @size-change="handleSearch"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import dayjs from 'dayjs';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type {
  DigitalEmployee,
  EmployeeBudgetReport,
  EmployeeRuntimeRun
} from '@/views/ai-agent/services/digitalEmployee';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import EmployeeRunDetailDrawer from './employee-run-detail-drawer.vue';

defineOptions({ name: 'EmployeeRuntimeTab' });

const props = defineProps<{
  employee: DigitalEmployee;
}>();

/** 运行状态标签（对应后端 RuntimeRunState 枚举，本地字典避免依赖他域 services） */
const RUN_STATE_LABELS: Record<string, string> = {
  PENDING: '待启动',
  RUNNING: '运行中',
  WAITING_APPROVAL: '等待审批',
  WAITING_INPUT: '等待用户输入',
  CANCELLING: '取消中',
  SUCCEEDED: '成功',
  FAILED: '失败',
  CANCELLED: '已取消',
  TIMED_OUT: '超时'
};

const route = useRoute();
const detailDrawerRef = ref<{ open: (employeeId: string, runtimeRunId: string) => void } | null>(null);

const rows = ref<EmployeeRuntimeRun[]>([]);
const total = ref(0);
const loading = ref(false);
const budgetLoading = ref(false);
const budget = ref<EmployeeBudgetReport | null>(null);

const budgetAmount = (type: string) =>
  (budget.value?.totals || []).find(item => item.budgetType === type)?.amount;

const budgetRunCount = computed(() => {
  const counts = (budget.value?.totals || []).map(item => Number(item.runCount || 0));
  return counts.length ? Math.max(...counts) : '-';
});

const budgetToolCalls = computed(() => formatAmount(budgetAmount('TOOL_CALL')));
const budgetDuration = computed(() => formatDuration(budgetAmount('DURATION_MS')));

function formatAmount(value?: number | string) {
  if (value == null || value === '') return '-';
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric.toLocaleString() : String(value);
}

function formatDuration(value?: number | string) {
  if (value == null || value === '') return '-';
  const ms = Number(value);
  if (!Number.isFinite(ms)) return String(value);
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

const queryForm = reactive({
  current: 1,
  size: 10,
  state: '',
  keyword: ''
});

const runStateLabel = (state?: string) => RUN_STATE_LABELS[state || ''] || state || '-';

const runStateTagType = (state?: string): 'primary' | 'success' | 'info' | 'warning' | 'danger' => {
  switch (state) {
    case 'RUNNING':
      return 'primary';
    case 'SUCCEEDED':
      return 'success';
    case 'FAILED':
    case 'TIMED_OUT':
      return 'danger';
    case 'WAITING_APPROVAL':
    case 'WAITING_INPUT':
    case 'CANCELLING':
      return 'warning';
    default:
      return 'info';
  }
};

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

watch(
  () => props.employee.id,
  () => {
    queryForm.current = 1;
    queryForm.state = '';
    queryForm.keyword = '';
    rows.value = [];
    budget.value = null;
    loadRuns();
    loadBudget();
  }
);

/** 分页查询该员工履历（POST /digital-employees/{id}/runs/page） */
async function loadRuns() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  loading.value = true;
  try {
    const page = await digitalEmployeeService.fetchRunPage(employeeId, {
      current: queryForm.current,
      size: queryForm.size,
      state: queryForm.state || undefined,
      keyword: queryForm.keyword || undefined
    });
    rows.value = page.data;
    total.value = page.total;
  } catch (error) {
    console.error('运行记录查询失败, employeeId=%s:', employeeId, error);
    ElMessage.error(extractApiErrorMessage(error, '运行记录查询失败'));
  } finally {
    loading.value = false;
  }
}

async function loadBudget() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  budgetLoading.value = true;
  try {
    budget.value = await digitalEmployeeService.fetchBudgetReport(employeeId);
  } catch (error) {
    budget.value = { totals: [] };
    ElMessage.error(extractApiErrorMessage(error, '运行成本加载失败'));
  } finally {
    budgetLoading.value = false;
  }
}

function handleSearch() {
  queryForm.current = 1;
  loadRuns();
}

function openRunDetail(row: EmployeeRuntimeRun) {
  const employeeId = String(props.employee.id || '').trim();
  const runId = String(row.id || '').trim();
  if (!employeeId || !runId || !/^\d+$/.test(runId)) return;
  detailDrawerRef.value?.open(employeeId, runId);
}

function openFromRouteQuery() {
  const employeeId = String(props.employee.id || '').trim();
  const raw = route.query.runtimeRunId;
  const runId = String(Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '')).trim();
  if (!employeeId || !runId || !/^\d+$/.test(runId)) return;
  detailDrawerRef.value?.open(employeeId, runId);
}

onMounted(() => {
  loadRuns();
  loadBudget();
  openFromRouteQuery();
});

watch(
  () => route.query.runtimeRunId,
  () => openFromRouteQuery()
);
</script>

<style scoped>
.filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filter-select {
  width: 150px;
}

.filter-input {
  width: 240px;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.cell-muted {
  color: var(--el-text-color-secondary);
}

.cell-error {
  color: var(--el-color-danger);
}

.mb-12 {
  margin-bottom: 12px;
}

.cost-block {
  padding: 12px 14px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.cost-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.cost-head .cell-muted {
  flex: 1;
  font-size: 12px;
}

.cost-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.cost-cards article {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.cost-cards span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.cost-cards strong {
  font-size: 18px;
}
</style>
