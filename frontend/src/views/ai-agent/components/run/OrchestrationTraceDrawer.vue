<template>
  <ElDrawer v-if="canViewThinking" v-model="visible" title="编排链路" size="48%" destroy-on-close>
    <ElSkeleton v-if="loading" animated :rows="10" />
    <ElAlert v-else-if="errorMessage" :title="errorMessage" type="info" :closable="false" show-icon />
    <div v-else-if="orchestrationTrace?.run" class="orchestration-trace-panel">
      <div class="orchestration-trace-summary">
        <span>Request: {{ orchestrationTrace.run.runtimeRequestId || '-' }}</span>
        <span>状态: {{ orchestrationTrace.run.status || '-' }}</span>
      </div>
      <div class="orchestration-metric-grid">
        <div class="orchestration-metric-card">
          <span>总耗时</span>
          <strong>{{ formatTraceDuration(orchestrationTrace.run.totalMs || 0) }}</strong>
        </div>
        <div class="orchestration-metric-card">
          <span>路由耗时</span>
          <strong>{{ formatTraceDuration(orchestrationTrace.run.routeMs || 0) }}</strong>
        </div>
        <div class="orchestration-metric-card">
          <span>协作者耗时</span>
          <strong>{{ formatTraceDuration(orchestrationTrace.run.collaboratorMs || 0) }}</strong>
        </div>
        <div class="orchestration-metric-card">
          <span>汇总耗时</span>
          <strong>{{ formatTraceDuration(orchestrationTrace.run.summaryMs || 0) }}</strong>
        </div>
        <div class="orchestration-metric-card">
          <span>协作者数量</span>
          <strong>{{ orchestrationTrace.run.collaboratorCount ?? orchestrationTrace.steps?.length ?? 0 }}</strong>
        </div>
      </div>

      <div class="orchestration-step-list">
        <article
          v-for="step in orchestrationTrace.steps || []"
          :key="step.id || `${step.stepNo}-${step.collaboratorAgentId}`"
          class="orchestration-step-card"
        >
          <div class="orchestration-step-header">
            <strong>{{ step.stepNo }}. 协作 Agent {{ step.collaboratorAgentId || '-' }}</strong>
            <span>{{ step.status || '-' }}</span>
          </div>
          <div class="orchestration-step-body">
            <div v-if="step.task">任务：{{ step.task }}</div>
            <div v-if="step.reason">原因：{{ step.reason }}</div>
            <div>耗时：{{ formatTraceDuration(step.durationMs || 0) }}</div>
            <div>ReAct：{{ formatTraceDuration(step.reactMs || 0) }}</div>
            <div>工具调用：{{ step.toolCount ?? 0 }}，失败：{{ step.toolFailCount ?? 0 }}</div>
            <div v-if="step.childRuntimeRequestId">子 Request：{{ step.childRuntimeRequestId }}</div>
            <div v-if="step.errorMessage" class="orchestration-step-error">错误：{{ step.errorMessage }}</div>
          </div>
        </article>
      </div>
    </div>
    <ElEmpty v-else description="当前回答没有编排链路记录" :image-size="96" />
  </ElDrawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import orchestrationService, { type OrchestrationTrace } from '@/views/ai-agent/services/agentOrchestration';
import { formatTraceDuration } from '@/views/ai-agent/utils/runFormatters';

defineOptions({ name: 'OrchestrationTraceDrawer' });

const props = defineProps<{
  canViewThinking: boolean;
  agentId?: string | null;
}>();

const visible = ref(false);
const loading = ref(false);
const errorMessage = ref('');
const orchestrationTrace = ref<OrchestrationTrace | null>(null);

const resolvedAgentId = computed(() => String(props.agentId ?? '').trim());

const reset = () => {
  visible.value = false;
  orchestrationTrace.value = null;
  errorMessage.value = '';
};

const open = async (runtimeRequestId: string) => {
  if (!props.canViewThinking) {
    return;
  }
  visible.value = true;
  loading.value = true;
  errorMessage.value = '';
  try {
    orchestrationTrace.value = await orchestrationService.getRuntimeTrace(resolvedAgentId.value, runtimeRequestId);
    if (!orchestrationTrace.value?.run) {
      errorMessage.value = '当前回答没有编排链路记录';
    }
  } catch (error: any) {
    orchestrationTrace.value = null;
    if (error?.response?.status === 404) {
      errorMessage.value = '当前回答没有编排链路记录';
    } else if (error?.response?.status === 403) {
      errorMessage.value = '需要 Thinking 查看权限';
    } else {
      errorMessage.value = '加载编排链路失败，请稍后重试。';
    }
  } finally {
    loading.value = false;
  }
};

defineExpose({
  open,
  reset,
  loading
});
</script>

<style scoped>
.orchestration-trace-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: none;
}

.orchestration-trace-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.orchestration-trace-summary span {
  padding: 4px 8px;
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 999px;
}

.orchestration-metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: 10px;
}

.orchestration-metric-card {
  padding: 10px 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}

.orchestration-metric-card span {
  display: block;
  margin-bottom: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.orchestration-metric-card strong {
  color: var(--el-text-color-primary);
  font-size: 15px;
}

.orchestration-step-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.orchestration-step-card {
  padding: 12px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}

.orchestration-step-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  color: var(--el-text-color-primary);
}

.orchestration-step-header span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.orchestration-step-body {
  display: grid;
  gap: 6px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.5;
  word-break: break-word;
}

.orchestration-step-error {
  color: var(--el-color-danger);
}
</style>
