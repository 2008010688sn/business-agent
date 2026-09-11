<template>
  <BaseLayout>
    <main class="runtime-runs-page">
      <ElCard>
        <header class="page-header">
          <div>
            <h1>运行记录</h1>
            <p>持久运行时权威运行记录：时间线、审批与中断、取消与恢复。与智能体评估同级，不挂在数字员工菜单下。</p>
          </div>
          <ElButton :loading="loading" @click="loadRuns">
            <ElIcon><Refresh /></ElIcon>
            刷新
          </ElButton>
        </header>
      </ElCard>

      <ElCard class="mt-8px">
        <ElTabs v-model="activeTab">
          <ElTabPane label="运行记录" name="runs">
            <ElAlert
              v-if="!canQuery"
              title="当前账号没有 ai-agent:runtime-run:query 权限"
              type="warning"
              :closable="false"
              show-icon
            />
            <template v-else>
              <ElForm :model="query" label-width="70px" class="filter-form">
                <ElRow :gutter="24">
                  <ElCol :span="6">
                    <ElFormItem label="状态">
                      <ElSelect v-model="query.state" clearable placeholder="全部状态">
                        <ElOption
                          v-for="item in stateOptions"
                          :key="item.value"
                          :label="item.label"
                          :value="item.value"
                        />
                      </ElSelect>
                    </ElFormItem>
                  </ElCol>
                  <ElCol :span="6">
                    <ElFormItem label="运行模式">
                      <ElSelect v-model="query.runMode" clearable placeholder="全部模式">
                        <ElOption
                          v-for="item in RUN_MODE_OPTIONS"
                          :key="item.value"
                          :label="item.label"
                          :value="item.value"
                        />
                      </ElSelect>
                    </ElFormItem>
                  </ElCol>
                  <ElCol :span="6">
                    <ElFormItem label="执行主体">
                      <ElSelect v-model="query.ownerType" clearable placeholder="全部主体" @change="handleSearch">
                        <ElOption
                          v-for="item in RUN_OWNER_TYPE_OPTIONS"
                          :key="item.value"
                          :label="item.label"
                          :value="item.value"
                        />
                      </ElSelect>
                    </ElFormItem>
                  </ElCol>
                  <ElCol :span="6">
                    <ElFormItem label="数字员工">
                      <ElSelect
                        v-model="query.digitalEmployeeId"
                        clearable
                        filterable
                        allow-create
                        default-first-option
                        placeholder="选择或输入员工ID"
                        :loading="employeeOptionsLoading"
                        @change="handleSearch"
                      >
                        <ElOption
                          v-for="item in employeeOptions"
                          :key="String(item.id)"
                          :label="employeeOptionLabel(item)"
                          :value="String(item.id)"
                        />
                      </ElSelect>
                    </ElFormItem>
                  </ElCol>
                  <ElCol :span="6">
                    <ElFormItem label="会话ID">
                      <ElInput v-model="query.threadId" clearable placeholder="会话ID" @keyup.enter="handleSearch" />
                    </ElFormItem>
                  </ElCol>
                  <ElCol :span="6">
                    <ElFormItem label="关键字">
                      <ElInput v-model="query.keyword" clearable placeholder="问题关键字" @keyup.enter="handleSearch" />
                    </ElFormItem>
                  </ElCol>
                </ElRow>
                <div class="filter-actions">
                  <ElButton type="primary" :loading="loading" @click="handleSearch">
                    <ElIcon><Search /></ElIcon>
                    查询
                  </ElButton>
                  <ElButton @click="handleReset">重置</ElButton>
                </div>
              </ElForm>

              <ElTable v-loading="loading" :data="rows" border stripe row-key="id" empty-text="暂无运行记录">
                <ElTableColumn label="运行ID" width="190" fixed="left">
                  <template #default="{ row }">
                    <ElButton link type="primary" class="mono-link" @click="openDetail(row)">{{ row.id }}</ElButton>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="问题 / 任务" min-width="240" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.query || '-' }}</template>
                </ElTableColumn>
                <ElTableColumn label="执行主体" width="170" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div>{{ runOwnerTypeLabel(row.ownerType) }}</div>
                    <span v-if="row.ownerId" class="cell-muted mono-text">{{ row.ownerId }}</span>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="所属数字员工" width="170" show-overflow-tooltip>
                  <template #default="{ row }">
                    <template v-if="row.digitalEmployeeId">
                      <div>{{ employeeLabelById(row.digitalEmployeeId) }}</div>
                      <span class="cell-muted mono-text">{{ row.digitalEmployeeId }}</span>
                    </template>
                    <span v-else>-</span>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="状态" width="110">
                  <template #default="{ row }">
                    <ElTag :type="runStateTagType(row.state)" effect="light" size="small">
                      {{ runStateLabel(row.state) }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="模式" width="110">
                  <template #default="{ row }">{{ runModeLabel(row.runMode) }}</template>
                </ElTableColumn>
                <ElTableColumn label="Release" width="170" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.releaseId || '-' }}</template>
                </ElTableColumn>
                <ElTableColumn label="发起人" width="140" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.initiatorUserName || row.initiatorUserId || '-' }}</template>
                </ElTableColumn>
                <ElTableColumn label="执行 Principal" width="180" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div>{{ row.subjectKind || '-' }}</div>
                    <span v-if="row.executionPrincipalId" class="cell-muted mono-text">{{ row.executionPrincipalId }}</span>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="会话ID" min-width="170" show-overflow-tooltip>
                  <template #default="{ row }">
                    <span class="mono-text">{{ row.threadId || '-' }}</span>
                  </template>
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
                <ElTableColumn prop="errorMessage" label="错误" min-width="180" show-overflow-tooltip />
                <ElTableColumn label="操作" width="200" fixed="right" align="center">
                  <template #default="{ row }">
                    <ElButton link type="primary" @click="openDetail(row)">详情</ElButton>
                    <ElTooltip
                      v-if="row.state === 'WAITING_APPROVAL'"
                      content="存在待处理审批，处理后可恢复"
                      placement="top"
                    >
                      <ElIcon class="approval-hint"><Warning /></ElIcon>
                    </ElTooltip>
                    <ElButton
                      v-if="isActiveRuntimeRunState(row.state)"
                      link
                      type="danger"
                      :disabled="!canCancel"
                      @click="handleCancel(row)"
                    >
                      取消
                    </ElButton>
                    <ElButton
                      v-if="isResumableRuntimeRunState(row.state)"
                      link
                      type="primary"
                      :disabled="!canResume"
                      @click="handleResume(row)"
                    >
                      恢复
                    </ElButton>
                  </template>
                </ElTableColumn>
              </ElTable>

              <div class="table-pagination">
                <ElPagination
                  v-model:current-page="page.current"
                  v-model:page-size="page.size"
                  background
                  layout="total, sizes, prev, pager, next, jumper"
                  :page-sizes="[10, 20, 50, 100]"
                  :total="page.total"
                  @size-change="handleSizeChange"
                  @current-change="loadRuns"
                />
              </div>
            </template>
          </ElTabPane>
          <ElTabPane v-if="canQueryApproval" label="待审批" name="approvals">
            <PendingApprovalPanel :can-review="canReviewApproval" @open-run="openRunById" @changed="loadRuns" />
          </ElTabPane>
          <ElTabPane v-if="canQueryReconcile" label="待对账" name="reconcile">
            <PendingReconcilePanel :can-reconcile="canReconcile" @open-run="openRunById" />
          </ElTabPane>
        </ElTabs>
      </ElCard>

      <RuntimeRunDetailDrawer
        ref="detailDrawerRef"
        :can-cancel="canCancel"
        :can-resume="canResume"
        :can-query-approval="canQueryApproval"
        :can-review-approval="canReviewApproval"
        @changed="loadRuns"
      />
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Search, Warning } from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import runtimeRunService, {
  RUNTIME_RUN_STATE_LABELS,
  isActiveRuntimeRunState,
  isResumableRuntimeRunState,
  type RuntimeRunPageQueryReq,
  type RuntimeRunResp,
  type RuntimeRunState
} from '@/views/ai-agent/services/runtimeRun';
import agentTaskService, { type DigitalEmployeeOption } from '@/views/ai-agent/services/agentTask';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import { useAuthStore } from '@/store/modules/auth';
import { haveAuth } from '@/mixins/userAuth.js';
import RuntimeRunDetailDrawer from './components/runtime-run-detail-drawer.vue';
import PendingApprovalPanel from './components/pending-approval-panel.vue';
import PendingReconcilePanel from './components/pending-reconcile-panel.vue';
import {
  RUN_MODE_OPTIONS,
  RUN_OWNER_TYPE_OPTIONS,
  formatRunDateTime,
  formatRunDuration,
  runModeLabel,
  runOwnerTypeLabel,
  runStateLabel,
  runStateTagType
} from './runtime-run-display';

