<template>
  <div
    ref="chatContainer"
    class="chat-container"
    :class="{ 'has-input-area': state.currentSession }"
    @scroll="handleChatScroll"
  >
    <AgentRunEmptyState v-if="!state.currentSession" />
    <div v-else class="messages-area">
      <AgentMessageList
        v-model:result-set-page-size="resultSetPageSizeModel"
        :messages="state.messages"
        :markdown-options="reportState.markdownOptions"
        :is-submitting-message="state.isSubmittingMessage"
        :is-streaming="state.isStreaming"
        :has-selected-images="state.hasSelectedImages"
        :agent-id="state.agentId ?? ''"
        :professional-loading-request-id="reportState.professionalLoadingRequestId"
        @resend-question="actions.resendQuestion"
        @submit-suggested-reply="actions.submitSuggestedReply"
        @download-report="actions.downloadReport"
        @professional-report="actions.generateProfessionalReport"
        @flow-action="actions.submitFlowAction"
      />

      <StreamingResponsePanel
        v-model:result-set-page-size="resultSetPageSizeModel"
        :streaming="state.isStreaming"
        :waiting-for-answer-output="streamState.waitingForAnswerOutput"
        :answer-waiting-text="streamState.answerWaitingText"
        :runtime-progress-events="streamState.runtimeProgressEvents"
        :can-view-thinking="state.canViewThinking"
        :thinking-node-blocks="streamState.thinkingNodeBlocks"
        :has-final-answer-output="streamState.hasFinalAnswerOutput"
        :final-node-blocks="streamState.finalNodeBlocks"
        :report-format="reportFormatModel"
        :markdown-options="reportState.markdownOptions"
        :markdown-report-content="reportState.markdownReportContent"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import type { ChatMessage, ChatSession } from '@/views/ai-agent/services/chat';
import type { AgentResponse, RuntimeProgressEvent } from '@/views/ai-agent/services/graph';
import AgentMessageList from '@/views/ai-agent/components/run/AgentMessageList.vue';
import AgentRunEmptyState from '@/views/ai-agent/components/run/AgentRunEmptyState.vue';
import StreamingResponsePanel from '@/views/ai-agent/components/run/StreamingResponsePanel.vue';
import type { AgentUiActionRequest } from '@/views/ai-agent/utils/agentUi';
import type { SuggestedReplySubmission } from '@/views/ai-agent/utils/suggestedReplies';

defineOptions({ name: 'AgentRunConversationPanel' });

declare global {
  interface Window {
    copyTextToClipboard?: (btn: HTMLElement) => void;
    handleResultSetPagination?: (btn: HTMLElement, direction: 'prev' | 'next') => void;
  }
}

export interface AgentRunConversationState {
  agentId: string | undefined;
  currentSession: ChatSession | null;
  messages: ChatMessage[];
  isSubmittingMessage: boolean;
  hasSelectedImages: boolean;
  canViewThinking: boolean;
  isStreaming: boolean;
}

export interface AgentRunConversationStreamState {
  waitingForAnswerOutput: boolean;
  answerWaitingText: string;
  runtimeProgressEvents: RuntimeProgressEvent[];
  thinkingNodeBlocks: AgentResponse[][];
  hasFinalAnswerOutput: boolean;
  finalNodeBlocks: AgentResponse[][];
}

export interface AgentRunConversationReportState {
  markdownOptions: Record<string, any>;
  markdownReportContent: string;
  professionalLoadingRequestId?: string;
}

export interface AgentRunConversationActions {
  resendQuestion: (question: string) => void | Promise<void>;
  submitSuggestedReply: (submission: SuggestedReplySubmission) => void | Promise<void>;
  downloadReport: (message: ChatMessage, format?: 'markdown' | 'html') => void | Promise<void>;
  generateProfessionalReport: (message: ChatMessage) => void | Promise<void>;
  submitFlowAction: (request: AgentUiActionRequest) => void | Promise<void>;
}

const props = defineProps<{
  state: AgentRunConversationState;
  streamState: AgentRunConversationStreamState;
  reportState: AgentRunConversationReportState;
  actions: AgentRunConversationActions;
  resultSetPageSize: number;
  reportFormat: 'markdown' | 'html';
}>();

const emit = defineEmits<{
  'update:resultSetPageSize': [value: number];
  'update:reportFormat': [value: 'markdown' | 'html'];
  scrollToLatestChange: [value: boolean];
}>();

const chatContainer = ref<HTMLElement | null>(null);
const scrollBottomThreshold = 80;

const resultSetPageSizeModel = computed({
  get: () => props.resultSetPageSize,
  set: value => emit('update:resultSetPageSize', value)
});

const reportFormatModel = computed({
  get: () => props.reportFormat,
  set: value => emit('update:reportFormat', value)
});

