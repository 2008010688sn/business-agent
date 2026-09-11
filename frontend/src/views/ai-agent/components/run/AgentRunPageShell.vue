<template>
  <BaseLayout
    class="agent-run-page"
    :class="{ 'agent-run-page-normal': mode === 'normal', 'agent-run-page-ai': mode === 'ai' }"
  >
    <ElContainer class="run-layout">
      <button
        v-if="isCompactViewport && !sidebarCollapsed"
        class="mobile-sidebar-backdrop"
        type="button"
        aria-label="关闭会话列表"
        @click="collapseSidebar"
      ></button>
      <!-- 运行页区域 -->
      <ChatSessionSidebar
        ref="chatSessionSidebarRef"
        v-model:collapsed="sidebarCollapsed"
        :agent="agent"
        :available-agents="availableAgents"
        :mode="mode"
        :handleSetCurrentSession="handleSetCurrentSession"
        :handleGetCurrentSession="handleGetCurrentSession"
        :handleSelectSession="selectSession"
        :handleDeleteSessionState="deleteSessionState"
        :handleSwitchAgent="switchRunAgent"
      />

      <!-- 运行页区域 -->
      <ElMain class="run-main" :class="{ 'has-input-area': currentSession }">
        <div class="run-workspace-layout">
          <section class="run-chat-region">
            <AgentRunHeader
              :title="agent.name"
              :mode="mode"
              :has-agent="Boolean(agent.id)"
              :sidebar-collapsed="sidebarCollapsed"
              @expand-sidebar="expandSidebar"
              @collapse-sidebar="collapseSidebar"
              @create-session="createSidebarSession"
              @clear-sessions="clearSidebarSessions"
              @back-to-workbench="emit('backToWorkbench')"
              @open-agent-catalog="emit('openAgentCatalog')"
            />
            <!-- 运行页区域 -->
            <AgentRunConversationPanel
              v-if="!showAiLoadError && !showAiEmptyState"
              ref="conversationPanelRef"
              v-model:result-set-page-size="resultSetPageSize"
              v-model:report-format="requestOptions.reportFormat"
              :state="conversationState"
              :stream-state="conversationStreamState"
              :report-state="conversationReportState"
              :actions="conversationActions"
              @scroll-to-latest-change="showScrollToLatest = $event"
            />
            <div v-else-if="showAiLoadError" class="agent-run-empty-state agent-run-error-state">
              <ElEmpty :description="initialRunMetaError || 'Agent 列表加载失败'" :image-size="96">
                <div class="agent-run-empty-copy">请检查网络后重试</div>
                <ElButton type="primary" @click="reloadUserWorkbenchMeta()">重试</ElButton>
              </ElEmpty>
            </div>
            <div v-else class="agent-run-empty-state">
              <ElEmpty description="暂无可用 Agent，可申请更多 Agent 后开始对话" :image-size="96">
                <div class="agent-run-empty-copy">申请通过后会自动出现在这里</div>
                <ElButton type="primary" @click="emit('openAgentCatalog')">申请更多 Agent</ElButton>
              </ElEmpty>
            </div>

            <!-- 运行页区域 -->
            <div v-if="currentSession && !showAiLoadError && !showAiEmptyState" class="run-input-region">
              <div v-if="showScrollToLatest" class="scroll-latest-row">
                <ElButton
                  text
                  bg
                  circle
                  size="small"
                  class="scroll-latest-button"
                  title="回到最新对话"
                  @click="handleScrollToLatest"
                >
                  <ElIcon><ArrowDown /></ElIcon>
                </ElButton>
              </div>
              <AgentRunInputArea
                ref="agentRunInputAreaRef"
                v-model:user-input="userInput"
                :state="inputAreaState"
                :model-state="inputAreaModelState"
                :diagnostics-state="inputAreaDiagnosticsState"
                :context-state="inputAreaContextState"
                :voice-state="inputAreaVoiceState"
                :agent-switch-state="inputAreaAgentSwitchState"
                :show-preset-questions="mode !== 'ai'"
                :actions="inputAreaActions"
              />
            </div>
          </section>

        </div>

        <footer v-if="mode === 'ai'" class="agent-run-ai-footer">Business Agent</footer>
      </ElMain>
    </ElContainer>

    <AgentRunDiagnosticsHost
      ref="diagnosticsHostRef"
      :can-view-thinking="canViewThinking"
      :session-id="currentSession?.id"
      :agent-id="diagnosticsAgentId"
      @answer-explain-state-change="handleAnswerExplainDrawerStateChange"
    />

    <Transition name="agent-switch-overlay">
      <div v-if="mode === 'ai' && isSwitchingAgent" class="agent-switch-mask">
        <div class="agent-switch-panel" aria-live="polite" aria-busy="true">
          <div class="agent-switch-robot" aria-hidden="true">
            <svg viewBox="0 0 1024 1024" class="agent-switch-robot-icon" xmlns="http://www.w3.org/2000/svg">
              <path
                d="M981.333333 618.666667h-106.666666v-21.333334a298.666667 298.666667 0 0 0-118.186667-244.053333l103.893333-162.986667a64 64 0 1 0-36.053333-23.04l-103.04 162.133334A416.213333 416.213333 0 0 0 512 277.333333a416.213333 416.213333 0 0 0-209.28 52.053334l-103.04-162.133334a64 64 0 1 0-36.053333 23.04l103.893333 162.986667A298.666667 298.666667 0 0 0 149.333333 597.333333v21.333334H42.666667v256h106.666666v85.333333h725.333334v-85.333333h106.666666zM85.333333 832v-170.666667h64v170.666667z m746.666667 85.333333H192V597.333333c0-192 160.64-277.333333 320-277.333333s320 85.333333 320 277.333333v320z m106.666667-85.333333h-64v-170.666667h64z"
                fill="currentColor"
              ></path>
              <path
                d="M405.333333 661.333333m-42.666666 0a42.666667 42.666667 0 1 0 85.333333 0 42.666667 42.666667 0 1 0-85.333333 0Z"
                fill="currentColor"
              ></path>
              <path
                d="M618.666667 661.333333m-42.666667 0a42.666667 42.666667 0 1 0 85.333333 0 42.666667 42.666667 0 1 0-85.333333 0Z"
                fill="currentColor"
              ></path>
              <path
                d="M661.333333 533.333333H362.666667a128 128 0 0 0 0 256h298.666666a128 128 0 0 0 0-256z m0 213.333334H362.666667a85.333333 85.333333 0 0 1 0-170.666667h298.666666a85.333333 85.333333 0 0 1 0 170.666667z"
                fill="currentColor"
              ></path>
            </svg>
          </div>
          <div class="agent-switch-copy">
            <div class="agent-switch-title">
              <span>智能体切换中</span>
              <span class="agent-switch-dots" aria-hidden="true">
                <span>.</span>
                <span>.</span>
                <span>.</span>
                <span>.</span>
                <span>.</span>
                <span>.</span>
              </span>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, nextTick, watch } from 'vue';