defineOptions({ name: 'DataAgentRuntimeRuns' });

/** 权限码对应后端 RuntimeRunController / AgentApprovalController 的 @SaCheckPermission，需在 IAM 注册 */
const QUERY_PERMISSION = 'ai-agent:runtime-run:query';
const CANCEL_PERMISSION = 'ai-agent:runtime-run:cancel';
const RESUME_PERMISSION = 'ai-agent:runtime-run:resume';
const APPROVAL_QUERY_PERMISSION = 'ai-agent:approval:query';
const APPROVAL_REVIEW_PERMISSION = 'ai-agent:approval:review';
const RECONCILE_PERMISSION = 'ai-agent:runtime-invocation:reconcile';

const stateOptions = (Object.keys(RUNTIME_RUN_STATE_LABELS) as RuntimeRunState[]).map(value => ({
  value,
  label: RUNTIME_RUN_STATE_LABELS[value]
}));

const authStore = useAuthStore();

const normalizeUserType = (value: unknown): string => {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'object') {
    const userType = value as Record<string, unknown>;
    return String(userType.value ?? userType.code ?? userType.name ?? '');
  }
  return String(value);
};

// 与 token-usage 页保持一致：平台/租户管理员放行，其余按权限码
const isAdminUser = computed(() =>
  ['0', '1', 'PLATFORM_ADMIN', 'TENANT_ADMIN'].includes(normalizeUserType(authStore.userInfo?.type))
);
const canQuery = computed(() => isAdminUser.value || haveAuth(QUERY_PERMISSION));
const canCancel = computed(() => isAdminUser.value || haveAuth(CANCEL_PERMISSION));
const canResume = computed(() => isAdminUser.value || haveAuth(RESUME_PERMISSION));
const canQueryApproval = computed(() => isAdminUser.value || haveAuth(APPROVAL_QUERY_PERMISSION));
const canReviewApproval = computed(() => isAdminUser.value || haveAuth(APPROVAL_REVIEW_PERMISSION));
const canQueryReconcile = computed(() => isAdminUser.value || haveAuth(RECONCILE_PERMISSION));
const canReconcile = computed(() => isAdminUser.value || haveAuth(RECONCILE_PERMISSION));

