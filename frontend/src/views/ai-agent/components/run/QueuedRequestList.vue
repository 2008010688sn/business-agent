<template>
  <div v-if="requests.length > 0" class="queued-message-list">
    <div class="queued-message-list-header">
      <span>待执行 {{ requests.length }} 条</span>
      <ElButton link type="danger" @click="emit('clear')">清空待执行</ElButton>
    </div>
    <div v-for="queued in requests" :key="queued.id" class="queued-message-item">
      <span class="queued-message-content">{{ queued.content }}</span>
      <div class="queued-message-actions">
        <ElButton
          text
          bg
          circle
          size="small"
          class="queued-message-guide-button"
          :class="{ active: queued.request.humanFeedback }"
          :title="queued.request.humanFeedback ? '已设为引导补充' : '引导补充'"
          @click="emit('guide', queued.id)"
        >
          <ElIcon><Top /></ElIcon>
        </ElButton>
        <ElButton
          text
          bg
          circle
          size="small"
          class="queued-message-cancel-button"
          title="取消"
          @click="emit('remove', queued.id)"
        >
          <ElIcon><Close /></ElIcon>
        </ElButton>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { Close, Top } from '@element-plus/icons-vue';
import type { QueuedRequestState } from '@/views/ai-agent/services/sessionStateManager';

defineOptions({ name: 'QueuedRequestList' });

defineProps<{
  requests: QueuedRequestState[];
}>();

const emit = defineEmits<{
  clear: [];
  guide: [id: string];
  remove: [id: string];
}>();
</script>

<style scoped>
.queued-message-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  padding: 10px 12px;
  box-shadow: none;
}

.queued-message-list-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
}

.queued-message-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.queued-message-content {
  flex: 1;
  min-width: 0;
  word-break: break-word;
}

.queued-message-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 4px;
}

.queued-message-guide-button {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.queued-message-guide-button.active {
  color: #ffffff;
  background: var(--el-color-primary);
}

.queued-message-cancel-button {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}
</style>
