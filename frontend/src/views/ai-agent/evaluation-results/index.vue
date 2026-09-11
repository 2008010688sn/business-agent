<template>
  <BaseLayout class="eval-result-shell">
    <main class="eval-result-page">
      <ElCard>
        <section class="eval-result-header">
          <div>
            <h1>评估结果排障</h1>
          </div>
          <div class="eval-result-header-actions">
            <ElButton :loading="loading" @click="loadResults">
              <ElIcon><Refresh /></ElIcon>
              刷新
            </ElButton>
          </div>
        </section>
      </ElCard>
      <ElCard class="eval-result-list-card mt-8px">
        <ElForm :model="query" label-width="80px">
          <ElRow :gutter="24">
            <ElCol :span="6">
              <ElFormItem label="运行ID">
                <ElInput v-model="query.runId" clearable placeholder="运行ID" @keyup.enter="handleSearch" />
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="评估集">
                <ElSelect
                  v-model="query.suiteId"
                  clearable
                  filterable
                  placeholder="全部评估集"
                  @visible-change="handleOptionsVisible"
                >
                  <ElOption
                    v-for="item in suiteOptions"
                    :key="String(item.id)"
                    :label="suiteLabel(item)"
                    :value="item.id || ''"
                  />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="对象">
                <ElSelect
                  v-model="query.subjectId"
                  clearable
                  filterable
                  placeholder="全部对象"
                  @visible-change="handleOptionsVisible"
                >
                  <ElOption
                    v-for="item in subjectOptions"
                    :key="String(item.id)"
                    :label="subjectLabel(item)"
                    :value="item.id || ''"
                  />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="状态">
                <ElSelect v-model="query.status" clearable placeholder="全部">
                  <ElOption
                    v-for="item in resultStatusOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="硬失败">
                <ElSelect v-model="query.hardFail" clearable placeholder="全部">
                  <ElOption label="是" :value="true" />
                  <ElOption label="否" :value="false" />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="关键词">
                <ElInput v-model="query.keyword" clearable placeholder="输入/输出/错误" @keyup.enter="handleSearch" />
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElButton type="primary" :loading="loading" @click="handleSearch">
                <ElIcon><Search /></ElIcon>
                查询
              </ElButton>
              <ElButton @click="handleReset">重置</ElButton>
            </ElCol>
          </ElRow>
        </ElForm>

        <div class="eval-result-table-wrap">
          <ElTable v-loading="loading" :data="rows" border stripe row-key="id" height="100%" empty-text="暂无评估结果">
            <ElTableColumn label="结果 ID" width="120" fixed="left">
              <template #default="{ row }">{{ row.id || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="运行 / 用例" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="cell-strong">Run: {{ row.runId || '-' }}</div>
                <span class="cell-muted">Case: {{ row.caseId || '-' }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="评估集 / 对象" min-width="220" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ resolveSuiteName(row.suiteId) }}</div>
                <span class="cell-muted">{{ resolveSubjectName(row.subjectId) }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="userInput" label="用户输入" min-width="260" show-overflow-tooltip />
            <ElTableColumn prop="agentOutput" label="智能体输出" min-width="260" show-overflow-tooltip />
            <ElTableColumn label="状态" width="104">
              <template #default="{ row }">
                <ElTag :type="resultStatusTag(row.status)" effect="light" size="small">
                  {{ resultStatusLabel(row.status) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="硬失败" width="92" align="center">
              <template #default="{ row }">
                <ElTag :type="row.hardFail ? 'danger' : 'success'" effect="light" size="small">
                  {{ row.hardFail ? '是' : '否' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="DRY_RUN 违规（写/隔离）" width="168" align="right">
              <template #default="{ row }">
                <span v-if="hasViolations(row)" class="violation-danger">
                  写 {{ row.writeViolationCount ?? 0 }} / 隔离 {{ row.isolationViolationCount ?? 0 }}
                </span>
                <span v-else>-</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="得分" width="104" align="right">
              <template #default="{ row }">{{ formatScore(row.score) }}</template>
            </ElTableColumn>
            <ElTableColumn label="耗时" width="100" align="right">
              <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
            </ElTableColumn>
            <ElTableColumn prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip />
            <ElTableColumn label="操作" width="132" fixed="right" align="center">
              <template #default="{ row }">
                <ElTooltip content="详情" placement="top">
                  <ElButton text type="primary" @click="openDetail(row)">
                    <ElIcon><View /></ElIcon>
                  </ElButton>
                </ElTooltip>
                <ElTooltip content="会话排障" placement="top">
                  <ElButton
                    :disabled="!row.sessionId || !row.runtimeRequestId"
                    text
                    type="success"
                    @click="goDiagnostics(row)"
                  >
                    <ElIcon><Connection /></ElIcon>
                  </ElButton>
                </ElTooltip>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>

        <div class="result-pagination">
          <ElPagination
            v-model:current-page="page.current"
            v-model:page-size="page.size"
            background
            layout="total, sizes, prev, pager, next, jumper"
            :page-sizes="[10, 20, 50, 100]"
            :total="page.total"
            @size-change="handleSizeChange"
            @current-change="loadResults"
          />
        </div>
      </ElCard>

      <ElDrawer v-model="detailVisible" title="评估结果详情" size="66%" destroy-on-close>
        <div v-loading="detailLoading" class="detail-body">
          <ElDescriptions :column="3" border>
            <ElDescriptionsItem label="结果 ID">{{ selectedResult?.id || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="运行 ID">{{ selectedResult?.runId || '-' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="状态">{{ resultStatusLabel(selectedResult?.status) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="总分">{{ formatScore(selectedResult?.score) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="质量">{{ formatScore(selectedResult?.qualityScore) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="安全">{{ formatScore(selectedResult?.safetyScore) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="效率">{{ formatScore(selectedResult?.efficiencyScore) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="稳定性">{{ formatScore(selectedResult?.stabilityScore) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="耗时">{{ formatDuration(selectedResult?.durationMs) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="写副作用违规">
              <span :class="{ 'violation-danger': (selectedResult?.writeViolationCount ?? 0) > 0 }">
                {{ selectedResult?.writeViolationCount ?? 0 }} 次
              </span>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="权限/隔离违规">
              <span :class="{ 'violation-danger': (selectedResult?.isolationViolationCount ?? 0) > 0 }">
                {{ selectedResult?.isolationViolationCount ?? 0 }} 次
              </span>
            </ElDescriptionsItem>
            <ElDescriptionsItem label="违规说明">DRY_RUN 拦截，硬门禁要求为 0</ElDescriptionsItem>
          </ElDescriptions>

          <section class="detail-section">
            <h2>输入与输出</h2>
            <div class="text-grid">
              <div>
                <strong>用户输入</strong>
                <pre>{{ selectedResult?.userInput || '-' }}</pre>
              </div>
              <div>
                <strong>期望输出</strong>
                <pre>{{ selectedResult?.expectedOutput || '-' }}</pre>
              </div>
              <div class="text-grid-wide">
                <strong>智能体输出</strong>
                <pre>{{ selectedResult?.agentOutput || '-' }}</pre>
              </div>
            </div>
          </section>

          <section class="detail-section">
            <h2>评分与失败原因</h2>
            <div class="json-grid">
              <div>
                <strong>评分明细</strong>
                <pre>{{ jsonText(selectedResult?.scoreDetailJson) }}</pre>
              </div>
              <div>
                <strong>失败原因</strong>
                <pre>{{ jsonText(selectedResult?.failureReasonsJson) }}</pre>
              </div>
              <div>
                <strong>效率指标</strong>
                <pre>{{ jsonText(selectedResult?.efficiencyMetricsJson) }}</pre>
              </div>
              <div>
                <strong>Trace 快照</strong>
                <pre>{{ jsonText(detailTrace?.traceSnapshotJson || selectedResult?.traceSnapshotJson) }}</pre>
              </div>
              <div v-if="hasViolations(selectedResult)" class="text-grid-wide">
                <strong>DRY_RUN 违规明细（写副作用 / 权限或租户隔离拦截，硬门禁要求为 0）</strong>
                <pre>{{ jsonText(selectedResult?.violationDetailJson) }}</pre>
              </div>
            </div>
          </section>

          <section class="detail-section">
            <div class="section-title-row">
              <h2>Trace 分层</h2>
              <ElTag :type="detailTrace?.sourceVisible ? 'success' : 'info'" effect="light">
                {{ detailTrace?.sourceVisible ? '源 Trace 可见' : '仅摘要可见' }}
              </ElTag>
            </div>
            <div class="json-grid">
              <div>
                <strong>Trace 摘要</strong>
                <pre>{{ jsonText(detailTrace?.traceSnapshotJson || selectedResult?.traceSnapshotJson) }}</pre>
              </div>
              <div>
                <strong>源 Trace</strong>
                <pre>{{
                  detailTrace?.sourceVisible
                    ? jsonText(detailTrace?.sourceTrace)
                    : detailTrace?.sourceMessage || '当前账号无源 Trace 权限'
                }}</pre>
              </div>
            </div>
          </section>

          <section class="detail-section">
            <div class="section-title-row">
              <h2>会话排障</h2>
              <ElButton
                :disabled="!selectedResult?.sessionId || !selectedResult?.runtimeRequestId"
                type="primary"
                link
                @click="selectedResult && goDiagnostics(selectedResult)"
              >
                打开会话排障
              </ElButton>
            </div>
          </section>
        </div>
      </ElDrawer>
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Connection, Refresh, Search, View } from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import evaluationService from '@/views/ai-agent/services/evaluation';
import type {
  EvalCaseResult,
  EvalId,
  EvalResultQueryRequest,
  EvalResultTrace,
  EvalSubject,
  EvalSuite
} from '@/views/ai-agent/services/evaluation';

defineOptions({ name: 'AgentEvaluationResultsPage' });

const route = useRoute();
const router = useRouter();

const resultStatusOptions = [
  { label: '成功', value: 'success' },
  { label: '失败', value: 'failed' },
  { label: '超时', value: 'timeout' },
  { label: '已取消', value: 'cancelled' }
];

const query = reactive<EvalResultQueryRequest>({
  runId: stringQuery('runId'),
  suiteId: stringQuery('suiteId'),
  subjectId: '',
  status: '',
  hardFail: '',
  keyword: ''
});
const page = reactive({ current: 1, size: 20, total: 0 });
const rows = ref<EvalCaseResult[]>([]);
const loading = ref(false);
const detailLoading = ref(false);
const detailVisible = ref(false);
const selectedResult = ref<EvalCaseResult | null>(null);
const detailTrace = ref<EvalResultTrace | null>(null);
const suiteOptions = ref<EvalSuite[]>([]);
const subjectOptions = ref<EvalSubject[]>([]);
const optionsLoaded = ref(false);
const detailRequestSeq = ref(0);

async function loadOptions(force = false) {
  if (optionsLoaded.value && !force) {
    return;
  }
  const [suites, subjects] = await Promise.all([
    evaluationService.querySuitesPage({ current: 1, size: 100, status: 'enabled' }),
    evaluationService.querySubjectsPage({ current: 1, size: 100, status: 'enabled' })
  ]);
  suiteOptions.value = suites.data;
  subjectOptions.value = subjects.data;
  optionsLoaded.value = true;
}

function handleOptionsVisible(visible: boolean) {
  if (visible) {
    loadOptions().catch(() => {
      ElMessage.warning('评估基础数据加载失败，可继续按 ID 查询');
    });
  }
}

async function loadResults() {
  loading.value = true;
  try {
    const response = await evaluationService.queryResultsPage({
      ...query,
      current: page.current,
      size: page.size
    });
    rows.value = response.data;
    page.total = response.total;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '评估结果查询失败');
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  page.current = 1;
  loadResults();
}

function handleReset() {
  Object.assign(query, {
    runId: '',
    suiteId: '',
    subjectId: '',
    status: '',
    hardFail: '',
    keyword: ''
  });
  handleSearch();
}

function handleSizeChange() {
  page.current = 1;
  loadResults();
}

async function openDetail(row: EvalCaseResult) {
  if (!row.id) {
    return;
  }
  selectedResult.value = row;
  detailTrace.value = null;
  detailVisible.value = true;
  detailLoading.value = true;
  const requestSeq = detailRequestSeq.value + 1;
  detailRequestSeq.value = requestSeq;
  try {
    const [response, traceResponse] = await Promise.all([
      evaluationService.getResultDetail(row.id),
      evaluationService.getResultTrace(row.id)
    ]);
    if (requestSeq !== detailRequestSeq.value) {
      return;
    }
    detailTrace.value = traceResponse;
    selectedResult.value = response.result || traceResponse.result || row;
  } catch (error) {
    if (requestSeq === detailRequestSeq.value) {
      ElMessage.error(error instanceof Error ? error.message : '评估结果详情查询失败');
    }
  } finally {
    if (requestSeq === detailRequestSeq.value) {
      detailLoading.value = false;
    }
  }
}

function goDiagnostics(row: EvalCaseResult) {
  router.push({
    path: '/ai-agent/diagnostics',
    query: {
      sessionId: row.sessionId ? String(row.sessionId) : undefined,
      runtimeRequestId: row.runtimeRequestId || undefined
    }
  });
}

function suiteLabel(item: EvalSuite) {
  return `${item.suiteName || '-'}（${item.id || '-'}）`;
}

function subjectLabel(item: EvalSubject) {
  return `${item.subjectName || item.subjectId || '-'}（${item.id || '-'}）`;
}

function resolveSuiteName(id?: EvalId) {
  const suite = suiteOptions.value.find(item => sameId(item.id, id));
  return suite?.suiteName || (id ? String(id) : '-');
}

function resolveSubjectName(id?: EvalId) {
  const subject = subjectOptions.value.find(item => sameId(item.id, id));
  return subject?.subjectName || (id ? String(id) : '-');
}

function sameId(left?: EvalId | '', right?: EvalId | '') {
  return String(left || '') === String(right || '');
}

/** 是否记录到 DRY_RUN 违规（写副作用 / 权限或租户隔离），有违规才展示明细。 */
function hasViolations(row?: EvalCaseResult | null) {
  if (!row) return false;
  return (row.writeViolationCount ?? 0) > 0 || (row.isolationViolationCount ?? 0) > 0;
}

function resultStatusLabel(value?: string) {
  const labels: Record<string, string> = {
    success: '成功',
    failed: '失败',
    timeout: '超时',
    cancelled: '已取消'
  };
  return labels[value || ''] || value || '-';
}

function resultStatusTag(value?: string) {
  if (value === 'success') return 'success';
  if (value === 'failed') return 'danger';
  if (value === 'timeout') return 'danger';
  if (value === 'cancelled') return 'info';
  return undefined;
}

function formatScore(value?: number | string | null) {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) ? numeric.toFixed(2) : '-';
}

function formatDuration(value?: number | string | null) {
  const numeric = Number(value || 0);
  if (!numeric) return '-';
  if (numeric < 1000) return `${numeric}ms`;
  return `${(numeric / 1000).toFixed(1)}s`;
}

function jsonText(value: unknown) {
  if (value === undefined || value === null || value === '') {
    return '-';
  }
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  return JSON.stringify(value, null, 2);
}

function stringQuery(key: string) {
  const value = route.query[key];
  return Array.isArray(value) ? value[0] || '' : value ? String(value) : '';
}

onMounted(async () => {
  await loadResults();
});
</script>

<style scoped>
.eval-result-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.eval-result-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.eval-result-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.eval-result-header h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
  letter-spacing: 0;
}

.eval-result-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.eval-result-list-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.eval-result-list-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.eval-result-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.eval-result-table-wrap :deep(.el-table) {
  height: 100%;
}

.w-120 {
  width: 120px;
}

.w-150 {
  width: 150px;
}

.w-180 {
  width: 180px;
}

.cell-strong {
  font-weight: 600;
  color: #111827;
}

.cell-muted {
  color: #64748b;
}

.violation-danger {
  color: var(--el-color-danger);
  font-weight: 600;
}

.result-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.detail-body {
  display: grid;
  gap: 18px;
}

.detail-section {
  display: grid;
  gap: 10px;
}

.detail-section h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  letter-spacing: 0;
}

.section-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.text-grid,
.json-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.text-grid-wide {
  grid-column: 1 / -1;
}

pre {
  overflow: auto;
  max-height: 320px;
  margin: 8px 0 0;
  padding: 12px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8fafc;
  color: #111827;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

@media (max-width: 920px) {
  .eval-result-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .text-grid,
  .json-grid {
    grid-template-columns: 1fr;
  }

  .text-grid-wide {
    grid-column: auto;
  }

  .w-120,
  .w-150,
  .w-180 {
    width: 100%;
  }
}
</style>
