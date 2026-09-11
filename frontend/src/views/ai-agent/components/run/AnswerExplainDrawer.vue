<template>
  <ElDrawer v-if="canViewThinking" v-model="visible" title="数据来源与解释链" size="48%" destroy-on-close>
    <ElSkeleton v-if="loading" animated :rows="10" />
    <ElAlert v-else-if="errorMessage" :title="errorMessage" type="warning" :closable="false" show-icon />
    <div v-else-if="answerExplain" class="answer-explain-panel">
      <div class="answer-explain-summary">
        <span class="answer-explain-pill">Request: {{ answerExplain.runtimeRequestId }}</span>
        <span v-if="answerExplain.datasource" class="answer-explain-pill"> 数据源: {{ answerExplain.datasource }} </span>
        <span v-if="answerExplain.updatedAt" class="answer-explain-pill">
          更新时间: {{ formatTraceTime(answerExplain.updatedAt) }}
        </span>
      </div>

      <section
        v-if="answerExplain.clarify && Object.keys(answerExplain.clarify).length > 0"
        class="answer-explain-section"
      >
        <div class="answer-explain-section-title">澄清来源</div>
        <div class="answer-explain-section-desc">
          以下信息说明系统在正式查库前如何判断问题是否存在歧义，以及是否需要你补充信息。
        </div>
        <div class="answer-explain-kv">
          <div v-if="answerExplain.clarify.summary" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">澄清结论</span>
            <span class="answer-explain-kv-value">{{ answerExplain.clarify.summary }}</span>
          </div>
          <div v-if="answerExplain.clarify.riskLevel" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">风险等级</span>
            <span class="answer-explain-kv-value">{{ answerExplain.clarify.riskLevel }}</span>
          </div>
          <div v-if="typeof answerExplain.clarify.clarifyRequired === 'boolean'" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">是否需要澄清</span>
            <span class="answer-explain-kv-value">
              {{ answerExplain.clarify.clarifyRequired ? '是' : '否' }}
            </span>
          </div>
          <div v-if="typeof answerExplain.clarify.shouldBlockExecution === 'boolean'" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">是否阻止直接查库</span>
            <span class="answer-explain-kv-value">
              {{ answerExplain.clarify.shouldBlockExecution ? '是' : '否' }}
            </span>
          </div>
          <div
            v-if="asExplainStringList(answerExplain.clarify.missingDimensions).length > 0"
            class="answer-explain-kv-row"
          >
            <span class="answer-explain-kv-key">缺失维度</span>
            <span class="answer-explain-kv-value">
              {{ asExplainStringList(answerExplain.clarify.missingDimensions).join('、') }}
            </span>
          </div>
          <div
            v-if="asExplainStringList(answerExplain.clarify.followUpQuestions).length > 0"
            class="answer-explain-kv-row"
          >
            <span class="answer-explain-kv-key">追问建议</span>
            <span class="answer-explain-kv-value">
              {{ asExplainStringList(answerExplain.clarify.followUpQuestions).join('；') }}
            </span>
          </div>
          <div
            v-if="asExplainStringList(answerExplain.clarify.suggestedAssumptions).length > 0"
            class="answer-explain-kv-row"
          >
            <span class="answer-explain-kv-key">建议假设</span>
            <span class="answer-explain-kv-value">
              {{ asExplainStringList(answerExplain.clarify.suggestedAssumptions).join('；') }}
            </span>
          </div>
          <div v-if="answerExplain.clarify.humanFeedbackContent" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">人工补充</span>
            <span class="answer-explain-kv-value">
              {{ answerExplain.clarify.humanFeedbackContent }}
            </span>
          </div>
        </div>
      </section>

      <section v-if="linkResolveView" class="answer-explain-section">
        <div class="answer-explain-section-title">链接解析</div>
        <div class="answer-explain-section-desc">
          本轮从链接或页面上下文抽出的键，仅展示键名与可信度，不展示完整 URL。
        </div>
        <div class="answer-explain-kv">
          <div v-if="linkResolveView.keyNames.length > 0" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">键名</span>
            <span class="answer-explain-kv-value">{{ linkResolveView.keyNames.join('、') }}</span>
          </div>
          <div v-if="linkResolveView.trust" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">可信度</span>
            <span class="answer-explain-kv-value">{{ linkResolveView.trust }}</span>
          </div>
          <div v-if="typeof linkResolveView.ownOrigin === 'boolean'" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">是否本系统</span>
            <span class="answer-explain-kv-value">{{ linkResolveView.ownOrigin ? '是' : '否' }}</span>
          </div>
        </div>
      </section>

      <section v-if="answerExplain.semanticHits.length > 0" class="answer-explain-section">
        <div class="answer-explain-section-title">语义模型来源</div>
        <div class="answer-explain-section-desc">以下命中来自语义模型召回，用于补充表、字段和业务语义理解。</div>
        <div class="answer-explain-card-list">
          <article
            v-for="(hit, index) in answerExplain.semanticHits"
            :key="`semantic-${index}`"
            class="answer-explain-card"
          >
            <div class="answer-explain-card-title">
              {{ hit.tableName || '未命名表' }}
              <template v-if="hit.columnName">.{{ hit.columnName }}</template>
            </div>
            <div class="answer-explain-card-meta">
              <span>分数: {{ hit.score ?? '-' }}</span>
              <span>命中依据: {{ hit.matchedBy || '-' }}</span>
            </div>
            <div v-if="hit.businessName" class="answer-explain-card-body">业务名: {{ hit.businessName }}</div>
            <div v-if="hit.businessDescription || hit.relationHint" class="answer-explain-card-body">
              {{ hit.businessDescription || hit.relationHint }}
            </div>
          </article>
        </div>
      </section>

      <section v-if="answerExplain.sql" class="answer-explain-section">
        <div class="answer-explain-section-title">执行 SQL</div>
        <pre class="answer-explain-code">{{ answerExplain.sql }}</pre>
      </section>

      <section class="answer-explain-section">
        <div class="answer-explain-section-title">数据来源</div>
        <div class="answer-explain-kv">
          <div class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">执行说明</span>
            <span class="answer-explain-kv-value">
              {{ summarizeExplainExecution(answerExplain) }}
            </span>
          </div>
          <div v-if="answerExplain.question" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">用户问题</span>
            <span class="answer-explain-kv-value">{{ answerExplain.question }}</span>
          </div>
          <div v-if="answerExplain.answer" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">最终回答</span>
            <span class="answer-explain-kv-value">{{ answerExplain.answer }}</span>
          </div>
          <div v-if="answerExplain.datasource" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">数据源</span>
            <span class="answer-explain-kv-value">{{ answerExplain.datasource }}</span>
          </div>
          <div v-if="answerExplain.usedTables.length > 0" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">使用表</span>
            <span class="answer-explain-kv-value">{{ answerExplain.usedTables.join('、') }}</span>
          </div>
          <div v-if="answerExplain.usedColumns.length > 0" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">使用字段</span>
            <span class="answer-explain-kv-value">
              {{ answerExplain.usedColumns.join('、') }}
            </span>
          </div>
          <div v-if="answerExplain.decisionReason" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">工具决策来源</span>
            <span class="answer-explain-kv-value">{{ answerExplain.decisionReason }}</span>
          </div>
          <div
            v-if="answerExplain.toolDecisionReasons && answerExplain.toolDecisionReasons.length > 0"
            class="answer-explain-kv-row"
          >
            <span class="answer-explain-kv-key">工具决策细节</span>
            <span class="answer-explain-kv-value">
              <ul class="answer-explain-inline-list">
                <li v-for="(reason, index) in answerExplain.toolDecisionReasons" :key="`decision-${index}`">
                  {{ reason }}
                </li>
              </ul>
            </span>
          </div>
          <div v-if="answerExplain.resultScope" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">结果裁剪来源</span>
            <span class="answer-explain-kv-value">{{ answerExplain.resultScope }}</span>
          </div>
          <div
            v-if="answerExplain.resultScopeDetails && answerExplain.resultScopeDetails.length > 0"
            class="answer-explain-kv-row"
          >
            <span class="answer-explain-kv-key">结果裁剪细节</span>
            <span class="answer-explain-kv-value">
              <ul class="answer-explain-inline-list">
                <li v-for="(detail, index) in answerExplain.resultScopeDetails" :key="`scope-${index}`">
                  {{ detail }}
                </li>
              </ul>
            </span>
          </div>
          <div v-if="answerExplain.semanticHits.length > 0" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">语义模型命中</span>
            <span class="answer-explain-kv-value">{{ answerExplain.semanticHits.length }} 条</span>
          </div>
          <div v-if="answerExplain.knowledgeHits.length > 0" class="answer-explain-kv-row">
            <span class="answer-explain-kv-key">RAG / 知识命中</span>
            <span class="answer-explain-kv-value">{{ answerExplain.knowledgeHits.length }} 条</span>
          </div>
        </div>
      </section>

      <section
        v-if="answerExplain.relationEvidence && answerExplain.relationEvidence.length > 0"
        class="answer-explain-section"
      >
        <div class="answer-explain-section-title">关联来源</div>
        <div class="answer-explain-section-desc">
          以下信息说明本轮多表查询优先依据哪些物理外键或逻辑关系完成关联。
        </div>
        <div class="answer-explain-card-list">
          <article
            v-for="(relation, index) in answerExplain.relationEvidence"
            :key="`relation-${index}`"
            class="answer-explain-card"
          >
            <div class="answer-explain-card-title">
              {{ relation.sourceTable }}.{{ relation.sourceColumn }} -> {{ relation.targetTable }}.{{
                relation.targetColumn
              }}
            </div>
            <div class="answer-explain-card-meta">
              <span>{{ relation.sourceType || '-' }}</span>
              <span v-if="relation.relationType">{{ relation.relationType }}</span>
              <span v-if="relation.declaredInDatabase">数据库声明</span>
              <span v-else-if="relation.virtual">逻辑关系</span>
            </div>
            <div v-if="relation.description" class="answer-explain-card-body">
              {{ relation.description }}
            </div>
          </article>
        </div>
      </section>

      <section v-if="answerExplain.toolSteps.length > 0" class="answer-explain-section">
        <div class="answer-explain-section-title">执行过程</div>
        <div class="answer-explain-step-list">
          <article
            v-for="(step, index) in answerExplain.toolSteps"
            :key="`step-${index}`"
            class="answer-explain-step"
          >
            <div class="answer-explain-step-title">
              {{ step.title || step.toolName || '未命名步骤' }}
            </div>
            <div class="answer-explain-step-meta">
              <span>{{ step.toolName || '-' }}</span>
              <span v-if="step.datasource">数据源: {{ step.datasource }}</span>
              <span v-if="step.timestampEpochMs">
                {{ formatTraceTime(step.timestampEpochMs) }}
              </span>
            </div>
            <div v-if="step.summary" class="answer-explain-step-body">{{ step.summary }}</div>
            <pre v-if="step.detail" class="answer-explain-step-detail">{{ step.detail }}</pre>
          </article>
        </div>
      </section>

      <section v-if="answerExplain.knowledgeHits.length > 0" class="answer-explain-section">
        <div class="answer-explain-section-title">RAG / 知识来源</div>
        <div class="answer-explain-section-desc">
          以下命中来自业务知识库、FAQ、文档切片等 RAG 召回结果，会直接影响最终回答。
        </div>
        <div class="answer-explain-card-list">
          <article
            v-for="(hit, index) in answerExplain.knowledgeHits"
            :key="`knowledge-${index}`"
            class="answer-explain-card"
          >
            <div class="answer-explain-card-title">
              {{ hit.title || hit.source || '知识命中' }}
            </div>
            <div class="answer-explain-card-meta">
              <span>{{ hit.vectorType || '-' }}</span>
              <span v-if="hit.concreteType">{{ hit.concreteType }}</span>
              <span v-if="hit.source">{{ hit.source }}</span>
            </div>
            <div v-if="hit.summary" class="answer-explain-card-body">{{ hit.summary }}</div>
            <div v-if="hit.snippet" class="answer-explain-card-body">{{ hit.snippet }}</div>
          </article>
        </div>
      </section>

      <section v-if="answerExplain.warnings.length > 0" class="answer-explain-section">
        <div class="answer-explain-section-title">提示与限制</div>
        <ul class="answer-explain-warning-list">
          <li v-for="(warning, index) in answerExplain.warnings" :key="`warning-${index}`">
            {{ warning }}
          </li>
        </ul>
      </section>
    </div>
    <ElEmpty v-else description="当前回答还没有可展示的数据来源信息" :image-size="96" />
  </ElDrawer>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import ChatService, { type AnswerTraceExplain } from '@/views/ai-agent/services/chat';
