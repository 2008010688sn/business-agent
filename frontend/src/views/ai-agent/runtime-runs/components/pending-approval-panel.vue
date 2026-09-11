<template>
  <div class="pending-approval-panel">
    <ElForm :model="query" label-width="70px" class="filter-form">
      <ElRow :gutter="24">
        <ElCol :span="6">
          <ElFormItem label="状态">
            <ElSelect v-model="query.state" clearable placeholder="全部状态">
              <ElOption v-for="item in stateOptions" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
        </ElCol>
        <ElCol :span="6">
          <ElFormItem label="风险等级">
            <ElSelect v-model="query.riskLevel" clearable placeholder="全部等级">
              <ElOption v-for="item in riskOptions" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
        </ElCol>
        <ElCol :span="6">
          <ElFormItem label="运行ID">
            <ElInput v-model="query.runId" clearable placeholder="所属运行ID" @keyup.enter="handleSearch" />
          </ElFormItem>
        </ElCol>
        <ElCol :span="6">
          <div class="filter-actions">
            <ElButton type="primary" :loading="loading" @click="handleSearch">查询</ElButton>
            <ElButton @click="handleReset">重置</ElButton>
          </div>
        </ElCol>
      </ElRow>
    </ElForm>

    <ElTable v-loading="loading" :data="rows" border stripe row-key="id" empty-text="暂无审批记录">
      <ElTableColumn label="审批ID" width="190" fixed="left">
        <template #default="{ row }">
          <span class="mono-text">{{ row.id }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="业务描述" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ approvalBusinessSummary(row) }}</template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="96">
        <template #default="{ row }">
          <ElTag :type="approvalStateTagType(row.state)" effect="light" size="small">
            {{ approvalStateLabel(row.state) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="风险" width="88">
        <template #default="{ row }">
          <ElTag :type="approvalRiskTagType(row.riskLevel)" effect="plain" size="small">
            {{ approvalRiskLabel(row.riskLevel) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="所属运行" width="180">
        <template #default="{ row }">
          <ElButton v-if="hasRun(row)" link type="primary" class="mono-link" @click="emit('openRun', row.runId!)">
            {{ row.runId }}
          </ElButton>
          <span v-else class="cell-muted">无关联运行</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="发起人" width="120" show-overflow-tooltip>
        <template #default="{ row }">{{ row.requestedBy || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="发起 / 过期" width="180">
        <template #default="{ row }">
          <div>{{ formatRunDateTime(row.createTime) }}</div>
          <span class="cell-muted">{{ formatRunDateTime(row.expiresAt) }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="审批意见" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.decisionComment || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="150" fixed="right" align="center">
        <template #default="{ row }">
          <template v-if="isPendingApprovalState(row.state) && canReview">
            <ElButton link type="success" :loading="decidingId === row.id" @click="handleApprove(row)">通过</ElButton>
            <ElButton link type="danger" :loading="decidingId === row.id" @click="handleReject(row)">驳回</ElButton>
          </template>
          <span v-else class="cell-muted">只读</span>
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
        @current-change="loadApprovals"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import agentApprovalService, {
  RUNTIME_APPROVAL_RISK_LABELS,
  RUNTIME_APPROVAL_STATE_LABELS,
  isPendingApprovalState,
  type RuntimeApprovalPageQueryReq,
  type RuntimeApprovalResp,
  type RuntimeApprovalRiskLevel,
  type RuntimeApprovalState
} from '@/views/ai-agent/services/agentApproval';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { formatRunDateTime } from '../runtime-run-display';
import {
  approvalBusinessSummary,
  approvalRiskLabel,
  approvalRiskTagType,
  approvalStateLabel,
  approvalStateTagType,
  approveWithPrompt,
  rejectWithPrompt
} from '../runtime-approval-display';

defineOptions({ name: 'PendingApprovalPanel' });

const props = defineProps<{
  canReview: boolean;
}>();

const emit = defineEmits<{
  /** 打开所属运行详情抽屉 */
  openRun: [runId: string];
  /** 审批决定成功后触发，父级可刷新运行列表 */
  changed: [];
}>();

const stateOptions = (Object.keys(RUNTIME_APPROVAL_STATE_LABELS) as RuntimeApprovalState[]).map(value => ({
  value,
  label: RUNTIME_APPROVAL_STATE_LABELS[value]
}));

const riskOptions = (Object.keys(RUNTIME_APPROVAL_RISK_LABELS) as RuntimeApprovalRiskLevel[]).map(value => ({
  value,
  label: RUNTIME_APPROVAL_RISK_LABELS[value]
}));

const loading = ref(false);
const loadRequestId = ref(0);
const rows = ref<RuntimeApprovalResp[]>([]);
const decidingId = ref<string | null>(null);

// 方案第十七章 Approval/Interruption 页面：默认聚焦待审批
const query = reactive<Pick<RuntimeApprovalPageQueryReq, 'state' | 'riskLevel' | 'runId'>>({
  state: 'PENDING',
  riskLevel: '',
  runId: ''
});

const page = reactive({
  current: 1,
  size: 20,
  total: 0
});

/** runId 为 "0" 表示无关联运行（后端约定） */
const hasRun = (row: RuntimeApprovalResp): boolean => Boolean(row.runId && row.runId !== '0');

async function loadApprovals() {
  const requestId = loadRequestId.value + 1;
  loadRequestId.value = requestId;
  loading.value = true;
  try {
    const response = await agentApprovalService.page({
      current: page.current,
      size: page.size,
      state: query.state || undefined,
      riskLevel: query.riskLevel || undefined,
      runId: query.runId?.trim() || undefined
    });
    if (loadRequestId.value !== requestId) {
      return;
    }
    rows.value = response.data;
    page.total = response.total;
  } catch (error) {
    if (loadRequestId.value === requestId) {
      console.error('查询审批列表失败:', error);
      ElMessage.error(extractApiErrorMessage(error, '审批列表查询失败'));
    }
  } finally {
    if (loadRequestId.value === requestId) {
      loading.value = false;
    }
  }
}

function handleSearch() {
  page.current = 1;
  loadApprovals();
}

function handleReset() {
  query.state = 'PENDING';
  query.riskLevel = '';
  query.runId = '';
  handleSearch();
}

function handleSizeChange() {
  page.current = 1;
  loadApprovals();
}

async function handleApprove(row: RuntimeApprovalResp) {
  if (!props.canReview) {
    return;
  }
  decidingId.value = row.id;
  try {
    const decided = await approveWithPrompt(row);
    if (decided) {
      await loadApprovals();
      emit('changed');
    }
  } finally {
    decidingId.value = null;
  }
}

async function handleReject(row: RuntimeApprovalResp) {
  if (!props.canReview) {
    return;
  }
  decidingId.value = row.id;
  try {
    const decided = await rejectWithPrompt(row);
    if (decided) {
      await loadApprovals();
      emit('changed');
    }
  } finally {
    decidingId.value = null;
  }
}

onMounted(loadApprovals);

defineExpose({ reload: loadApprovals });
</script>

<style scoped>
.pending-approval-panel {
  display: flex;
  flex-direction: column;
}

.filter-form {
  margin-bottom: 4px;
}

.filter-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
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

.table-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}
</style>
