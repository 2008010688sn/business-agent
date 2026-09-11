<template>
  <!-- eslint-disable vue/no-v-html -->
  <div
    ref="contentRef"
    class="safe-markdown-content"
    :aria-label="ariaLabel"
    @click="handleContentClick"
    v-html="renderedContent"
  ></div>
  <!-- eslint-enable vue/no-v-html -->
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { writeTextToClipboard } from '@/views/ai-agent/utils/runMessage';
import { hydrateReportCharts } from '@/views/ai-agent/utils/reportRenderer';
import { renderSafeMarkdown } from '@/views/ai-agent/utils/safeMarkdown';

defineOptions({ name: 'SafeMarkdownContent' });

const props = withDefaults(
  defineProps<{
    content?: string | null;
    ariaLabel?: string;
  }>(),
  {
    content: '',
    ariaLabel: 'AI 回复'
  }
);

const contentRef = ref<HTMLElement | null>(null);
const renderedContent = computed(() => renderSafeMarkdown(props.content));
let copiedButton: HTMLButtonElement | null = null;
let copiedButtonTimer: number | null = null;
let chartRenderVersion = 0;
let disposeReportCharts: (() => void) | null = null;

const resetCopiedButton = () => {
  if (!copiedButton) {
    return;
  }
  copiedButton.textContent = '复制';
  copiedButton.setAttribute('aria-label', '复制代码');
  copiedButton.classList.remove('copied', 'copy-failed');
  copiedButton = null;
  copiedButtonTimer = null;
};

const showCopyResult = (button: HTMLButtonElement, text: string, failed = false) => {
  if (copiedButtonTimer !== null) {
    window.clearTimeout(copiedButtonTimer);
  }
  if (copiedButton && copiedButton !== button) {
    resetCopiedButton();
  }
  copiedButton = button;
  button.textContent = text;
  button.setAttribute('aria-label', text);
  button.classList.toggle('copied', !failed);
  button.classList.toggle('copy-failed', failed);
  copiedButtonTimer = window.setTimeout(resetCopiedButton, failed ? 2200 : 1600);
};

const handleContentClick = async (event: MouseEvent) => {
  const target = event.target as Element | null;
  const button = target?.closest<HTMLButtonElement>('.safe-markdown-copy-button');
  if (!button || !contentRef.value?.contains(button)) {
    return;
  }
  const code = button.closest('.safe-markdown-code-block')?.querySelector('code');
  if (!code?.textContent) {
    return;
  }
  try {
    await writeTextToClipboard(code.textContent);
    showCopyResult(button, '已复制');
  } catch (error) {
    console.error('复制代码失败:', error);
    showCopyResult(button, '复制失败', true);
  }
};

const disposeCharts = () => {
  disposeReportCharts?.();
  disposeReportCharts = null;
};

const renderEchartsBlocks = async () => {
  const currentVersion = ++chartRenderVersion;
  await nextTick();
  if (currentVersion !== chartRenderVersion) {
    return;
  }
  disposeCharts();
  const container = contentRef.value;
  if (!container) {
    return;
  }
  const dispose = await hydrateReportCharts(container);
  if (currentVersion !== chartRenderVersion) {
    dispose();
    return;
  }
  disposeReportCharts = dispose;
};

watch(
  renderedContent,
  () => {
    renderEchartsBlocks().catch(error => console.warn('ECharts 图表渲染调度失败:', error));
  },
  { immediate: true, flush: 'post' }
);

onMounted(() => {
  renderEchartsBlocks().catch(error => console.warn('ECharts 图表渲染调度失败:', error));
});

onBeforeUnmount(() => {
  if (copiedButtonTimer !== null) {
    window.clearTimeout(copiedButtonTimer);
  }
  chartRenderVersion += 1;
  disposeCharts();
});
</script>

