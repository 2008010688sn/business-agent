<template>
  <BaseLayout>
    <main>
      <ElCard>
        <section class="token-usage-header">
          <div>
            <h1>Token 用量</h1>
          </div>
          <section class="token-usage-summary">
            <article v-for="card in summaryCards" :key="card.label" class="summary-card">
              <span>{{ card.label }}</span>
              <strong>{{ card.value }}</strong>
              <small>{{ card.hint }}</small>
            </article>
          </section>
        </section>
      </ElCard>
      <ElCard class="mt-8px">
        <ElForm :model="query" label-width="80px">
          <ElRow :gutter="24">
            <ElCol :span="6">
              <ElFormItem label="时间范围">
                <ElDatePicker
                  v-model="timeRange"
                  type="datetimerange"
                  start-placeholder="开始时间"
                  end-placeholder="结束时间"
                  value-format="YYYY-MM-DDTHH:mm:ss.SSSZ"
                  clearable
                />
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="租户">
                <ElInput v-model="query.tenantId" clearable placeholder="租户ID / 租户编码" />
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="使用用户">
                <ElSelect
                  v-model="query.userId"
                  clearable
                  filterable
                  placeholder="全部用户"
                  :loading="optionLoading"
                  @visible-change="handleOptionsVisible"
                >
                  <ElOption
                    v-for="item in userOptions"
                    :key="String(resolveUserId(item))"
                    :label="userLabel(item)"
                    :value="String(resolveUserId(item))"
                  />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="智能体">
                <ElSelect
                  v-model="query.agentId"
                  clearable
                  filterable
                  placeholder="全部智能体"
                  :loading="optionLoading"
                  @visible-change="handleOptionsVisible"
                >
                  <ElOption
                    v-for="item in agentOptions"
                    :key="String(item.id)"
                    :label="agentLabel(item)"
                    :value="item.id || ''"
                  />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="模型">
                <ElSelect
                  v-model="query.modelConfigId"
                  clearable
                  filterable
                  placeholder="全部模型"
                  :loading="optionLoading"
                  @visible-change="handleOptionsVisible"
                >
                  <ElOption
                    v-for="item in modelOptions"
                    :key="String(item.id)"
                    :label="modelLabel(item)"
                    :value="item.id || ''"
                  />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="调用来源">
                <ElSelect v-model="query.usageSource" clearable placeholder="全部来源">
                  <ElOption
                    v-for="item in usageSourceOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="计量模式">
                <ElSelect v-model="query.meteringMode" clearable placeholder="全部模式">
                  <ElOption v-for="item in meteringOptions" :key="item.value" :label="item.label" :value="item.value" />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="状态">
                <ElSelect v-model="query.status" clearable placeholder="全部状态">
                  <ElOption v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="请求ID">
                <ElInput v-model="query.runtimeRequestId" clearable placeholder="请求ID" @keyup.enter="handleSearch" />
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="根请求ID">
                <ElInput
                  v-model="query.rootRuntimeRequestId"
                  clearable
                  placeholder="根请求ID"
                  @keyup.enter="handleSearch"
                />
              </ElFormItem>
            </ElCol>
            <ElButton type="primary" :loading="loading" @click="handleSearch">
              <ElIcon><Search /></ElIcon>
              查询
            </ElButton>
            <ElButton @click="handleReset">
              <ElIcon><Refresh /></ElIcon>
              重置
            </ElButton>
          </ElRow>
        </ElForm>
      </ElCard>
      <ElCard class="mt-10px">
        <ElTabs v-model="activeTab" class="token-usage-tabs">
          <ElTabPane label="用量分析" name="analysis">
            <section class="analysis-grid">
              <article class="analysis-panel trend-panel">
                <div class="panel-title">
                  <strong>按天趋势</strong>
                  <span>{{ dayBreakdown.length }} 天</span>
                </div>
                <div v-if="dayBreakdown.length" class="trend-chart">
                  <div v-for="item in dayBreakdown" :key="item.groupKey || item.groupName" class="trend-row">
                    <span class="trend-date">{{ item.groupName || item.groupKey || '-' }}</span>
                    <div class="trend-bars">
                      <div class="trend-track total">
                        <i :style="barStyle(item.totalTokens)" />
                      </div>
                      <div class="trend-track prompt">
                        <i :style="barStyle(item.promptTokens)" />
                      </div>
                      <div class="trend-track completion">
                        <i :style="barStyle(item.completionTokens)" />
                      </div>
                    </div>
                    <strong>{{ formatNumber(item.totalTokens) }}</strong>
                  </div>
                  <div class="trend-legend">
                    <span>
                      <i class="legend-total" />
                      total
                    </span>
                    <span>
                      <i class="legend-prompt" />
                      prompt
                    </span>
                    <span>
                      <i class="legend-completion" />
                      completion
                    </span>
                  </div>
                </div>
                <ElEmpty v-else description="暂无趋势数据" />
              </article>

              <article class="analysis-panel">
                <div class="panel-title">
                  <strong>用户排行</strong>
                  <span>Top 8</span>
                </div>
                <RankList :rows="topUsers" :label-resolver="resolveUserName" />
              </article>

              <article class="analysis-panel">
                <div class="panel-title">
                  <strong>智能体排行</strong>
                  <span>Top 8</span>
                </div>
                <RankList :rows="topAgents" :label-resolver="resolveAgentName" />
              </article>

              <article class="analysis-panel">
                <div class="panel-title">
                  <strong>模型排行</strong>
                  <span>Top 8</span>
                </div>
                <RankList :rows="topModels" :label-resolver="resolveModelName" />
              </article>
            </section>
          </ElTabPane>

          <ElTabPane label="明细流水" name="details">
            <section>
              <div class="table-toolbar">
                <strong>调用流水</strong>
                <span>{{ detailPage.total }} 条</span>
              </div>
              <ElTable
                v-loading="loading"
                :data="detailRows"
                border
                stripe
                row-key="id"
                empty-text="暂无 Token 用量流水"
              >
                <ElTableColumn label="时间" width="170" fixed="left">
                  <template #default="{ row }">
                    <div class="cell-strong">{{ formatDateTime(row.createTime) }}</div>
                    <span class="cell-muted">{{ formatDuration(row.durationMs) }}</span>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="请求ID" min-width="260" show-overflow-tooltip>
                  <template #default="{ row }">
                    <ElButton link type="primary" class="mono-link" @click="goDiagnostics(row)">
                      {{ row.runtimeRequestId || '-' }}
                    </ElButton>
                    <div class="mono-line">Root: {{ row.rootRuntimeRequestId || '-' }}</div>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="用户 / 租户" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div>{{ row.userNickName || resolveUserName(row.userId) }}</div>
                    <span class="cell-muted">{{ row.tenantCode || row.tenantId || '-' }}</span>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="智能体" min-width="150" show-overflow-tooltip>
                  <template #default="{ row }">{{ row.agentName || resolveAgentName(row.agentId) }}</template>
                </ElTableColumn>
                <ElTableColumn label="模型" min-width="180" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div>{{ row.modelName || resolveModelName(row.modelConfigId) }}</div>
                    <span class="cell-muted">{{ row.provider || '-' }}</span>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="来源" width="150">
                  <template #default="{ row }">{{ usageSourceLabel(row.usageSource) }}</template>
                </ElTableColumn>
                <ElTableColumn label="模式" width="104">
                  <template #default="{ row }">
                    <ElTag :type="meteringTagType(row.meteringMode)" effect="light" size="small">
                      {{ meteringLabel(row.meteringMode) }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="状态" width="104">
                  <template #default="{ row }">
                    <ElTag :type="statusTagType(row.status)" effect="light" size="small">
                      {{ statusLabel(row.status) }}
                    </ElTag>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="Token" width="150" align="right">
                  <template #default="{ row }">
                    <div class="cell-strong">{{ formatNumber(row.totalTokens) }}</div>
                    <span class="cell-muted">
                      P {{ formatNumber(row.promptTokens) }} / C {{ formatNumber(row.completionTokens) }}
                    </span>
                  </template>
                </ElTableColumn>
                <ElTableColumn prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip />
              </ElTable>
              <div class="token-usage-pagination">
                <ElPagination
                  v-model:current-page="detailPage.current"
                  v-model:page-size="detailPage.size"
                  background
                  layout="total, sizes, prev, pager, next, jumper"
                  :page-sizes="[10, 20, 50, 100]"
                  :total="detailPage.total"
                  @size-change="handleDetailSizeChange"
                  @current-change="loadDetails"
                />
              </div>
            </section>
          </ElTabPane>

          <ElTabPane label="限额策略" name="policies">
            <section v-if="canManageUsage">
              <div class="table-toolbar policy-toolbar">
                <div>
                  <strong>限额与速率策略</strong>
                  <span>{{ policyPage.total }} 条</span>
                </div>
                <ElButton type="primary" @click="openCreatePolicy">
                  <ElIcon><Plus /></ElIcon>
                  新增策略
                </ElButton>
              </div>
              <ElTable
                v-loading="policyLoading"
                :data="policyRows"
                border
                stripe
                row-key="id"
                empty-text="暂无限额策略"
              >
                <ElTableColumn label="状态" width="92" fixed="left">
                  <template #default="{ row }">
                    <ElSwitch :model-value="row.enabled" @change="value => handlePolicyStatus(row, Boolean(value))" />
                  </template>
                </ElTableColumn>
                <ElTableColumn label="范围" min-width="160">
                  <template #default="{ row }">
                    <div class="cell-strong">{{ scopeLabel(row.scopeType) }}</div>
                    <span class="cell-muted">{{ row.userNickName || row.scopeId || '-' }}</span>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="策略" min-width="160">
                  <template #default="{ row }">
                    <div>{{ policyTypeLabel(row.policyType) }}</div>
                    <span class="cell-muted">{{ actionLabel(row.action) }}</span>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="窗口" width="150">
                  <template #default="{ row }">
                    <div>{{ windowLabel(row.windowType) }}</div>
                    <span v-if="row.windowType === 'ROLLING'" class="cell-muted">{{ row.windowSeconds }} 秒</span>
                  </template>
                </ElTableColumn>
                <ElTableColumn label="限制值" width="130" align="right">
                  <template #default="{ row }">{{ formatNumber(row.limitValue) }}</template>
                </ElTableColumn>
                <ElTableColumn label="智能体 / 模型" min-width="220" show-overflow-tooltip>
                  <template #default="{ row }">
                    <div>智能体: {{ row.agentId ? row.agentName || resolveAgentName(row.agentId) : '全部' }}</div>
                    <span class="cell-muted">
                      模型: {{ row.modelConfigId ? resolveModelName(row.modelConfigId) : '全部' }}
                    </span>
                  </template>
                </ElTableColumn>
                <ElTableColumn prop="description" label="说明" min-width="220" show-overflow-tooltip />
                <ElTableColumn label="操作" width="132" fixed="right" align="center">
                  <template #default="{ row }">
                    <ElTooltip content="编辑" placement="top">
                      <ElButton text type="primary" @click="openEditPolicy(row)">
                        <ElIcon><Edit /></ElIcon>
                      </ElButton>
                    </ElTooltip>
                    <ElTooltip content="删除" placement="top">
                      <ElButton text type="danger" @click="handleDeletePolicy(row)">
                        <ElIcon><Delete /></ElIcon>
                      </ElButton>
                    </ElTooltip>
                  </template>
                </ElTableColumn>
              </ElTable>
              <div class="token-usage-pagination">
                <ElPagination
                  v-model:current-page="policyPage.current"
                  v-model:page-size="policyPage.size"
                  background
                  layout="total, sizes, prev, pager, next, jumper"
                  :page-sizes="[10, 20, 50, 100]"
                  :total="policyPage.total"
                  @size-change="handlePolicySizeChange"
                  @current-change="loadPolicies"
                />
              </div>
            </section>
            <ElAlert
              v-else
              title="当前账号没有 data-agent:usage:manage 权限"
              type="warning"
              :closable="false"
              show-icon
            />
          </ElTabPane>
        </ElTabs>
      </ElCard>

      <ElDialog
        v-model="policyDialogVisible"
        :title="editingPolicyId ? '编辑策略' : '新增策略'"
        width="720px"
        destroy-on-close
      >
        <ElForm :model="policyForm" label-position="top" class="policy-form">
          <ElFormItem label="策略范围">
            <ElSelect v-model="policyForm.scopeType" @change="handleScopeTypeChange">
              <ElOption label="全局" value="GLOBAL" />
              <ElOption label="租户" value="TENANT" />
              <ElOption label="用户" value="USER" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="范围 ID">
            <ElInput
              v-model="policyForm.scopeId"
              :disabled="policyForm.scopeType === 'GLOBAL'"
              placeholder="租户 ID 或用户 ID"
            />
          </ElFormItem>
          <ElFormItem label="Agent">
            <ElSelect
              v-model="policyForm.agentId"
              clearable
              filterable
              placeholder="全部 Agent"
              @visible-change="handleOptionsVisible"
            >
              <ElOption
                v-for="item in agentOptions"
                :key="String(item.id)"
                :label="agentLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="模型">
            <ElSelect
              v-model="policyForm.modelConfigId"
              clearable
              filterable
              placeholder="全部模型"
              @visible-change="handleOptionsVisible"
            >
              <ElOption
                v-for="item in modelOptions"
                :key="String(item.id)"
                :label="modelLabel(item)"
                :value="item.id || ''"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="策略类型">
            <ElSelect v-model="policyForm.policyType">
              <ElOption label="Token 总量" value="TOKEN_QUOTA" />
              <ElOption label="Token 速率" value="TOKEN_RATE" />
              <ElOption label="请求速率" value="REQUEST_RATE" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="窗口类型">
            <ElSelect v-model="policyForm.windowType">
              <ElOption label="自然日" value="DAY" />
              <ElOption label="自然月" value="MONTH" />
              <ElOption label="滚动窗口" value="ROLLING" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem v-if="policyForm.windowType === 'ROLLING'" label="滚动窗口秒数">
            <ElInputNumber v-model="policyForm.windowSeconds" :min="1" :step="60" controls-position="right" />
          </ElFormItem>
          <ElFormItem label="限制值">
            <ElInputNumber v-model="policyForm.limitValue" :min="1" :step="1000" controls-position="right" />
          </ElFormItem>
          <ElFormItem label="预警阈值">
            <ElInputNumber
              v-model="policyForm.warnThresholdRatio"
              :min="0"
              :max="1"
              :step="0.05"
              :precision="2"
              controls-position="right"
            />
          </ElFormItem>
          <ElFormItem label="动作">
            <ElSelect v-model="policyForm.action">
              <ElOption label="阻断" value="BLOCK" />
              <ElOption label="告警" value="WARN" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="启用">
            <ElSwitch v-model="policyForm.enabled" />
          </ElFormItem>
          <ElFormItem label="说明" class="policy-form-wide">
            <ElInput v-model="policyForm.description" type="textarea" :rows="3" maxlength="200" show-word-limit />
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="policyDialogVisible = false">取消</ElButton>
          <ElButton type="primary" :loading="policySubmitting" @click="submitPolicy">保存</ElButton>
        </template>
      </ElDialog>
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElEmpty, ElMessage, ElMessageBox, ElProgress } from 'element-plus';
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import agentService from '@/views/ai-agent/services/agent';
import type { Agent } from '@/views/ai-agent/services/agent';
import diagnosticsService from '@/views/ai-agent/services/diagnostics';
import type { BasicUser } from '@/views/ai-agent/services/diagnostics';
import modelConfigService from '@/views/ai-agent/services/modelConfig';
import type { ModelConfig } from '@/views/ai-agent/services/modelConfig';
import tokenUsageService from '@/views/ai-agent/services/tokenUsage';
import type {
  AgentTokenUsage,
  AgentTokenUsageBreakdown,
  AgentTokenUsageQuery,
  AgentTokenUsageSummary,
  AgentUsageLimitPolicy,
  AgentUsageLimitPolicyRequest,
  TokenUsageId
} from '@/views/ai-agent/services/tokenUsage';
import { useAuthStore } from '@/store/modules/auth';
import { haveAuth } from '@/mixins/userAuth.js';