const activeTab = ref('runs');
const loading = ref(false);
const loadRequestId = ref(0);
const rows = ref<RuntimeRunResp[]>([]);
const nowMs = ref(Date.now());
const detailDrawerRef = ref<InstanceType<typeof RuntimeRunDetailDrawer> | null>(null);
let tickTimer: ReturnType<typeof setInterval> | null = null;

const query = reactive<
  Pick<RuntimeRunPageQueryReq, 'state' | 'runMode' | 'threadId' | 'keyword' | 'ownerType' | 'digitalEmployeeId'>
>({
  state: '',
  runMode: '',
  threadId: '',
  keyword: '',
  ownerType: '',
  digitalEmployeeId: ''
});

/** 数字员工筛选项与名称回显缓存（运行列表 owner 维度过滤辅助）。 */
const employeeOptions = ref<DigitalEmployeeOption[]>([]);
const employeeOptionsLoading = ref(false);
const employeeNameMap = ref(new Map<string, string>());

const employeeOptionLabel = (item: DigitalEmployeeOption): string =>
  [item.employeeName, item.employeeCode].filter(Boolean).join(' · ') || `员工 #${item.id}`;

/** 员工名回显；选项未覆盖（禁用/归档/超页）时降级为「员工 #ID」。 */
const employeeLabelById = (id?: string | null): string => {
  if (!id) return '-';
  return employeeNameMap.value.get(String(id)) || `员工 #${id}`;
};

/**
 * 数字员工选项（与任务页共用最小只读接口）；无 ai-agent:digital-employee:query 权限时
 * 加载失败不阻断运行列表，筛选框仍可手输员工 ID（allow-create）。
 */
async function loadEmployeeOptions() {
  employeeOptionsLoading.value = true;
  try {
    const options = await agentTaskService.fetchEmployeeOptions();
    employeeOptions.value = options;
    employeeNameMap.value = new Map(
      options.filter(item => item.id != null).map(item => [String(item.id), item.employeeName || ''])
    );
  } catch (error) {
    console.error('数字员工选项加载失败:', error);
    if (shouldShowLocalApiError(error)) {
      ElMessage.warning('数字员工选项加载失败，可在筛选框手动输入员工 ID');
    }
  } finally {
    employeeOptionsLoading.value = false;
  }
}

const page = reactive({
  current: 1,
  size: 20,
  total: 0
});

