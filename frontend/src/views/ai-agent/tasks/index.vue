<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <BaseLayout class="task-shell">
    <main class="task-page">
      <ElCard>
        <section class="task-header">
          <div>
            <h1>任务中心</h1>
            <p class="task-subtitle">
              跨员工维护任务定义与多种触发器（定时 / 事件 / API）。会话与 IM 触发枚举已预留，本轮未开通。
            </p>
          </div>
          <div class="task-header-actions">
            <ElButton v-if="canQueryTask" :loading="loading" @click="loadTasks">
              <ElIcon><Refresh /></ElIcon>
              刷新
            </ElButton>
            <ElButton v-if="canManageTask" type="primary" @click="openCreateDrawer">
              <ElIcon><Plus /></ElIcon>
              新建任务
            </ElButton>
          </div>
        </section>
      </ElCard>

      <ElCard class="task-list-card mt-8px">
        <section class="filter-panel">
          <ElForm label-width="70px">
            <ElRow :gutter="24">
              <ElCol :span="6">
                <ElFormItem label="关键字">
                  <ElInput v-model="query.keyword" clearable placeholder="任务名/描述" @keyup.enter="handleSearch" />
                </ElFormItem>
              </ElCol>
              <ElCol :span="6">
                <ElFormItem label="数字员工">
                  <ElSelect
                    :key="employeeSelectEpoch"
                    v-model="query.digitalEmployeeId"
                    clearable
                    filterable
                    placeholder="全部员工"
                    :loading="employeeOptionsLoading"
                    @change="handleSearch"
                  >
                    <template #label="{ value }">
                      {{ selectedEmployeeLabel(value) }}
                    </template>
                    <ElOption
                      v-for="item in employeeOptions"
                      :key="String(item.id)"
                      :label="employeeOptionLabel(item)"
                      :value="String(item.id)"
                    />
                  </ElSelect>
                </ElFormItem>
              </ElCol>
              <ElCol :span="4">
                <ElFormItem label="任务类型">
                  <ElInput v-model="query.taskType" clearable placeholder="如 REPORT" @keyup.enter="handleSearch" />
                </ElFormItem>
              </ElCol>
              <ElCol :span="4">
                <ElFormItem label="状态">
                  <ElSelect v-model="query.status" clearable placeholder="全部" @change="handleSearch">
                    <ElOption label="启用（enabled）" value="enabled" />
                    <ElOption label="停用（disabled）" value="disabled" />
                  </ElSelect>
                </ElFormItem>
              </ElCol>
              <ElCol :span="4">
                <ElFormItem label="触发类型">
                  <ElSelect v-model="query.triggerType" clearable placeholder="全部" @change="handleSearch">
                    <ElOption
                      v-for="item in TRIGGER_TYPE_FILTER_OPTIONS"
                      :key="item.value"
                      :label="item.label"
                      :value="item.value"
                    />
                  </ElSelect>
                </ElFormItem>
              </ElCol>
              <ElButton v-if="canQueryTask" type="primary" :loading="loading" @click="handleSearch">
                <ElIcon><Search /></ElIcon>
                查询
              </ElButton>
              <ElButton v-if="canQueryTask" @click="handleReset">
                <ElIcon><Refresh /></ElIcon>
                重置
              </ElButton>
            </ElRow>
          </ElForm>
        </section>

        <div class="task-table-wrap">
          <ElTable
            v-loading="loading"
            :data="taskRows"
            border
            stripe
            row-key="id"
            height="100%"
            empty-text="暂无任务定义"
          >
            <ElTableColumn label="任务" min-width="220" fixed="left" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="cell-strong">{{ row.taskName || '-' }}</div>
                <span class="cell-muted">ID: {{ row.id ?? '-' }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="所属数字员工" width="170">
              <template #default="{ row }">
                <div class="cell-strong">{{ employeeLabelById(row.digitalEmployeeId) }}</div>
                <span class="cell-muted">ID: {{ row.digitalEmployeeId ?? '-' }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="类型" width="110">
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
                    class="ml-4"
                    :type="TRIGGER_TYPE_TAG_TYPES[type] || 'info'"
                  >
                    {{ TRIGGER_TYPE_LABELS[type] || type }}
                  </ElTag>
                </template>
                <span v-else class="cell-muted">-</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="自治级别" width="150">
              <template #default="{ row }">
                <ElTag
                  :type="row.defaultAutonomyLevel === 'AUTONOMOUS' ? 'success' : 'warning'"
                  effect="light"
                  size="small"
                >
                  {{ AUTONOMY_LEVEL_LABELS[row.defaultAutonomyLevel || ''] || row.defaultAutonomyLevel || '-' }}
                </ElTag>
                <ElTooltip v-if="row.highRiskWrite" content="高风险写任务强制需审批" placement="top">
                  <ElTag type="danger" effect="light" size="small" class="ml-4">高风险</ElTag>
                </ElTooltip>
              </template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="90">
              <template #default="{ row }">
                <ElTag :type="row.status === 'enabled' ? 'success' : 'info'" effect="light" size="small">
                  {{ TASK_STATUS_LABELS[row.status || ''] || row.status || '-' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="触发器数" width="90" align="center">
              <template #default="{ row }">{{ triggerCountText(row) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="taskDescription" label="描述" min-width="200" show-overflow-tooltip />
            <ElTableColumn label="创建时间" width="170">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="240" fixed="right" align="center">
              <template #default="{ row }">
                <ElTooltip v-if="canQueryTask" content="详情（触发器/运行记录）" placement="top">
                  <ElButton text type="primary" @click="openDetail(row)">
                    <ElIcon><View /></ElIcon>
                  </ElButton>
                </ElTooltip>
                <ElTooltip v-if="canTriggerRun" content="立即执行一次" placement="top">
                  <ElButton text type="success" :disabled="row.status !== 'enabled'" @click="runOnce(row)">
                    <ElIcon><VideoPlay /></ElIcon>
                  </ElButton>
                </ElTooltip>
                <template v-if="canManageTask">
                  <ElTooltip content="编辑" placement="top">
                    <ElButton text type="primary" @click="openEditDrawer(row)">
                      <ElIcon><Edit /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip :content="row.status === 'enabled' ? '停用' : '启用'" placement="top">
                    <ElButton text :type="row.status === 'enabled' ? 'warning' : 'success'" @click="toggleStatus(row)">
                      <ElIcon><SwitchButton /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip content="删除" placement="top">
                    <ElButton text type="danger" @click="removeTask(row)">
                      <ElIcon><Delete /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                </template>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>

        <div class="task-pagination">
          <ElPagination
            v-model:current-page="page.current"
            v-model:page-size="page.size"
            background
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50, 100]"
            :total="page.total"
            @size-change="handleSizeChange"
            @current-change="loadTasks"
          />
        </div>
      </ElCard>

      <TaskFormDrawer v-model:visible="formDrawerVisible" :mode="formMode" :task="activeTask" @saved="loadTasks" />

      <TaskDetailDrawer
        v-model:visible="detailDrawerVisible"
        :task="activeTask"
        :can-manage="canManageTask"
        :can-trigger="canTriggerRun"
        @changed="refreshTriggerCount"
      />
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete, Edit, Plus, Refresh, Search, SwitchButton, VideoPlay, View } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import { haveAuth } from '@/mixins/userAuth.js';
import agentTaskService from '@/views/ai-agent/services/agentTask';
import type { AgentTaskDefinition, DigitalEmployeeOption, TaskStatus } from '@/views/ai-agent/services/agentTask';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import {
  AUTONOMY_LEVEL_LABELS,
  TASK_MANAGE_PERMISSION,
  TASK_QUERY_PERMISSION,
  TASK_STATUS_LABELS,
  TASK_TRIGGER_PERMISSION,
  TRIGGER_TYPE_FILTER_OPTIONS,
  TRIGGER_TYPE_LABELS,
  TRIGGER_TYPE_TAG_TYPES,
  concurrentSkipToast,
  isConsoleManualTriggerType,
  isNil
} from './task-support';
import TaskFormDrawer from './components/task-form-drawer.vue';
import TaskDetailDrawer from './components/task-detail-drawer.vue';

defineOptions({ name: 'AgentTasksPage' });

const route = useRoute();

// 按钮级权限与后端 @SaCheckPermission 三档一致；IAM 未注册权限码时按钮隐藏（失败关闭）。
const canQueryTask = computed(() => haveAuth(TASK_QUERY_PERMISSION));
const canManageTask = computed(() => haveAuth(TASK_MANAGE_PERMISSION));
const canTriggerRun = computed(() => haveAuth(TASK_TRIGGER_PERMISSION));

const query = reactive<{
  keyword: string;
  digitalEmployeeId: string;
  taskType: string;
  status: TaskStatus | '';
  triggerType: string;
}>({
  keyword: '',
  digitalEmployeeId: '',
  taskType: '',
  status: '',
  triggerType: ''
});
/** 数字员工筛选选项；失败不阻塞任务列表查询 */
const employeeOptions = ref<DigitalEmployeeOption[]>([]);
const employeeOptionsLoading = ref(false);
const employeeNameMap = reactive(new Map<string, string>());
/** ElSelect 在 v-model 早于 options 时会把雪花 ID 当标签，options 到达后重挂载以回显名称 */
const employeeSelectEpoch = ref(0);
const page = reactive({ current: 1, size: 10, total: 0 });
const taskRows = ref<AgentTaskDefinition[]>([]);
const loading = ref(false);
const formDrawerVisible = ref(false);
const detailDrawerVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const activeTask = ref<AgentTaskDefinition | null>(null);
/** 触发器数量缓存（definitionId → count）；分页响应不含该字段，按页并行补查。 */
const triggerCounts = reactive(new Map<string, number>());
const triggerTypeMap = reactive(new Map<string, string[]>());

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

const triggerCountText = (row: AgentTaskDefinition) => {
  if (isNil(row.id)) return '-';
  const count = triggerCounts.get(String(row.id));
  return count === undefined ? '-' : String(count);
};

const triggerTypesOf = (row: AgentTaskDefinition): string[] => {
  if (isNil(row.id)) return [];
  return triggerTypeMap.get(String(row.id)) || [];
};

const employeeOptionLabel = (employee: DigitalEmployeeOption): string => {
  const name = employee.employeeName || `员工 #${employee.id ?? '-'}`;
  return employee.employeeCode ? `${name}（${employee.employeeCode}）` : name;
};

/** 列表行员工名称回显：优先用筛选选项缓存，未命中时降级展示 ID */
const employeeLabelById = (digitalEmployeeId?: string | number | null): string => {
  if (isNil(digitalEmployeeId)) return '-';
  const id = String(digitalEmployeeId);
  const name = employeeNameMap.get(id);
  return name ? name : `员工 #${id}`;
};

const selectedEmployeeLabel = (value: string | number | boolean | undefined): string => {
  const id = String(value ?? '');
  if (!id) return '';
  const item = employeeOptions.value.find(option => String(option.id) === id);
  return item ? employeeOptionLabel(item) : employeeLabelById(id);
};

function rememberEmployee(item: DigitalEmployeeOption) {
  if (isNil(item.id)) return;
  employeeNameMap.set(String(item.id), item.employeeName || '');
}

/** 路由预填的员工若不在选项里，补拉详情以免筛选框只显示雪花 ID */
async function ensureSelectedEmployeeOption() {
  const id = query.digitalEmployeeId;
  if (!id) return;
  if (employeeOptions.value.some(item => String(item.id) === id)) return;
  try {
    const detail = await digitalEmployeeService.fetchDetail(id);
    const option: DigitalEmployeeOption = {
      id: String(detail.id ?? id),
      employeeCode: detail.employeeCode,
      employeeName: detail.employeeName,
      status: detail.status
    };
    employeeOptions.value = [option, ...employeeOptions.value];
    rememberEmployee(option);
    employeeSelectEpoch.value += 1;
  } catch {
    // 详情失败时仍展示 ID，不阻断筛选
  }
}

async function loadEmployeeOptions() {
  employeeOptionsLoading.value = true;
  try {
    const options = await agentTaskService.fetchEmployeeOptions(undefined, null);
    employeeOptions.value = options;
    employeeNameMap.clear();
    options.forEach(rememberEmployee);
    await ensureSelectedEmployeeOption();
    employeeSelectEpoch.value += 1;
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '数字员工选项加载失败，仍可按 ID 过滤'));
    }
    await ensureSelectedEmployeeOption();
  } finally {
    employeeOptionsLoading.value = false;
  }
}

