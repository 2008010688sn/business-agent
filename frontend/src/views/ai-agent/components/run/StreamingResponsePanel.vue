<template>
  <div v-if="streaming || runtimeProgressEvents.length > 0 || hasFinalAnswerOutput" class="streaming-response">
    <div class="streaming-header">
      <span v-if="isLoading" class="loading-orbit" aria-hidden="true"></span>
      <ElIcon v-else class="summary-check" :class="{ 'summary-waiting': isWaitingForInteraction }" aria-hidden="true">
        <Clock v-if="isWaitingForInteraction" />
        <CircleCheck v-else />
      </ElIcon>
      <span>{{ progressHeaderText }}</span>
      <span v-if="isLoading" class="thinking-duration">{{ thinkingDurationText }}</span>
    </div>
    <div v-if="runtimeProgressItems.length > 0" class="runtime-progress-list">
      <div
        v-for="item in runtimeProgressItems"
        :key="item.key"
        class="runtime-progress-item"
        :class="[`runtime-progress-${item.status}`, `runtime-progress-${item.kind}`]"
        :style="{ paddingLeft: `${item.depth * 18}px` }"
      >
        <span class="runtime-progress-dot" aria-hidden="true"></span>
        <span class="runtime-progress-label">{{ item.label }}</span>
        <span class="runtime-progress-status">{{ item.statusLabel }}</span>
        <span v-if="item.durationText" class="runtime-progress-duration">{{ item.durationText }}</span>
      </div>
    </div>
    <div v-if="waitingForAnswerOutput && !isWaitingForInteraction" class="answer-waiting">
      <span class="answer-waiting-text" :aria-label="answerWaitingText">
        <span
          v-for="(char, index) in answerWaitingTextChars"
          :key="`${char}-${index}`"
          aria-hidden="true"
          :style="{ animationDelay: `${index * 0.08}s` }"
        >
          {{ char === ' ' ? '\u00a0' : char }}
        </span>
      </span>
    </div>
    <div class="agent-response-container">
      <SafeLegacyHtmlContent
        v-if="canViewThinking && thinkingNodeBlocks.length > 0"
        class="thinking-preview"
        :content="generateThinkingHtml(thinkingNodeBlocks, !hasFinalAnswerOutput)"
      />
      <template v-for="(nodeBlock, index) in renderedFinalNodeBlocks" :key="index">
        <div v-if="getAgentUiMessage(nodeBlock)" class="flow-readonly-preview" aria-busy="true">
          <SafeMarkdownContent :content="getAgentUiMessage(nodeBlock)?.content?.text || ''" />
        </div>
        <div
          v-else-if="
            nodeBlock.length > 0 &&
            nodeBlock[0].nodeName === 'ReportGeneratorNode' &&
            nodeBlock[0].textType === 'MARK_DOWN'
          "
          class="agent-response-block"
        >
          <div class="agent-response-title">
            {{ nodeBlock[0].nodeName }}
          </div>
          <div class="agent-response-content">
            <SafeMarkdownContent
              v-if="reportFormat === 'markdown'"
              :content="getMarkdownContentFromNode(nodeBlock)"
              aria-label="分析报告"
            />
            <ReportHtmlView v-else :content="getMarkdownContentFromNode(nodeBlock)" source-format="markdown" />
          </div>
        </div>
        <div v-else-if="nodeBlock.length > 0 && nodeBlock[0].textType === 'RESULT_SET'" class="agent-response-block">
          <div class="agent-response-title">
            {{ nodeBlock[0].nodeName }}
          </div>
          <div class="agent-response-content">
            <ResultSetDisplay
              v-if="nodeBlock[0].text"
              v-model:page-size="pageSizeModel"
              :resultData="JSON.parse(nodeBlock[0].text)"
            />
          </div>
        </div>
        <div v-else-if="isMarkdownAnswerNode(nodeBlock)" class="streaming-answer-row">
          <img class="streaming-assistant-avatar" src="@/assets/imgs/logo.svg" alt="AI" />
          <SafeMarkdownContent
            class="streaming-markdown-content"
            :content="getNodeText(nodeBlock)"
            aria-label="AI 回复"
          />
        </div>
        <div v-else-if="nodeBlock[0]?.textType === 'TEXT'" class="streaming-plain-content">
          {{ getNodeText(nodeBlock) }}
        </div>
        <SafeLegacyHtmlContent v-else :content="generateNodeHtml(nodeBlock)" />
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { CircleCheck, Clock } from '@element-plus/icons-vue';
import type { AgentResponse, RuntimeProgressEvent } from '@/views/ai-agent/services/graph';
import ReportHtmlView from '@/views/ai-agent/components/run/ReportHtmlView.vue';
import ResultSetDisplay from '@/views/ai-agent/components/run/ResultSetDisplay.vue';
import SafeLegacyHtmlContent from '@/views/ai-agent/components/run/SafeLegacyHtmlContent.vue';
import SafeMarkdownContent from '@/views/ai-agent/components/run/SafeMarkdownContent.vue';
import { createAgentRunNodeRenderer } from '@/views/ai-agent/utils/runNodeRenderer';
import {
  buildRuntimeProgressTimeline,
  isRuntimeWaitingForInteraction
} from '@/views/ai-agent/utils/runtimeProgressTimeline';
import { parseAgentUiFromResponses } from '@/views/ai-agent/utils/agentUi';

