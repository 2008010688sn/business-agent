<template>
  <TraceDialog
    ref="traceDialogRef"
    :can-view-thinking="canViewThinking"
    :session-id="sessionId"
    :agent-id="agentId"
  />
  <OrchestrationTraceDrawer
    ref="orchestrationTraceDrawerRef"
    :can-view-thinking="canViewThinking"
    :agent-id="agentId"
  />
  <AnswerExplainDrawer
    ref="answerExplainDrawerRef"
    :can-view-thinking="canViewThinking"
    :agent-id="agentId"
    @state-change="snapshot => emit('answerExplainStateChange', snapshot)"
  />
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import AnswerExplainDrawer from '@/views/ai-agent/components/run/AnswerExplainDrawer.vue';
import OrchestrationTraceDrawer from '@/views/ai-agent/components/run/OrchestrationTraceDrawer.vue';
import TraceDialog from '@/views/ai-agent/components/run/TraceDialog.vue';
import type { AnswerExplainDrawerSnapshot } from '@/views/ai-agent/utils/useRunDiagnostics';

defineOptions({ name: 'AgentRunDiagnosticsHost' });

defineProps<{
  canViewThinking: boolean;
  sessionId?: string | null;
  agentId?: string | null;
}>();

const emit = defineEmits<{
  answerExplainStateChange: [snapshot: AnswerExplainDrawerSnapshot];
}>();

const traceDialogRef = ref<InstanceType<typeof TraceDialog> | null>(null);
const answerExplainDrawerRef = ref<InstanceType<typeof AnswerExplainDrawer> | null>(null);
const orchestrationTraceDrawerRef = ref<InstanceType<typeof OrchestrationTraceDrawer> | null>(null);

const traceLoading = computed(() => Boolean(traceDialogRef.value?.loading));
const orchestrationTraceLoading = computed(() => Boolean(orchestrationTraceDrawerRef.value?.loading));

const openTrace = async () => {
  await traceDialogRef.value?.open();
};

const openOrchestrationTrace = async (runtimeRequestId: string) => {
  await orchestrationTraceDrawerRef.value?.open(runtimeRequestId);
};

const openAnswerExplain = async (sessionId: string, runtimeRequestId: string) => {
  await answerExplainDrawerRef.value?.open(sessionId, runtimeRequestId);
};

const reset = () => {
  traceDialogRef.value?.reset();
  orchestrationTraceDrawerRef.value?.reset();
  answerExplainDrawerRef.value?.reset();
};

const setAnswerExplainSnapshot = (snapshot?: AnswerExplainDrawerSnapshot | null) => {
  answerExplainDrawerRef.value?.setSnapshot(snapshot);
};

const getAnswerExplainSnapshot = () => {
  return answerExplainDrawerRef.value?.getSnapshot();
};

defineExpose({
  traceLoading,
  orchestrationTraceLoading,
  openTrace,
  openOrchestrationTrace,
  openAnswerExplain,
  reset,
  setAnswerExplainSnapshot,
  getAnswerExplainSnapshot
});
</script>
