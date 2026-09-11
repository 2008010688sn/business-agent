<template>
  <div v-if="suggestedReplies" class="suggested-replies" :class="{ disabled }">
    <div v-if="suggestedReplies.title" class="suggested-reply-header">
      <span class="suggested-reply-header-title">{{ suggestedReplies.title }}</span>
      <span v-if="suggestedReplies.source === 'confirmation'" class="suggested-reply-confirmation-tag">待确认</span>
    </div>
    <div v-if="suggestedReplies.prompt" class="suggested-reply-prompt">
      {{ suggestedReplies.prompt }}
    </div>
    <div v-if="suggestedReplies.summary" class="suggested-reply-plan-summary">
      <span class="suggested-reply-plan-summary-label">{{ summaryLabel }}</span>
      <span>{{ suggestedReplies.summary }}</span>
    </div>
    <ol v-if="suggestedReplies.planSteps?.length" class="suggested-reply-plan-steps">
      <li v-for="(step, index) in suggestedReplies.planSteps" :key="`${index}-${step.name}`">
        <span class="suggested-reply-plan-step-name">{{ index + 1 }}. {{ step.name }}</span>
        <span v-if="step.description" class="suggested-reply-plan-step-description">{{ step.description }}</span>
        <span v-if="stepRiskLabel(step.riskLevel)" class="suggested-reply-plan-step-risk">
          {{ stepRiskLabel(step.riskLevel) }}
        </span>
      </li>
    </ol>
    <div v-if="riskLevelLabel || expiryLabel" class="suggested-reply-metadata">
      <span v-if="riskLevelLabel">风险：{{ riskLevelLabel }}</span>
      <span v-if="expiryLabel">有效期至（北京时间）：{{ expiryLabel }}</span>
    </div>
    <div v-for="group in replyGroups" :key="group.groupId" class="suggested-reply-group">
      <div class="suggested-reply-title">{{ group.title }}</div>
      <div class="suggested-reply-options" role="group" :aria-label="group.title">
        <button
          v-for="option in group.options"
          :key="option.optionId"
          type="button"
          class="suggested-reply-chip"
          :class="{ selected: isSelected(group, option) }"
          :aria-pressed="isSelected(group, option)"
          :disabled="disabled"
          :title="option.summary || option.label"
          @click="selectOption(group, option)"
        >
          <span class="suggested-reply-label">{{ option.label }}</span>
          <span v-if="option.summary" class="suggested-reply-summary">{{ option.summary }}</span>
        </button>
      </div>
    </div>
    <ElInput
      v-if="suggestedReplies.allowFreeText"
      v-model="freeText"
      type="textarea"
      :rows="2"
      :maxlength="2000"
      show-word-limit
      resize="none"
      :disabled="disabled"
      placeholder="也可以直接补充业务信息"
      aria-label="补充业务信息"
    />
    <div class="suggested-reply-actions">
      <ElButton size="small" type="primary" :disabled="!canSubmit" @click="submitSelected">
        {{ submitLabel }}
      </ElButton>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type {
  SuggestedReplies,
  SuggestedReplyGroup,
  SuggestedReplyOption,
  SuggestedReplySubmission
} from '@/views/ai-agent/utils/suggestedReplies';
import { buildSuggestedReplySubmission } from '@/views/ai-agent/utils/suggestedReplies';

const props = withDefaults(
  defineProps<{
    suggestedReplies: SuggestedReplies | null;
    disabled?: boolean;
  }>(),
  {
    disabled: false
  }
);

const emit = defineEmits<{
  submit: [submission: SuggestedReplySubmission];
}>();

const selectedOptionIds = ref<Record<string, string[]>>({});
const freeText = ref('');
const replyGroups = computed(() => props.suggestedReplies?.groups ?? []);
const submitLabel = computed(() => (props.suggestedReplies?.source === 'confirmation' ? '确认提交' : '提交'));
const summaryLabel = computed(() => (props.suggestedReplies?.source === 'confirmation' ? '执行计划：' : '说明：'));
const riskLabels: Record<string, string> = {
  READ_ONLY: '只读',
  WRITE: '会变更业务数据',
  FLOW: '流程操作',
  HIGH: '高风险'
};
const stepRiskLabel = (riskLevel?: string) => {
  const normalized = riskLevel?.trim();
  if (!normalized) return '';
  return riskLabels[normalized.toUpperCase()] || '需确认';
};
const riskLevelLabel = computed(() => {
  const riskLevel = props.suggestedReplies?.riskLevel;
  if (!riskLevel) return '';
  return stepRiskLabel(riskLevel);
});
const expiryLabel = computed(() => {
  const expiresAt = props.suggestedReplies?.expiresAt?.trim();
  if (!expiresAt) return '';
  const date = new Date(expiresAt);
  if (Number.isNaN(date.getTime())) return expiresAt;
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date);
});