defineOptions({ name: 'DataAgentTokenUsage' });

const USAGE_MANAGE_PERMISSION = 'data-agent:usage:manage';
const DATE_FORMAT = 'YYYY-MM-DDTHH:mm:ss.SSSZ';

const usageSourceOptions = [
  { label: 'ReAct 主模型', value: 'AGENT_REACT' },
  { label: '知识快路径', value: 'KNOWLEDGE_FAST_PATH' },
  { label: '编排路由', value: 'ORCHESTRATION_ROUTE' },
  { label: '编排总结', value: 'ORCHESTRATION_SUMMARY' }
];

const meteringOptions = [
  { label: '实际', value: 'ACTUAL' },
  { label: '估算', value: 'ESTIMATED' },
  { label: '未知', value: 'UNKNOWN' }
];

const statusOptions = [
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' },
  { label: '取消', value: 'CANCELLED' }
];

const authStore = useAuthStore();
const router = useRouter();
const canManageUsage = computed(() => isAdminUserType(authStore.userInfo?.type) || haveAuth(USAGE_MANAGE_PERMISSION));

const defaultRange = (): [string, string] => [
  dayjs().subtract(6, 'day').startOf('day').format(DATE_FORMAT),
  dayjs().endOf('day').format(DATE_FORMAT)
];