import {
  asExplainStringList,
  formatTraceTime,
  summarizeExplainExecution
} from '@/views/ai-agent/utils/runFormatters';

defineOptions({ name: 'AnswerExplainDrawer' });

type AnswerExplainDrawerSnapshot = {
  answerExplain: AnswerTraceExplain | null;
  visible: boolean;
};

const props = defineProps<{
  canViewThinking: boolean;
  agentId?: string | null;
}>();

const emit = defineEmits<{
  stateChange: [snapshot: AnswerExplainDrawerSnapshot];
}>();

const visible = ref(false);
const loading = ref(false);
const errorMessage = ref('');
const answerExplain = ref<AnswerTraceExplain | null>(null);
const answerExplainRetryDelays = [700, 1200, 2000];
let suppressStateEmit = false;

const resolvedAgentId = computed(() => String(props.agentId ?? '').trim());

const asExplainRecord = (value: unknown): Record<string, unknown> | null => {
  return value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
};

const looksLikeAbsoluteUrl = (value: string) =>
  /^(https?:)?\/\//i.test(value) || /^[a-z][a-z0-9+.-]*:\/\//i.test(value);

const TRUST_LABELS: Record<string, string> = {
  own_origin: '本系统链接',
  page_context: '页面上下文',
  user_provided: '用户提供',
  external: '外部'
};

