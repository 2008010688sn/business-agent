<template>
  <AgentRunPageShell
    mode="ai"
    :controller="controller"
    @back-to-workbench="startBackToWorkbench"
    @open-agent-catalog="openAgentCatalog"
  />
  <AgentVisibilityCatalogDialog v-model="catalogVisible" @applied="handleCatalogApplied" />
  <AiModeTransitionOverlay
    :visible="showEntryTransition"
    :duration="5000"
    :auto-finish="false"
    :timeout="entryTransitionTimeout"
  />
  <NormalModeTransitionOverlay :visible="showExitTransition" :duration="2600" @finished="finishBackToWorkbench" />
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import 'highlight.js/styles/github.css';
import { useTabStore } from '@/store/modules/tab';
import AgentRunPageShell from '@/views/ai-agent/components/run/AgentRunPageShell.vue';
import AgentVisibilityCatalogDialog from '@/views/ai-agent/components/run/AgentVisibilityCatalogDialog.vue';
import AiModeTransitionOverlay from '@/views/home/modules/AiModeTransitionOverlay.vue';
import NormalModeTransitionOverlay from '@/views/home/modules/NormalModeTransitionOverlay.vue';
import { useAgentRunController } from '@/views/ai-agent/utils/useAgentRunController';
import type { AgentId } from '@/views/ai-agent/services/agent';

defineOptions({ name: 'AgentRunAiMode' });

const router = useRouter();
const tabStore = useTabStore();
const controller = useAgentRunController({ mode: 'ai' });
const showEntryTransition = ref(true);
const showExitTransition = ref(false);
const entryTransitionTimeout = ref(false);
const catalogVisible = ref(false);

const ENTRY_FULL_MIN_DURATION = 5000;
const ENTRY_FAST_MIN_DURATION = 1200;
const entryStartedAt = Date.now();

let entryCloseTimer: number | undefined;
let timeoutTimer: number | undefined;

const pageReady = computed(() => {
  return Boolean(controller.initialRunMetaLoaded.value && !controller.chatModelsLoading.value);
});

const shouldFastCloseEntryTransition = computed(() => {
  if (controller.initialRunMetaError.value) {
    return true;
  }
  return Boolean(pageReady.value && !controller.agent.value.id && controller.availableAgents.value.length === 0);
});

const clearEntryTimers = () => {
  if (entryCloseTimer !== undefined) {
    window.clearTimeout(entryCloseTimer);
    entryCloseTimer = undefined;
  }
  if (timeoutTimer !== undefined) {
    window.clearTimeout(timeoutTimer);
    timeoutTimer = undefined;
  }
};

const closeEntryTransitionIfReady = () => {
  if (!pageReady.value || !showEntryTransition.value) {
    return;
  }
  if (entryCloseTimer !== undefined) {
    window.clearTimeout(entryCloseTimer);
    entryCloseTimer = undefined;
  }
  const minDuration = shouldFastCloseEntryTransition.value ? ENTRY_FAST_MIN_DURATION : ENTRY_FULL_MIN_DURATION;
  const remainingDuration = Math.max(minDuration - (Date.now() - entryStartedAt), 0);
  if (remainingDuration === 0) {
    showEntryTransition.value = false;
    clearEntryTimers();
    return;
  }
  entryCloseTimer = window.setTimeout(closeEntryTransitionIfReady, remainingDuration);
};

timeoutTimer = window.setTimeout(() => {
  if (!pageReady.value) {
    entryTransitionTimeout.value = true;
  }
}, 30000);

watch([pageReady, shouldFastCloseEntryTransition], closeEntryTransitionIfReady, { immediate: true });

const startBackToWorkbench = () => {
  if (showExitTransition.value) {
    return;
  }
  showExitTransition.value = true;
};

const openAgentCatalog = () => {
  catalogVisible.value = true;
};

const handleCatalogApplied = async (agentId?: AgentId) => {
  await controller.reloadUserWorkbenchMeta(agentId);
};

const finishBackToWorkbench = async () => {
  try {
    await router.push('/home');
    await nextTick();
    await tabStore.clearTabs([], false);
  } catch (error) {
    console.error('切换到普通模式失败:', error);
    ElMessage.error('切换到普通模式失败');
    showExitTransition.value = false;
  }
};

onBeforeUnmount(clearEntryTimers);
</script>