const query = reactive<AgentTokenUsageQuery>({
  tenantId: '',
  userId: '',
  agentId: '',
  modelConfigId: '',
  usageSource: '',
  meteringMode: '',
  status: '',
  runtimeRequestId: '',
  rootRuntimeRequestId: ''
});

const timeRange = ref<[string, string] | []>(defaultRange());
const activeTab = ref('analysis');
const loading = ref(false);
const usageRequestId = ref(0);
const optionLoading = ref(false);
const optionsLoaded = ref(false);
const detailsLoaded = ref(false);
const policiesLoaded = ref(false);
const summary = ref<AgentTokenUsageSummary>({});
const userBreakdown = ref<AgentTokenUsageBreakdown[]>([]);
const agentBreakdown = ref<AgentTokenUsageBreakdown[]>([]);
const modelBreakdown = ref<AgentTokenUsageBreakdown[]>([]);
const dayBreakdown = ref<AgentTokenUsageBreakdown[]>([]);
const detailRows = ref<AgentTokenUsage[]>([]);
const agentOptions = ref<Agent[]>([]);
const userOptions = ref<BasicUser[]>([]);
const modelOptions = ref<ModelConfig[]>([]);

const detailPage = reactive({
  current: 1,
  size: 20,
  total: 0
});

const policyRows = ref<AgentUsageLimitPolicy[]>([]);
const policyLoading = ref(false);
const policySubmitting = ref(false);
const policyDialogVisible = ref(false);
const editingPolicyId = ref<TokenUsageId | null>(null);
const policyPage = reactive({
  current: 1,
  size: 20,
  total: 0
});
const policyForm = reactive<AgentUsageLimitPolicyRequest>(defaultPolicyForm());