const readLinkResolveKeyNames = (source: Record<string, unknown>): string[] => {
  const names: string[] = [];
  const pushName = (value: unknown) => {
    if (typeof value !== 'string') return;
    const name = value.trim();
    if (!name || looksLikeAbsoluteUrl(name) || names.includes(name)) return;
    names.push(name);
  };
  const keysValue = source.keys ?? source.keyNames ?? source.key_names;
  if (Array.isArray(keysValue)) {
    keysValue.forEach(item => {
      if (typeof item === 'string') {
        pushName(item);
        return;
      }
      const record = asExplainRecord(item);
      if (!record) return;
      pushName(record.name ?? record.key ?? record.keyName);
    });
  } else {
    const keysRecord = asExplainRecord(keysValue);
    if (keysRecord) {
      Object.keys(keysRecord).forEach(pushName);
    }
  }
  return names.slice(0, 12);
};

const readLinkResolveTrust = (source: Record<string, unknown>): string => {
  const raw = source.trust ?? source.trustLevel ?? source.trust_level;
  if (typeof raw !== 'string' || !raw.trim()) return '';
  const value = raw.trim();
  return TRUST_LABELS[value] || value;
};

const readLinkResolveOwnOrigin = (source: Record<string, unknown>): boolean | undefined => {
  const raw = source.ownOrigin ?? source.own_origin ?? source.ownSystem ?? source.isOwnOrigin ?? source.appOrigin;
  return typeof raw === 'boolean' ? raw : undefined;
};