import { useMediaQuery } from '@vueuse/core';
import { useI18n } from 'vue-i18n';
import { ArrowDown } from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import AgentRunConversationPanel from '@/views/ai-agent/components/run/AgentRunConversationPanel.vue';
import AgentRunDiagnosticsHost from '@/views/ai-agent/components/run/AgentRunDiagnosticsHost.vue';
import AgentRunHeader from '@/views/ai-agent/components/run/AgentRunHeader.vue';
import AgentRunInputArea from '@/views/ai-agent/components/run/AgentRunInputArea.vue';
import ChatSessionSidebar from '@/views/ai-agent/components/run/ChatSessionSidebar.vue';
import type { ChatSession } from '@/views/ai-agent/services/chat';
import type { useAgentRunController } from '@/views/ai-agent/utils/useAgentRunController';

defineOptions({ name: 'AgentRunPageShell' });

const props = defineProps<{
  mode: 'normal' | 'ai';
  controller: ReturnType<typeof useAgentRunController>;
}>();

const emit = defineEmits<{
  backToWorkbench: [];
  openAgentCatalog: [];
}>();

const { t } = useI18n();

const {
  agent,
  availableAgents,
  chatSessionSidebarRef,
  agentRunInputAreaRef,
  conversationPanelRef,
  diagnosticsHostRef,
  sidebarCollapsed,
  currentSession,
  userInput,
  canViewThinking,
  initialRunMetaError,
  requestOptions,
  showScrollToLatest,
  inputAreaState,
  inputAreaModelState,
  inputAreaDiagnosticsState,
  inputAreaContextState,
  inputAreaVoiceState,
  inputAreaAgentSwitchState,
  inputAreaActions,
  conversationState,
  conversationStreamState,
  conversationReportState,
  conversationActions,
  resultSetPageSize,
  currentRunAgentId,
  isSwitchingAgent,
  switchRunAgent,
  selectSession,
  collapseSidebar,
  expandSidebar,
  createSidebarSession,
  clearSidebarSessions,
  handleAnswerExplainDrawerStateChange,
  deleteSessionState,
  reloadUserWorkbenchMeta,
  scrollToBottom
} = props.controller;