const RankList = defineComponent({
  name: 'TokenUsageRankList',
  props: {
    rows: {
      type: Array as () => AgentTokenUsageBreakdown[],
      required: true
    },
    labelResolver: {
      type: Function as unknown as () => (value?: TokenUsageId | string) => string,
      required: true
    }
  },
  setup(props) {
    const maxTokens = computed(() => Math.max(1, ...props.rows.map(row => Number(row.totalTokens || 0))));
    return () =>
      props.rows.length
        ? h(
            'div',
            { class: 'rank-list' },
            props.rows.map((row, index) =>
              h('div', { class: 'rank-item', key: row.groupKey || `${index}` }, [
                h('span', { class: 'rank-index' }, String(index + 1).padStart(2, '0')),
                h('div', { class: 'rank-main' }, [
                  h('div', { class: 'rank-label' }, row.groupName || props.labelResolver(row.groupKey) || '-'),
                  h(ElProgress, {
                    percentage: Math.round((Number(row.totalTokens || 0) / maxTokens.value) * 100),
                    showText: false,
                    strokeWidth: 6
                  })
                ]),
                h('strong', null, formatNumber(row.totalTokens))
              ])
            )
          )
        : h(ElEmpty, { description: '暂无排行数据' });
  }
});

const summaryCards = computed(() => [
  { label: '总 Token', value: formatNumber(summary.value.totalTokens), hint: '包含实际与估算' },
  { label: '实际 Token', value: formatNumber(summary.value.actualTokens), hint: '账单口径' },
  { label: '估算 Token', value: formatNumber(summary.value.estimatedTokens), hint: '单独标识' },
  {
    label: '请求数',
    value: formatNumber(summary.value.requestCount),
    hint: `${formatNumber(summary.value.successCount)} 成功`
  },
  {
    label: '失败数',
    value: formatNumber(summary.value.failedCount),
    hint: `${formatNumber(summary.value.unknownCount)} 未知用量`
  },
  {
    label: '平均 Token',
    value: formatNumber(Math.round(Number(summary.value.averageTokens || 0))),
    hint: '按调用流水'
  },
  { label: '超限次数', value: formatNumber(summary.value.blockedCount), hint: '限额或速率阻断' }
]);