const linkResolveView = computed(() => {
  const explain = asExplainRecord(answerExplain.value);
  if (!explain) return null;
  const source = asExplainRecord(explain.linkResolve) || asExplainRecord(explain.link_resolve);
  if (!source) return null;
  const keyNames = readLinkResolveKeyNames(source);
  const trust = readLinkResolveTrust(source);
  const ownOrigin = readLinkResolveOwnOrigin(source);
  if (!keyNames.length && !trust && typeof ownOrigin !== 'boolean') {
    return null;
  }
  return { keyNames, trust, ownOrigin };
});

const emitStateChange = () => {
  emit('stateChange', {
    answerExplain: answerExplain.value,
    visible: visible.value
  });
};

watch(visible, () => {
  if (!suppressStateEmit) {
    emitStateChange();
  }
});

const isAnswerExplainNotFound = (error: any): boolean => error?.response?.status === 404;

const waitForAnswerExplainRetry = (delayMs: number): Promise<void> => {
  return new Promise(resolve => {
    window.setTimeout(resolve, delayMs);
  });
};

const loadAnswerExplainWithRetry = async <T,>(loader: () => Promise<T>): Promise<T> => {
  let lastError: unknown;
  for (let attempt = 0; attempt <= answerExplainRetryDelays.length; attempt += 1) {
    try {
      return await loader();
    } catch (error) {
      lastError = error;
      if (!isAnswerExplainNotFound(error) || attempt === answerExplainRetryDelays.length) {
        throw error;
      }
      await waitForAnswerExplainRetry(answerExplainRetryDelays[attempt]);
    }
  }
  throw lastError;
};

const reset = () => {
  suppressStateEmit = true;
  answerExplain.value = null;
  errorMessage.value = '';
  visible.value = false;
  suppressStateEmit = false;
};