const isCompactViewport = useMediaQuery('(max-width: 768px)');

const handleScrollToLatest = async () => {
  scrollToBottom(true, true);
  await nextTick();
  await agentRunInputAreaRef.value?.focusInput?.();
};

watch(
  isCompactViewport,
  compact => {
    if (compact) {
      collapseSidebar();
    }
  },
  { immediate: true }
);

const diagnosticsAgentId = computed(() => (props.mode === 'ai' ? currentRunAgentId.value : agent.value.id));
const showAiLoadError = computed(
  () =>
    props.mode === 'ai' && Boolean(initialRunMetaError.value) && !agent.value.id && availableAgents.value.length === 0
);
const showAiEmptyState = computed(() => props.mode === 'ai' && !agent.value.id && availableAgents.value.length === 0);

const handleSetCurrentSession = async (session: ChatSession | null) => {
  await selectSession(session);
};

const handleGetCurrentSession = () => {
  return currentSession.value;
};
</script>

<style scoped src="@/views/ai-agent/styles/agent-run-shared.css"></style>

<style scoped>
.agent-run-page-normal {
  --agent-run-page-height: calc(100dvh - 128px);
}

.agent-run-page-ai {
  --agent-run-ai-footer-height: 40px;
  position: relative;
}

.agent-run-page-ai :deep(.run-main) {
  --agent-run-input-bottom: 9px;
}

.run-workspace-layout {
  box-sizing: border-box;
  position: relative;
  display: flex;
  flex: 1 1 0;
  min-height: 0;
}

.agent-run-page-ai .run-workspace-layout {
  padding-bottom: var(--agent-run-ai-footer-height);
}

.mobile-sidebar-backdrop {
  display: none;
}

.run-chat-region {
  position: relative;
  display: flex;
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
}

.run-input-region {
  position: relative;
  flex: 0 0 auto;
  min-width: 0;
}

.scroll-latest-row {
  position: absolute;
  top: 0;
  right: 0;
  left: 0;
  z-index: 12;
  display: flex;
  justify-content: center;
  pointer-events: none;
  transform: translateY(-50%);
}

.scroll-latest-button {
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  color: #ffffff;
  pointer-events: auto;
  background: var(--el-color-primary);
  border: 1px solid var(--el-color-primary);
  box-shadow: 0 8px 18px rgba(22, 114, 67, 0.18);
}

.scroll-latest-button:hover,
.scroll-latest-button:focus {
  color: #ffffff;
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

.agent-run-empty-state {
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--el-bg-color);
}

.agent-run-empty-state :deep(.el-empty__bottom) {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.agent-run-empty-copy {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.4;
}

.agent-run-ai-footer {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 20;
  display: flex;
  height: var(--agent-run-ai-footer-height);
  align-items: center;
  justify-content: center;
  padding: 0 16px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  line-height: 1;
  background: var(--el-bg-color);
  border-top: 1px solid var(--el-border-color-extra-light);
  pointer-events: none;
}

.agent-switch-mask {
  position: absolute;
  inset: 0;
  z-index: 200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--minimal-modal-overlay, rgba(0, 0, 0, 0.3));
}

:global(html.theme-style-enterprise) .agent-switch-mask {
  background: var(--enterprise-modal-overlay);
}

.agent-switch-panel {
  position: relative;
  display: flex;
  width: 240px;
  height: 200px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border-radius: 12px;
  box-shadow: var(--el-box-shadow-light);
}

.agent-switch-robot {
  position: relative;
  z-index: 1;
  display: grid;
  width: 76px;
  height: 76px;
  place-items: center;
  color: #8a8a8a;
  filter: drop-shadow(0 14px 20px color-mix(in srgb, var(--el-color-primary) 28%, rgba(0, 0, 0, 0.18)));
  transform-origin: center bottom;
  animation: agent-switch-robot-cycle 5.44s linear infinite;
}

.agent-switch-robot::after {
  position: absolute;
  bottom: 2px;
  left: 50%;
  width: 44px;
  height: 8px;
  background: color-mix(in srgb, currentColor 38%, rgba(0, 0, 0, 0.26));
  border-radius: 50%;
  content: '';
  filter: blur(5px);
  transform: translateX(-50%);
  animation: agent-switch-robot-shadow 1.36s linear infinite;
}

.agent-switch-robot-icon {
  position: relative;
  z-index: 1;
  width: 38px;
  height: 38px;
}