async function loadTasks() {
  loading.value = true;
  try {
    const response = await agentTaskService.fetchTaskPage({
      keyword: query.keyword.trim(),
      digitalEmployeeId: query.digitalEmployeeId,
      taskType: query.taskType.trim(),
      status: query.status,
      triggerType: query.triggerType,
      current: page.current,
      size: page.size
    });
    taskRows.value = response.data;
    page.total = response.total;
    loadTriggerCounts(response.data);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '任务列表查询失败'));
  } finally {
    loading.value = false;
  }
}

/** 分页响应无触发器数字段，对当前页任务并行补查触发器列表；单行失败不阻塞列表。 */
function loadTriggerCounts(rows: AgentTaskDefinition[]) {
  rows.forEach(async row => {
    if (isNil(row.id)) return;
    const key = String(row.id);
    try {
      const triggers = await agentTaskService.listTriggers(row.id);
      triggerCounts.set(key, triggers.length);
      triggerTypeMap.set(
        key,
        Array.from(new Set(triggers.map(item => String(item.triggerType || '')).filter(Boolean)))
      );
    } catch {
      triggerCounts.delete(key);
    }
  });
}

function refreshTriggerCount() {
  const task = activeTask.value;
  if (task && !isNil(task.id)) {
    loadTriggerCounts([task]);
  }
}