const setSnapshot = (snapshot?: AnswerExplainDrawerSnapshot | null) => {
  suppressStateEmit = true;
  answerExplain.value = snapshot?.answerExplain ?? null;
  errorMessage.value = '';
  visible.value = props.canViewThinking ? Boolean(snapshot?.visible) : false;
  suppressStateEmit = false;
};

const getSnapshot = () => ({
  answerExplain: answerExplain.value,
  visible: visible.value
});

const open = async (sessionId: string, runtimeRequestId: string) => {
  if (!props.canViewThinking) {
    reset();
    return;
  }
  visible.value = true;
  loading.value = true;
  errorMessage.value = '';
  try {
    answerExplain.value = await loadAnswerExplainWithRetry(() =>
      ChatService.getAnswerExplain(sessionId, runtimeRequestId, resolvedAgentId.value)
    );
    emitStateChange();
  } catch (error: any) {
    answerExplain.value = null;
    if (error?.response?.status === 404) {
      errorMessage.value = '当前回答还没有生成 explain 数据，请稍后再试。';
      console.warn('当前回答暂无 answer explain:', error);
    } else {
      errorMessage.value = '加载数据来源失败，请稍后重试。';
      console.error('加载 answer explain 失败:', error);
    }
    emitStateChange();
  } finally {
    loading.value = false;
  }
};

defineExpose({
  open,
  reset,
  setSnapshot,
  getSnapshot,
  loading
});
</script>

<style scoped>
.answer-explain-panel {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding-right: 6px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: none;
}

.answer-explain-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 16px;
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.answer-explain-pill {
  display: inline-flex;
  align-items: center;
  min-height: 36px;
  padding: 0 16px;
  border-radius: 999px;
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  color: var(--el-color-primary);
  font-size: 13px;
  font-weight: 600;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  box-shadow: none;
}

.answer-explain-section,
.answer-explain-card,
.answer-explain-step {
  background: var(--el-bg-color);
  border-color: var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: none;
}

.answer-explain-section {
  padding: 20px;
  border: 1px solid var(--el-border-color-light);
}

.answer-explain-section-title,
.answer-explain-card-title,
.answer-explain-step-title,
.answer-explain-kv-value,
.answer-explain-inline-list {
  color: var(--el-text-color-primary);
}

.answer-explain-section-title {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  font-size: 16px;
  font-weight: 600;
}

.answer-explain-section-title::before {
  content: '';
  display: inline-block;
  width: 4px;
  height: 20px;
  background: var(--el-color-primary);
  border-radius: 2px;
}

.answer-explain-section-desc,
.answer-explain-card-meta,
.answer-explain-step-meta,
.answer-explain-card-body,
.answer-explain-step-body,
.answer-explain-kv-key,
.answer-explain-warning-list {
  color: var(--el-text-color-secondary);
}

.answer-explain-card-list,
.answer-explain-step-list,
.answer-explain-kv {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.answer-explain-card,
.answer-explain-step {
  position: relative;
  overflow: hidden;
  padding: 16px 18px;
  border: 1px solid var(--el-border-color-light);
}

.answer-explain-card-title,
.answer-explain-step-title {
  font-weight: 600;
  font-size: 14px;
}

.answer-explain-card-meta,
.answer-explain-step-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 8px;
  font-size: 12px;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
}

.answer-explain-card-body,
.answer-explain-step-body {
  margin-top: 12px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
}

.answer-explain-code,
.answer-explain-step-detail {
  margin: 12px 0 0;
  padding: 16px;
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
  color: var(--el-text-color-primary);
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  line-height: 1.8;
  box-shadow: none;
  border: 1px solid var(--el-border-color-light);
}

.answer-explain-kv-row {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  transition: background 0.2s ease;
}

.answer-explain-kv-row:hover {
  background: var(--el-fill-color-extra-light);
}

.answer-explain-kv-row:last-child {
  border-bottom: none;
  padding-bottom: 0;
}

.answer-explain-kv-key {
  font-size: 13px;
  font-weight: 600;
}

.answer-explain-kv-value {
  white-space: pre-wrap;
  word-break: break-word;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 13px;
  line-height: 1.8;
}

.answer-explain-inline-list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.8;
}

.answer-explain-inline-list li + li {
  margin-top: 6px;
}

.answer-explain-warning-list {
  margin: 0;
  padding-left: 20px;
  line-height: 1.9;
}
</style>