.agent-switch-copy {
  position: relative;
  z-index: 1;
  width: 100%;
  min-width: 176px;
  text-align: center;
}

.agent-switch-title {
  display: grid;
  width: 100%;
  min-width: 176px;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
  text-align: center;
}

.agent-switch-title > span:first-child {
  grid-column: 2;
}

.agent-switch-dots {
  display: inline-flex;
  grid-column: 3;
  width: 3.6em;
  justify-content: flex-start;
  justify-self: start;
  text-align: left;
}

.agent-switch-dots span {
  opacity: 0;
  animation: agent-switch-dot-first 1.8s steps(1, end) infinite;
}

.agent-switch-dots span:nth-child(2) {
  animation-name: agent-switch-dot-second;
}

.agent-switch-dots span:nth-child(3) {
  animation-name: agent-switch-dot-third;
}

.agent-switch-dots span:nth-child(4) {
  animation-name: agent-switch-dot-fourth;
}

.agent-switch-dots span:nth-child(5) {
  animation-name: agent-switch-dot-fifth;
}

.agent-switch-dots span:nth-child(6) {
  animation-name: agent-switch-dot-sixth;
}

.agent-switch-overlay-enter-active,
.agent-switch-overlay-leave-active {
  transition:
    opacity 0.22s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.22s cubic-bezier(0.16, 1, 0.3, 1);
}

.agent-switch-overlay-enter-from,
.agent-switch-overlay-leave-to {
  opacity: 0;
  transform: scale(1.01);
}

@keyframes agent-switch-robot-cycle {
  0% {
    color: #8a8a8a;
    transform: translateY(0) scaleX(1.05) scaleY(0.95);
  }

  2% {
    color: #8a8a8a;
    transform: translateY(2px) scaleX(1.12) scaleY(0.88);
  }

  5.5% {
    color: #8a8a8a;
    transform: translateY(-30px) scaleX(0.96) scaleY(1.04);
  }

  10% {
    color: #8a8a8a;
    transform: translateY(-40px) scale(1);
  }

  13% {
    color: #8a8a8a;
    transform: translateY(-38px) scale(1);
  }

  17.5% {
    color: #8a8a8a;
    transform: translateY(-16px) scaleX(0.98) scaleY(1.02);
  }

  20.5% {
    color: #8a8a8a;
    transform: translateY(0) scaleX(1.14) scaleY(0.86);
  }

  22.5% {
    color: #8a8a8a;
    transform: translateY(-8px) scaleX(0.99) scaleY(1.01);
  }

  24.99% {
    color: #8a8a8a;
    transform: translateY(0) scaleX(1.05) scaleY(0.95);
  }

  25% {
    color: var(--el-color-primary);
    transform: translateY(0) scaleX(1.05) scaleY(0.95);
  }

  27% {
    color: var(--el-color-primary);
    transform: translateY(2px) scaleX(1.12) scaleY(0.88);
  }

  30.5% {
    color: var(--el-color-primary);
    transform: translateY(-30px) scaleX(0.96) scaleY(1.04);
  }

  35% {
    color: var(--el-color-primary);
    transform: translateY(-40px) scale(1);
  }

  38% {
    color: var(--el-color-primary);
    transform: translateY(-38px) scale(1);
  }

  42.5% {
    color: var(--el-color-primary);
    transform: translateY(-16px) scaleX(0.98) scaleY(1.02);
  }

  45.5% {
    color: var(--el-color-primary);
    transform: translateY(0) scaleX(1.14) scaleY(0.86);
  }

  47.5% {
    color: var(--el-color-primary);
    transform: translateY(-8px) scaleX(0.99) scaleY(1.01);
  }

  49.99% {
    color: var(--el-color-primary);
    transform: translateY(0) scaleX(1.05) scaleY(0.95);
  }

  50% {
    color: #1677ff;
    transform: translateY(0) scaleX(1.05) scaleY(0.95);
  }

  52% {
    color: #1677ff;
    transform: translateY(2px) scaleX(1.12) scaleY(0.88);
  }

  55.5% {
    color: #1677ff;
    transform: translateY(-30px) scaleX(0.96) scaleY(1.04);
  }

  60% {
    color: #1677ff;
    transform: translateY(-40px) scale(1);
  }

  63% {
    color: #1677ff;
    transform: translateY(-38px) scale(1);
  }

  67.5% {
    color: #1677ff;
    transform: translateY(-16px) scaleX(0.98) scaleY(1.02);
  }

  70.5% {
    color: #1677ff;
    transform: translateY(0) scaleX(1.14) scaleY(0.86);
  }

  72.5% {
    color: #1677ff;
    transform: translateY(-8px) scaleX(0.99) scaleY(1.01);
  }

  74.99% {
    color: #1677ff;
    transform: translateY(0) scaleX(1.05) scaleY(0.95);
  }

  75% {
    color: #f59e0b;
    transform: translateY(0) scaleX(1.05) scaleY(0.95);
  }

  77% {
    color: #f59e0b;
    transform: translateY(2px) scaleX(1.12) scaleY(0.88);
  }

  80.5% {
    color: #f59e0b;
    transform: translateY(-30px) scaleX(0.96) scaleY(1.04);
  }

  85% {
    color: #f59e0b;
    transform: translateY(-40px) scale(1);
  }

  88% {
    color: #f59e0b;
    transform: translateY(-38px) scale(1);
  }

  92.5% {
    color: #f59e0b;
    transform: translateY(-16px) scaleX(0.98) scaleY(1.02);
  }

  95.5% {
    color: #f59e0b;
    transform: translateY(0) scaleX(1.14) scaleY(0.86);
  }

  97.5% {
    color: #f59e0b;
    transform: translateY(-8px) scaleX(0.99) scaleY(1.01);
  }

  99.99% {
    color: #f59e0b;
    transform: translateY(0) scaleX(1.05) scaleY(0.95);
  }

  100% {
    color: #8a8a8a;
    transform: translateY(0) scaleX(1.05) scaleY(0.95);
  }
}

