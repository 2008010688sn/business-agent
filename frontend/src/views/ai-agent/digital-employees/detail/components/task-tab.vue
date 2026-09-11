<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="task-tab">
    <ElAlert
      v-if="recommendedTask"
      class="mb-12"
      type="success"
      :closable="false"
      :title="`岗位模板建议：${recommendedTask.taskName}`"
    >
      <template #default>
        {{ recommendedTask.taskDescription }}
        {{ recommendedTask.triggerHint }}
        <div class="mt-8">
          <ElButton type="primary" size="small" @click="goCreateRecommendedTask">按模板创建任务</ElButton>
        </div>
      </template>
    </ElAlert>

    <ElAlert
      class="mb-12"
      type="info"
      :closable="false"
      title="本员工任务（定时 / 事件 / API 等触发）"
    >
      <template #default>
        任务中心维护跨员工的任务定义与多种触发器，不只是定时。本页只读本员工；创建/编辑请去任务中心并会预填员工。
        停用员工后新调度/事件不再拉起；进行中的 Run 去运行记录取消。
      </template>
    </ElAlert>

    <div class="filter-row mb-12">
      <ElInput
        v-model="queryForm.keyword"
        placeholder="任务名/描述关键字"
        clearable
        class="filter-input"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <ElSelect v-model="queryForm.status" class="filter-select" @change="handleSearch">
        <ElOption label="全部状态" value="" />
        <ElOption label="启用" value="enabled" />
        <ElOption label="停用" value="disabled" />
      </ElSelect>
      <ElSelect v-model="queryForm.triggerType" class="filter-select" @change="handleSearch">
        <ElOption label="全部触发" value="" />
        <ElOption v-for="item in TRIGGER_TYPE_FILTER_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
      </ElSelect>
      <ElButton type="primary" :loading="loading" @click="handleSearch">查询</ElButton>
      <ElButton :loading="loading" @click="loadTasks">刷新</ElButton>
      <ElButton type="primary" plain @click="goCreateTask">新建任务</ElButton>
      <ElButton link type="primary" @click="goTaskCenter">前往任务中心</ElButton>
    </div>

    <ElTable v-loading="loading" :data="rows" border stripe empty-text="该员工暂无任务定义">
      <ElTableColumn label="任务名" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="cell-strong">{{ row.taskName || '-' }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="类型" width="110" show-overflow-tooltip>
        <template #default="{ row }">{{ row.taskType || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="触发类型" min-width="180">
        <template #default="{ row }">
          <template v-if="triggerTypesOf(row).length">
            <ElTag
              v-for="type in triggerTypesOf(row)"
              :key="type"
              size="small"
              effect="light"
              class="mr-4"
              :type="TRIGGER_TYPE_TAG_TYPES[type] || 'info'"
            >
              {{ TRIGGER_TYPE_LABELS[type] || type }}
            </ElTag>
          </template>
          <span v-else class="cell-muted">-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="90">
        <template #default="{ row }">
          <ElTag :type="row.status === 'enabled' ? 'success' : 'info'" effect="light" size="small">
            {{ row.status === 'enabled' ? '启用' : row.status === 'disabled' ? '停用' : row.status || '-' }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="自治级别" width="100">
        <template #default="{ row }">
          <ElTag
            :type="row.defaultAutonomyLevel === 'AUTONOMOUS' ? 'warning' : 'info'"
            effect="plain"
            size="small"
          >
            {{ row.defaultAutonomyLevel === 'AUTONOMOUS' ? '全自动' : '需审批' }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="高风险写" width="90" align="center">
        <template #default="{ row }">
          <ElTag v-if="row.highRiskWrite" type="danger" effect="light" size="small">高风险</ElTag>
          <span v-else class="cell-muted">-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="钉定 Release" width="170" show-overflow-tooltip>
        <template #default="{ row }">{{ row.employeeReleaseId || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="并发槽" width="150" show-overflow-tooltip>
        <template #default="{ row }">
          <template v-if="row.activeRunId">
            <ElTooltip content="点击查看占用运行，可手动取消并释放并发槽" placement="top">
              <ElTag class="occupy-tag" type="warning" effect="light" size="small" @click.stop="openOccupied(row)">
                占用中 run#{{ row.activeRunId }}
              </ElTag>
            </ElTooltip>
          </template>
          <span v-else class="cell-muted">空闲</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="创建时间" width="160">
        <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
      </ElTableColumn>
    </ElTable>

    <TaskRunResultDrawer ref="resultDrawerRef" @cancelled="loadTasks" />

    <div class="pagination-row">
      <ElPagination
        v-model:current-page="queryForm.current"
        v-model:page-size="queryForm.size"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="loadTasks"
        @size-change="handleSearch"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import dayjs from 'dayjs';
import { Http } from '@/service/request';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployee, EmployeeJobTemplateTaskHint } from '@/views/ai-agent/services/digitalEmployee';
import agentTaskService from '@/views/ai-agent/services/agentTask';
import type { AgentTaskId } from '@/views/ai-agent/services/agentTask';
import { extractApiErrorMessage, toPageResponse } from '@/views/ai-agent/services/common';
import type { ApiResponse, MybatisPage, XxCloudResult } from '@/views/ai-agent/services/common';
import { TRIGGER_TYPE_FILTER_OPTIONS, TRIGGER_TYPE_LABELS, TRIGGER_TYPE_TAG_TYPES } from '@/views/ai-agent/tasks/task-support';
import TaskRunResultDrawer from '@/views/ai-agent/tasks/components/task-run-result-drawer.vue';

defineOptions({ name: 'EmployeeTaskTab' });

const props = defineProps<{
  employee: DigitalEmployee;
}>();

/**
 * 任务定义行（本地最小类型，字段以后端 AgentTaskDefinition entity 为准）。
 * 不 import 他域 services/agentTask.ts（任务域页面并发改造中，避免编译耦合）。
 */
interface AgentTaskRow {
  id?: string;
  digitalEmployeeId?: string;
  employeeReleaseId?: string;
  taskName?: string;
  taskDescription?: string;
  taskType?: string;
  defaultAutonomyLevel?: string;
  highRiskWrite?: boolean;
  servicePrincipal?: string;
  /** 当前占用 FORBID 并发槽的运行 ID（可空镜像列，正确性由 agent_task_run 唯一索引保证） */
  activeRunId?: string;
  status?: string;
  createTime?: string;
  lastModifyTime?: string;
}

/** 任务中心路由（字符串路径导航，路由注册由 gen-route 统一生成） */
const TASK_CENTER_ROUTE_PATH = '/ai-agent/tasks';

/** 网关 ai 域前缀 + 后端 AgentTaskController @RequestMapping("/agent-tasks") */
const TASK_PAGE_URL = '/ai/agent-tasks/page';

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const router = useRouter();

const resultDrawerRef = ref<{ open: (taskRunId: AgentTaskId) => void } | null>(null);
const rows = ref<AgentTaskRow[]>([]);
const total = ref(0);
const loading = ref(false);
const recommendedTask = ref<EmployeeJobTemplateTaskHint | null>(null);

const jobTemplateCode = computed(() => props.employee.jobTemplateCode || '');

const queryForm = reactive({
  current: 1,
  size: 10,
  keyword: '',
  status: '',
  triggerType: ''
});
const triggerTypes = reactive(new Map<string, string[]>());

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

watch(
  () => props.employee.id,
  () => {
    queryForm.current = 1;
    queryForm.keyword = '';
    queryForm.status = '';
    queryForm.triggerType = '';
    rows.value = [];
    loadTasks();
  }
);

onMounted(() => {
  loadTasks();
  loadRecommendedTask();
});

watch(
  () => props.employee.jobTemplateCode,
  () => {
    loadRecommendedTask();
  }
);

async function loadRecommendedTask() {
  recommendedTask.value = null;
  const code = jobTemplateCode.value;
  if (!code) return;
  try {
    const templates = await digitalEmployeeService.listTemplates();
    recommendedTask.value = templates.find(item => item.templateCode === code)?.recommendedTask || null;
  } catch {
    recommendedTask.value = null;
  }
}

/** 分页查询该员工绑定的任务定义（POST /agent-tasks/page，digitalEmployeeId 过滤） */
async function loadTasks() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  loading.value = true;
  try {
    const response = await Http.post(TASK_PAGE_URL, {
      current: queryForm.current,
      size: queryForm.size,
      digitalEmployeeId: employeeId,
      keyword: queryForm.keyword || undefined,
      status: queryForm.status || undefined,
      triggerType: queryForm.triggerType || undefined
    });
    const page = toPageResponse(response as ServiceResponse<MybatisPage<AgentTaskRow>>);
    rows.value = page.data;
    total.value = page.total;
    loadTriggerTypes(page.data);
  } catch (error) {
    // 无 ai-agent:task:query 权限或服务异常时给出行内提示（axios 层已弹错误，避免重复打扰）
    console.error('任务定义查询失败, employeeId=%s:', employeeId, error);
    ElMessage.error(extractApiErrorMessage(error, '任务定义查询失败'));
  } finally {
    loading.value = false;
  }
}

function triggerTypesOf(row: AgentTaskRow): string[] {
  if (!row.id) return [];
  return triggerTypes.get(String(row.id)) || [];
}

function loadTriggerTypes(taskRows: AgentTaskRow[]) {
  taskRows.forEach(async row => {
    if (!row.id) return;
    const key = String(row.id);
    try {
      const triggers = await agentTaskService.listTriggers(row.id);
      triggerTypes.set(
        key,
        Array.from(new Set(triggers.map(item => String(item.triggerType || '')).filter(Boolean)))
      );
    } catch {
      triggerTypes.delete(key);
    }
  });
}

function handleSearch() {
  queryForm.current = 1;
  loadTasks();
}

function openOccupied(row: AgentTaskRow) {
  if (!row.activeRunId) return;
  resultDrawerRef.value?.open(row.activeRunId);
}

function goTaskCenter() {
  const employeeId = props.employee.id;
  if (!employeeId) {
    router.push(TASK_CENTER_ROUTE_PATH);
    return;
  }
  router.push({ path: TASK_CENTER_ROUTE_PATH, query: { digitalEmployeeId: employeeId } });
}

function goCreateTask() {
  const employeeId = props.employee.id;
  if (!employeeId) {
    router.push(TASK_CENTER_ROUTE_PATH);
    return;
  }
  router.push({ path: TASK_CENTER_ROUTE_PATH, query: { digitalEmployeeId: employeeId, create: '1' } });
}

function goCreateRecommendedTask() {
  const employeeId = props.employee.id;
  if (!employeeId) {
    router.push(TASK_CENTER_ROUTE_PATH);
    return;
  }
  router.push({
    path: TASK_CENTER_ROUTE_PATH,
    query: {
      digitalEmployeeId: employeeId,
      create: '1',
      templateCode: jobTemplateCode.value
    }
  });
}
</script>

<style scoped>
.filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filter-input {
  width: 240px;
}

.filter-select {
  width: 130px;
}

.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.cell-strong {
  font-weight: 600;
  color: #111827;
}

.mt-8 {
  margin-top: 8px;
}

.cell-muted {
  color: var(--el-text-color-secondary);
}

.mb-12 {
  margin-bottom: 12px;
}

.mr-4 {
  margin-right: 4px;
}

.occupy-tag {
  cursor: pointer;
}
</style>