function handleSearch() {
  page.current = 1;
  loadTasks();
}

function handleReset() {
  Object.assign(query, { keyword: '', digitalEmployeeId: '', taskType: '', status: '', triggerType: '' });
  handleSearch();
}

function handleSizeChange() {
  page.current = 1;
  loadTasks();
}

function openCreateDrawer() {
  formMode.value = 'create';
  activeTask.value = null;
  formDrawerVisible.value = true;
}

function openEditDrawer(row: AgentTaskDefinition) {
  formMode.value = 'edit';
  activeTask.value = row;
  formDrawerVisible.value = true;
}

function openDetail(row: AgentTaskDefinition) {
  activeTask.value = row;
  detailDrawerVisible.value = true;
}

async function runOnce(row: AgentTaskDefinition) {
  if (isNil(row.id)) return;
  if (row.status !== 'enabled') {
    ElMessage.warning('请先启用任务');
    return;
  }
  let triggerId: string | number | undefined;
  try {
    const triggers = await agentTaskService.listTriggers(row.id);
    const enabled = triggers.filter(
      item => isConsoleManualTriggerType(item.triggerType) && item.status === 'enabled' && item.id != null
    );
    const preferred = enabled.find(item => item.triggerType === 'SCHEDULE') || enabled[0];
    if (!preferred?.id) {
      ElMessage.warning('没有已启用的定时/事件/API 触发器，请先在详情中创建并启用');
      openDetail(row);
      return;
    }
    triggerId = preferred.id;
    await ElMessageBox.confirm(
      `立即按任务描述执行一次「${row.taskName || row.id}」？不会改定时的下次执行时间。`,
      '立即执行一次',
      { type: 'warning', confirmButtonText: '执行', cancelButtonText: '取消' }
    );
  } catch (error) {
    if (triggerId == null) {
      ElMessage.error(extractApiErrorMessage(error, '读取触发器失败'));
    }
    return;
  }
  try {
    const run = await agentTaskService.manualTrigger(row.id, triggerId);
    const skipped = concurrentSkipToast(run);
    if (skipped) {
      ElMessage.warning(skipped);
      openDetail(row);
    } else {
      ElMessage.success(`已受理，运行 ID ${run.id ?? '-'}`);
    }
    await loadTasks();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '立即执行失败'));
  }
}

