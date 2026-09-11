import { computed, ref, type Ref } from 'vue';
import { ElMessage } from 'element-plus';
import type AgentRunDiagnosticsHost from '@/views/ai-agent/components/run/AgentRunDiagnosticsHost.vue';
import type { AnswerTraceExplain, ChatSession } from '@/views/ai-agent/services/chat';
import type { AgentResponse } from '@/views/ai-agent/services/graph';
import type {
  PendingClarifyState,
  QueuedRequestState,
  SessionRuntimeState
} from '@/views/ai-agent/services/sessionStateManager';

export type AnswerExplainDrawerSnapshot = {
  answerExplain: AnswerTraceExplain | null;
  visible: boolean;
};

type RuntimeViewState = {
  isStreaming: Ref<boolean>;
  nodeBlocks: Ref<AgentResponse[][]>;
  pendingClarify: Ref<PendingClarifyState | null>;
  queuedRequests: Ref<QueuedRequestState[]>;
};

type UseRunDiagnosticsOptions = RuntimeViewState & {
  currentSession: Ref<ChatSession | null>;
  canViewThinking: Ref<boolean>;
  latestExplainRuntimeRequestId: Ref<string | null>;
  getSessionState: (sessionId: string) => SessionRuntimeState;
  saveViewToState: (sessionId: string, viewState: RuntimeViewState) => void;
};

export function useRunDiagnostics(options: UseRunDiagnosticsOptions) {
  const diagnosticsHostRef = ref<InstanceType<typeof AgentRunDiagnosticsHost> | null>(null);

  const traceLoading = computed(() => Boolean(diagnosticsHostRef.value?.traceLoading));
  const orchestrationTraceLoading = computed(() => Boolean(diagnosticsHostRef.value?.orchestrationTraceLoading));

  const syncAnswerExplainDrawerFromSession = (sessionId: string) => {
    const sessionState = options.getSessionState(sessionId);
    diagnosticsHostRef.value?.setAnswerExplainSnapshot({
      answerExplain: sessionState.answerExplain,
      visible: options.canViewThinking.value ? sessionState.answerExplainVisible : false
    });
  };

  const persistAnswerExplainDrawerToSession = (sessionId: string) => {
    const drawerSnapshot = diagnosticsHostRef.value?.getAnswerExplainSnapshot();
    if (!drawerSnapshot) {
      return;
    }
    const sessionState = options.getSessionState(sessionId);
    sessionState.answerExplain = drawerSnapshot.answerExplain;
    sessionState.answerExplainVisible = drawerSnapshot.visible;
    options.saveViewToState(sessionId, {
      isStreaming: options.isStreaming,
      nodeBlocks: options.nodeBlocks,
      pendingClarify: options.pendingClarify,
      queuedRequests: options.queuedRequests
    });
  };

  const handleAnswerExplainDrawerStateChange = (snapshot: AnswerExplainDrawerSnapshot) => {
    if (!options.currentSession.value) {
      return;
    }
    const sessionState = options.getSessionState(options.currentSession.value.id);
    sessionState.answerExplain = snapshot.answerExplain;
    sessionState.answerExplainVisible = snapshot.visible;
    options.saveViewToState(options.currentSession.value.id, {
      isStreaming: options.isStreaming,
      nodeBlocks: options.nodeBlocks,
      pendingClarify: options.pendingClarify,
      queuedRequests: options.queuedRequests
    });
  };

  const openLatestAnswerExplain = async () => {
    if (!options.canViewThinking.value) {
      return;
    }
    const runtimeRequestId = options.latestExplainRuntimeRequestId.value;
    if (!options.currentSession.value || !runtimeRequestId) {
      ElMessage.warning('当前会话还没有可查看的数据来源');
      return;
    }
    await diagnosticsHostRef.value?.openAnswerExplain(options.currentSession.value.id, runtimeRequestId);
  };

  const openOrchestrationTrace = async () => {
    if (!options.canViewThinking.value) {
      return;
    }
    const runtimeRequestId = options.latestExplainRuntimeRequestId.value;
    if (!runtimeRequestId) {
      ElMessage.warning('当前回答没有编排链路记录');
      return;
    }
    await diagnosticsHostRef.value?.openOrchestrationTrace(runtimeRequestId);
  };

  const openTraceDialog = async () => {
    if (!options.canViewThinking.value) {
      return;
    }
    await diagnosticsHostRef.value?.openTrace();
  };

  const resetRunDiagnostics = () => {
    diagnosticsHostRef.value?.reset();
  };

  return {
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
  };
}