defineOptions({ name: 'StreamingResponsePanel' });

const props = defineProps<{
  streaming: boolean;
  waitingForAnswerOutput: boolean;
  answerWaitingText: string;
  runtimeProgressEvents: RuntimeProgressEvent[];
  canViewThinking: boolean;
  thinkingNodeBlocks: AgentResponse[][];
  hasFinalAnswerOutput: boolean;
  finalNodeBlocks: AgentResponse[][];
  reportFormat: 'markdown' | 'html';
  markdownOptions: Record<string, any>;
  resultSetPageSize: number;
  markdownReportContent: string;
}>();

const emit = defineEmits<{
  'update:resultSetPageSize': [value: number];
}>();

const pageSizeModel = computed({
  get: () => props.resultSetPageSize,
  set: value => emit('update:resultSetPageSize', value)
});

const thinkingSeconds = ref(0);
const runtimeProgressNowMs = ref(Date.now());
let thinkingTimer: ReturnType<typeof setInterval> | undefined;
const renderedFinalNodeBlocks = ref<AgentResponse[][]>([]);
let renderPreviewTimer: ReturnType<typeof setTimeout> | undefined;
let pendingFinalNodeBlocks: AgentResponse[][] = [];
let lastPreviewRenderAt = 0;

const thinkingDurationText = computed(() => {
  const minutes = Math.floor(thinkingSeconds.value / 60);
  const seconds = thinkingSeconds.value % 60;
  return minutes > 0 ? `${minutes}m${seconds}s` : `${seconds}s`;
});

const runtimeProgressItems = computed(() =>
  buildRuntimeProgressTimeline(props.runtimeProgressEvents, runtimeProgressNowMs.value)
);
const isWaitingForInteraction = computed(() => {
  return props.streaming && isRuntimeWaitingForInteraction(props.runtimeProgressEvents);
});
const isLoading = computed(() => props.streaming && !isWaitingForInteraction.value);
const progressHeaderText = computed(() => {
  if (isWaitingForInteraction.value) return '等待用户操作';
  if (props.streaming) return '正在思考中...';
  return runtimeProgressItems.value[0]?.kind === 'group' ? '编排摘要' : '运行摘要';
});

const clearThinkingTimer = () => {
  if (thinkingTimer) {
    clearInterval(thinkingTimer);
    thinkingTimer = undefined;
  }
  thinkingSeconds.value = 0;
  runtimeProgressNowMs.value = Date.now();
};

const clearRenderPreviewTimer = () => {
  if (renderPreviewTimer !== undefined) {
    clearTimeout(renderPreviewTimer);
    renderPreviewTimer = undefined;
  }
};

const commitFinalNodePreview = () => {
  renderedFinalNodeBlocks.value = pendingFinalNodeBlocks.map(block => block.map(response => ({ ...response })));
  lastPreviewRenderAt = Date.now();
  renderPreviewTimer = undefined;
};

const startThinkingTimer = () => {
  if (thinkingTimer) {
    return;
  }
  thinkingSeconds.value = 0;
  runtimeProgressNowMs.value = Date.now();
  thinkingTimer = setInterval(() => {
    thinkingSeconds.value += 1;
    runtimeProgressNowMs.value = Date.now();
  }, 1000);
};

watch(
  isLoading,
  loading => {
    if (loading) {
      startThinkingTimer();
    } else {
      clearThinkingTimer();
    }
  },
  { immediate: true }
);

watch(
  () => props.runtimeProgressEvents,
  () => {
    runtimeProgressNowMs.value = Date.now();
  },
  { deep: true }
);

watch(
  () => props.finalNodeBlocks,
  blocks => {
    pendingFinalNodeBlocks = blocks;
    if (renderPreviewTimer !== undefined) {
      return;
    }
    const waitTime = Math.max(0, 80 - (Date.now() - lastPreviewRenderAt));
    if (waitTime === 0) {
      commitFinalNodePreview();
      return;
    }
    renderPreviewTimer = setTimeout(commitFinalNodePreview, waitTime);
  },
  { deep: true, immediate: true }
);

onBeforeUnmount(() => {
  clearThinkingTimer();
  clearRenderPreviewTimer();
});

const answerWaitingTextChars = computed(() => Array.from(props.answerWaitingText));

const nodeRenderer = createAgentRunNodeRenderer({
  getResultSetPageSize: () => pageSizeModel.value,
  getMarkdownReportContent: () => props.markdownReportContent
});

const { generateThinkingHtml, generateNodeHtml, getMarkdownContentFromNode } = nodeRenderer;

const getAgentUiMessage = (nodeBlock: AgentResponse[]) => parseAgentUiFromResponses(nodeBlock);