watch(
  () => props.suggestedReplies,
  () => {
    selectedOptionIds.value = {};
    freeText.value = '';
  },
  { immediate: true }
);

const isSelected = (group: SuggestedReplyGroup, option: SuggestedReplyOption) => {
  return selectedOptionIds.value[group.groupId]?.includes(option.optionId) ?? false;
};

const selectOption = (group: SuggestedReplyGroup, option: SuggestedReplyOption) => {
  if (props.disabled) {
    return;
  }
  const selected = selectedOptionIds.value[group.groupId] ?? [];
  const nextSelected = selected.includes(option.optionId)
    ? selected.filter(id => id !== option.optionId)
    : group.maxSelect === 1
      ? [option.optionId]
      : [...selected, option.optionId].slice(0, group.maxSelect);
  selectedOptionIds.value = { ...selectedOptionIds.value, [group.groupId]: nextSelected };
};

const selectedOptions = computed(() => {
  return replyGroups.value.flatMap(group => {
    const selectedIds = new Set(selectedOptionIds.value[group.groupId] ?? []);
    return group.options.filter(option => selectedIds.has(option.optionId));
  });
});

const missingRequiredSelection = computed(() => {
  if (props.suggestedReplies?.allowFreeText && freeText.value.trim()) return false;
  return replyGroups.value.some(group => group.required && !selectedOptionIds.value[group.groupId]?.length);
});

const canSubmit = computed(() => {
  const hasAnswer = selectedOptions.value.length > 0 || Boolean(freeText.value.trim());
  return !props.disabled && hasAnswer && !missingRequiredSelection.value;
});

const submitSelected = () => {
  if (!canSubmit.value) {
    return;
  }
  if (!props.suggestedReplies) {
    return;
  }
  const submission = buildSuggestedReplySubmission(props.suggestedReplies, selectedOptions.value, freeText.value);
  if (submission) {
    emit('submit', submission);
    return;
  }
  if (props.suggestedReplies.clarificationId) {
    ElMessage.error('该问题已失效，请刷新后重试');
  }
};
</script>

<style scoped>
.suggested-replies {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: min(100%, 640px);
  margin-top: 8px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.suggested-replies.disabled {
  opacity: 0.72;
}

.suggested-reply-header,
.suggested-reply-metadata {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}

.suggested-reply-header-title {
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.45;
}

.suggested-reply-confirmation-tag {
  padding: 1px 6px;
  color: var(--el-color-warning-dark-2);
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 4px;
  background: var(--el-color-warning-light-9);
  font-size: 12px;
  line-height: 1.4;
}

.suggested-reply-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.suggested-reply-prompt {
  color: var(--el-text-color-primary);
  font-size: 13px;
  line-height: 1.55;
}

.suggested-reply-plan-summary {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 6px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.suggested-reply-plan-summary-label {
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.suggested-reply-plan-steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding-left: 20px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.5;
}

.suggested-reply-plan-steps li {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 4px 8px;
  overflow-wrap: anywhere;
}

.suggested-reply-plan-step-name {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.suggested-reply-plan-step-description {
  color: var(--el-text-color-secondary);
}

.suggested-reply-plan-step-risk {
  color: var(--el-color-warning-dark-2);
  font-size: 12px;
}

.suggested-reply-metadata {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.45;
  overflow-wrap: anywhere;
}

.suggested-reply-title {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.4;
}

.suggested-reply-options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.suggested-reply-chip {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  max-width: 100%;
  min-height: 30px;
  padding: 5px 11px;
  color: var(--el-text-color-regular);
  border: 1px solid var(--el-border-color);
  border-radius: 6px;
  background: var(--el-bg-color);
  cursor: pointer;
  transition:
    color 0.16s ease,
    border-color 0.16s ease,
    background-color 0.16s ease,
    box-shadow 0.16s ease;
}

.suggested-reply-chip:hover,
.suggested-reply-chip:focus-visible {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  outline: none;
}

.suggested-reply-chip:focus-visible {
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
}

.suggested-reply-chip.selected {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.suggested-reply-chip:disabled {
  cursor: not-allowed;
}

.suggested-reply-label {
  display: block;
  overflow: hidden;
  max-width: 100%;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.suggested-reply-summary {
  display: block;
  overflow: hidden;
  max-width: 280px;
  color: var(--el-text-color-secondary);
  font-size: 11px;
  line-height: 1.35;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.suggested-reply-actions {
  display: flex;
  justify-content: flex-end;
}
</style>