const topUsers = computed(() => userBreakdown.value.slice(0, 8));
const topAgents = computed(() => agentBreakdown.value.slice(0, 8));
const topModels = computed(() => modelBreakdown.value.slice(0, 8));
const trendMax = computed(() => Math.max(1, ...dayBreakdown.value.map(row => Number(row.totalTokens || 0))));

function defaultPolicyForm(): AgentUsageLimitPolicyRequest {
  return {
    scopeType: 'GLOBAL',
    scopeId: '*',
    agentId: '',
    modelConfigId: '',
    policyType: 'TOKEN_QUOTA',
    windowType: 'DAY',
    windowSeconds: 3600,
    limitValue: 100000,
    warnThresholdRatio: 0.8,
    action: 'BLOCK',
    enabled: true,
    description: ''
  };
}

async function loadOptions(force = false) {
  if (optionsLoaded.value && !force) {
    return;
  }
  optionLoading.value = true;
  try {
    const [agents, users, models] = await Promise.all([
      agentService.list(),
      diagnosticsService.listUsers(),
      modelConfigService.list()
    ]);
    agentOptions.value = agents;
    userOptions.value = users;
    modelOptions.value = models.filter(item => String(item.modelType || '').toUpperCase() === 'CHAT');
    optionsLoaded.value = true;
  } catch (error) {
    ElMessage.warning('基础筛选数据加载失败，可继续按 ID 查询');
  } finally {
    optionLoading.value = false;
  }
}

async function loadUsage() {
  const requestId = usageRequestId.value + 1;
  usageRequestId.value = requestId;
  loading.value = true;
  try {
    const overview = await tokenUsageService.queryOverview(buildBaseQuery());
    if (usageRequestId.value !== requestId) {
      return;
    }
    summary.value = overview.summary || {};
    userBreakdown.value = overview.userBreakdown || [];
    agentBreakdown.value = overview.agentBreakdown || [];
    modelBreakdown.value = overview.modelBreakdown || [];
    dayBreakdown.value = overview.dayBreakdown || [];
  } catch (error) {
    if (usageRequestId.value === requestId) {
      ElMessage.error(error instanceof Error ? error.message : 'Token 用量查询失败');
    }
  } finally {
    if (usageRequestId.value === requestId) {
      loading.value = false;
    }
  }
}

async function loadDetails() {
  loading.value = true;
  try {
    const response = await tokenUsageService.queryDetailsPage({
      ...buildBaseQuery(),
      current: detailPage.current,
      size: detailPage.size
    });
    detailRows.value = response.data;
    detailPage.total = response.total;
    detailsLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '明细流水查询失败');
  } finally {
    loading.value = false;
  }
}

async function loadPolicies() {
  if (!canManageUsage.value) {
    return;
  }
  policyLoading.value = true;
  try {
    const response = await tokenUsageService.queryPoliciesPage({
      current: policyPage.current,
      size: policyPage.size
    });
    policyRows.value = response.data;
    policyPage.total = response.total;
    policiesLoaded.value = true;
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '限额策略查询失败');
  } finally {
    policyLoading.value = false;
  }
}