const getNodeText = (nodeBlock: AgentResponse[]) => nodeBlock.map(response => response.text || '').join('');

const isMarkdownAnswerNode = (nodeBlock: AgentResponse[]) => {
  const firstNode = nodeBlock[0];
  return (
    firstNode?.metadata?.contentFormat === 'markdown' ||
    firstNode?.nodeName === 'AgentScopeRuntime' ||
    firstNode?.nodeName === 'planner-reasoning'
  );
};
</script>

<style scoped>
.streaming-response {
  color: var(--el-text-color-primary);
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
  margin-bottom: 16px;
  padding: 4px 0;
  scroll-margin-bottom: var(--agent-run-input-reserve);
  overflow: hidden;
}

.streaming-header {
  display: flex;
  align-items: center;
  gap: 8px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  margin-bottom: 10px;
  padding-bottom: 8px;
}

.streaming-header span {
  color: var(--el-color-primary);
  font-weight: 500;
}

.streaming-header .thinking-duration {
  color: color-mix(in srgb, var(--el-color-primary) 82%, var(--el-text-color-secondary));
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.loading-orbit {
  width: 18px;
  height: 18px;
  border: 2px solid var(--el-border-color-light);
  border-top-color: var(--el-color-primary);
  border-radius: 50%;
  animation: spin 0.85s linear infinite;
  flex: 0 0 auto;
}

.summary-check {
  color: var(--el-color-success);
  font-size: 18px;
}

.summary-check.summary-waiting {
  color: var(--el-color-warning);
}

.runtime-progress-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0 0 10px;
  padding: 2px 0 8px;
  border-bottom: 1px dashed var(--el-border-color-lighter);
}

.runtime-progress-item {
  display: grid;
  grid-template-columns: 10px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.runtime-progress-group {
  min-height: 28px;
}

.runtime-progress-group .runtime-progress-label {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.runtime-progress-dot {
  position: relative;
  z-index: 1;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--el-color-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-primary) 12%, transparent);
}

.runtime-progress-group .runtime-progress-dot {
  width: 9px;
  height: 9px;
}

.runtime-progress-success .runtime-progress-dot {
  background: var(--el-color-success);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-success) 14%, transparent);
}

.runtime-progress-failed .runtime-progress-dot {
  background: var(--el-color-danger);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-danger) 14%, transparent);
}

.runtime-progress-cancelled .runtime-progress-dot {
  background: var(--el-color-info);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--el-color-info) 14%, transparent);
}

.runtime-progress-label {
  overflow: hidden;
  min-width: 0;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-progress-status,
.runtime-progress-duration {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.answer-waiting {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 60px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
}

.answer-waiting-text {
  display: inline-flex;
  align-items: center;
  color: var(--el-text-color-regular);
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.answer-waiting-text span {
  display: inline-block;
  color: var(--el-color-primary);
  text-shadow: 0 0 12px color-mix(in srgb, var(--el-color-primary) 22%, transparent);
  animation: ai-agent-text-wave 1.2s cubic-bezier(0.16, 1, 0.3, 1) infinite;
}

.agent-response-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.flow-readonly-preview {
  width: min(100%, 760px);
  padding: 10px 14px;
  border-left: 3px solid var(--el-color-primary-light-5);
  background: var(--el-fill-color-extra-light);
}

.streaming-answer-row {
  display: flex;
  width: min(88%, 760px);
  max-width: 100%;
  align-items: flex-start;
  gap: var(--agent-run-message-gap);
}

.streaming-assistant-avatar {
  display: block;
  flex: 0 0 var(--agent-run-message-avatar-size);
  width: var(--agent-run-message-avatar-size);
  height: var(--agent-run-message-avatar-size);
  border: 1px solid var(--el-border-color-light);
  border-radius: 50%;
  background: var(--el-bg-color);
  object-fit: cover;
}

.streaming-markdown-content {
  min-width: 0;
  flex: 1;
  padding: 3px 2px 3px 14px;
  border-left: 2px solid var(--el-color-primary-light-7);
}

.streaming-plain-content {
  width: min(88%, 760px);
  padding: 10px 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
  line-height: 1.65;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.thinking-preview {
  max-width: min(100%, 760px);
}

.agent-response-block {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  overflow: hidden;
}

.agent-response-title {
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-extra-light);
  border-bottom: 1px solid var(--el-border-color-light);
  padding: 10px 14px;
  font-weight: 600;
}

.agent-response-content {
  padding: 14px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes ai-agent-text-wave {
  0%,
  100% {
    opacity: 0.58;
    transform: translateY(0);
  }

  30% {
    opacity: 1;
    transform: translateY(-4px);
  }

  60% {
    opacity: 0.78;
    transform: translateY(2px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .loading-orbit,
  .answer-waiting-text span {
    animation: none;
  }
}

@media (max-width: 768px) {
  .streaming-answer-row,
  .streaming-plain-content {
    width: 100%;
  }

  .streaming-assistant-avatar {
    flex-basis: 28px;
    width: 28px;
    height: 28px;
  }
}
</style>
