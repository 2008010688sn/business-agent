<template>
  <section class="agent-ui-message tool-confirm-card" :class="{ 'is-disabled': disabled }" :aria-disabled="disabled">
    <header class="agent-ui-header">
      <div class="agent-ui-title">
        <ElIcon><WarningFilled /></ElIcon>
        <span>写操作确认</span>
      </div>
      <div class="agent-ui-meta">
        <span v-if="toolName">{{ toolName }}</span>
        <span v-if="expireLabel">{{ expireLabel }}</span>
      </div>
    </header>

    <SafeMarkdownContent v-if="contentFormat === 'markdown' && ui.content?.text" :content="ui.content.text" />
    <p v-else-if="ui.content?.text" class="agent-ui-text">{{ ui.content.text }}</p>

    <ElDescriptions v-if="argsPreview" :column="1" border size="small">
      <ElDescriptionsItem label="参数摘要">{{ argsPreview }}</ElDescriptionsItem>
      <ElDescriptionsItem label="参数指纹">{{ paramFingerprint }}</ElDescriptionsItem>
    </ElDescriptions>

    <ElAlert v-if="expired" type="warning" :closable="false" title="确认已过期，请重新发起写操作" />
    <ElAlert v-else-if="decision === 'approved'" type="success" :closable="false" title="已确认，等待执行消费该凭证" />
    <ElAlert v-else-if="decision === 'denied'" type="info" :closable="false" title="已拒绝本次写操作" />

    <div v-if="!expired && !decision" class="flow-actions">
      <ElButton type="primary" :disabled="disabled || submitting" :loading="submitting === 'approve'" @click="approve">
        确认执行
      </ElButton>
      <ElButton type="danger" :disabled="disabled || submitting" :loading="submitting === 'deny'" @click="deny">
        拒绝
      </ElButton>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { WarningFilled } from '@element-plus/icons-vue';
import SafeMarkdownContent from '@/views/ai-agent/components/run/SafeMarkdownContent.vue';
import agentApprovalService from '@/views/ai-agent/services/agentApproval';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { toolConfirmDecisionFromUi, type AgentUiMessage } from '@/views/ai-agent/utils/agentUi';

defineOptions({ name: 'ToolConfirmCard' });

const props = defineProps<{ ui: AgentUiMessage; disabled?: boolean }>();

const submitting = ref<'approve' | 'deny' | null>(null);
const decision = ref<'approved' | 'denied' | null>(null);

const payloadValues = computed(() => props.ui.payload?.values || {});
const contentFormat = computed(() => props.ui.content?.format || 'markdown');
const toolName = computed(() => {
  const value = payloadValues.value.toolName;
  return typeof value === 'string' && value.trim() ? value.trim() : '';
});
const paramFingerprint = computed(() => {
  const value = payloadValues.value.paramFingerprint;
  return typeof value === 'string' ? value : '';
});
const argsPreview = computed(() => {
  const value = payloadValues.value.argsPreview;
  return typeof value === 'string' && value.trim() ? value.trim() : '';
});
const expiresAt = computed(() => {
  const value = payloadValues.value.expiresAt;
  if (typeof value !== 'string' || !value.trim()) return null;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? null : parsed;
});
const expired = computed(() => expiresAt.value !== null && expiresAt.value <= Date.now());
const expireLabel = computed(() => {
  if (expiresAt.value === null) return '';
  if (expired.value) return '已过期';
  return `有效至 ${new Date(expiresAt.value).toLocaleString()}`;
});

const submit = async (approved: boolean, comment?: string) => {
  const body = toolConfirmDecisionFromUi(props.ui, approved, comment);
  if (!body) {
    ElMessage.error('确认凭证不完整，请重新发起写操作');
    return;
  }
  submitting.value = approved ? 'approve' : 'deny';
  try {
    if (body.approvalId) {
      if (approved) {
        await agentApprovalService.approve(body.approvalId, comment);
      } else {
        await agentApprovalService.reject(body.approvalId, comment || '拒绝本次写操作');
      }
    } else if (approved) {
      await agentApprovalService.approveToolConfirm({
        toolCallId: body.toolCallId,
        toolName: body.toolName,
        paramFingerprint: body.paramFingerprint,
        replyId: body.replyId,
        comment
      });
    } else {
      await agentApprovalService.rejectToolConfirm({
        toolCallId: body.toolCallId,
        toolName: body.toolName,
        paramFingerprint: body.paramFingerprint,
        replyId: body.replyId,
        comment: comment || '拒绝本次写操作'
      });
    }
    decision.value = approved ? 'approved' : 'denied';
    ElMessage.success(approved ? '已确认写操作' : '已拒绝写操作');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, approved ? '确认失败，请稍后重试' : '拒绝失败，请稍后重试'));
  } finally {
    submitting.value = null;
  }
};

const approve = async () => {
  if (props.disabled || expired.value || decision.value) return;
  await submit(true);
};

const deny = async () => {
  if (props.disabled || expired.value || decision.value) return;
  let comment = '';
  try {
    const { value } = await ElMessageBox.prompt('拒绝后本次写操作不会执行。', '拒绝写操作', {
      confirmButtonText: '确认拒绝',
      cancelButtonText: '取消',
      inputPlaceholder: '拒绝原因（必填）',
      inputType: 'textarea',
      inputValidator: input => (input && input.trim().length > 0 ? true : '拒绝原因必填')
    });
    comment = value?.trim() || '';
  } catch {
    return;
  }
  await submit(false, comment);
};
</script>

<style scoped>
.agent-ui-message {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: min(100%, 760px);
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
  box-shadow: 0 1px 2px rgb(0 0 0 / 4%);
}
.agent-ui-message.is-disabled {
  opacity: 0.78;
}
.agent-ui-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.agent-ui-title,
.agent-ui-meta {
  display: flex;
  align-items: center;
  gap: 7px;
}
.agent-ui-title {
  color: var(--el-text-color-primary);
  font-weight: 650;
}
.agent-ui-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.agent-ui-text {
  margin: 0;
  line-height: 1.7;
}
.flow-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
