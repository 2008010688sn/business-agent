<template>
  <Teleport to="body">
    <div v-if="visible" class="report-fullscreen-overlay" @click.self="emit('close')">
      <div class="report-fullscreen-container">
        <div class="report-fullscreen-header">
          <span class="report-fullscreen-title">
            {{ reportFormat === 'markdown' ? 'Markdown 报告' : 'HTML 报告' }}
          </span>
          <ElButton type="danger" circle class="report-fullscreen-close" @click="emit('close')">
            <ElIcon><Close /></ElIcon>
          </ElButton>
        </div>
        <div class="report-fullscreen-content">
          <SafeMarkdownContent
            v-if="reportFormat === 'markdown'"
            class="md-body report-fullscreen-body"
            :content="content"
            aria-label="分析报告全屏预览"
          />
          <ReportHtmlView v-else :content="content" :source-format="sourceFormat" class="report-fullscreen-body" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { Close } from '@element-plus/icons-vue';
import ReportHtmlView from '@/views/ai-agent/components/run/ReportHtmlView.vue';
import SafeMarkdownContent from '@/views/ai-agent/components/run/SafeMarkdownContent.vue';

defineOptions({ name: 'ReportFullscreenPreview' });

defineProps<{
  visible: boolean;
  reportFormat: 'markdown' | 'html';
  sourceFormat: 'markdown' | 'html';
  content: string;
  markdownOptions: Record<string, any>;
}>();

const emit = defineEmits<{
  close: [];
}>();
</script>

<style scoped>
.report-fullscreen-overlay {
  position: fixed;
  inset: 0;
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(0, 0, 0, 0.7);
}

.report-fullscreen-container {
  display: flex;
  width: 100%;
  max-width: 1200px;
  height: 90vh;
  flex-direction: column;
  overflow: hidden;
  background: var(--el-bg-color);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.3);
}

.report-fullscreen-header {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px;
  background: var(--el-fill-color-extra-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.report-fullscreen-title {
  color: var(--el-text-color-primary);
  font-size: 18px;
  font-weight: 600;
}

.report-fullscreen-close {
  flex-shrink: 0;
}

.report-fullscreen-content {
  flex: 1;
  overflow: auto;
  padding: 24px;
}

.report-fullscreen-body {
  min-height: 100%;
}
</style>
