import { ref, onMounted, nextTick, computed, onBeforeUnmount, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import 'highlight.js/styles/github.css';
import ChatService, {
  CHAT_MESSAGE_TYPES,
  type ChatSession,
  type ChatMessage,
  type SessionContextCompressionResult,
  type SessionContextUsage
} from '@/views/ai-agent/services/chat';
import GraphService, {
  type AgentPageContext,
  type ClarifyMetadata,
  type AgentRequest,
  type AgentResponse,
  type ClarificationResponse,
  type RuntimeProgressEvent,
  TextType
} from '@/views/ai-agent/services/graph';
import runtimeRunService, {
  isActiveRuntimeRunState,
  toRuntimeProgressEvent,
  type RuntimeEventResp
} from '@/views/ai-agent/services/runtimeRun';
import { type AgentUiActionRequest } from '@/views/ai-agent/utils/agentUi';
import { type Agent } from '@/views/ai-agent/services/agent';
import { useSessionStateManager } from '@/views/ai-agent/services/sessionStateManager';
import type {
  SessionRuntimeState,
  PendingClarifyState,
  QueuedRequestState,
  SessionResponseMode
} from '@/views/ai-agent/services/sessionStateManager';
import type AgentRunConversationPanel from '@/views/ai-agent/components/run/AgentRunConversationPanel.vue';
import type AgentRunInputArea from '@/views/ai-agent/components/run/AgentRunInputArea.vue';
import { type ModelConfig, type ModelConfigId } from '@/views/ai-agent/services/modelConfig';
import { type AgentModelConfigItem } from '@/views/ai-agent/services/agentModelConfig';
import agentRunMetaService, { type RuntimeChatModel } from '@/views/ai-agent/services/agentRunMeta';
import { parseAgentId } from '@/views/ai-agent/utils/agentDisplay';
import { createAgentRunNodeRenderer, splitNodeBlocks } from '@/views/ai-agent/utils/runNodeRenderer';
import { buildReportHtmlDocument } from '@/views/ai-agent/utils/reportRenderer';
import { formatTokenCount } from '@/views/ai-agent/utils/runFormatters';
import {
  parseMessageMetadata,
  type MessageAttachment,
  type MessageExplainMetadata
} from '@/views/ai-agent/utils/runMessage';
import { useRunDiagnostics } from '@/views/ai-agent/utils/useRunDiagnostics';
import { useTtsPlaybackController } from '@/views/ai-agent/utils/useTtsPlaybackController';
import {
  BUSINESS_CLARIFICATION_INPUT_LOCK_HINT,
  getUnconsumedBusinessClarification,
  shouldBlockBareAgentSend,
  type SuggestedReplySubmission
} from '@/views/ai-agent/utils/suggestedReplies';
import { parseAgentRunPrefillQuery, parsePageContextFromQuery } from '@/views/ai-agent/utils/openAgentWithPageContext';
import {
  buildPersistedRuntimeErrorMetadata,
  createRuntimeStreamError,
  isClarificationLockReleaseError,
  normalizeRuntimeErrorMetadata,
  normalizeRuntimeErrorMessage,
  shouldDiscardPartialOutput
} from '@/views/ai-agent/utils/runtimeError';

type ChatModelOption = ModelConfig & { id: ModelConfigId };
type ReportDownloadFormat = 'markdown' | 'html';
type SaveGroupedAssistantOptions = {
  saveFinalBlocks?: boolean;
  thinkingExpanded?: boolean;
};
type SendMessageOptions = {
  clarificationResponse?: ClarificationResponse;
  displayText?: string;
};
type AgentChatModelCache = {
  chatModelOptions: ChatModelOption[];
  agentModelConfigs: AgentModelConfigItem[];
};

const MAX_RUNTIME_PROGRESS_EVENTS = 200;

const isSelectableChatModel = (item: RuntimeChatModel): item is RuntimeChatModel & { modelConfig: ChatModelOption } => {
  return Boolean(item.selectable && item.modelConfig?.modelType === 'CHAT' && item.modelConfig.id !== undefined);
};

interface ChatSessionSidebarExpose {
  createNewSession: (options?: { silent?: boolean }) => Promise<void>;
  clearAllSessions: () => Promise<void>;
  reloadSessions: () => Promise<void>;
  focusAgentGroup: (agentOrId: Agent | string | null | undefined) => Promise<void>;
}

const normalizeRouteValue = (value: unknown) => {
  const rawValue = Array.isArray(value) ? value[0] : value;
  return parseAgentId(rawValue) ?? '';
};

const isClarifyMetadata = (
  metadata?: (ClarifyMetadata & Record<string, any>) | null
): metadata is ClarifyMetadata & { clarifyRequired: true; originalQuery: string } => {
  return Boolean(metadata?.clarifyRequired && metadata?.originalQuery);
};

export interface UseAgentRunControllerOptions {
  mode?: 'normal' | 'ai';
}

export function useAgentRunController(controllerOptions: UseAgentRunControllerOptions = {}) {
  const route = useRoute();
  const router = useRouter();
  const mode = controllerOptions.mode ?? 'normal';
  const isAiMode = mode === 'ai';

  // 运行页逻辑
  const agent = ref<Agent>({} as Agent);
  const availableAgents = ref<Agent[]>([]);
  const isSwitchingAgent = ref(false);
  const chatSessionSidebarRef = ref<ChatSessionSidebarExpose | null>(null);
  const agentRunInputAreaRef = ref<InstanceType<typeof AgentRunInputArea> | null>(null);
  const conversationPanelRef = ref<InstanceType<typeof AgentRunConversationPanel> | null>(null);
  const sidebarCollapsed = ref(false);
  const currentSession = ref<ChatSession | null>(null);
  const currentMessages = ref<ChatMessage[]>([]);
  const userInput = ref('');
  const { getSessionState, syncStateToView, saveViewToState, persistSessionState, deleteSessionState } =
    useSessionStateManager();
  const isStreaming = ref(false);
  const isSubmittingMessage = ref(false);
  const nodeBlocks = ref<AgentResponse[][]>([]);
  const runtimeProgressEvents = ref<RuntimeProgressEvent[]>([]);
  const options = ref({
    markdownIt: {
      linkify: true
    },
    linkAttributes: {
      attrs: {
        target: '_blank',
        rel: 'noopener'
      }
    }
  });
  const requestOptions = ref({
    reportFormat: 'markdown' as 'markdown' | 'html',
    responseMode: 'normal' as 'normal' | 'report',
    clarifyCheckEnabled: false,
    chatModelConfigId: undefined as ModelConfigId | undefined
  });
  const ttsPlayback = useTtsPlaybackController();
  const chatModelOptions = ref<ChatModelOption[]>([]);
  const agentModelConfigs = ref<AgentModelConfigItem[]>([]);
  const agentChatModelCache = new Map<string, AgentChatModelCache>();
  const chatModelSelectionTouched = ref(false);
  const chatModelsLoading = ref(false);
  const contextUsage = ref<SessionContextUsage | null>(null);
  const contextUsageLoading = ref(false);
  const contextCompressing = ref(false);
  const contextUsageError = ref('');
  const contextUsageRequestId = ref(0);
  const lastContextCompression = ref<SessionContextCompressionResult | null>(null);
  const pendingClarify = ref<PendingClarifyState | null>(null);
  const pageContext = ref<AgentPageContext | undefined>();
  const releasedClarificationIdsBySession = new Map<string, Set<string>>();
  const releasedClarificationTick = ref(0);
  const queuedRequests = ref<QueuedRequestState[]>([]);
  const canViewThinking = ref(false);
  const initialRunMetaLoaded = ref(false);
  const initialRunMetaError = ref('');
  let runAgentRequestSeq = 0;

  const showScrollToLatest = ref(false);

  const collapseSidebar = () => {
    sidebarCollapsed.value = true;
  };

  const expandSidebar = () => {
    sidebarCollapsed.value = false;
  };

  const createSidebarSession = async () => {
    await chatSessionSidebarRef.value?.createNewSession();
  };

  const clearSidebarSessions = async () => {
    await chatSessionSidebarRef.value?.clearAllSessions();
  };

  // 运行页逻辑
  const resultSetPageSize = ref(20);
  const nodeRenderer = createAgentRunNodeRenderer({
    getResultSetPageSize: () => resultSetPageSize.value,
    getMarkdownReportContent: () => {
      const sessionId = currentSession.value?.id;
      return sessionId ? getSessionState(sessionId).markdownReportContent || '' : '';
    }
  });
  const currentMarkdownReportContent = computed(() => {
    const sessionId = currentSession.value?.id;
    return sessionId ? getSessionState(sessionId).markdownReportContent || '' : '';
  });
  const professionalReportLoadingRequestId = ref('');
  const visibleCurrentMessages = computed(() => {
    const messages = Array.isArray(currentMessages.value) ? currentMessages.value : [];
    return messages.filter(message => message.messageType !== CHAT_MESSAGE_TYPES.THINKING);
  });

  const isHiddenAssistantMessageType = (messageType?: string) => {
    const type = String(messageType || '').toLowerCase();
    return type === CHAT_MESSAGE_TYPES.THINKING || type === 'answer-explain';
  };

  const isPublicAssistantMessage = (message: ChatMessage) => {
    if ((message.role || '').toLowerCase() !== 'assistant') {
      return false;
    }
    if (isHiddenAssistantMessageType(message.messageType)) {
      return false;
    }
    return Boolean(message.content);
  };

  const hasPersistedPublicAnswerAfterLastUser = (messages: ChatMessage[]) => {
    let lastUserIndex = -1;
    for (let index = messages.length - 1; index >= 0; index -= 1) {
      if ((messages[index].role || '').toLowerCase() === 'user') {
        lastUserIndex = index;
        break;
      }
    }
    if (lastUserIndex < 0) {
      return false;
    }
    return messages.slice(lastUserIndex + 1).some(isPublicAssistantMessage);
  };

  const retainLiveNodeBlocksIfUnpersisted = (sessionId: string, sessionState: SessionRuntimeState) => {
    const keepLiveOutput =
      sessionState.nodeBlocks.length > 0 && !hasPersistedPublicAnswerAfterLastUser(currentMessages.value);
    if (keepLiveOutput) {
      if (currentSession.value?.id === sessionId) {
        nodeBlocks.value = sessionState.nodeBlocks;
      }
      return;
    }
    sessionState.nodeBlocks = [];
    if (currentSession.value?.id === sessionId) {
      nodeBlocks.value = [];
    }
  };

  const answerWaitingText = '智能体思考中，请稍候...';

  const routeAgentId = computed(() => normalizeRouteValue(route.query.agentId));
  const agentId = computed(() => {
    if (isAiMode) {
      return parseAgentId(agent.value?.id) ?? routeAgentId.value;
    }
    return routeAgentId.value;
  });

  const getReleasedClarificationIds = (sessionId?: string | null) => {
    const id = sessionId || currentSession.value?.id;
    if (!id) {
      return new Set<string>();
    }
    let released = releasedClarificationIdsBySession.get(id);
    if (!released) {
      released = new Set<string>();
      releasedClarificationIdsBySession.set(id, released);
    }
    return released;
  };

  const unconsumedBusinessClarification = computed(() => {
    return getUnconsumedBusinessClarification(
      currentMessages.value,
      releasedClarificationTick.value >= 0 ? getReleasedClarificationIds() : undefined
    );
  });

  const isBusinessClarificationLocked = computed(() => Boolean(unconsumedBusinessClarification.value));

  const releaseBusinessClarificationLock = (clarificationId?: string | null) => {
    const id = typeof clarificationId === 'string' ? clarificationId.trim() : '';
    if (!id) {
      return;
    }
    getReleasedClarificationIds().add(id);
    releasedClarificationTick.value += 1;
  };

  const releaseClarificationLockFromError = (error: Error, request?: AgentRequest | null) => {
    if (!isClarificationLockReleaseError(error)) {
      return;
    }
    releaseBusinessClarificationLock(
      request?.clarificationResponse?.clarificationId || unconsumedBusinessClarification.value?.clarificationId
    );
  };

  const attachPageContext = (request: AgentRequest): AgentRequest => {
    if (!pageContext.value) {
      return request;
    }
    return {
      ...request,
      pageContext: {
        keys: { ...pageContext.value.keys },
        ...(pageContext.value.objectType ? { objectType: pageContext.value.objectType } : {})
      }
    };
  };

  const syncPageContextFromRoute = () => {
    pageContext.value = parsePageContextFromQuery(route.query);
  };

  const applyRunPrefillQuery = () => {
    const prefill = parseAgentRunPrefillQuery(route.query);
    if (prefill && !userInput.value.trim()) {
      userInput.value = prefill;
    }
  };

  const contextCompressionSummary = computed(() => {
    const result = lastContextCompression.value;
    if (!result || !result.compressed) {
      return '';
    }
    return `上下文已压缩：${formatTokenCount(result.beforeRuntimeTokens)} -> ${formatTokenCount(
      result.afterRuntimeTokens
    )} tokens / ${result.beforeRuntimeMessageCount ?? 0} -> ${result.afterRuntimeMessageCount ?? 0} 条消息`;
  });
  const canCompressContext = computed(() => {
    return Boolean(currentSession.value) && !isStreaming.value && !isSubmittingMessage.value;
  });

  const loadSessionContextUsage = async () => {
    const session = currentSession.value;
    if (!session) {
      contextUsage.value = null;
      contextUsageError.value = '';
      return;
    }
    const requestId = contextUsageRequestId.value + 1;
    contextUsageRequestId.value = requestId;
    contextUsageLoading.value = true;
    contextUsageError.value = '';
    try {
      const usage = await ChatService.getSessionContext(
        session.id,
        requireResolvedAgentId(),
        requestOptions.value.chatModelConfigId
      );
      if (contextUsageRequestId.value === requestId && currentSession.value?.id === session.id) {
        contextUsage.value = usage;
      }
    } catch (error) {
      if (contextUsageRequestId.value === requestId) {
        contextUsage.value = null;
        contextUsageError.value = '上下文用量暂不可用';
      }
      console.warn('Failed to load context usage:', error);
    } finally {
      if (contextUsageRequestId.value === requestId) {
        contextUsageLoading.value = false;
      }
    }
  };

  const compressSessionContext = async () => {
    const session = currentSession.value;
    if (!session || contextCompressing.value || !canCompressContext.value) {
      return;
    }
    try {
      await ElMessageBox.confirm('是否开始压缩？', '提示', {
        confirmButtonText: '开始',
        cancelButtonText: '取消',
        type: 'warning'
      });
    } catch {
      return;
    }
    contextCompressing.value = true;
    try {
      const result = await ChatService.compressSessionContext(
        session.id,
        requireResolvedAgentId(),
        requestOptions.value.chatModelConfigId
      );
      lastContextCompression.value = result;
      if (result.displayContextUsage) {
        contextUsage.value = result.displayContextUsage;
      } else {
        await loadSessionContextUsage();
      }
      const message = result.message || '上下文已压缩';
      if (result.status === 'COMPRESSED') {
        ElMessage.success(message);
      } else if (result.status === 'FAILED') {
        ElMessage.error(message);
      } else {
        ElMessage.info(message);
      }
    } catch (error) {
      console.warn('Failed to compress context:', error);
      ElMessage.error('操作失败，请稍后重试');
    } finally {
      contextCompressing.value = false;
    }
  };
  const selectChatModel = async (modelId?: ModelConfigId) => {
    chatModelSelectionTouched.value = true;
    requestOptions.value.chatModelConfigId = modelId;
    await loadSessionContextUsage();
  };

  function resolveAgentDefaultModelConfigId() {
    return agentModelConfigs.value.find(item => item.isDefault && item.enabled)?.modelConfigId;
  }

  function resolveAgentDefaultModel(): ModelConfig | undefined {
    const defaultModelId = resolveAgentDefaultModelConfigId();
    return (
      agentModelConfigs.value.find(item => item.enabled && item.modelConfigId === defaultModelId)?.modelConfig ??
      chatModelOptions.value.find(model => model.id === defaultModelId)
    );
  }

  const defaultChatModel = computed(() => resolveAgentDefaultModel());

  const conversationState = computed(() => ({
    agentId: agent.value.id,
    currentSession: currentSession.value,
    messages: visibleCurrentMessages.value,
    isSubmittingMessage: isSubmittingMessage.value,
    hasSelectedImages: hasSelectedImages(),
    canViewThinking: canViewThinking.value,
    isStreaming: isStreaming.value
  }));

  const conversationStreamState = computed(() => ({
    waitingForAnswerOutput: isWaitingForAnswerOutput.value,
    answerWaitingText,
    runtimeProgressEvents: runtimeProgressEvents.value,
    thinkingNodeBlocks: thinkingNodeBlocks.value,
    hasFinalAnswerOutput: hasFinalAnswerOutput.value,
    finalNodeBlocks: finalNodeBlocks.value
  }));

  const conversationReportState = computed(() => ({
    markdownOptions: options.value,
    markdownReportContent: currentMarkdownReportContent.value,
    professionalLoadingRequestId: professionalReportLoadingRequestId.value
  }));

  const conversationActions = computed(() => ({
    resendQuestion: (question: string) => resendEditedQuestion(question),
    submitSuggestedReply: (submission: SuggestedReplySubmission) => submitSuggestedReply(submission),
    downloadReport: (message: ChatMessage, format?: ReportDownloadFormat) => downloadReportFromMessage(message, format),
    generateProfessionalReport: (message: ChatMessage) => generateProfessionalReportForMessage(message),
    submitFlowAction: (request: AgentUiActionRequest) => submitFlowAction(request)
  }));

  const inputAreaState = computed(() => ({
    pendingClarify: pendingClarify.value,
    businessClarificationLocked: isBusinessClarificationLocked.value,
    isStreaming: isStreaming.value,
    isSubmittingMessage: isSubmittingMessage.value,
    queuedRequests: queuedRequests.value,
    responseMode: requestOptions.value.responseMode,
    agentId: agent.value.id,
    currentSession: currentSession.value,
    currentMessages: currentMessages.value
  }));

  const inputAreaModelState = computed(() => ({
    chatModelConfigId: requestOptions.value.chatModelConfigId,
    chatModelOptions: chatModelOptions.value,
    chatModelsLoading: chatModelsLoading.value,
    defaultChatModel: defaultChatModel.value
  }));

  const inputAreaDiagnosticsState = computed(() => ({
    canViewThinking: canViewThinking.value,
    latestExplainRuntimeRequestId: latestExplainRuntimeRequestId.value,
    traceLoading: traceLoading.value,
    orchestrationTraceLoading: orchestrationTraceLoading.value
  }));

  const inputAreaContextState = computed(() => ({
    usage: contextUsage.value,
    loading: contextUsageLoading.value,
    compressing: contextCompressing.value,
    error: contextUsageError.value,
    compressionSummary: contextCompressionSummary.value,
    canCompress: canCompressContext.value
  }));

  const inputAreaVoiceState = computed(() => ({
    answerTtsEnabled: ttsPlayback.enabled.value,
    answerTtsPlaying: ttsPlayback.playing.value,
    answerTtsLoading: ttsPlayback.loading.value,
    answerTtsStatusLabel: ttsPlayback.statusLabel.value
  }));

  const inputAreaAgentSwitchState = computed(() => ({
    show: isAiMode,
    availableAgents: availableAgents.value,
    currentRunAgentId: currentRunAgentId.value,
    isSwitchingAgent: isSwitchingAgent.value
  }));

  const reloadCurrentMessages = async () => {
    if (!currentSession.value?.id) {
      return;
    }
    currentMessages.value = await ChatService.getSessionMessages(currentSession.value.id, requireResolvedAgentId());
    scrollToBottom(true, true);
  };

  const inputAreaActions = computed(() => ({
    send: () => sendMessage(),
    stopStreaming: () => stopStreaming(),
    toggleReportMode: () => toggleReportMode(),
    selectChatModel: (modelId: ModelConfigId | undefined) => selectChatModel(modelId),
    openLatestAnswerExplain: () => openLatestAnswerExplain(),
    openTraceDialog: () => openTraceDialog(),
    openOrchestrationTrace: () => openOrchestrationTrace(),
    loadSessionContextUsage: () => loadSessionContextUsage(),
    compressSessionContext: () => compressSessionContext(),
    clearQueuedRequests: () => clearQueuedRequests(),
    guideQueuedRequest: (id: string) => guideQueuedRequest(id),
    removeQueuedRequest: (id: string) => removeQueuedRequest(id),
    cancelPendingClarify: () => cancelPendingClarify(),
    clarifyAssumption: (assumption: string) => applyClarifyAssumption(assumption),
    presetQuestionClick: (question: string) => handlePresetQuestionClick(question),
    switchAgent: (targetAgent: Agent) => switchRunAgent(targetAgent),
    setAnswerTtsEnabled: (enabled: boolean) => ttsPlayback.setEnabled(enabled),
    stopAnswerTts: () => ttsPlayback.stop(),
    reloadCurrentMessages: () => reloadCurrentMessages()
  }));

  const applyDefaultChatModelSelection = () => {
    const currentModelId = requestOptions.value.chatModelConfigId;
    const hasCurrentModel =
      currentModelId !== undefined && chatModelOptions.value.some(model => model.id === currentModelId);
    if (chatModelSelectionTouched.value && hasCurrentModel) {
      return;
    }
    const defaultModelId = resolveAgentDefaultModelConfigId();
    const selectableDefaultModel = chatModelOptions.value.find(model => model.id === defaultModelId);
    requestOptions.value.chatModelConfigId =
      chatModelSelectionTouched.value && selectableDefaultModel ? selectableDefaultModel.id : undefined;
  };

  const cacheCurrentChatModels = (currentAgentId: string) => {
    agentChatModelCache.set(currentAgentId, {
      agentModelConfigs: [...agentModelConfigs.value],
      chatModelOptions: [...chatModelOptions.value]
    });
  };

  const applyRuntimeChatModels = (runtimeModels: RuntimeChatModel[] = []) => {
    agentModelConfigs.value = runtimeModels.map(item => ({
      modelConfigId: item.modelConfigId,
      isDefault: item.isDefault,
      userSelectable: item.userSelectable,
      enabled: item.enabled,
      modelConfig: item.modelConfig
    }));
    chatModelOptions.value = runtimeModels.filter(isSelectableChatModel).map(item => item.modelConfig);
    applyDefaultChatModelSelection();
  };

  const loadChatModelOptions = async () => {
    chatModelsLoading.value = true;
    try {
      const currentAgentId = parseAgentId(agentId.value);
      const cached = currentAgentId ? agentChatModelCache.get(currentAgentId) : undefined;
      if (cached) {
        agentModelConfigs.value = [...cached.agentModelConfigs];
        chatModelOptions.value = [...cached.chatModelOptions];
        applyDefaultChatModelSelection();
        return;
      }

      if (!currentAgentId) {
        applyRuntimeChatModels([]);
        return;
      }
      const meta = await agentRunMetaService.getRunMeta(currentAgentId);
      if (meta.agent) {
        agent.value = meta.agent;
      }
      applyRuntimeChatModels(meta.chatModels);
      if (currentAgentId) {
        cacheCurrentChatModels(currentAgentId);
      }
    } catch (error) {
      console.error('Failed to load chat model configs:', error);
      ElMessage.warning('当前操作暂不可用');
    } finally {
      chatModelsLoading.value = false;
    }
  };

  const getRouteAgentId = (): string | null => {
    if (isAiMode) {
      return parseAgentId(agent.value?.id) ?? (routeAgentId.value || null);
    }
    return routeAgentId.value || null;
  };

  const getResolvedAgentId = (): string | null => {
    if (!isAiMode) {
      return getRouteAgentId();
    }
    return parseAgentId(currentSession.value?.agentId) ?? parseAgentId(agent.value?.id) ?? getRouteAgentId();
  };

  const requireResolvedAgentId = (): string => {
    const resolvedAgentId = getResolvedAgentId();
    if (resolvedAgentId === null) {
      throw new Error('缺少智能体 ID');
    }
    return resolvedAgentId;
  };

  const currentRunAgentId = computed(() => {
    if (!isAiMode) {
      return routeAgentId.value || parseAgentId(agent.value?.id);
    }
    return parseAgentId(agent.value?.id) ?? parseAgentId(agentId.value);
  });

  const isSpeakableAnswerChunk = (sessionId: string, response: AgentResponse) => {
    if (currentSession.value?.id !== sessionId || requestOptions.value.responseMode === 'report') {
      return false;
    }
    if (!response.text || response.nodeName === 'ReportGeneratorNode') {
      return false;
    }
    if (response.textType !== TextType.TEXT && response.textType !== TextType.MARK_DOWN) {
      return false;
    }
    return response.nodeName === 'AgentScopeRuntime' || response.nodeName === 'planner-reasoning';
  };

  const persistCurrentResponseMode = () => {
    if (!currentSession.value) {
      return;
    }
    saveViewToState(currentSession.value.id, {
      isStreaming,
      nodeBlocks,
      runtimeProgressEvents,
      pendingClarify,
      queuedRequests,
      responseMode: requestOptions.value.responseMode
    });
  };

  const setRequestResponseMode = (mode: SessionResponseMode) => {
    requestOptions.value.responseMode = mode;
    if (currentSession.value) {
      getSessionState(currentSession.value.id).responseMode = mode;
      persistCurrentResponseMode();
    }
  };

  const resetRunViewForAgentSwitch = (options: { closeActiveStream?: boolean } = {}) => {
    ttsPlayback.stop();
    if (currentSession.value) {
      const sessionState = getSessionState(currentSession.value.id);
      if (options.closeActiveStream) {
        sessionState.closeStream?.();
        resetStreamingState(sessionState);
      }
      persistAnswerExplainDrawerToSession(currentSession.value.id);
      saveViewToState(currentSession.value.id, {
        isStreaming,
        nodeBlocks,
        runtimeProgressEvents,
        pendingClarify,
        queuedRequests,
        responseMode: requestOptions.value.responseMode
      });
    }
    currentSession.value = null;
    currentMessages.value = [];
    userInput.value = '';
    clearSelectedImages();
    isStreaming.value = false;
    isSubmittingMessage.value = false;
    nodeBlocks.value = [];
    runtimeProgressEvents.value = [];
    showScrollToLatest.value = false;
    resetRunDiagnostics();
    pendingClarify.value = null;
    queuedRequests.value = [];
    contextUsage.value = null;
    contextUsageError.value = '';
    contextUsageLoading.value = false;
    contextCompressing.value = false;
    lastContextCompression.value = null;
    requestOptions.value.responseMode = 'normal';
    requestOptions.value.reportFormat = 'markdown';
    requestOptions.value.chatModelConfigId = undefined;
    chatModelSelectionTouched.value = false;
    chatModelOptions.value = [];
    agentModelConfigs.value = [];
  };

  const switchRunAgent = async (targetAgent: Agent) => {
    if (!isAiMode) {
      return;
    }
    const targetAgentId = parseAgentId(targetAgent.id);
    if (!targetAgentId || targetAgentId === currentRunAgentId.value || isSwitchingAgent.value) {
      return;
    }
    if (isStreaming.value || isSubmittingMessage.value) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }
    isSwitchingAgent.value = true;
    try {
      resetRunViewForAgentSwitch();
      agent.value = targetAgent;
      await loadChatModelOptions();
      await nextTick();
      await chatSessionSidebarRef.value?.focusAgentGroup(targetAgentId);
    } catch (error) {
      console.error('运行智能体处理失败:', error);
      ElMessage.error('操作失败，请稍后重试');
    } finally {
      isSwitchingAgent.value = false;
    }
  };

  const applyUserWorkbenchMeta = async (preferredAgentId?: string | null) => {
    initialRunMetaError.value = '';
    const meta = await agentRunMetaService.getUserWorkbenchMeta(preferredAgentId);
    availableAgents.value = meta.availableAgents;
    agentChatModelCache.clear();
    if (!meta.currentAgent) {
      resetRunViewForAgentSwitch();
      agent.value = {} as Agent;
      applyRuntimeChatModels([]);
      await nextTick();
      await chatSessionSidebarRef.value?.reloadSessions();
      return;
    }

    const previousAgentId = parseAgentId(agent.value?.id);
    const nextAgentId = parseAgentId(meta.currentAgent.id);
    if (previousAgentId && nextAgentId && previousAgentId !== nextAgentId) {
      resetRunViewForAgentSwitch();
    }
    agent.value = meta.currentAgent;
    applyRuntimeChatModels(meta.chatModels);
    if (nextAgentId) {
      cacheCurrentChatModels(nextAgentId);
    }
    await nextTick();
    await chatSessionSidebarRef.value?.reloadSessions();
    if (nextAgentId) {
      await chatSessionSidebarRef.value?.focusAgentGroup(nextAgentId);
    }
  };

  const reloadUserWorkbenchMeta = async (preferredAgentId?: string | null) => {
    if (!isAiMode) {
      return;
    }
    try {
      await applyUserWorkbenchMeta(preferredAgentId ?? getRouteAgentId());
    } catch (error) {
      console.error('刷新用户 Agent 工作台失败:', error);
      initialRunMetaError.value = 'Agent 列表加载失败，请稍后重试';
      ElMessage.error('刷新 Agent 列表失败');
    } finally {
      initialRunMetaLoaded.value = true;
    }
  };

  const isCurrentNormalRunRequest = (requestSeq: number, targetAgentId: string) => {
    return (
      requestSeq === runAgentRequestSeq && route.name === 'ai-agent_agent_run' && routeAgentId.value === targetAgentId
    );
  };

  const loadRunAgentByRouteId = async (targetAgentId: string) => {
    const requestSeq = ++runAgentRequestSeq;
    initialRunMetaError.value = '';
    resetRunViewForAgentSwitch({ closeActiveStream: true });
    agent.value = {} as Agent;
    applyRuntimeChatModels([]);

    if (!targetAgentId) {
      initialRunMetaLoaded.value = true;
      if (route.name === 'ai-agent_agent_run') {
        ElMessage.error('操作失败，请稍后重试');
      }
      return;
    }

    isSwitchingAgent.value = true;
    try {
      const meta = await agentRunMetaService.getRunMeta(targetAgentId);
      if (!isCurrentNormalRunRequest(requestSeq, targetAgentId)) {
        return;
      }
      if (!meta.agent) {
        throw new Error('Agent 不存在或加载失败');
      }
      agent.value = meta.agent;
      applyRuntimeChatModels(meta.chatModels);
      cacheCurrentChatModels(targetAgentId);
      await nextTick();
      if (isCurrentNormalRunRequest(requestSeq, targetAgentId)) {
        await chatSessionSidebarRef.value?.reloadSessions();
      }
    } catch (error) {
      if (!isCurrentNormalRunRequest(requestSeq, targetAgentId)) {
        return;
      }
      initialRunMetaError.value = 'Agent 加载失败，请稍后重试';
      ElMessage.error('操作失败，请稍后重试');
      console.error('运行智能体处理失败:', error);
    } finally {
      if (requestSeq === runAgentRequestSeq) {
        initialRunMetaLoaded.value = true;
        isSwitchingAgent.value = false;
      }
    }
  };

  const loadInitialRunMeta = async () => {
    try {
      if (isAiMode) {
        await applyUserWorkbenchMeta(getRouteAgentId());
        return;
      }

      await loadRunAgentByRouteId(routeAgentId.value);
    } catch (error) {
      if (isAiMode) {
        initialRunMetaError.value = 'Agent 列表加载失败，请稍后重试';
        console.error('刷新用户 Agent 工作台失败:', error);
        return;
      }
      ElMessage.error('操作失败，请稍后重试');
      console.error('运行智能体处理失败:', error);
    } finally {
      initialRunMetaLoaded.value = true;
    }
  };

  const isSessionForCurrentAgent = (session: ChatSession | null) => {
    if (isAiMode || session === null) {
      return true;
    }
    const currentAgentId = getRouteAgentId();
    const sessionAgentId = parseAgentId(session.agentId);
    return Boolean(currentAgentId && (!sessionAgentId || sessionAgentId === currentAgentId));
  };

  const selectSession = async (session: ChatSession | null) => {
    if (!isSessionForCurrentAgent(session)) {
      return;
    }
    // 运行页逻辑
    const isSameSession = currentSession.value?.id === session?.id;
    if (!isSameSession) {
      ttsPlayback.stop();
    }
    if (currentSession.value) {
      persistAnswerExplainDrawerToSession(currentSession.value.id);
      saveViewToState(currentSession.value.id, {
        isStreaming,
        nodeBlocks,
        runtimeProgressEvents,
        pendingClarify,
        queuedRequests,
        responseMode: requestOptions.value.responseMode
      });
    }
    currentSession.value = session;
    showScrollToLatest.value = false;
    resetRunDiagnostics();
    contextUsageRequestId.value += 1;
    contextUsage.value = null;
    contextUsageError.value = '';
    contextUsageLoading.value = false;
    lastContextCompression.value = null;

    try {
      if (session === null) {
        currentMessages.value = [];
        nodeBlocks.value = [];
        runtimeProgressEvents.value = [];
        isStreaming.value = false;
        pendingClarify.value = null;
        queuedRequests.value = [];
        requestOptions.value.responseMode = 'normal';
        return;
      }
      syncStateToView(session.id, {
        isStreaming,
        nodeBlocks,
        runtimeProgressEvents,
        pendingClarify,
        queuedRequests
      });
      const sessionState = getSessionState(session.id);
      requestOptions.value.responseMode = sessionState.responseMode;
      syncAnswerExplainDrawerFromSession(session.id);
      const resolvedAgentId = requireResolvedAgentId();
      const messages = await ChatService.getSessionMessages(session.id, resolvedAgentId);
      if (!isSessionForCurrentAgent(session) || currentSession.value?.id !== session.id) {
        return;
      }
      currentMessages.value = messages;
      const restoredPendingClarify = restorePendingClarify(messages);
      sessionState.pendingClarify = restoredPendingClarify;
      pendingClarify.value = restoredPendingClarify;
      scrollToBottom(false, !isSameSession);
      // 会话挂载/恢复后 reconcile：若该会话仍有进行中的持久运行，附着回放恢复运行态（内部自捕获异常）
      reconcileActiveRuntimeRun(session.id);
    } catch (error) {
      ElMessage.error('操作失败，请稍后重试');
      console.error('运行智能体处理失败:', error);
    }
  };

  const buildPendingClarifyState = (metadata: ClarifyMetadata & { originalQuery: string }): PendingClarifyState => {
    return {
      originalQuery: metadata.originalQuery,
      riskLevel: metadata.riskLevel || 'high',
      summary: metadata.summary,
      missingDimensions: metadata.missingDimensions || [],
      followUpQuestions: metadata.followUpQuestions || [],
      suggestedAssumptions: metadata.suggestedAssumptions || []
    };
  };

  const restorePendingClarify = (messages: ChatMessage[]): PendingClarifyState | null => {
    for (let index = messages.length - 1; index >= 0; index -= 1) {
      const message = messages[index];
      if (message.role !== 'assistant') {
        return null;
      }
      const metadata = parseMessageMetadata(message) as (ClarifyMetadata & Record<string, unknown>) | null;
      if (isClarifyMetadata(metadata)) {
        return buildPendingClarifyState(metadata);
      }
      if (message.messageType !== CHAT_MESSAGE_TYPES.THINKING) {
        return null;
      }
    }
    return null;
  };

  const cancelPendingClarify = () => {
    pendingClarify.value = null;
    if (currentSession.value) {
      getSessionState(currentSession.value.id).pendingClarify = null;
    }
  };

  const syncQueuedRequestsToSession = (sessionId: string, nextQueuedRequests: QueuedRequestState[]) => {
    if (!sessionId) {
      return;
    }
    const sessionState = getSessionState(sessionId);
    sessionState.queuedRequests = nextQueuedRequests;
    if (currentSession.value?.id === sessionId) {
      queuedRequests.value = nextQueuedRequests;
    }
  };

  const makeQueuedRequestId = () => {
    return `queued-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
  };

  const enqueueQueuedRequest = (sessionId: string, content: string, request: AgentRequest) => {
    if (!sessionId) {
      return;
    }
    const sessionState = getSessionState(sessionId);
    const nextQueuedRequests = [
      ...sessionState.queuedRequests,
      {
        id: makeQueuedRequestId(),
        sessionId,
        content,
        createdAt: Date.now(),
        request
      }
    ];
    syncQueuedRequestsToSession(sessionId, nextQueuedRequests);
  };

  const removeQueuedRequest = (queuedRequestId: string) => {
    if (!currentSession.value) {
      return;
    }
    const sessionId = currentSession.value.id;
    const sessionState = getSessionState(sessionId);
    const nextQueuedRequests = sessionState.queuedRequests.filter(item => item.id !== queuedRequestId);
    syncQueuedRequestsToSession(sessionId, nextQueuedRequests);
  };

  const clearQueuedRequests = () => {
    if (!currentSession.value) {
      return;
    }
    const sessionId = currentSession.value.id;
    syncQueuedRequestsToSession(sessionId, []);
  };

  const guideQueuedRequest = async (queuedRequestId: string) => {
    if (!currentSession.value) {
      return;
    }
    const sessionId = currentSession.value.id;
    const sessionState = getSessionState(sessionId);
    const queuedRequest = sessionState.queuedRequests.find(item => item.id === queuedRequestId);
    if (!queuedRequest) {
      return;
    }
    if (queuedRequest.request.humanFeedback) {
      ElMessage.info('Operation submitted');
      return;
    }
    const feedbackQuery = sessionState.lastRequest?.query || queuedRequest.request.query || queuedRequest.content;
    const guidedRequest: QueuedRequestState = {
      ...queuedRequest,
      request: {
        ...queuedRequest.request,
        query: feedbackQuery,
        humanFeedback: true,
        humanFeedbackContent: queuedRequest.content
      }
    };
    const nextQueuedRequests = [
      guidedRequest,
      ...sessionState.queuedRequests.filter(item => item.id !== queuedRequestId)
    ];
    syncQueuedRequestsToSession(sessionId, nextQueuedRequests);
    if (!sessionState.isStreaming && !sessionState.closeStream) {
      ElMessage.success('操作成功');
      await drainQueuedRequests(sessionId);
      return;
    }
    ElMessage.success('操作成功');
  };

  const applyClarifyAssumption = (assumption: string) => {
    userInput.value = `Use this assumption: ${assumption}`;
  };

  const toggleReportMode = () => {
    setRequestResponseMode(requestOptions.value.responseMode === 'report' ? 'normal' : 'report');
  };

  const clearSelectedImages = () => {
    agentRunInputAreaRef.value?.clearSelectedImages();
  };

  const getSelectedImageFiles = () => {
    return agentRunInputAreaRef.value?.getSelectedImageFiles() ?? [];
  };

  const hasSelectedImages = () => {
    return agentRunInputAreaRef.value?.hasSelectedImages() ?? false;
  };

  const sendMessage = async (sendOptions: SendMessageOptions = {}) => {
    const { clarificationResponse, displayText } = sendOptions;
    const hasImages = hasSelectedImages();
    if (shouldBlockBareAgentSend(unconsumedBusinessClarification.value, clarificationResponse)) {
      ElMessage.warning(BUSINESS_CLARIFICATION_INPUT_LOCK_HINT);
      return;
    }
    if (!userInput.value.trim() && !hasImages) {
      ElMessage.warning('请输入问题');
      return;
    }
    if (isSubmittingMessage.value) {
      return;
    }
    if (!currentSession.value) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }
    ttsPlayback.resetForNewAnswer();
    const sessionId = currentSession.value.id;
    const sessionState = getSessionState(sessionId);
    const queryContent = userInput.value.trim();
    const messageContent = displayText?.trim() || queryContent;
    const activeClarify = pendingClarify.value;
    if (hasImages && activeClarify) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }
    const shouldUseFeedback = Boolean(activeClarify);
    const requestQuery =
      activeClarify?.originalQuery ??
      (shouldUseFeedback && sessionState.lastRequest?.query
        ? sessionState.lastRequest.query
        : queryContent || '请继续');
    const humanFeedbackContent = shouldUseFeedback ? queryContent : undefined;
    const needsTitle = !currentSession.value?.title || currentSession.value.title === '新会话';
    const userMessage: ChatMessage = {
      sessionId,
      role: 'user',
      content: messageContent,
      messageType: CHAT_MESSAGE_TYPES.TEXT,
      titleNeeded: needsTitle && !activeClarify
    };

    if (isStreaming.value) {
      if (hasImages) {
        ElMessage.warning('当前操作暂不可用');
        return;
      }
      const queuedRequest: AgentRequest = attachPageContext({
        agentId: String(requireResolvedAgentId()),
        query: requestQuery,
        clarifyCheckEnabled: requestOptions.value.clarifyCheckEnabled,
        humanFeedback: shouldUseFeedback,
        humanFeedbackContent,
        rejectedPlan: false,
        threadId: sessionId,
        runtimeRequestId: createRuntimeRequestId(),
        chatModelConfigId: requestOptions.value.chatModelConfigId,
        responseMode: requestOptions.value.responseMode,
        clarificationResponse
      });
      enqueueQueuedRequest(sessionId, messageContent, queuedRequest);
      userInput.value = '';
      if (activeClarify) {
        pendingClarify.value = null;
        sessionState.pendingClarify = null;
      }
      ElMessage.success('操作成功');
      scrollToBottom(true, true);
      return;
    }

    if (!currentSession.value) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }

    isSubmittingMessage.value = true;
    try {
      const request: AgentRequest = attachPageContext({
        agentId: String(requireResolvedAgentId()),
        query: requestQuery,
        clarifyCheckEnabled: requestOptions.value.clarifyCheckEnabled,
        humanFeedback: shouldUseFeedback,
        humanFeedbackContent,
        rejectedPlan: false,
        threadId: sessionId,
        runtimeRequestId: createRuntimeRequestId(),
        chatModelConfigId: requestOptions.value.chatModelConfigId,
        responseMode: requestOptions.value.responseMode,
        clarificationResponse
      });

      userInput.value = '';
      pendingClarify.value = null;
      sessionState.pendingClarify = null;

      isSubmittingMessage.value = false;
      if (hasImages) {
        const files = getSelectedImageFiles();
        clearSelectedImages();
        await sendAgentRequest(request, files);
      } else {
        // Stream search persists the user row. Only pre-save when the first message
        // needs a session title; persistUserMessage reuses that same-text row.
        if (userMessage.titleNeeded) {
          const savedMessage = await ChatService.saveMessage(sessionId, requireResolvedAgentId(), {
            ...userMessage,
            clientRequestId: request.runtimeRequestId
          });
          currentMessages.value.push(savedMessage);
        } else {
          currentMessages.value.push({
            ...userMessage,
            id: request.runtimeRequestId
          });
        }
        getSessionState(sessionId);
        scrollToBottom(true, true);
        await sendAgentRequest(request);
      }
    } catch (error) {
      if (shouldUseFeedback && currentSession.value) {
        pendingClarify.value = activeClarify;
        sessionState.pendingClarify = activeClarify;
      }
      ElMessage.error('操作失败，请稍后重试');
      console.error(error);
    } finally {
      isSubmittingMessage.value = false;
    }
  };

  const buildExplainMetadataForNode = (request: AgentRequest, node: AgentResponse[]): MessageExplainMetadata | null => {
    if (!request.runtimeRequestId || !node.length) {
      return null;
    }
    const firstNode = node[0];
    const explainAvailable =
      firstNode.nodeName === 'AgentScopeRuntime' ||
      firstNode.nodeName === 'planner-reasoning' ||
      firstNode.textType === TextType.RESULT_SET;
    if (!explainAvailable) {
      return null;
    }
    return {
      runtimeRequestId: request.runtimeRequestId,
      explainAvailable: true,
      nodeName: firstNode.nodeName
    };
  };

  const mergeAssistantMessageMetadata = (
    node: AgentResponse[],
    request?: AgentRequest | null
  ): MessageExplainMetadata | null => {
    const metadata = node.reduce<MessageExplainMetadata>((result, response) => {
      if (response.metadata) {
        Object.assign(result, response.metadata);
      }
      return result;
    }, {});
    if (request) {
      Object.assign(metadata, buildExplainMetadataForNode(request, node) || {});
    }
    return Object.keys(metadata).length > 0 ? metadata : null;
  };

  const buildReportMessageMetadataJson = (
    request?: AgentRequest | null,
    reportNode?: AgentResponse[]
  ) => {
    if (!request?.runtimeRequestId) {
      return undefined;
    }
    const metadata: Record<string, unknown> = {
      runtimeRequestId: request.runtimeRequestId,
      explainAvailable: true,
      nodeName: 'ReportGeneratorNode'
    };
    const followUps = reportNode?.[0]?.metadata?.analysisFollowUps;
    if (Array.isArray(followUps) && followUps.length > 0) {
      metadata.analysisFollowUps = followUps;
    }
    return JSON.stringify(metadata);
  };

  const saveAssistantNodeMessage = async (
    sessionId: string,
    node: AgentResponse[],
    request?: AgentRequest | null
  ): Promise<ChatMessage | null> => {
    if (!node || !node.length) {
      return null;
    }
    // /sessions/messages only accepts role=user. Assistant, result-set, thinking and
    // skill-flow rows are persisted by the stream. Posting them here 400s and the
    // complete handler then wipes the live answer.
    return null;
  };

  const saveGroupedAssistantMessages = async (
    sessionId: string,
    blocks: AgentResponse[][],
    request?: AgentRequest | null,
    options: SaveGroupedAssistantOptions = {}
  ): Promise<ChatMessage[]> => {
    if (!blocks || blocks.length === 0) {
      return [];
    }

    const groups = splitNodeBlocks(blocks);
    const savedMessages: ChatMessage[] = [];
    const saveFinalBlocks = options.saveFinalBlocks ?? true;
    if (!saveFinalBlocks) {
      return savedMessages;
    }

    await groups.finalBlocks.reduce<Promise<void>>(
      (promise, block) =>
        promise.then(async () => {
          const savedMessage = await saveAssistantNodeMessage(sessionId, block, request);
          if (savedMessage) {
            savedMessages.push(savedMessage);
          }
        }),
      Promise.resolve()
    );
    return savedMessages;
  };

  const saveGroupedAssistantMessagesExceptReports = async (
    sessionId: string,
    blocks: AgentResponse[][],
    request?: AgentRequest | null
  ): Promise<ChatMessage[]> => {
    const nonReportBlocks = (blocks || []).filter(
      block => block.length === 0 || block[0].nodeName !== 'ReportGeneratorNode'
    );
    return saveGroupedAssistantMessages(sessionId, nonReportBlocks, request, {
      saveFinalBlocks: true,
      thinkingExpanded: false
    });
  };

  const appendSavedMessagesToCurrentSession = (sessionId: string, savedMessages: Array<ChatMessage | null>) => {
    if (currentSession.value?.id !== sessionId) {
      return;
    }
    const visibleSavedMessages = savedMessages.filter((message): message is ChatMessage => Boolean(message));
    if (visibleSavedMessages.length > 0) {
      currentMessages.value.push(...visibleSavedMessages);
    }
  };

  const refreshCurrentSessionAfterStream = async (sessionId: string) => {
    if (currentSession.value?.id !== sessionId) {
      return;
    }
    await reloadCurrentMessages();
    await nextTick();
    scrollToBottomIfNeeded();
  };

  const buildRequestFromQueueItem = (queuedRequest: QueuedRequestState): AgentRequest => {
    return {
      ...queuedRequest.request,
      runtimeRequestId: createRuntimeRequestId(),
      threadId: queuedRequest.sessionId
    };
  };

  const sendQueuedRequest = async (queuedRequest: QueuedRequestState) => {
    const sessionId = queuedRequest.sessionId;
    const nextRequest = buildRequestFromQueueItem(queuedRequest);
    const queuedUserMessage: ChatMessage = {
      sessionId,
      role: 'user',
      content: queuedRequest.content,
      messageType: CHAT_MESSAGE_TYPES.TEXT,
      titleNeeded: false
    };
    try {
      if (currentSession.value?.id === sessionId) {
        currentMessages.value.push({
          ...queuedUserMessage,
          id: nextRequest.runtimeRequestId
        });
        scrollToBottom(true, true);
      }
      await sendAgentRequest(nextRequest);
    } catch (error) {
      ElMessage.error('操作失败，请稍后重试');
      console.error('运行智能体处理失败:', error);
    }
  };

  const drainQueuedRequests = async (sessionId: string) => {
    const sessionState = getSessionState(sessionId);
    if (sessionState.isStreaming || sessionState.closeStream) {
      return;
    }
    const nextQueuedRequest = sessionState.queuedRequests[0];
    if (!nextQueuedRequest) {
      return;
    }
    const remainingQueuedRequests = sessionState.queuedRequests.slice(1);
    syncQueuedRequestsToSession(sessionId, remainingQueuedRequests);
    await sendQueuedRequest(nextQueuedRequest);
  };

  const resetStreamingState = (sessionState: SessionRuntimeState) => {
    sessionState.isStreaming = false;
    sessionState.nodeBlocks = [];
    sessionState.runtimeProgressEvents = [];
    sessionState.persistedBlockCount = 0;
    sessionState.closeStream = null;
    sessionState.htmlReportContent = '';
    sessionState.htmlReportSize = 0;
    sessionState.markdownReportContent = '';
    sessionState.activeRuntimeRunId = null;
    sessionState.lastRuntimeEventSeq = 0;
  };

  const runtimeProgressKey = (progress: RuntimeProgressEvent) =>
    [
      progress.childRuntimeRequestId || progress.runtimeRequestId || '',
      progress.stageCode,
      progress.toolExecutionSeq || '',
      progress.resolverId || progress.nodeId || '',
      progress.displayName || ''
    ].join(':');

  const mergeRuntimeProgress = (
    events: RuntimeProgressEvent[],
    progress: RuntimeProgressEvent
  ): RuntimeProgressEvent[] => {
    const next = [...events];
    const key = runtimeProgressKey(progress);
    const existingIndex = next.findIndex(item => runtimeProgressKey(item) === key);
    if (existingIndex >= 0) {
      const existing = next[existingIndex];
      next[existingIndex] = {
        ...existing,
        ...progress,
        clientReceivedAtMs: existing.clientReceivedAtMs ?? progress.clientReceivedAtMs
      };
    } else {
      next.push(progress);
    }
    return next.slice(-MAX_RUNTIME_PROGRESS_EVENTS);
  };

  /**
   * 将 durable 事件合入会话进度时间线，并推进/持久化本地 seq 游标（刷新后续传用）。
   */
  const applyRuntimeRunEvent = (sessionId: string, sessionState: SessionRuntimeState, event: RuntimeEventResp) => {
    // seq 后端是 Long，全局 ToStringSerializer 后 JSON 里是字符串，必须先转数值再比较
    const seq = Number(event.seq);
    if (Number.isFinite(seq) && seq > sessionState.lastRuntimeEventSeq) {
      sessionState.lastRuntimeEventSeq = seq;
    }
    const progress = toRuntimeProgressEvent(event);
    if (progress) {
      const nextEvents = mergeRuntimeProgress(sessionState.runtimeProgressEvents, progress);
      sessionState.runtimeProgressEvents = nextEvents;
      if (currentSession.value?.id === sessionId) {
        runtimeProgressEvents.value = nextEvents;
        scrollToBottomIfNeeded();
      }
    }
    persistSessionState(sessionId);
  };

  /**
   * durable 运行终态收尾：最终回答由服务端持久化，重新拉取会话消息后驱动排队请求。
   */
  const finishAttachedRuntimeRun = async (sessionId: string, sessionState: SessionRuntimeState) => {
    resetStreamingState(sessionState);
    if (currentSession.value?.id === sessionId) {
      isStreaming.value = false;
      nodeBlocks.value = [];
      runtimeProgressEvents.value = [];
      await refreshCurrentSessionAfterStream(sessionId);
    }
    persistSessionState(sessionId);
    await drainQueuedRequests(sessionId);
  };

  /**
   * 为会话附着 durable 事件流：afterSeq 取本地已有最大 seq，断线重连与去重由 attach 内部保证。
   */
  const attachRuntimeRunToSession = (sessionId: string, runId: string) => {
    const sessionState = getSessionState(sessionId);
    sessionState.activeRuntimeRunId = runId;
    sessionState.isStreaming = true;
    if (currentSession.value?.id === sessionId) {
      isStreaming.value = true;
    }
    const attachment = GraphService.attachRuntimeRun({
      runId,
      afterSeq: sessionState.lastRuntimeEventSeq,
      onEvent: event => applyRuntimeRunEvent(sessionId, sessionState, event),
      onTerminal: () => {
        sessionState.closeStream = null;
        finishAttachedRuntimeRun(sessionId, sessionState).catch(finishError => {
          console.error('持久运行收尾失败, runId=%s:', runId, finishError);
        });
      },
      onError: error => {
        console.error('运行事件流连接失败, runId=%s:', runId, error);
        ElMessage.error('运行事件流连接中断，请刷新页面重试');
        // 保留 run id 与 seq 游标：刷新后 reconcile 可从断点继续
        sessionState.isStreaming = false;
        sessionState.closeStream = null;
        if (currentSession.value?.id === sessionId) {
          isStreaming.value = false;
        }
        persistSessionState(sessionId);
      }
    });
    sessionState.closeStream = () => attachment.close();
    persistSessionState(sessionId);
  };

  /**
   * 刷新/切换会话时 reconcile：查询该会话进行中的持久运行时 run（threadId 过滤，字段以后端 dto 为准），
   * 存在则以本地最大 seq 作为 afterSeq 附着回放，再恢复 UI 运行态，避免进行中的回答无声丢失。
   */
  const reconcileActiveRuntimeRun = async (sessionId: string) => {
    const sessionState = getSessionState(sessionId);
    // 本页已有活跃流（老链路 SSE 或已附着的 durable 流）时无需 reconcile
    if (sessionState.closeStream) {
      return;
    }
    try {
      const page = await runtimeRunService.page({ threadId: sessionId, current: 1, size: 20 });
      const activeRun = (page.data || []).find(run => isActiveRuntimeRunState(run.state));
      if (!activeRun) {
        if (sessionState.activeRuntimeRunId) {
          // 服务端已终态而本地仍留有游标：清理游标（最终回答已随会话消息落库）
          sessionState.activeRuntimeRunId = null;
          sessionState.lastRuntimeEventSeq = 0;
          persistSessionState(sessionId);
        }
        return;
      }
      if (currentSession.value?.id !== sessionId || sessionState.closeStream) {
        return;
      }
      attachRuntimeRunToSession(sessionId, activeRun.id);
    } catch (error) {
      // 查询失败不阻塞会话打开；HTTP 失败已由全局请求层向用户提示
      console.warn('恢复进行中的运行失败:', error);
    }
  };

  const finishStreamingWithError = async (
    sessionId: string,
    sessionState: SessionRuntimeState,
    request: AgentRequest,
    error: Error
  ) => {
    ttsPlayback.stop();
    const displayMessage = normalizeRuntimeErrorMessage(error);
    releaseClarificationLockFromError(error, request);
    ElMessage.error(displayMessage);
    console.error('运行智能体处理失败:', error);
    const discardPartialOutput = shouldDiscardPartialOutput(error);
    if (discardPartialOutput) {
      sessionState.nodeBlocks = [];
      sessionState.htmlReportContent = '';
      sessionState.htmlReportSize = 0;
      sessionState.markdownReportContent = '';
      if (currentSession.value?.id === sessionId) {
        nodeBlocks.value = [];
      }
    }
    const liveBlocks = discardPartialOutput ? [] : [...sessionState.nodeBlocks];
    const errorMessage: ChatMessage = {
      sessionId,
      role: 'assistant',
      content: displayMessage,
      messageType: CHAT_MESSAGE_TYPES.TEXT,
      id: `local-error-${request.runtimeRequestId || Date.now()}`,
      metadata: JSON.stringify(buildPersistedRuntimeErrorMetadata(error, request.runtimeRequestId))
    };
    resetStreamingState(sessionState);
    sessionState.pendingClarify = null;
    if (liveBlocks.length > 0) {
      sessionState.nodeBlocks = liveBlocks;
    }
    if (currentSession.value?.id === sessionId) {
      isStreaming.value = false;
      runtimeProgressEvents.value = [];
      pendingClarify.value = null;
      await refreshCurrentSessionAfterStream(sessionId);
      retainLiveNodeBlocksIfUnpersisted(sessionId, sessionState);
      currentMessages.value.push(errorMessage);
    }
    await drainQueuedRequests(sessionId);
  };

  const sendAgentRequest = async (request: AgentRequest, imageFiles: File[] = []) => {
    const resolvedSessionId = request.threadId ?? currentSession.value?.id;
    if (!resolvedSessionId) {
      ElMessage.error('操作失败，请稍后重试');
      return;
    }
    const sessionId: string = resolvedSessionId;
    const sessionState = getSessionState(sessionId);
    let currentNodeName: string | null = null;
    let currentBlockIndex: number = -1;
    let receivedClarification = false;
    try {
      // 运行页逻辑
      isStreaming.value = true;
      nodeBlocks.value = [];
      runtimeProgressEvents.value = [];

      // 运行页逻辑
      resetReportState(sessionState, request);

      // 运行页逻辑
      const streamHandlers = {
        onMessage: handleStreamResponse,
        onError: handleStreamError,
        onComplete: handleStreamComplete,
        onRuntimeProgress: handleRuntimeProgress
      };
      const closeStream =
        imageFiles.length > 0
          ? await GraphService.streamSearchMultipart(request, imageFiles, {
              ...streamHandlers,
              onUserMessage: async savedMessage => {
                if (currentSession.value?.id === sessionId) {
                  currentMessages.value.push(savedMessage);
                  scrollToBottom(true, true);
                }
              }
            })
          : await GraphService.streamSearch(request, streamHandlers);
      // 运行页逻辑
      sessionState.closeStream = closeStream;
    } catch (error) {
      await finishStreamingWithError(
        sessionId,
        sessionState,
        request,
        error instanceof Error ? error : createRuntimeStreamError()
      );
    }

    async function handleRuntimeProgress(progress: RuntimeProgressEvent) {
      // This SSE is already this turn. Durable runs mint a different runtimeRequestId
      // than the browser request; dropping on inequality emptied the timeline.
      const progressWithClientTime: RuntimeProgressEvent = {
        ...progress,
        clientReceivedAtMs: Date.now()
      };
      const nextEvents = mergeRuntimeProgress(sessionState.runtimeProgressEvents, progressWithClientTime);
      sessionState.runtimeProgressEvents = nextEvents;
      if (currentSession.value?.id === sessionId) {
        runtimeProgressEvents.value = nextEvents;
        scrollToBottomIfNeeded();
      }
    }

    async function handleStreamResponse(response: AgentResponse) {
      if (response.error) {
        throw createRuntimeStreamError(response.text, normalizeRuntimeErrorMetadata(response.metadata));
      }

      if (sessionState.lastRequest) {
        sessionState.lastRequest.threadId = response.threadId;
      }
      if (isClarifyMetadata(response.metadata)) {
        receivedClarification = true;
        const nextPendingClarify = buildPendingClarifyState(response.metadata);
        sessionState.pendingClarify = nextPendingClarify;
        if (currentSession.value?.id === sessionId) {
          pendingClarify.value = nextPendingClarify;
        }
      }

      // 运行页逻辑
      if (isSpeakableAnswerChunk(sessionId, response)) {
        ttsPlayback.appendText(response.text);
      }

      if (response.nodeName === 'ReportGeneratorNode') {
        const isNewNode: boolean = currentNodeName === null || response.nodeName !== currentNodeName;

        if (isNewNode) {
          // 运行页逻辑
          const newBlock: AgentResponse = {
            ...response,
            text: response.text
          };
          sessionState.nodeBlocks.push([newBlock]);
          currentBlockIndex = sessionState.nodeBlocks.length - 1;
          currentNodeName = response.nodeName;
        }
        // 运行页逻辑
        if (response.textType === 'HTML') {
          sessionState.htmlReportContent += response.text;
          sessionState.htmlReportSize = sessionState.htmlReportContent.length;

          // 运行页逻辑
          const reportNode = sessionState.nodeBlocks.find(
            (block: AgentResponse[]) =>
              block.length > 0 && block[0].nodeName === 'ReportGeneratorNode' && block[0].textType === 'HTML'
          );
          if (reportNode) {
            reportNode[0].text = `HTML report generating... received ${sessionState.htmlReportSize} chars`;
          } else {
            sessionState.nodeBlocks.push([
              {
                ...response,
                text: `HTML report generating... received ${sessionState.htmlReportSize} chars`
              }
            ]);
          }
        }
        // 运行页逻辑
        else if (response.textType === 'MARK_DOWN') {
          sessionState.markdownReportContent += response.text;
          const reportNode = sessionState.nodeBlocks.find(
            (block: AgentResponse[]) =>
              block.length > 0 && block[0].nodeName === 'ReportGeneratorNode' && block[0].textType === 'MARK_DOWN'
          );
          if (reportNode) {
            reportNode[0].text = `Markdown report generating... received ${sessionState.markdownReportContent.length} chars`;
          } else {
            sessionState.nodeBlocks.push([
              {
                ...response,
                text: `Markdown report generating... received ${sessionState.markdownReportContent.length} chars`
              }
            ]);
          }
        }
      } else if (response.textType === TextType.RESULT_SET) {
        currentNodeName = 'result_set';
        // 运行页逻辑
        const newBlock: AgentResponse = {
          ...response,
          text: response.text
        };
        sessionState.nodeBlocks.push([newBlock]);
        currentBlockIndex = sessionState.nodeBlocks.length - 1;
      } else {
        // 运行页逻辑
        const isNewNode: boolean = currentNodeName === null || response.nodeName !== currentNodeName;

        if (isNewNode) {
          // 运行页逻辑
          const newBlock: AgentResponse = {
            ...response,
            text: response.text
          };
          sessionState.nodeBlocks.push([newBlock]);
          currentBlockIndex = sessionState.nodeBlocks.length - 1;
          currentNodeName = response.nodeName;
        } else {
          // 运行页逻辑
          if (currentBlockIndex >= 0 && sessionState.nodeBlocks[currentBlockIndex]) {
            const newBlock: AgentResponse = {
              ...response,
              text: response.text
            };
            sessionState.nodeBlocks[currentBlockIndex].push(newBlock);
          } else {
            // 运行页逻辑
            const newBlock: AgentResponse = {
              ...response,
              text: response.text
            };
            sessionState.nodeBlocks.push([newBlock]);
            currentBlockIndex = sessionState.nodeBlocks.length - 1;
            currentNodeName = response.nodeName;
          }
        }
      }

      // 运行页逻辑
      if (currentSession.value?.id === sessionId) {
        nodeBlocks.value = sessionState.nodeBlocks;
        scrollToBottomIfNeeded();
      }
    }

    async function handleStreamError(error: Error) {
      currentNodeName = null;
      await finishStreamingWithError(sessionId, sessionState, request, error);
    }

    async function handleStreamComplete() {
      try {
        if (currentSession.value?.id === sessionId) {
          ttsPlayback.flush();
        }
        currentNodeName = null;
        sessionState.closeStream?.();
        await refreshCurrentSessionAfterStream(sessionId);
        retainLiveNodeBlocksIfUnpersisted(sessionId, sessionState);
        sessionState.isStreaming = false;
        sessionState.persistedBlockCount = 0;
        if (currentSession.value?.id === sessionId) {
          isStreaming.value = false;
        }
      } catch (error) {
        console.error('运行智能体处理失败:', error);
      } finally {
        sessionState.isStreaming = false;
        sessionState.persistedBlockCount = 0;
        sessionState.closeStream = null;
        sessionState.runtimeProgressEvents = [];
        if (!receivedClarification) {
          sessionState.pendingClarify = null;
        }
        if (currentSession.value?.id === sessionId) {
          isStreaming.value = false;
          runtimeProgressEvents.value = [];
          pendingClarify.value = sessionState.pendingClarify;
        }
        await drainQueuedRequests(sessionId);
      }
    }
  };

  const triggerReportDownload = (content: string, mimeType: string, extension: 'md' | 'html') => {
    if (!content) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }

    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `report_${new Date().getTime()}.${extension}`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    ElMessage.success('操作成功');
  };

  const downloadMarkdownReportFromMessage = (content: string) => {
    triggerReportDownload(content, 'text/markdown;charset=utf-8', 'md');
  };

  const downloadHtmlReportFromMessage = (content: string, sourceFormat: 'markdown' | 'html' = 'markdown') => {
    const html = buildReportHtmlDocument(content, { sourceFormat });
    triggerReportDownload(html, 'text/html;charset=utf-8', 'html');
  };

  const downloadReportFromMessage = (message: ChatMessage, format?: ReportDownloadFormat) => {
    if (!message?.content) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }
    if (String(message.messageType || '').toLowerCase() === CHAT_MESSAGE_TYPES.HTML_REPORT) {
      downloadHtmlReportFromMessage(message.content, 'html');
      return;
    }
    if (String(message.messageType || '').toLowerCase() !== CHAT_MESSAGE_TYPES.MARKDOWN_REPORT) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }
    if (format === 'html') {
      downloadHtmlReportFromMessage(message.content, 'markdown');
      return;
    }
    downloadMarkdownReportFromMessage(message.content);
  };

  // 专业解读：基于当轮快照重新生成 LLM 叙事报告，失败时后端自动回落标准报告并通过 degraded 标记。
  const generateProfessionalReportForMessage = async (message: ChatMessage) => {
    if (!message || String(message.messageType || '').toLowerCase() !== CHAT_MESSAGE_TYPES.MARKDOWN_REPORT) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }
    const session = currentSession.value;
    if (!session?.id) {
      ElMessage.warning('会话不存在，无法生成专业解读');
      return;
    }
    if (isStreaming.value) {
      ElMessage.warning('智能体运行中，请稍后再试');
      return;
    }
    if (professionalReportLoadingRequestId.value) {
      ElMessage.warning('专业解读生成中，请稍候');
      return;
    }
    const metadata = parseMessageMetadata(message);
    const runtimeRequestId = message.runtimeRequestId || metadata?.runtimeRequestId;
    if (typeof runtimeRequestId !== 'string' || !runtimeRequestId.trim()) {
      ElMessage.warning('当前报告缺少运行标识，无法生成专业解读');
      return;
    }
    professionalReportLoadingRequestId.value = runtimeRequestId;
    let degraded: boolean | undefined;
    try {
      let content = '';
      await ChatService.streamGenerateReport(
        session.id,
        runtimeRequestId,
        requireResolvedAgentId(),
        {
          onChunk: chunk => {
            content += chunk;
            message.content = content;
          },
          onComplete: async () => {
            const nextMetadata: Record<string, unknown> = { ...(metadata || {}), runtimeRequestId };
            if (degraded) {
              nextMetadata.reportLevel = 'standard';
              nextMetadata.degraded = true;
              ElMessage.warning('专业解读生成失败，已展示标准报告');
            } else {
              nextMetadata.reportLevel = 'professional';
              nextMetadata.degraded = false;
              ElMessage.success('已生成专业解读');
            }
            message.metadata = JSON.stringify(nextMetadata);
          },
          onError: async () => {
            ElMessage.error('专业解读生成失败，请稍后重试');
          }
        },
        { reportLevel: 'professional', onMetadata: meta => (degraded = meta.degraded) }
      );
    } catch (error) {
      console.error('专业解读生成失败:', error);
      ElMessage.error('专业解读生成失败，请稍后重试');
    } finally {
      professionalReportLoadingRequestId.value = '';
    }
  };

  // 运行页逻辑
  const nodeBlockGroups = computed(() => splitNodeBlocks(nodeBlocks.value));
  const thinkingNodeBlocks = computed(() => nodeBlockGroups.value.thinkingBlocks);
  const finalNodeBlocks = computed(() => nodeBlockGroups.value.finalBlocks);
  const hasFinalAnswerOutput = computed(() => finalNodeBlocks.value.length > 0);
  const isWaitingForAnswerOutput = computed(() => isStreaming.value && finalNodeBlocks.value.length === 0);
  // 运行页逻辑
  const resetReportState = (sessionState: SessionRuntimeState, request: AgentRequest) => {
    sessionState.isStreaming = true;
    sessionState.nodeBlocks = [];
    sessionState.runtimeProgressEvents = [];
    sessionState.persistedBlockCount = 0;
    sessionState.lastRequest = request;
    sessionState.responseMode = request.responseMode === 'report' ? 'report' : 'normal';
    sessionState.htmlReportContent = '';
    sessionState.htmlReportSize = 0;
    sessionState.markdownReportContent = '';
  };
  const createRuntimeRequestId = () => {
    if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
      return crypto.randomUUID();
    }
    return `req-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`;
  };

  const latestExplainMessage = computed<ChatMessage | null>(() => {
    for (let index = currentMessages.value.length - 1; index >= 0; index -= 1) {
      const message = currentMessages.value[index];
      if (message.role !== 'assistant') {
        continue;
      }
      const metadata = parseMessageMetadata(message);
      if (metadata?.explainAvailable && metadata.runtimeRequestId) {
        return message;
      }
    }
    return null;
  });

  const latestExplainRuntimeRequestId = computed(() => {
    const message = latestExplainMessage.value;
    if (!message) {
      return null;
    }
    return parseMessageMetadata(message)?.runtimeRequestId ?? null;
  });

  const {
    diagnosticsHostRef,
    traceLoading,
    orchestrationTraceLoading,
    syncAnswerExplainDrawerFromSession,
    persistAnswerExplainDrawerToSession,
    handleAnswerExplainDrawerStateChange,
    openLatestAnswerExplain,
    openOrchestrationTrace,
    openTraceDialog,
    resetRunDiagnostics
  } = useRunDiagnostics({
    currentSession,
    canViewThinking,
    latestExplainRuntimeRequestId,
    getSessionState,
    saveViewToState,
    isStreaming,
    nodeBlocks,
    pendingClarify,
    queuedRequests
  });

  const scrollToBottom = (smooth = false, force = false) => {
    conversationPanelRef.value?.scrollToBottom(smooth, force);
  };

  const scrollToBottomIfNeeded = () => {
    conversationPanelRef.value?.scrollToBottomIfNeeded();
  };

  const resendEditedQuestion = async (nextQuestion: string) => {
    if (shouldBlockBareAgentSend(unconsumedBusinessClarification.value)) {
      ElMessage.warning(BUSINESS_CLARIFICATION_INPUT_LOCK_HINT);
      return;
    }
    if (!nextQuestion) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }
    if (hasSelectedImages()) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }
    if (currentSession.value) {
      getSessionState(currentSession.value.id).pendingClarify = null;
    }
    pendingClarify.value = null;
    userInput.value = nextQuestion;
    await nextTick();
    await sendMessage();
    scrollToBottom(true, true);
  };

  const submitSuggestedReply = async (submission: SuggestedReplySubmission) => {
    const content = submission.value.trim();
    if (!content || !currentSession.value || hasSelectedImages() || isSubmittingMessage.value || isStreaming.value) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }
    userInput.value = content;
    await nextTick();
    await sendMessage({
      clarificationResponse: submission.clarificationResponse,
      displayText: submission.displayText
    });
    scrollToBottom(true, true);
  };

  const submitFlowAction = async (actionRequest: AgentUiActionRequest) => {
    if (!currentSession.value || isSubmittingMessage.value || isStreaming.value || hasSelectedImages()) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }
    if (!actionRequest.flowInstanceId) {
      ElMessage.error('流程状态已失效，请刷新后重试');
      return;
    }
    const sessionId = currentSession.value.id;
    const displayText = actionRequest.displayText.trim() || '继续流程';
    isSubmittingMessage.value = true;
    try {
      const request: AgentRequest = attachPageContext({
        agentId: String(requireResolvedAgentId()),
        threadId: sessionId,
        runtimeRequestId: createRuntimeRequestId(),
        query: displayText,
        rejectedPlan: false,
        chatModelConfigId: requestOptions.value.chatModelConfigId,
        responseMode: requestOptions.value.responseMode,
        flowInstanceId: actionRequest.flowInstanceId,
        flowAction: actionRequest.flowAction
      });
      currentMessages.value.push({
        sessionId,
        role: 'user',
        content: displayText,
        messageType: CHAT_MESSAGE_TYPES.TEXT,
        id: request.runtimeRequestId
      });
      isSubmittingMessage.value = false;
      scrollToBottom(true, true);
      await sendAgentRequest(request);
    } catch (error) {
      ElMessage.error('流程操作失败，请稍后重试');
      console.error('提交 FLOW 操作失败:', error);
    } finally {
      isSubmittingMessage.value = false;
    }
  };

  // 运行页逻辑
  const handlePresetQuestionClick = async (question: string) => {
    // 运行页逻辑
    if (!currentSession.value) {
      try {
        const newSession = await ChatService.createSession(requireResolvedAgentId(), '新会话');
        currentSession.value = newSession;
        setRequestResponseMode('normal');
        ElMessage.success('操作成功');
      } catch (error) {
        ElMessage.error('操作失败，请稍后重试');
        return;
      }
    }

    userInput.value = question;
    // 运行页逻辑
    nextTick().then(() => {
      sendMessage();
    });
  };

  // 运行页逻辑
  const stopStreaming = async () => {
    if (!currentSession.value) {
      ElMessage.warning('当前操作暂不可用');
      return;
    }

    const sessionId = currentSession.value.id;
    const sessionState = getSessionState(sessionId);
    ttsPlayback.stop();

    try {
      // 运行页逻辑
      if (!sessionState.closeStream) {
        ElMessage.warning('当前操作暂不可用');
        return;
      }

      // 新 durable 链路：会话携带 runtime run id 时先请求服务端取消。
      // 老链路 /ai/stream/search 用 lastRequest.runtimeRequestId 调 /chat/runtime/stop，
      // 避免只断开前端 SSE 而服务端继续烧钱。
      const activeRunId = sessionState.activeRuntimeRunId;
      const runtimeRequestId = sessionState.lastRequest?.runtimeRequestId;
      if (activeRunId) {
        try {
          await runtimeRunService.cancel(activeRunId, '用户手动停止');
        } catch (cancelError) {
          // 服务端取消失败不阻塞本地停止，但任务可能仍在后台运行，需让用户可见
          console.error('取消持久运行失败, runId=%s:', activeRunId, cancelError);
          ElMessage.warning('服务端取消请求失败，任务可能仍在后台运行');
        }
      } else if (runtimeRequestId) {
        try {
          await GraphService.stopRuntime({
            agentId: sessionState.lastRequest?.agentId,
            threadId: String(sessionId),
            runtimeRequestId
          });
        } catch (cancelError) {
          console.error('取消 stream/search 失败, runtimeRequestId=%s:', runtimeRequestId, cancelError);
          ElMessage.warning('服务端取消请求失败，任务可能仍在后台运行');
        }
      }

      // 运行页逻辑
      sessionState.closeStream();
      sessionState.closeStream = null;

      const liveBlocks = [...(sessionState.nodeBlocks || [])];
      resetStreamingState(sessionState);
      sessionState.pendingClarify = null;
      if (liveBlocks.length > 0) {
        sessionState.nodeBlocks = liveBlocks;
      }

      if (currentSession.value?.id === sessionId) {
        isStreaming.value = false;
        runtimeProgressEvents.value = [];
        pendingClarify.value = null;
      }

      await refreshCurrentSessionAfterStream(sessionId);
      retainLiveNodeBlocksIfUnpersisted(sessionId, sessionState);

      ElMessage.success('操作成功');
    } catch (error) {
      console.error('运行智能体处理失败:', error);
      ElMessage.error('操作失败，请稍后重试');
      // 运行页逻辑
      resetStreamingState(sessionState);
      sessionState.pendingClarify = null;
      if (currentSession.value?.id === sessionId) {
        isStreaming.value = false;
        nodeBlocks.value = [];
        runtimeProgressEvents.value = [];
        pendingClarify.value = null;
      }
    }
  };

  watch(
    () => ({ name: route.name, agentId: routeAgentId.value }),
    async (current, previous) => {
      if (isAiMode || current.name !== 'ai-agent_agent_run') {
        return;
      }
      if (previous?.name === current.name && previous.agentId === current.agentId) {
        return;
      }
      await loadRunAgentByRouteId(current.agentId);
      applyRunPrefillQuery();
    }
  );

  watch(
    () => [route.query.pageContextKeys, route.query.pageContextObjectType, route.query.q],
    () => {
      syncPageContextFromRoute();
      applyRunPrefillQuery();
    },
    { immediate: true }
  );

  // 运行页逻辑
  onMounted(async () => {
    canViewThinking.value = false;
    syncPageContextFromRoute();
    await loadInitialRunMeta();
    applyRunPrefillQuery();
  });

  onBeforeUnmount(() => {
    ttsPlayback.stop();
    clearSelectedImages();
  });

  return {
    agent,
    availableAgents,
    isSwitchingAgent,
    chatSessionSidebarRef,
    agentRunInputAreaRef,
    conversationPanelRef,
    sidebarCollapsed,
    currentSession,
    currentMessages,
    userInput,
    isStreaming,
    isSubmittingMessage,
    pendingClarify,
    queuedRequests,
    canViewThinking,
    initialRunMetaLoaded,
    initialRunMetaError,
    requestOptions,
    showScrollToLatest,
    diagnosticsHostRef,
    traceLoading,
    orchestrationTraceLoading,
    latestExplainMessage,
    latestExplainRuntimeRequestId,
    nodeBlocks,
    agentId,
    currentRunAgentId,
    resultSetPageSize,
    chatModelOptions,
    chatModelsLoading,
    contextUsage,
    contextUsageLoading,
    contextCompressing,
    contextUsageError,
    defaultChatModel,
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
    switchRunAgent,
    reloadUserWorkbenchMeta,
    loadSessionContextUsage,
    compressSessionContext,
    selectChatModel,
    options,
    selectSession,
    collapseSidebar,
    expandSidebar,
    createSidebarSession,
    clearSidebarSessions,
    sendMessage,
    scrollToBottom,
    guideQueuedRequest,
    removeQueuedRequest,
    clearQueuedRequests,
    cancelPendingClarify,
    applyClarifyAssumption,
    toggleReportMode,
    hasSelectedImages,
    resendEditedQuestion,
    parseMessageMetadata,
    downloadMarkdownReportFromMessage,
    downloadReportFromMessage,
    resetReportState,
    handlePresetQuestionClick,
    stopStreaming,
    openLatestAnswerExplain,
    openOrchestrationTrace,
    openTraceDialog,
    handleAnswerExplainDrawerStateChange,
    deleteSessionState
  };
}