function buildBaseQuery(): AgentTokenUsageQuery {
  const [startTime, endTime] = timeRange.value;
  return {
    ...query,
    startTime,
    endTime
  };
}

function handleSearch() {
  detailPage.current = 1;
  detailsLoaded.value = false;
  if (activeTab.value === 'policies') {
    policyPage.current = 1;
    loadPolicies();
    return;
  }
  if (activeTab.value === 'details') {
    Promise.all([loadUsage(), loadDetails()]);
    return;
  }
  loadUsage();
}

function handleReset() {
  Object.assign(query, {
    tenantId: '',
    userId: '',
    agentId: '',
    modelConfigId: '',
    usageSource: '',
    meteringMode: '',
    status: '',
    runtimeRequestId: '',
    rootRuntimeRequestId: ''
  });
  timeRange.value = defaultRange();
  handleSearch();
}

function handleDetailSizeChange() {
  detailPage.current = 1;
  loadDetails();
}

function handlePolicySizeChange() {
  policyPage.current = 1;
  loadPolicies();
}

function handleRefresh() {
  if (activeTab.value === 'policies') {
    loadPolicies();
    return;
  }
  if (activeTab.value === 'details') {
    Promise.all([loadUsage(), loadDetails()]);
    return;
  }
  loadUsage();
}

function handleOptionsVisible(visible: boolean) {
  if (visible) {
    loadOptions();
  }
}

async function openCreatePolicy() {
  await loadOptions();
  editingPolicyId.value = null;
  Object.assign(policyForm, defaultPolicyForm());
  policyDialogVisible.value = true;
}

async function openEditPolicy(row: AgentUsageLimitPolicy) {
  await loadOptions();
  editingPolicyId.value = row.id ?? null;
  Object.assign(policyForm, {
    scopeType: row.scopeType || 'GLOBAL',
    scopeId: row.scopeId || '*',
    agentId: row.agentId || '',
    modelConfigId: row.modelConfigId || '',
    policyType: row.policyType || 'TOKEN_QUOTA',
    windowType: row.windowType || 'DAY',
    windowSeconds: row.windowSeconds || 3600,
    limitValue: row.limitValue || 1,
    warnThresholdRatio: row.warnThresholdRatio ?? 0.8,
    action: row.action || 'BLOCK',
    enabled: row.enabled !== false,
    description: row.description || ''
  });
  policyDialogVisible.value = true;
}

function handleScopeTypeChange() {
  if (policyForm.scopeType === 'GLOBAL') {
    policyForm.scopeId = '*';
  } else if (policyForm.scopeId === '*') {
    policyForm.scopeId = '';
  }
}

async function submitPolicy() {
  const error = validatePolicyForm();
  if (error) {
    ElMessage.warning(error);
    return;
  }
  policySubmitting.value = true;
  try {
    const payload = normalizePolicyPayload();
    if (editingPolicyId.value) {
      await tokenUsageService.updatePolicy(editingPolicyId.value, payload);
      ElMessage.success('策略已更新');
    } else {
      await tokenUsageService.createPolicy(payload);
      ElMessage.success('策略已创建');
    }
    policyDialogVisible.value = false;
    await loadPolicies();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '策略保存失败');
  } finally {
    policySubmitting.value = false;
  }
}

async function handlePolicyStatus(row: AgentUsageLimitPolicy, enabled: boolean) {
  if (!row.id) {
    return;
  }
  const previous = row.enabled;
  row.enabled = enabled;
  try {
    await tokenUsageService.updatePolicyStatus(row.id, enabled);
    ElMessage.success(enabled ? '策略已启用' : '策略已停用');
  } catch (error) {
    row.enabled = previous;
    ElMessage.error(error instanceof Error ? error.message : '策略状态更新失败');
  }
}

async function handleDeletePolicy(row: AgentUsageLimitPolicy) {
  if (!row.id) {
    return;
  }
  try {
    await ElMessageBox.confirm('删除后该策略不再参与实时阻断，是否继续？', '删除限额策略', { type: 'warning' });
    await tokenUsageService.deletePolicy(row.id);
    ElMessage.success('策略已删除');
    await loadPolicies();
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(error instanceof Error ? error.message : '策略删除失败');
    }
  }
}

function validatePolicyForm() {
  if (!policyForm.scopeType) return '请选择策略范围';
  if (policyForm.scopeType !== 'GLOBAL' && !String(policyForm.scopeId || '').trim()) return '请填写范围 ID';
  if (!policyForm.policyType) return '请选择策略类型';
  if (!policyForm.windowType) return '请选择窗口类型';
  if (policyForm.windowType === 'ROLLING' && Number(policyForm.windowSeconds || 0) < 1) return '滚动窗口秒数必须大于 0';
  if (Number(policyForm.limitValue || 0) < 1) return '限制值必须大于 0';
  return '';
}