<style scoped>
.safe-markdown-content {
  min-width: 0;
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 1.75;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.safe-markdown-content :deep(> :first-child) {
  margin-top: 0;
}

.safe-markdown-content :deep(> :last-child) {
  margin-bottom: 0;
}

.safe-markdown-content :deep(h1),
.safe-markdown-content :deep(h2),
.safe-markdown-content :deep(h3),
.safe-markdown-content :deep(h4),
.safe-markdown-content :deep(h5),
.safe-markdown-content :deep(h6) {
  margin: 20px 0 10px;
  color: #183627;
  font-weight: 650;
  line-height: 1.35;
}

.safe-markdown-content :deep(h1) {
  font-size: 20px;
}

.safe-markdown-content :deep(h2) {
  font-size: 18px;
}

.safe-markdown-content :deep(h3) {
  font-size: 16px;
}

.safe-markdown-content :deep(h4),
.safe-markdown-content :deep(h5),
.safe-markdown-content :deep(h6) {
  font-size: 14px;
}

.safe-markdown-content :deep(p),
.safe-markdown-content :deep(ul),
.safe-markdown-content :deep(ol),
.safe-markdown-content :deep(blockquote),
.safe-markdown-content :deep(table),
.safe-markdown-content :deep(pre) {
  margin: 10px 0;
}

.safe-markdown-content :deep(ul),
.safe-markdown-content :deep(ol) {
  padding-left: 22px;
}

.safe-markdown-content :deep(li + li) {
  margin-top: 4px;
}

.safe-markdown-content :deep(blockquote) {
  margin-left: 0;
  padding: 8px 12px;
  border-left: 3px solid #71b68d;
  background: #f5faf6;
  color: var(--el-text-color-regular);
}

.safe-markdown-content :deep(a) {
  color: var(--el-color-primary);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.safe-markdown-content :deep(a:hover) {
  color: var(--el-color-primary-light-3);
}

.safe-markdown-content :deep(code) {
  padding: 1px 5px;
  border-radius: 4px;
  background: #eef5f0;
  color: #275238;
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 0.92em;
}

.safe-markdown-content :deep(.safe-markdown-code-block) {
  overflow: hidden;
  margin: 12px 0;
  border: 1px solid #d9e7dc;
  border-radius: 8px;
  background: #f8fbf8;
}

.safe-markdown-content :deep(.safe-markdown-code-header) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 34px;
  padding: 0 8px 0 12px;
  border-bottom: 1px solid #e2ece4;
  color: #537260;
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 12px;
}

.safe-markdown-content :deep(.safe-markdown-copy-button) {
  min-width: 52px;
  min-height: 26px;
  border: 1px solid #c8dbce;
  border-radius: 5px;
  background: #fff;
  color: #315b40;
  cursor: pointer;
  font: inherit;
}

.safe-markdown-content :deep(.safe-markdown-copy-button:hover) {
  border-color: #5aa878;
  background: #f0f8f2;
}

.safe-markdown-content :deep(.safe-markdown-copy-button.copied) {
  border-color: #4b9c6b;
  background: #e9f7ed;
  color: #1b6d3a;
}

.safe-markdown-content :deep(.safe-markdown-copy-button.copy-failed) {
  border-color: var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}

.safe-markdown-content :deep(.safe-markdown-echarts) {
  width: 100%;
  height: 360px;
  min-height: 280px;
  margin: 14px 0;
  overflow: hidden;
  border: 1px solid #d9e7dc;
  border-radius: 8px;
  background: #fff;
  font-size: 0;
}

.safe-markdown-content :deep(.safe-markdown-echarts-error) {
  display: flex;
  align-items: center;
  justify-content: center;
  height: auto;
  min-height: 120px;
  padding: 16px;
  border-style: dashed;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  font-size: 13px;
  line-height: 1.6;
  text-align: center;
}

.safe-markdown-content :deep(pre) {
  max-width: 100%;
  overflow-x: auto;
  padding: 12px;
  background: transparent;
}

.safe-markdown-content :deep(pre code) {
  padding: 0;
  background: transparent;
  color: #244630;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre;
}

.safe-markdown-content :deep(.safe-markdown-table-wrapper) {
  width: 100%;
  max-width: 100%;
  overflow-x: auto;
  margin: 10px 0;
}

.safe-markdown-content :deep(table) {
  width: 100%;
  margin: 0;
  border-collapse: collapse;
  border-spacing: 0;
}

.safe-markdown-content :deep(th),
.safe-markdown-content :deep(td) {
  padding: 8px 10px;
  border: 1px solid #dce8df;
  overflow-wrap: break-word;
  text-align: left;
  vertical-align: top;
  word-break: normal;
}

.safe-markdown-content :deep(th) {
  background: #f3f8f4;
  color: #315b40;
  font-weight: 650;
}

.safe-markdown-content :deep(tr:nth-child(even) td) {
  background: #fbfdfb;
}

@media (max-width: 640px) {
  .safe-markdown-content {
    font-size: 14px;
  }

  .safe-markdown-content :deep(h1) {
    font-size: 18px;
  }

  .safe-markdown-content :deep(h2) {
    font-size: 16px;
  }

  .safe-markdown-content :deep(.safe-markdown-echarts) {
    height: 300px;
  }

  .safe-markdown-content :deep(th),
  .safe-markdown-content :deep(td) {
    min-width: 96px;
  }
}
</style>