async function toggleStatus(row: AgentTaskDefinition) {
  if (isNil(row.id)) return;
  const next: TaskStatus = row.status === 'enabled' ? 'disabled' : 'enabled';
  try {
    await ElMessageBox.confirm(
      next === 'disabled'
        ? `停用后「${row.taskName || row.id}」的触发器不再产生新运行，确认停用？`
        : `确认启用任务「${row.taskName || row.id}」？`,
      next === 'disabled' ? '停用确认' : '启用确认',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    await agentTaskService.modifyTask(row.id, { status: next });
    ElMessage.success(next === 'enabled' ? '任务已启用' : '任务已停用');
    await loadTasks();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '任务状态切换失败'));
  }
}

async function removeTask(row: AgentTaskDefinition) {
  if (isNil(row.id)) return;
  try {
    await ElMessageBox.confirm(
      `删除任务「${row.taskName || row.id}」将连带删除其全部触发器，且不可恢复。确认删除？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    await agentTaskService.removeTask(row.id);
    ElMessage.success('任务已删除');
    await loadTasks();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '任务删除失败'));
  }
}

onMounted(() => {
  applyRouteEmployeeFilter();
  loadEmployeeOptions();
  loadTasks();
  if (String(route.query.create || '') === '1') {
    openCreateDrawer();
  }
});

watch(
  () => route.query.digitalEmployeeId,
  async () => {
    applyRouteEmployeeFilter();
    await ensureSelectedEmployeeOption();
    handleSearch();
  }
);

function applyRouteEmployeeFilter() {
  const raw = route.query.digitalEmployeeId;
  const id = String(Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '')).trim();
  query.digitalEmployeeId = id;
}
</script>

<style scoped>
.task-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.task-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.task-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.task-header h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
}

.task-subtitle {
  margin: 6px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.task-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filter-panel {
  margin-bottom: 12px;
}

.filter-panel :deep(.el-form-item) {
  margin-bottom: 10px;
}

.filter-panel :deep(.el-input),
.filter-panel :deep(.el-select) {
  width: 100%;
}

.task-list-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.task-list-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.task-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.task-table-wrap :deep(.el-table) {
  height: 100%;
}

.task-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.cell-strong {
  font-weight: 600;
  color: #111827;
}

.cell-muted {
  color: #64748b;
  font-size: 12px;
}

.ml-4 {
  margin-left: 4px;
}

@media (max-width: 920px) {
  .task-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