function normalizePolicyPayload(): AgentUsageLimitPolicyRequest {
  return {
    ...policyForm,
    scopeId: policyForm.scopeType === 'GLOBAL' ? '*' : String(policyForm.scopeId || '').trim(),
    agentId: policyForm.agentId || undefined,
    modelConfigId: policyForm.modelConfigId || undefined,
    windowSeconds: policyForm.windowType === 'ROLLING' ? Number(policyForm.windowSeconds || 1) : undefined,
    limitValue: Number(policyForm.limitValue || 1),
    warnThresholdRatio: Number(policyForm.warnThresholdRatio ?? 0.8)
  };
}

function goDiagnostics(row: AgentTokenUsage) {
  router.push({
    path: '/ai-agent/diagnostics',
    query: {
      sessionId: row.sessionId ? String(row.sessionId) : undefined,
      runtimeRequestId: row.runtimeRequestId || undefined
    }
  });
}

function resolveUserId(user: BasicUser) {
  return user.userId ?? user.id ?? user.code ?? '';
}

function userLabel(user: BasicUser) {
  const name = user.name || user.realName || user.nickName || user.username || user.userName || user.account || '';
  const id = resolveUserId(user);
  return name && id ? `${name}（${id}）` : name || String(id || '-');
}

function resolveUserName(value?: TokenUsageId | string) {
  const key = String(value || '');
  const user = userOptions.value.find(item => String(resolveUserId(item)) === key);
  return user ? userLabel(user) : key || '-';
}

function agentLabel(agent: Agent) {
  return agent.name ? `${agent.name}（${agent.id || '-'}）` : String(agent.id || '-');
}

function resolveAgentName(value?: TokenUsageId | string) {
  const key = String(value || '');
  const agent = agentOptions.value.find(item => String(item.id) === key);
  return agent?.name || key || '-';
}

function modelLabel(model: ModelConfig) {
  return `${model.provider || '-'} / ${model.modelName || '-'}（${model.id || '-'}）`;
}

function resolveModelName(value?: TokenUsageId | string) {
  const key = String(value || '');
  const model = modelOptions.value.find(item => String(item.id) === key || item.modelName === key);
  return model ? `${model.provider || '-'} / ${model.modelName || '-'}` : key || '-';
}

function usageSourceLabel(value?: string) {
  return usageSourceOptions.find(item => item.value === value)?.label || value || '-';
}

function meteringLabel(value?: string) {
  return meteringOptions.find(item => item.value === value)?.label || value || '-';
}

function meteringTagType(value?: string) {
  if (value === 'ACTUAL') return 'success';
  if (value === 'ESTIMATED') return 'warning';
  if (value === 'UNKNOWN') return 'info';
  return undefined;
}

function statusLabel(value?: string) {
  return statusOptions.find(item => item.value === value)?.label || value || '-';
}

function statusTagType(value?: string) {
  if (value === 'SUCCESS') return 'success';
  if (value === 'FAILED') return 'danger';
  if (value === 'CANCELLED') return 'info';
  return undefined;
}

function normalizeUserType(value: unknown) {
  if (value === null || value === undefined) {
    return '';
  }
  if (typeof value === 'object') {
    const userType = value as Record<string, unknown>;
    return String(userType.value ?? userType.code ?? userType.name ?? '');
  }
  return String(value);
}

function isAdminUserType(value: unknown) {
  return ['0', '1', 'PLATFORM_ADMIN', 'TENANT_ADMIN'].includes(normalizeUserType(value));
}

function scopeLabel(value?: string) {
  return { GLOBAL: '全局', TENANT: '租户', USER: '用户' }[value || ''] || value || '-';
}

function policyTypeLabel(value?: string) {
  return { TOKEN_QUOTA: 'Token 总量', TOKEN_RATE: 'Token 速率', REQUEST_RATE: '请求速率' }[value || ''] || value || '-';
}

function windowLabel(value?: string) {
  return { DAY: '自然日', MONTH: '自然月', ROLLING: '滚动窗口' }[value || ''] || value || '-';
}

function actionLabel(value?: string) {
  return { BLOCK: '阻断', WARN: '告警' }[value || ''] || value || '-';
}

function formatNumber(value?: number | string | null) {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) ? new Intl.NumberFormat('zh-CN').format(numeric) : '0';
}

function formatDateTime(value?: string) {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
}

function formatDuration(value?: number | string | null) {
  const numeric = Number(value || 0);
  if (!numeric) return '-';
  if (numeric < 1000) return `${numeric}ms`;
  return `${(numeric / 1000).toFixed(1)}s`;
}

function barStyle(value?: number | string | null) {
  const numeric = Number(value || 0);
  return {
    width: numeric <= 0 ? '0%' : `${Math.max(4, Math.round((numeric / trendMax.value) * 100))}%`
  };
}

watch(activeTab, value => {
  if (value === 'details' && !detailsLoaded.value) {
    loadDetails();
  }
  if (value === 'policies' && !policiesLoaded.value) {
    loadPolicies();
  }
});

onMounted(() => {
  loadUsage();
});
</script>