const isChatNearBottom = () => {
  const container = chatContainer.value;
  if (!container) {
    return true;
  }
  return container.scrollHeight - container.scrollTop - container.clientHeight <= scrollBottomThreshold;
};

const updateScrollToLatestVisibility = () => {
  const container = chatContainer.value;
  if (!container || !props.state.currentSession) {
    emit('scrollToLatestChange', false);
    return;
  }
  const hasScrollableContent = container.scrollHeight > container.clientHeight + 4;
  emit('scrollToLatestChange', hasScrollableContent && !isChatNearBottom());
};

const handleChatScroll = () => {
  updateScrollToLatestVisibility();
};

const scrollToBottom = (smooth = false, force = false) => {
  const shouldScroll = force || smooth || isChatNearBottom();
  nextTick().then(() => {
    const container = chatContainer.value;
    if (!container) {
      emit('scrollToLatestChange', false);
      return;
    }
    if (shouldScroll) {
      container.scrollTo({
        top: container.scrollHeight,
        behavior: smooth ? 'smooth' : 'auto'
      });
      if (smooth || force) {
        emit('scrollToLatestChange', false);
      }
    }
    window.setTimeout(updateScrollToLatestVisibility, smooth ? 260 : 0);
  });
};

const scrollToBottomIfNeeded = () => {
  if (isChatNearBottom()) {
    scrollToBottom();
  } else {
    updateScrollToLatestVisibility();
  }
};

const copyTextToClipboard = (btn: HTMLElement) => {
  const text = btn.previousElementSibling?.textContent || '';
  const originalText = btn.textContent || '';

  navigator.clipboard
    .writeText(text)
    .then(() => {
      btn.textContent = '已复制';
      window.setTimeout(() => {
        btn.textContent = originalText;
      }, 3000);
    })
    .catch(() => {
      btn.textContent = '复制失败';
      window.setTimeout(() => {
        btn.textContent = originalText;
      }, 3000);
    });
};

const handleResultSetPagination = (btn: HTMLElement, direction: 'prev' | 'next') => {
  const container = btn.closest('.result-set-container');
  if (!container) {
    return;
  }

  const currentPageElement = container.querySelector('.result-set-current-page');
  const prevBtn = container.querySelector('.result-set-pagination-prev') as HTMLButtonElement;
  const nextBtn = container.querySelector('.result-set-pagination-next') as HTMLButtonElement;
  const pages = container.querySelectorAll('.result-set-page');

  if (!currentPageElement || !prevBtn || !nextBtn || pages.length === 0) {
    return;
  }

  let currentPage = Number.parseInt(currentPageElement.textContent || '1');
  const totalPages = pages.length;

  if (direction === 'prev' && currentPage > 1) {
    currentPage -= 1;
  } else if (direction === 'next' && currentPage < totalPages) {
    currentPage += 1;
  }

  pages.forEach((page: Element) => {
    page.classList.remove('result-set-page-active');
  });
  const targetPage = container.querySelector(`.result-set-page[data-page="${currentPage}"]`);
  targetPage?.classList.add('result-set-page-active');

  currentPageElement.textContent = currentPage.toString();
  prevBtn.disabled = currentPage === 1;
  nextBtn.disabled = currentPage === totalPages;
};

watch(
  () => props.state.currentSession?.id,
  () => {
    nextTick(updateScrollToLatestVisibility);
  }
);

onMounted(() => {
  window.copyTextToClipboard = copyTextToClipboard;
  window.handleResultSetPagination = handleResultSetPagination;
});

onBeforeUnmount(() => {
  if (window.copyTextToClipboard === copyTextToClipboard) {
    delete window.copyTextToClipboard;
  }
  if (window.handleResultSetPagination === handleResultSetPagination) {
    delete window.handleResultSetPagination;
  }
});

defineExpose({
  scrollToBottom,
  scrollToBottomIfNeeded
});
</script>

<style scoped>
.chat-container {
  flex: 1 1 0;
  min-height: 0;
  overflow-y: auto;
  padding: 16px var(--agent-run-chat-padding-x);
  background: var(--el-bg-color);
  border: 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  border-radius: 0;
  margin-bottom: 0;
  box-shadow: none;
}

.chat-container.has-input-area {
  padding-bottom: var(--agent-run-input-reserve);
}

.messages-area {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-bottom: 12px;
}
</style>

<style>
.chat-container {
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}

.chat-container::-webkit-scrollbar {
  width: var(--agent-run-scrollbar-size) !important;
  height: var(--agent-run-scrollbar-size) !important;
}

.chat-container::-webkit-scrollbar-button {
  -webkit-appearance: none;
  appearance: none;
  display: none;
  width: 0;
  height: 0;
  background: transparent;
}

.chat-container::-webkit-scrollbar-corner,
.chat-container::-webkit-scrollbar-track-piece,
.chat-container::-webkit-scrollbar-track {
  background: transparent !important;
}

