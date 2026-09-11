<template>
  <ElDialog
    v-if="canViewThinking"
    v-model="visible"
    title="最近一次 Trace"
    width="1280px"
    top="4vh"
    class="trace-dialog"
    destroy-on-close
  >
    <div class="trace-toolbar">
      <div v-if="sessionTrace" class="trace-summary">
        <span class="trace-summary-pill">Trace ID: {{ sessionTrace.traceId }}</span>
        <span class="trace-summary-pill">Span 数: {{ sessionTrace.spanCount }}</span>
        <span class="trace-summary-pill">耗时: {{ formatTraceDuration(sessionTrace.durationMs) }}</span>
        <span class="trace-summary-pill">开始时间: {{ formatTraceTime(sessionTrace.startEpochMs) }}</span>
        <span v-if="sessionTrace.runtimeRequestId" class="trace-summary-pill">
          Request: {{ sessionTrace.runtimeRequestId }}
        </span>
        <span v-if="sessionTrace.agentId" class="trace-summary-pill">Agent: {{ sessionTrace.agentId }}</span>
      </div>
      <div class="trace-toolbar-actions">
        <ElInput v-model="traceSearchKeyword" clearable placeholder="搜索 span / 属性 / 值" class="trace-search-input" />
        <ElButton size="small" :loading="loading" @click="refresh">刷新</ElButton>
      </div>
    </div>

    <ElAlert v-if="traceError" :title="traceError" type="info" :closable="false" show-icon class="trace-alert" />
    <ElEmpty v-else-if="!loading && !sessionTrace" description="当前会话还没有最近一次 trace" />

    <div v-if="sessionTrace" class="trace-explorer">
      <div class="trace-pane trace-pane-list">
        <div class="trace-pane-header">
          <span class="trace-pane-title">Span 列表</span>
          <span class="trace-pane-count">{{ filteredTraceSpans.length }}/{{ flattenedTraceSpans.length }}</span>
        </div>
        <ElEmpty
          v-if="filteredTraceSpans.length === 0"
          description="没有匹配的 span，试试其他关键词"
          :image-size="88"
        />
        <div v-else class="trace-list">
          <ElButton
            v-for="row in filteredTraceSpans"
            :key="row.span.spanId"
            native-type="button"
            class="trace-row"
            :class="{
              'is-selected': row.span.spanId === selectedTraceSpanId,
              'is-error': row.span.status === 'ERROR'
            }"
            :style="{ paddingLeft: `${row.depth * 32 + 20}px` }"
            @click="selectTraceSpan(row.span.spanId)"
          >
            <div class="trace-row-main">
              <span class="trace-row-name">{{ row.span.name }}</span>
              <ElTag size="small" effect="plain">{{ row.span.kind }}</ElTag>
              <ElTag size="small" effect="plain" :type="row.span.status === 'ERROR' ? 'danger' : 'success'">
                {{ row.span.status }}
              </ElTag>
              <span class="trace-row-duration">
                {{ formatTraceDuration(row.span.durationMs) }}
              </span>
            </div>
            <div class="trace-row-meta">
              <span>spanId: {{ row.span.spanId }}</span>
              <span>parent: {{ row.span.parentSpanId || '-' }}</span>
              <span>属性: {{ row.attributeEntries.length }}</span>
              <span>偏移: {{ formatTraceOffset(row.span.startEpochMs) }}</span>
            </div>
          </ElButton>
        </div>
      </div>

      <div class="trace-pane trace-pane-detail">
        <template v-if="selectedTraceRow">
          <div class="trace-detail-header">
            <div>
              <div class="trace-detail-title">{{ selectedTraceRow.span.name }}</div>
              <div class="trace-detail-subtitle">
                <span>spanId: {{ selectedTraceRow.span.spanId }}</span>
                <span>parent: {{ selectedTraceRow.span.parentSpanId || '-' }}</span>
              </div>
            </div>
            <div class="trace-detail-tags">
              <ElTag effect="plain">{{ selectedTraceRow.span.kind }}</ElTag>
              <ElTag effect="plain" :type="selectedTraceRow.span.status === 'ERROR' ? 'danger' : 'success'">
                {{ selectedTraceRow.span.status }}
              </ElTag>
            </div>
          </div>

          <ElDescriptions :column="2" border size="small" class="trace-descriptions">
            <ElDescriptionsItem label="开始时间">
              {{ formatTraceTime(selectedTraceRow.span.startEpochMs) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="结束时间">
              {{ formatTraceTime(selectedTraceRow.span.endEpochMs) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="耗时">
              {{ formatTraceDuration(selectedTraceRow.span.durationMs) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="相对偏移">
              {{ formatTraceOffset(selectedTraceRow.span.startEpochMs) }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="属性数">
              {{ selectedTraceRow.attributeEntries.length }}
            </ElDescriptionsItem>
            <ElDescriptionsItem label="子 Span 数">
              {{ selectedTraceRow.span.children?.length ?? 0 }}
            </ElDescriptionsItem>
          </ElDescriptions>

          <div v-if="parsedTraceConversations.length > 0" class="trace-message-panel">
            <div class="trace-pane-header">
              <span class="trace-pane-title">消息视图</span>
              <span class="trace-pane-count">{{ parsedTraceConversations.length }} 组</span>
            </div>
            <div class="trace-message-groups">
              <div v-for="group in parsedTraceConversations" :key="group.attributeKey" class="trace-message-group">
                <div class="trace-message-group-header">
                  <div class="trace-message-group-title">{{ group.title }}</div>
                  <div class="trace-message-group-meta">{{ group.attributeKey }}</div>
                </div>
                <div class="trace-message-list">
                  <div
                    v-for="message in group.messages"
                    :key="message.id"
                    class="trace-message-item"
                    :class="`is-${message.kind}`"
                  >
                    <div class="trace-message-role">
                      <span class="trace-message-role-badge">{{ message.label }}</span>
                    </div>
                    <div class="trace-message-body">
                      <div v-if="message.title" class="trace-message-title">
                        {{ message.title }}
                      </div>
                      <div v-if="message.skills.length > 0" class="trace-message-skills">
                        <span v-for="skill in message.skills" :key="`${message.id}-${skill}`" class="trace-skill-chip">
                          {{ skill }}
                        </span>
                      </div>
                      <pre
                        v-if="isStructuredTraceValue(message.content)"
                        class="trace-message-content trace-message-content-structured"
                        >{{ formatStructuredTraceValue(message.content) }}</pre
                      >
                      <div v-else class="trace-message-content">
                        {{ message.content || '空内容' }}
                      </div>
                      <pre v-if="message.details" class="trace-message-details">{{ message.details }}</pre>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="trace-attribute-panel">
            <div class="trace-pane-header">
              <span class="trace-pane-title">属性详情</span>
              <span class="trace-pane-count">
                {{ selectedTraceAttributeEntries.length }}/{{ selectedTraceRow.attributeEntries.length }}
              </span>
            </div>

            <ElEmpty
              v-if="selectedTraceAttributeEntries.length === 0"
              description="这个 span 没有可显示的属性"
              :image-size="88"
            />
            <div v-else class="trace-attribute-table">
              <div class="trace-attribute-table-header">
                <span>Key</span>
                <span>Value</span>
              </div>
              <div
                v-for="entry in selectedTraceAttributeEntries"
                :key="`${selectedTraceRow.span.spanId}-${entry.key}`"
                class="trace-attribute-row"
              >
                <div class="trace-attribute-key">{{ entry.key }}</div>
                <pre
                  v-if="isStructuredTraceValue(entry.value)"
                  class="trace-attribute-value trace-attribute-value-structured"
                  >{{ formatStructuredTraceValue(entry.value) }}</pre
                >
                <div v-else class="trace-attribute-value">{{ entry.value }}</div>
              </div>
            </div>
          </div>
        </template>
        <ElEmpty v-else description="选择一个 span 查看详情" :image-size="88" />
      </div>
    </div>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import ChatService, { type SessionTrace, type TraceSpan } from '@/views/ai-agent/services/chat';
import {
  formatStructuredTraceValue,
  formatTraceDuration,
  formatTraceOffset as formatTraceOffsetFromStart,
  formatTraceTime,
  isStructuredTraceValue
} from '@/views/ai-agent/utils/runFormatters';

defineOptions({ name: 'TraceDialog' });

const props = defineProps<{
  canViewThinking: boolean;
  sessionId?: string | null;
  agentId?: string | null;
}>();

type TraceAttributeEntry = { key: string; value: string };
type FlattenedTraceSpan = {
  span: TraceSpan;
  depth: number;
  attributeEntries: TraceAttributeEntry[];
  searchableText: string;
};
type TraceMessageKind = 'system' | 'user' | 'assistant' | 'tool-call' | 'tool-result' | 'other';
type ParsedTraceMessage = {
  id: string;
  kind: TraceMessageKind;
  label: string;
  title: string;
  content: string;
  details: string;
  skills: string[];
};

const visible = ref(false);
const loading = ref(false);
const traceError = ref('');
const traceSearchKeyword = ref('');
const selectedTraceSpanId = ref('');
const sessionTrace = ref<SessionTrace | null>(null);

const resolvedAgentId = computed(() => String(props.agentId ?? '').trim());

const isTraceRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value);

const tryParseTraceJson = (value: string): unknown | null => {
  if (!isStructuredTraceValue(value)) {
    return null;
  }
  try {
    return JSON.parse(value);
  } catch (error) {
    return null;
  }
};

const stringifyTracePayload = (value: unknown) => {
  if (typeof value === 'string') {
    return value;
  }
  if (value === null || value === undefined) {
    return '';
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch (error) {
    return String(value);
  }
};

const unwrapTraceTextEnvelope = (value: unknown): unknown => {
  if (!isTraceRecord(value)) {
    if (typeof value !== 'string') {
      return value;
    }
    const parsed = tryParseTraceJson(value);
    return parsed !== null ? unwrapTraceTextEnvelope(parsed) : value;
  }
  const keys = Object.keys(value);
  const textValue = typeof value.text === 'string' ? value.text.trim() : '';
  const isTextEnvelope =
    textValue.length > 0 && keys.every(key => ['text', 'type', 'mimeType', 'metadata'].includes(key));
  if (!isTextEnvelope) {
    return value;
  }
  const parsedText = tryParseTraceJson(textValue);
  return parsedText !== null ? unwrapTraceTextEnvelope(parsedText) : textValue;
};

const unwrapTraceToolCallPayload = (value: Record<string, unknown>) => {
  if (isTraceRecord(value.param)) {
    return value.param;
  }
  return value;
};

const buildTraceToolCallContent = (value: Record<string, unknown>) => {
  const unwrappedValue = unwrapTraceToolCallPayload(value);
  const preferredPayload =
    unwrappedValue.input ??
    (isTraceRecord(unwrappedValue.metadata) ? unwrappedValue.metadata.arguments : undefined) ??
    unwrappedValue.arguments ??
    unwrappedValue.content ??
    unwrappedValue;
  if (typeof preferredPayload === 'string') {
    const parsed = tryParseTraceJson(preferredPayload);
    return parsed !== null ? stringifyTracePayload(parsed) : preferredPayload;
  }
  return stringifyTracePayload(preferredPayload);
};

const buildTraceToolResultContent = (value: Record<string, unknown>) => {
  const preferredPayload = unwrapTraceTextEnvelope(value.output ?? value.content ?? value.result ?? value);
  return stringifyTracePayload(preferredPayload);
};

const traceMessageLabelMap: Record<TraceMessageKind, string> = {
  system: 'SYSTEM',
  user: 'USER',
  assistant: 'ASSISTANT',
  'tool-call': 'TOOL CALL',
  'tool-result': 'TOOL RESULT',
  other: 'OTHER'
};

const createParsedTraceMessage = (
  id: string,
  kind: TraceMessageKind,
  options: Partial<ParsedTraceMessage>
): ParsedTraceMessage => ({
  id,
  kind,
  label: traceMessageLabelMap[kind],
  title: options.title ?? '',
  content: options.content ?? '',
  details: options.details ?? '',
  skills: options.skills ?? []
});

const buildTraceAttributeEntries = (attributes: Record<string, string>): TraceAttributeEntry[] => {
  return Object.entries(attributes ?? {})
    .sort(([leftKey], [rightKey]) => leftKey.localeCompare(rightKey))
    .map(([key, value]) => ({
      key,
      value
    }));
};

const flattenTraceSpans = (spans: TraceSpan[], depth = 0): FlattenedTraceSpan[] => {
  return spans.flatMap(span => {
    const attributeEntries = buildTraceAttributeEntries(span.attributes ?? {});
    const searchableText = [
      span.name,
      span.spanId,
      span.parentSpanId,
      span.kind,
      span.status,
      ...attributeEntries.flatMap(entry => [entry.key, entry.value])
    ]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    return [
      {
        span,
        depth,
        attributeEntries,
        searchableText
      },
      ...flattenTraceSpans(span.children ?? [], depth + 1)
    ];
  });
};

const flattenedTraceSpans = computed(() =>
  sessionTrace.value ? flattenTraceSpans(sessionTrace.value.rootSpans ?? []) : []
);

const normalizedTraceSearchKeyword = computed(() => traceSearchKeyword.value.trim().toLowerCase());

const filteredTraceSpans = computed(() => {
  const keyword = normalizedTraceSearchKeyword.value;
  if (!keyword) {
    return flattenedTraceSpans.value;
  }
  return flattenedTraceSpans.value.filter(row => row.searchableText.includes(keyword));
});

const selectedTraceRow = computed(() => {
  if (!flattenedTraceSpans.value.length) {
    return null;
  }
  return (
    flattenedTraceSpans.value.find(row => row.span.spanId === selectedTraceSpanId.value) ??
    filteredTraceSpans.value[0] ??
    flattenedTraceSpans.value[0]
  );
});

const selectedTraceAttributeEntries = computed(() => {
  const row = selectedTraceRow.value;
  if (!row) {
    return [];
  }
  const keyword = normalizedTraceSearchKeyword.value;
  if (!keyword) {
    return row.attributeEntries;
  }
  return row.attributeEntries.filter(entry => `${entry.key} ${entry.value}`.toLowerCase().includes(keyword));
});

const parsedTraceConversations = computed(() => {
  const row = selectedTraceRow.value;
  if (!row) {
    return [];
  }

  const inputEntry = row.attributeEntries.find(e => e.key === 'agentscope.function.input');
  const outputEntry = row.attributeEntries.find(e => e.key === 'agentscope.function.output');
  const nameEntry = row.attributeEntries.find(e => e.key === 'agentscope.function.name');

  if (inputEntry && outputEntry) {
    const inputParsed = tryParseTraceJson(inputEntry.value);
    const outputParsed = tryParseTraceJson(outputEntry.value);
    const toolName = nameEntry ? tryParseTraceJson(nameEntry.value) : null;
    const messages: ParsedTraceMessage[] = [];

    let name = '工具调用';
    if (typeof toolName === 'string') {
      name = toolName;
    } else if (isTraceRecord(inputParsed) && isTraceRecord(inputParsed.param)) {
      name = typeof inputParsed.param.name === 'string' ? inputParsed.param.name : name;
    }

    if (inputParsed !== null) {
      const inputContent =
        isTraceRecord(inputParsed) && isTraceRecord(inputParsed.param)
          ? buildTraceToolCallContent(inputParsed.param)
          : stringifyTracePayload(inputParsed);

      messages.push(
        createParsedTraceMessage('agentscope-function-call', 'tool-call', {
          title: name,
          content: inputContent,
          details: ''
        })
      );
    }

    let outputContent: string;
    if (outputParsed !== null) {
      outputContent = buildTraceToolResultContent(isTraceRecord(outputParsed) ? outputParsed : { output: outputParsed });
    } else {
      outputContent = outputEntry.value;
      if (outputEntry.value.endsWith('...') || outputEntry.value.length > 10000) {
        outputContent += '\n\n[注意：内容可能被截断，请在属性详情中查看完整内容]';
      }
    }

    messages.push(
      createParsedTraceMessage('agentscope-function-result', 'tool-result', {
        title: name,
        content: outputContent,
        details: ''
      })
    );

    if (messages.length > 0) {
      return [
        {
          attributeKey: 'agentscope.function',
          title: 'agentscope.function · messages',
          messages
        }
      ];
    }
  }

  return [];
});

const selectTraceSpan = (spanId: string) => {
  selectedTraceSpanId.value = spanId;
};

const formatTraceOffset = (epochMs: number) => {
  return formatTraceOffsetFromStart(epochMs, sessionTrace.value?.startEpochMs);
};

const reset = () => {
  visible.value = false;
  sessionTrace.value = null;
  traceError.value = '';
  traceSearchKeyword.value = '';
  selectedTraceSpanId.value = '';
};

const loadLatestTrace = async () => {
  if (!props.canViewThinking) {
    sessionTrace.value = null;
    traceError.value = '';
    return;
  }
  if (!props.sessionId) {
    sessionTrace.value = null;
    traceError.value = '当前没有可查看 trace 的会话';
    return;
  }
  if (!resolvedAgentId.value) {
    sessionTrace.value = null;
    traceError.value = '智能体ID无效，请刷新后重试';
    return;
  }
  loading.value = true;
  traceError.value = '';
  try {
    sessionTrace.value = await ChatService.getSessionTrace(props.sessionId, resolvedAgentId.value);
    selectedTraceSpanId.value = sessionTrace.value.rootSpans?.[0]?.spanId ?? '';
  } catch (error: any) {
    sessionTrace.value = null;
    selectedTraceSpanId.value = '';
    if (error?.response?.status === 404) {
      traceError.value = '当前会话还没有最近一次 trace，请先执行一轮对话。';
      console.warn('当前会话暂无 trace:', error);
    } else {
      traceError.value = '加载 trace 失败，请稍后重试。';
      console.error('加载 trace 失败:', error);
    }
  } finally {
    loading.value = false;
  }
};

const open = async () => {
  if (!props.canViewThinking) {
    return;
  }
  visible.value = true;
  await loadLatestTrace();
};

const refresh = async () => {
  if (!props.canViewThinking) {
    return;
  }
  await loadLatestTrace();
};

defineExpose({
  open,
  refresh,
  reset,
  loading
});
</script>

<style scoped>
.trace-toolbar,
.trace-pane,
.trace-message-group,
.trace-attribute-table {
  background: var(--el-bg-color);
  border-color: var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: none;
}

.trace-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 12px;
  padding: 12px;
}

.trace-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.trace-summary-pill,
.trace-pane-count,
.trace-row-duration,
.trace-message-group-meta,
.trace-detail-subtitle span,
.trace-row-meta span {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
  box-shadow: none;
}

.trace-summary-pill {
  display: inline-flex;
  align-items: center;
  min-height: 36px;
  padding: 0 16px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.trace-toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.trace-search-input {
  width: 300px;
}

.trace-alert {
  margin-bottom: 16px;
}

.trace-explorer {
  display: grid;
  grid-template-columns: minmax(380px, 45%) minmax(440px, 1fr);
  gap: 12px;
  min-height: 0;
  max-height: calc(100dvh - 260px);
  overflow: hidden;
}

.trace-pane {
  overflow: hidden;
  transition: all 0.3s ease;
  min-height: 0;
}

.trace-pane-list,
.trace-pane-detail {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.trace-pane-header,
.trace-detail-header,
.trace-message-group-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  background: var(--el-fill-color-extra-light);
  border-bottom: 1px solid var(--el-border-color-light);
}

.trace-pane-header {
  padding: 18px 20px 14px;
}

.trace-pane-title,
.trace-detail-title,
.trace-row-name,
.trace-message-group-title,
.trace-message-title,
.trace-attribute-key {
  color: var(--el-text-color-primary);
}

.trace-pane-title {
  font-size: 15px;
  font-weight: 600;
}

.trace-pane-count {
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.trace-list {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  padding: 16px;
  overflow: auto;
}

.trace-row,
.trace-message-body {
  width: 100%;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: none;
}

.trace-row {
  appearance: none;
  padding: 16px 18px;
  text-align: left;
  cursor: pointer;
  transition: all 0.3s ease;
  position: relative;
  overflow: visible;
}

.trace-row:hover,
.trace-row.is-selected {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.trace-row.is-error {
  border-color: var(--el-color-danger-light-5);
  background: var(--el-color-danger-light-9);
}

.trace-row-main {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.trace-row-name {
  font-weight: 600;
  font-size: 14px;
}

.trace-row-duration {
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 600;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.trace-row-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  word-break: break-all;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.trace-row-meta span {
  padding: 2px 8px;
  border-radius: 6px;
}

.trace-pane-detail {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.trace-detail-header {
  align-items: flex-start;
  padding: 20px 22px 16px;
}

.trace-detail-title {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.4;
}

.trace-detail-subtitle {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.trace-detail-subtitle span {
  padding: 4px 10px;
  border-radius: 6px;
}

.trace-detail-tags {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.trace-descriptions,
.trace-message-panel,
.trace-attribute-panel {
  padding: 12px;
}

.trace-message-panel,
.trace-attribute-panel {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
}

.trace-message-groups {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-top: 14px;
}

.trace-message-group {
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
}

.trace-message-group-header {
  padding: 14px 16px;
}

.trace-message-group-title {
  font-size: 14px;
  font-weight: 600;
}

.trace-message-group-meta {
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.trace-message-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 12px;
}

.trace-message-item {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}

.trace-message-role {
  display: flex;
  justify-content: center;
  padding-top: 6px;
}

.trace-message-role-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 80px;
  min-height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.trace-message-item.is-system .trace-message-role-badge {
  color: #92400e;
  background: #fef3c7;
}

.trace-message-item.is-user .trace-message-role-badge {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.trace-message-item.is-assistant .trace-message-role-badge {
  color: #065f46;
  background: #d1fae5;
}

.trace-message-item.is-tool-call .trace-message-role-badge {
  color: #9a3412;
  background: #fed7aa;
}

.trace-message-item.is-tool-result .trace-message-role-badge {
  color: #6b21a8;
  background: #e9d5ff;
}

.trace-message-item.is-other .trace-message-role-badge {
  color: #374151;
  background: #e5e7eb;
}

.trace-message-body {
  padding: 16px 18px;
}

.trace-message-title {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 600;
}

.trace-message-skills {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.trace-skill-chip {
  display: inline-flex;
  align-items: center;
  min-height: 28px;
  padding: 0 12px;
  border-radius: 999px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 600;
  border: 1px solid var(--el-color-primary-light-7);
}

.trace-message-content,
.trace-attribute-value {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.trace-message-content-structured,
.trace-message-details,
.trace-attribute-value-structured {
  margin: 12px 0 0;
  padding: 14px;
  overflow: auto;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: none;
  font-size: 12px;
  line-height: 1.7;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

.trace-attribute-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.trace-attribute-table {
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
}

.trace-attribute-table-header,
.trace-attribute-row {
  display: grid;
  grid-template-columns: minmax(240px, 280px) minmax(0, 1fr);
}

.trace-attribute-table-header {
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid var(--el-border-color-light);
}

.trace-attribute-table-header span,
.trace-attribute-key,
.trace-attribute-value {
  padding: 14px 16px;
}

.trace-attribute-row + .trace-attribute-row {
  border-top: 1px solid var(--el-border-color-lighter);
}

.trace-attribute-key {
  font-size: 13px;
  font-weight: 600;
  word-break: break-all;
  background: var(--el-fill-color-extra-light);
  border-right: 1px solid var(--el-border-color-light);
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.trace-attribute-value-structured {
  margin: 0;
}
</style>