@keyframes agent-switch-robot-shadow {
  0% {
    opacity: 0.52;
    transform: translateX(-50%) scaleX(1);
  }

  8% {
    opacity: 0.62;
    transform: translateX(-50%) scaleX(1.18);
  }

  22% {
    opacity: 0.32;
    transform: translateX(-50%) scaleX(0.64);
  }

  40%,
  52% {
    opacity: 0.22;
    transform: translateX(-50%) scaleX(0.46);
  }

  70% {
    opacity: 0.38;
    transform: translateX(-50%) scaleX(0.72);
  }

  82% {
    opacity: 0.66;
    transform: translateX(-50%) scaleX(1.22);
  }

  90% {
    opacity: 0.42;
    transform: translateX(-50%) scaleX(0.84);
  }

  100% {
    opacity: 0.52;
    transform: translateX(-50%) scaleX(1);
  }
}

@keyframes agent-switch-dot-first {
  0%,
  99.99% {
    opacity: 1;
  }

  100% {
    opacity: 0;
  }
}

@keyframes agent-switch-dot-second {
  0%,
  16.66%,
  100% {
    opacity: 0;
  }

  16.67%,
  99.99% {
    opacity: 1;
  }
}

@keyframes agent-switch-dot-third {
  0%,
  33.32%,
  100% {
    opacity: 0;
  }

  33.33%,
  99.99% {
    opacity: 1;
  }
}

@keyframes agent-switch-dot-fourth {
  0%,
  49.99%,
  100% {
    opacity: 0;
  }

  50%,
  99.99% {
    opacity: 1;
  }
}

@keyframes agent-switch-dot-fifth {
  0%,
  66.66%,
  100% {
    opacity: 0;
  }

  66.67%,
  99.99% {
    opacity: 1;
  }
}

@keyframes agent-switch-dot-sixth {
  0%,
  83.32%,
  100% {
    opacity: 0;
  }

  83.33%,
  99.99% {
    opacity: 1;
  }
}

@media (prefers-reduced-motion: reduce) {
  .agent-switch-robot,
  .agent-switch-robot::after,
  .agent-switch-dots span {
    animation: none;
  }

  .agent-switch-overlay-enter-active,
  .agent-switch-overlay-leave-active {
    transition: opacity 0.16s ease;
  }
}

@media (max-width: 768px) {
  .run-layout {
    position: relative;
  }

  .mobile-sidebar-backdrop {
    position: absolute;
    inset: 0;
    z-index: 39;
    display: block;
    width: 100%;
    padding: 0;
    border: 0;
    background: rgba(17, 36, 25, 0.22);
  }

  .run-layout :deep(.chat-session-sidebar:not(.collapsed)) {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 40;
    width: min(86vw, 320px) !important;
    max-width: 320px;
    box-shadow: 10px 0 28px rgba(33, 65, 44, 0.14) !important;
  }

  .run-main {
    --agent-run-chat-padding-x: 12px;
    --agent-run-input-inline-start: 12px;
    --agent-run-input-inline-end: 12px;
  }

  .agent-run-ai-footer {
    padding: 0 10px;
    font-size: 12px;
  }
}
</style>
