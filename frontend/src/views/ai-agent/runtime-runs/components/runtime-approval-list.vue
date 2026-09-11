<template>
  <section class="approval-section">
    <header class="approval-section-header">
      <strong>审批记录</strong>
      <span class="approval-section-count">{{ approvals.length }} 条</span>
    </header>
    <ElAlert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
    <ElEmpty v-else-if="!loading && approvals.length === 0" description="暂无审批记录" :image-size="64" />
    <div v-else v-loading="loading" class="approval-list">
      <div v-for="approval in approvals" :key="approval.id" class="approval-item">
        <div class="approval-head">
          <ElTag :type="approvalStateTagType(approval.state)" effect="light" size="small">
            {{ approvalStateLabel(approval.state) }}
          </ElTag>
          <ElTag :type="approvalRiskTagType(approval.riskLevel)" effect="plain" size="small">
            {{ approvalRiskLabel(approval.riskLevel) }}
          </ElTag>
          <span class="approval-id">审批ID: {{ approval.id }}</span>
          <span v-if="approval.stepKey" class="approval-step">步骤: {{ approval.stepKey }}</span>
          <div v-if="isPendingApprovalState(approval.state) && canReview" class="approval-actions">
            <ElButton
              size="small"
              type="success"
              plain
              :loading="decidingId === approval.id"
              @click="handleApprove(approval)"
            >
              通过
            </ElButton>
            <ElButton
              size="small"
              type="danger"
              plain
              :loading="decidingId === approval.id"
              @click="handleReject(approval)"
            >
              驳回
            </ElButton>
          </div>
        </div>
        <div class="approval-summary">{{ approvalBusinessSummary(approval) }}</div>
        <div class="approval-meta">
          <span>发起人: {{ approval.requestedBy || '-' }}</span>
          <span>发起时间: {{ formatRunDateTime(approval.createTime) }}</span>
          <span>过期时间: {{ formatRunDateTime(approval.expiresAt) }}</span>
        </div>
        <div v-if="!isPendingApprovalState(approval.state)" class="approval-meta">
          <span>审批人: {{ approval.approver || '-' }}</span>
          <span>决定时间: {{ formatRunDateTime(approval.decidedAt) }}</span>
          <span v-if="approval.decisionComment" class="approval-comment">意见: {{ approval.decisionComment }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import agentApprovalService, {
  isPendingApprovalState,
  type RuntimeApprovalResp
} from '@/views/ai-agent/services/agentApproval';
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

defineOptions({ name: 'RuntimeApprovalList' });

const props = defineProps<{
  runId: string;
  canReview: boolean;
}>();

const emit = defineEmits<{
  /** 审批决定成功后触发，父级刷新运行详情 */
  decided: [];
}>();

/** 单个运行的审批记录极少，一页取满不做分页 */
const APPROVAL_PAGE_SIZE = 50;

const loading = ref(false);
const loadError = ref('');
const approvals = ref<RuntimeApprovalResp[]>([]);
const decidingId = ref<string | null>(null);

async function loadApprovals() {
  if (!props.runId) {
    approvals.value = [];
    return;
  }
  const runId = props.runId;
  loading.value = true;
  loadError.value = '';
  try {
    const response = await agentApprovalService.page({ current: 1, size: APPROVAL_PAGE_SIZE, runId });
    if (props.runId !== runId) {
      return;
    }
    approvals.value = response.data;
  } catch (error) {
    if (props.runId === runId) {
      loadError.value = '审批记录加载失败，请稍后重试';
      console.error('加载审批记录失败, runId=%s:', runId, error);
    }
  } finally {
    if (props.runId === runId) {
      loading.value = false;
    }
  }
}

async function handleApprove(approval: RuntimeApprovalResp) {
  decidingId.value = approval.id;
  try {
    const decided = await approveWithPrompt(approval);
    if (decided) {
      await loadApprovals();
      emit('decided');
    }
  } finally {
    decidingId.value = null;
  }
}

async function handleReject(approval: RuntimeApprovalResp) {
  decidingId.value = approval.id;
  try {
    const decided = await rejectWithPrompt(approval);
    if (decided) {
      await loadApprovals();
      emit('decided');
    }
  } finally {
    decidingId.value = null;
  }
}

watch(
  () => props.runId,
  () => {
    approvals.value = [];
    loadApprovals();
  }
);

onMounted(loadApprovals);

defineExpose({ reload: loadApprovals });
</script>

<style scoped>
.approval-section {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.approval-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.approval-section-header strong {
  color: var(--el-text-color-primary);
  font-size: 14px;
}

.approval-section-count {
  padding: 2px 10px;
  border-radius: 999px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  font-size: 12px;
  font-weight: 600;
}

.approval-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.approval-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.approval-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.approval-id,
.approval-step {
  color: var(--el-text-color-secondary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.approval-actions {
  display: flex;
  align-items: center;
  margin-left: auto;
}

.approval-summary {
  color: var(--el-text-color-regular);
  font-size: 13px;
  word-break: break-all;
}

.approval-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.approval-comment {
  word-break: break-word;
}
</style>