.chat-container::-webkit-scrollbar-thumb {
  background-color: transparent !important;
  border-radius: 999px;
}

.run-main:hover .chat-container,
.chat-container:hover {
  scrollbar-color: rgba(144, 147, 153, 0.22) transparent;
}

.run-main:hover .chat-container::-webkit-scrollbar-thumb,
.run-main:hover .chat-container::-webkit-scrollbar-thumb:hover,
.run-main:hover .chat-container::-webkit-scrollbar-thumb:active,
.chat-container:hover::-webkit-scrollbar-thumb,
.chat-container:hover::-webkit-scrollbar-thumb:hover,
.chat-container:hover::-webkit-scrollbar-thumb:active {
  background-color: rgba(144, 147, 153, 0.22) !important;
}

.result-set-container {
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  overflow: hidden;
  margin: 8px 0;
}

.result-set-header {
  background: var(--el-fill-color-extra-light);
  padding: 12px 16px;
  border-bottom: 1px solid var(--el-border-color-light);
}

.result-set-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.result-set-pagination-controls {
  display: flex;
  align-items: center;
  gap: 16px;
}

.result-set-pagination-info {
  font-size: 14px;
  color: var(--el-text-color-regular);
}

.result-set-pagination-buttons {
  display: flex;
  gap: 8px;
}

.result-set-pagination-btn {
  padding: 6px 12px;
  border: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
  border-radius: 4px;
  color: var(--el-text-color-primary);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.result-set-pagination-btn:hover:not(:disabled) {
  background: var(--el-fill-color-light);
  border-color: var(--el-color-primary-light-5);
}

.result-set-pagination-btn:disabled {
  color: var(--el-text-color-disabled);
  cursor: not-allowed;
  background: var(--el-fill-color-light);
}

.result-set-table-container {
  overflow-x: auto;
  position: relative;
}

.result-set-page {
  display: none;
}

.result-set-page-active {
  display: block;
}

.result-set-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.result-set-table th {
  background: var(--el-fill-color-light);
  padding: 8px 12px;
  text-align: left;
  font-weight: 600;
  color: var(--el-text-color-regular);
  border-bottom: 1px solid var(--el-border-color-light);
  white-space: nowrap;
}

.result-set-table td {
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  word-break: break-word;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.result-set-table tr:hover {
  background: var(--el-fill-color-extra-light);
}

.result-set-empty-cell {
  text-align: center;
  color: var(--el-text-color-secondary);
  padding: 20px;
}

.result-set-error {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  padding: 8px 12px;
  border-radius: 4px;
  margin: 8px 0;
  border: 1px solid var(--el-color-danger-light-7);
}

.result-set-empty {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  padding: 8px 12px;
  border-radius: 4px;
  margin: 8px 0;
  text-align: center;
}

.agent-thinking-block {
  margin: 0 0 10px;
  color: #9aa59e;
  background: transparent;
  border: 0 !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  overflow: visible !important;
}

.agent-thinking-block.agent-response-block {
  background: transparent !important;
  border: 0 !important;
  box-shadow: none !important;
}

.agent-thinking-group:hover {
  border-color: transparent;
  box-shadow: none;
}

.agent-thinking-block .agent-response-summary {
  cursor: pointer;
  list-style: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  color: #9aa59e;
  background: transparent;
  border: 0;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.5;
}

.agent-thinking-block .agent-response-summary::-webkit-details-marker {
  display: none;
}

.agent-thinking-block .agent-response-summary::after {
  content: '>';
  flex: 0 0 auto;
  font-size: 11px;
  transition: transform 0.2s ease;
}

.agent-thinking-block[open] .agent-response-summary::after {
  transform: rotate(90deg);
}

.agent-thinking-block .agent-response-title-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-response-summary-hint,
.agent-thinking-count {
  display: none;
}

.agent-thinking-content {
  padding: 6px 0 0 0;
  white-space: normal;
  font-family: inherit;
}

.agent-thinking-steps {
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  list-style: none;
}

.agent-thinking-step {
  padding: 7px 0;
}

.agent-thinking-step-index {
  display: none;
}

.agent-thinking-step-body {
  min-width: 0;
}

.agent-thinking-step-title {
  margin-bottom: 4px;
  color: #8f9d94;
  font-size: 13px;
  font-weight: 500;
  line-height: 1.4;
}

.agent-thinking-step-content {
  color: #9aa59e;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.agent-thinking-step-content pre {
  margin: 6px 0 0;
}

.agent-thinking-raw-content {
  color: #9aa59e;
  font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace;
  font-size: 13px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.agent-final-answer-content {
  color: #183627;
  line-height: 1.75;
  white-space: pre-wrap;
  word-wrap: break-word;
}
</style>
