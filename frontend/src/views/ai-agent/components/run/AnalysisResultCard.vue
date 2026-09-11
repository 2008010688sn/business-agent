<template>
  <section class="agent-ui-message analysis-result-card" :class="{ 'is-disabled': disabled }" :aria-disabled="disabled">
    <header class="agent-ui-header">
      <div class="agent-ui-title">
        <ElIcon><Histogram /></ElIcon>
        <span>分析结果</span>
      </div>
      <div v-if="intentLabel" class="agent-ui-meta">{{ intentLabel }}</div>
    </header>

    <SafeMarkdownContent v-if="contentFormat === 'markdown' && ui.content?.text" :content="ui.content.text" />
    <p v-else-if="ui.content?.text" class="agent-ui-text">{{ ui.content.text }}</p>
    <p v-if="note" class="analysis-note">{{ note }}</p>

    <section v-if="evidence.length" class="analysis-section">
      <h4>证据</h4>
      <ul class="analysis-list">
        <li v-for="item in evidence" :key="item.evidenceId">
          <strong>{{ item.source || item.evidenceId }}</strong>
          <span v-if="item.coverage" class="analysis-tag">{{ item.coverage }}</span>
          <span v-if="item.trust" class="analysis-tag">{{ item.trust }}</span>
          <span v-if="item.fileRef" class="analysis-sub">{{ item.fileRef }}</span>
          <span v-else-if="item.query" class="analysis-sub">{{ item.query }}</span>
          <span v-if="item.citation" class="analysis-sub">{{ item.citation }}</span>
        </li>
      </ul>
    </section>

    <section v-if="findings.length" class="analysis-section">
      <h4>发现</h4>
      <ul class="analysis-list">
        <li v-for="(item, index) in findings" :key="`${item.text}-${index}`">{{ item.text }}</li>
      </ul>
    </section>

    <section v-if="showRootCauses" class="analysis-section">
      <h4>根因</h4>
      <ul class="analysis-list">
        <li v-for="(item, index) in rootCauses" :key="`${item.claim}-${index}`">
          <span>{{ item.claim }}</span>
          <span v-if="item.confidence" class="analysis-tag">{{ item.confidence }}</span>
        </li>
      </ul>
    </section>

    <ElAlert v-if="missingItems.length" type="info" :closable="false" class="analysis-missing" title="待补充来源或键">
      <ul class="analysis-list">
        <li v-for="item in missingItems" :key="item">{{ item }}</li>
      </ul>
    </ElAlert>

    <div v-if="visibleActions.length" class="flow-actions">
      <ElButton
        v-for="action in visibleActions"
        :key="action.actionId"
        :type="actionButtonType(action)"
        :disabled="disabled"
        @click="trigger(action)"
      >
        {{ action.label || action.type }}
      </ElButton>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { Histogram } from '@element-plus/icons-vue';
import SafeMarkdownContent from '@/views/ai-agent/components/run/SafeMarkdownContent.vue';
import {
  ANALYSIS_ACTION_TYPES,
  toAnalysisActionRequest,
  type AgentUiAction,
  type AgentUiActionRequest,
  type AgentUiMessage
} from '@/views/ai-agent/utils/agentUi';

defineOptions({ name: 'AnalysisResultCard' });

const props = defineProps<{ ui: AgentUiMessage; disabled?: boolean }>();
const emit = defineEmits<{ action: [request: AgentUiActionRequest] }>();

type EvidenceItem = {
  evidenceId: string;
  source?: string;
  query?: string;
  fileRef?: string;
  coverage?: string;
  trust?: string;
  citation?: string;
};
type FindingItem = { text: string; evidenceIds?: string[] };
type RootCauseItem = { claim: string; evidenceIds?: string[]; confidence?: string };

