<template>
  <ElPopover placement="top-end" trigger="click" :width="320" popper-class="context-usage-popover" @show="emit('show')">
    <template #reference>
      <ElButton text bg class="context-usage-trigger" :loading="loading" title="上下文用量">
        <ElIcon><MenuIcon /></ElIcon>
        <span class="context-usage-trigger-text">{{ usagePercent }}%</span>
      </ElButton>
    </template>
    <div class="context-usage-panel">
      <div class="context-usage-panel-header">
        <span>上下文用量</span>
        <strong>{{ usagePercent }}%</strong>
      </div>
      <div v-if="error" class="context-usage-error">
        {{ error }}
      </div>
      <div v-else class="context-usage-detail-list">
        <div class="context-usage-summary">
          <span>{{ usageLabel }}</span>
          <div class="context-usage-bar" aria-hidden="true">
            <span :style="{ width: `${usagePercent}%` }"></span>
          </div>
        </div>
        <div class="context-usage-detail-row">
          <span>已用 Token</span>
          <strong>{{ usedTokenLabel }}</strong>
        </div>
        <div class="context-usage-detail-row">
          <span>上下文上限</span>
          <strong>{{ limitTokenLabel }}</strong>
        </div>
        <div class="context-usage-detail-row">
          <span>消息数</span>
          <strong>{{ messageCountLabel }}</strong>
        </div>
        <div class="context-usage-detail-row">
          <span>模型</span>
          <strong>{{ resolvedModelLabel }}</strong>
        </div>
        <div class="context-usage-note">
          {{ usageAccuracyLabel }}
        </div>
        <div v-if="compressionSummary" class="context-compression-summary">
          {{ compressionSummary }}
        </div>
        <ElButton
          native-type="button"
          class="context-compress-action"
          :disabled="!canCompress || loading || compressing"
          @click="emit('compress')"
        >
          {{ compressing ? '压缩中...' : '手动压缩' }}
        </ElButton>
      </div>
    </div>
  </ElPopover>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Menu as MenuIcon } from '@element-plus/icons-vue';
import type { SessionContextUsage } from '@/views/ai-agent/services/chat';
import { formatTokenCount } from '@/views/ai-agent/utils/runFormatters';

defineOptions({ name: 'ContextUsagePopover' });

const props = defineProps<{
  loading: boolean;
  error: string;
  usage: SessionContextUsage | null;
  currentModelLabel: string;
  compressionSummary: string;
  canCompress: boolean;
  compressing: boolean;
}>();

const emit = defineEmits<{
  show: [];
  compress: [];
}>();

const usagePercent = computed(() => {
  const ratio = props.usage?.usageRatio ?? 0;
  return Math.max(0, Math.min(100, Math.round(ratio * 100)));
});

const formatUsageTokenCount = (value?: number, fallback = '暂无统计') => {
  return value === undefined ? fallback : formatTokenCount(value);
};

const usedTokenLabel = computed(() => formatUsageTokenCount(props.usage?.usedTokens));

const limitTokenLabel = computed(() => formatUsageTokenCount(props.usage?.limitTokens, '未配置'));

const messageCountLabel = computed(() => {
  return props.usage?.messageCount === undefined ? '暂无统计' : String(props.usage.messageCount);
});

const hasUsageStats = computed(() => {
  return (
    props.usage?.usedTokens !== undefined ||
    props.usage?.limitTokens !== undefined ||
    props.usage?.messageCount !== undefined ||
    props.usage?.usageRatio !== undefined
  );
});

const usageLabel = computed(() => {
  if (!props.usage && props.loading) {
    return '正在获取上下文用量...';
  }
  if (!props.usage) {
    return '暂无上下文用量数据';
  }
  return `${usedTokenLabel.value} / ${limitTokenLabel.value}`;
});

const COMPRESS_SCHEME_NOTE = '上下文由运行时自动压缩，也可手动立即压缩，P0 规则不会丢掉';

const usageAccuracyLabel = computed(() => {
  if (!props.usage || !hasUsageStats.value) {
    return `暂无上下文用量数据。${COMPRESS_SCHEME_NOTE}`;
  }
  const accuracy = props.usage.estimated ? '估算值，基于当前可见消息' : '精确值';
  return `${accuracy}。${COMPRESS_SCHEME_NOTE}`;
});

const resolvedModelLabel = computed(() => props.usage?.modelName || props.currentModelLabel || '未选择模型');
</script>

<style scoped>
.context-usage-trigger {
  position: relative;
  display: inline-flex;
  width: 42px;
  height: 36px;
  align-items: center;
  justify-content: center;
  overflow: visible;
  padding: 8px 0 0;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 7px;
  box-shadow: none;
}

.context-usage-trigger:hover,
.context-usage-trigger:focus,
.context-usage-trigger.active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.context-usage-trigger :deep(span) {
  min-width: 0;
}

.context-usage-trigger-text {
  position: absolute;
  top: 1px;
  right: 1px;
  min-width: 22px;
  height: 16px;
  padding: 0 4px;
  border-radius: 999px;
  font-size: 8px;
  line-height: 16px;
  text-align: center;
  transform: translateY(-50%);
  box-shadow: 0 3px 8px rgba(36, 90, 45, 0.22);
}

.context-usage-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border-color: var(--el-border-color-light);
  box-shadow: none;
}

.context-usage-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
  font-weight: 600;
}

.context-usage-detail-list {
  display: flex;
  flex-direction: column;
  gap: 9px;
}

.context-usage-summary {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
}

.context-usage-bar {
  width: 100%;
  height: 4px;
  overflow: hidden;
  background: var(--el-border-color-lighter);
  border-radius: 999px;
}

.context-usage-bar span {
  display: block;
  height: 100%;
  background: var(--el-color-primary);
  border-radius: inherit;
  transition: width 0.2s ease;
}

.context-usage-detail-row {
  display: grid;
  grid-template-columns: 96px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
  font-size: 12px;
}

.context-usage-detail-row span {
  color: var(--el-text-color-secondary);
}

.context-usage-detail-row strong {
  min-width: 0;
  overflow: hidden;
  color: var(--el-text-color-primary);
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.context-usage-note,
.context-usage-error {
  padding-top: 9px;
  color: var(--el-text-color-secondary);
  border-top: 1px solid var(--el-border-color-lighter);
  font-size: 12px;
  line-height: 1.5;
}

.context-compression-summary {
  padding: 8px 10px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
}

.context-compress-action {
  width: 100%;
  min-height: 32px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.context-compress-action:hover:not(:disabled) {
  background: var(--el-color-primary-light-8);
  border-color: var(--el-color-primary);
}

.context-compress-action:disabled {
  cursor: not-allowed;
  opacity: 0.58;
}

:global(.context-usage-popover) {
  padding: 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: var(--el-box-shadow-light);
}
</style>