async function loadRuns() {
  if (!canQuery.value) {
    return;
  }
  const requestId = loadRequestId.value + 1;
  loadRequestId.value = requestId;
  loading.value = true;
  try {
    const response = await runtimeRunService.page({
      current: page.current,
      size: page.size,
      state: query.state || undefined,
      runMode: query.runMode || undefined,
      ownerType: query.ownerType || undefined,
      digitalEmployeeId: query.digitalEmployeeId?.trim() || undefined,
      threadId: query.threadId?.trim() || undefined,
      keyword: query.keyword?.trim() || undefined
    });
    if (loadRequestId.value !== requestId) {
      return;
    }
    rows.value = response.data;
    page.total = response.total;
    nowMs.value = Date.now();
  } catch (error) {
    if (loadRequestId.value === requestId) {
      console.error('查询运行列表失败:', error);
      ElMessage.error(error instanceof Error ? error.message : '运行列表查询失败');
    }
  } finally {
    if (loadRequestId.value === requestId) {
      loading.value = false;
    }
  }
}

function handleSearch() {
  page.current = 1;
  loadRuns();
}

function handleReset() {
  query.state = '';
  query.runMode = '';
  query.threadId = '';
  query.keyword = '';
  query.ownerType = '';
  query.digitalEmployeeId = '';
  handleSearch();
}

function handleSizeChange() {
  page.current = 1;
  loadRuns();
}

function openDetail(row: RuntimeRunResp) {
  detailDrawerRef.value?.open(row.id);
}

/** 从「待审批」Tab 打开所属运行的详情抽屉 */
function openRunById(runId: string) {
  detailDrawerRef.value?.open(runId);
}

const route = useRoute();
const router = useRouter();

/**
 * 深链直达详情：其他页面（如任务运行记录面板）以 query.runId 跳转本页时自动打开详情抽屉。
 * 后端 ID 为雪花串，仅做数字串校验，绝不转 number（会丢精度）；打开前先预检
 * detail 接口，run 不存在 / 无权限时提示后停留列表，不打开空抽屉。
 */
async function openRunFromRouteQuery() {
  const raw = route.query.runId;
  const runId = String(Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '')).trim();
  if (raw !== undefined) {
    // 无论直达成功与否都清理 query，避免关闭抽屉后刷新页面再次弹开
    router.replace({ query: { ...route.query, runId: undefined } });
  }
  if (!runId || !/^\d+$/.test(runId)) {
    return;
  }
  try {
    const detail = await runtimeRunService.detail(runId);
    if (!detail.run?.id) {
      ElMessage.warning(`未找到运行记录（runId: ${runId}）`);
      return;
    }
    detailDrawerRef.value?.open(runId);
  } catch (error) {
    // 403/404 等按后端报错提示，列表正常展示；axios 错误已由全局拦截器弹过，这里不再重复提示
    console.error('按路由参数打开运行详情失败, runId=%s:', runId, error);
    if (shouldShowLocalApiError(error)) {
      ElMessage.warning(extractApiErrorMessage(error, `运行记录不存在或无权限查看（runId: ${runId}）`));
    }
  }
}

async function handleCancel(row: RuntimeRunResp) {
  try {
    await ElMessageBox.confirm('取消后运行将走状态机终止，是否继续？', '取消运行', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await runtimeRunService.cancel(row.id, '管理页手动取消');
    ElMessage.success('已请求取消');
    await loadRuns();
  } catch (error) {
    console.error('取消运行失败, runId=%s:', row.id, error);
    ElMessage.error('取消运行失败，请稍后重试');
  }
}

async function handleResume(row: RuntimeRunResp) {
  try {
    await runtimeRunService.resume(row.id);
    ElMessage.success('已恢复运行');
    await loadRuns();
  } catch (error) {
    console.error('恢复运行失败, runId=%s:', row.id, error);
    ElMessage.error('恢复运行失败，请稍后重试');
  }
}

onMounted(() => {
  const tab = String(Array.isArray(route.query.tab) ? (route.query.tab[0] ?? '') : (route.query.tab ?? '')).trim();
  if (tab === 'reconcile' && canQueryReconcile.value) {
    activeTab.value = 'reconcile';
  } else if (tab === 'approvals' && canQueryApproval.value) {
    activeTab.value = 'approvals';
  }
  loadRuns();
  loadEmployeeOptions();
  openRunFromRouteQuery();
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
</script>

<style scoped>
.runtime-runs-page {
  display: flex;
  flex-direction: column;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.page-header h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 600;
  line-height: 28px;
}

.page-header p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.filter-form {
  margin-bottom: 4px;
}

.filter-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-bottom: 12px;
}

.mono-link {
  max-width: 100%;
  padding: 0;
  font-family: Consolas, Monaco, monospace;
}

.mono-text {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.cell-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.approval-hint {
  margin: 0 4px;
  color: var(--el-color-warning);
  vertical-align: middle;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}
</style>