const isRecord = (value: unknown): value is Record<string, unknown> => Boolean(value && typeof value === 'object');
const payloadValues = computed(() => props.ui.payload?.values || {});
const contentFormat = computed(() => props.ui.content?.format || 'markdown');
const intent = computed(() => String(payloadValues.value.intent || '').toUpperCase());
const intentLabel = computed(() => {
  if (intent.value === 'FILE_ONLY') return '仅文件';
  if (intent.value === 'FILE_JOIN') return '文件对照';
  if (intent.value === 'TABLE_QUERY') return '表侧问数';
  return '';
});
const note = computed(() => {
  const value = payloadValues.value.note;
  return typeof value === 'string' && value.trim() ? value.trim() : '';
});

const asList = (value: unknown): Record<string, unknown>[] =>
  Array.isArray(value) ? value.filter(isRecord) : [];

const evidence = computed<EvidenceItem[]>(() =>
  asList(payloadValues.value.evidence)
    .map(item => ({
      evidenceId: String(item.evidenceId || ''),
      source: typeof item.source === 'string' ? item.source : undefined,
      query: typeof item.query === 'string' ? item.query : undefined,
      fileRef: typeof item.fileRef === 'string' ? item.fileRef : undefined,
      coverage: typeof item.coverage === 'string' ? item.coverage : undefined,
      trust: typeof item.trust === 'string' ? item.trust : undefined,
      citation:
        typeof item.citation === 'string' && item.citation.trim() && !/^(https?:)?\/\//i.test(item.citation.trim())
          ? item.citation.trim()
          : undefined
    }))
    .filter(item => item.evidenceId || item.source)
);

const findings = computed<FindingItem[]>(() =>
  asList(payloadValues.value.findings)
    .map(item => ({
      text: String(item.text || ''),
      evidenceIds: Array.isArray(item.evidenceIds) ? item.evidenceIds.map(String) : []
    }))
    .filter(item => item.text)
);

const rootCauses = computed<RootCauseItem[]>(() =>
  asList(payloadValues.value.rootCauses)
    .map(item => ({
      claim: String(item.claim || ''),
      evidenceIds: Array.isArray(item.evidenceIds) ? item.evidenceIds.map(String) : [],
      confidence: typeof item.confidence === 'string' ? item.confidence : undefined
    }))
    .filter(item => item.claim && (item.evidenceIds?.length || 0) > 0)
);

const showRootCauses = computed(() => intent.value !== 'FILE_ONLY' && rootCauses.value.length > 0);

const missingItems = computed(() => {
  const fromNone = asList(payloadValues.value.nextActions)
    .filter(item => String(item.type || '').toUpperCase() === 'NONE')
    .flatMap(item => (Array.isArray(item.missing) ? item.missing.map(String) : []));
  return fromNone.filter(Boolean);
});

const visibleActions = computed(() =>
  (Array.isArray(props.ui.actions) ? props.ui.actions : []).filter(action => {
    const type = String(action.type || '').toUpperCase();
    return ANALYSIS_ACTION_TYPES.has(type) && type !== 'NONE';
  })
);

const actionButtonType = (action: AgentUiAction) => {
  const type = String(action.type || '').toUpperCase();
  if (type === 'START_FLOW') return 'primary';
  if (type === 'ASK_WRITE') return 'warning';
  return 'default';
};

const trigger = (action: AgentUiAction) => {
  emit('action', toAnalysisActionRequest(props.ui, action));
};
</script>

<style scoped>
.agent-ui-message {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: min(100%, 760px);
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
  box-shadow: 0 1px 2px rgb(0 0 0 / 4%);
}
.agent-ui-message.is-disabled {
  opacity: 0.78;
}
.agent-ui-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.agent-ui-title,
.agent-ui-meta {
  display: flex;
  align-items: center;
  gap: 7px;
}
.agent-ui-title {
  color: var(--el-text-color-primary);
  font-weight: 650;
}
.agent-ui-meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.agent-ui-text {
  margin: 0;
  line-height: 1.7;
}
.analysis-note {
  margin: 0;
  color: var(--el-text-color-regular);
  line-height: 1.6;
}
.analysis-section h4 {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.analysis-list {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.analysis-tag {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.analysis-sub {
  display: block;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.analysis-missing {
  margin-top: 4px;
}
.flow-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