<style scoped>
.token-usage-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 0;
}

.token-usage-header > div:first-child,
.token-usage-header-actions {
  flex: 0 0 auto;
}

.token-usage-header h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 600;
  line-height: 28px;
  letter-spacing: 0;
}

.token-usage-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.token-usage-table-panel,
.analysis-panel,
.summary-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
}

.token-usage-filter-actions {
  align-self: end;
}

.token-usage-filter-actions :deep(.el-form-item__content) {
  display: flex;
  flex-wrap: nowrap;
  gap: 8px;
}

.token-usage-summary {
  display: grid;
  flex: 1 1 0;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 8px;
  min-width: 0;
  margin-left: 30px;
}

.summary-card {
  display: grid;
  gap: 4px;
  min-width: 0;
  min-height: 74px;
  padding: 10px 12px;
}

.summary-card span,
.summary-card small,
.panel-title span,
.cell-muted {
  color: #64748b;
}

.summary-card span,
.summary-card small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-card strong {
  overflow: hidden;
  font-size: 20px;
  line-height: 1.15;
  color: #0f172a;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.analysis-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.analysis-panel {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  height: 320px;
  min-height: 0;
  padding: 14px;
}

.trend-panel {
  grid-row: auto;
}

.panel-title,
.table-toolbar {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.panel-title strong,
.table-toolbar strong {
  font-size: 15px;
}

.trend-chart {
  display: grid;
  overflow-y: auto;
  flex: 1 1 0;
  gap: 11px;
  min-height: 0;
  padding-right: 4px;
  scrollbar-color: transparent transparent;
  scrollbar-width: none;
}

.trend-row {
  display: grid;
  grid-template-columns: 96px 1fr 86px;
  align-items: center;
  gap: 10px;
}

.trend-date {
  color: #475569;
  font-size: 12px;
}

.trend-bars {
  display: grid;
  gap: 4px;
}

.trend-track {
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: #eef2f7;
}

.trend-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
}

.trend-track.total i,
.legend-total {
  background: #2563eb;
}

.trend-track.prompt i,
.legend-prompt {
  background: #16a34a;
}

.trend-track.completion i,
.legend-completion {
  background: #f97316;
}

.trend-legend {
  display: flex;
  gap: 14px;
  color: #64748b;
  font-size: 12px;
}

.trend-legend i {
  display: inline-block;
  width: 10px;
  height: 10px;
  margin-right: 5px;
  border-radius: 2px;
  vertical-align: -1px;
}

.token-usage-table-panel {
  padding: 14px;
}

.policy-toolbar > div {
  display: grid;
  gap: 2px;
}

.token-usage-pagination {
  display: flex;
  justify-content: flex-end;
  padding-top: 14px;
}

.cell-strong {
  font-weight: 600;
  color: #111827;
}

.mono-link {
  max-width: 100%;
  padding: 0;
  font-family: Consolas, Monaco, monospace;
}

.mono-line {
  overflow: hidden;
  color: #64748b;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-list {
  display: grid;
  overflow-y: auto;
  flex: 1 1 0;
  gap: 12px;
  min-height: 0;
  padding-right: 4px;
  scrollbar-color: transparent transparent;
  scrollbar-width: none;
}

.analysis-panel:hover .trend-chart,
.analysis-panel:hover .rank-list {
  scrollbar-color: rgb(203 213 225 / 70%) transparent;
  scrollbar-width: thin;
}

.trend-chart::-webkit-scrollbar,
.rank-list::-webkit-scrollbar {
  width: 0;
  height: 0;
}

.analysis-panel:hover .trend-chart::-webkit-scrollbar,
.analysis-panel:hover .rank-list::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

.trend-chart::-webkit-scrollbar-thumb,
.rank-list::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background-color: rgb(203 213 225 / 70%);
}

.trend-chart::-webkit-scrollbar-track,
.rank-list::-webkit-scrollbar-track {
  background: transparent;
}

.rank-item {
  display: grid;
  grid-template-columns: 32px 1fr 86px;
  align-items: center;
  gap: 10px;
}

.rank-index {
  color: #94a3b8;
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.rank-main {
  min-width: 0;
}

.rank-label {
  overflow: hidden;
  margin-bottom: 5px;
  font-size: 13px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-item strong {
  text-align: right;
}

.policy-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 14px;
}

.policy-form-wide {
  grid-column: 1 / -1;
}

.policy-form :deep(.el-input-number),
.policy-form :deep(.el-select) {
  width: 100%;
}

@media (max-width: 1280px) {
  .analysis-grid {
    grid-template-columns: 1fr 1fr;
  }
}

@media (max-width: 920px) {
  .token-usage-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .analysis-grid,
  .policy-form {
    grid-template-columns: 1fr;
  }

  .trend-panel {
    grid-row: auto;
  }

  .trend-row {
    grid-template-columns: 80px 1fr;
  }

  .trend-row strong {
    grid-column: 2;
  }
}
</style>
