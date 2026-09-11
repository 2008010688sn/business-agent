<template>
  <div class="orchestration-config">
    <div class="panel-header">
      <div>
        <h2>编排配置</h2>
        <p>配置编排规则、协作 Agent、调试预览和调用记录。编排 Agent 不直接查询业务库。</p>
      </div>
      <ElButton type="primary" class="save-action action-button" :loading="policySaving" @click="savePolicy">
        <ElIcon class="button-glyph"><component :is="Check" /></ElIcon>
        <span>保存规则</span>
      </ElButton>
    </div>

    <ElTabs v-model="activeTab">
      <ElTabPane label="编排规则" name="policy">
        <ElForm label-width="180px" class="policy-form">
          <ElFormItem label="单轮最多协作 Agent">
            <ElInputNumber v-model="policy.maxCollaboratorsPerRun" :min="1" />
          </ElFormItem>
          <ElFormItem label="失败策略">
            <ElRadioGroup v-model="policy.failureStrategy">
              <ElRadioButton value="continue">继续汇总</ElRadioButton>
              <ElRadioButton value="fail_fast">直接失败</ElRadioButton>
            </ElRadioGroup>
          </ElFormItem>
          <ElFormItem label="展示协作过程">
            <ElSwitch v-model="policy.exposeTrace" />
          </ElFormItem>
          <ElFormItem label="启用编排">
            <ElSwitch v-model="policy.enabled" />
          </ElFormItem>
          <ElDivider content-position="left">澄清词典</ElDivider>
          <ElFormItem v-for="group in clarificationGroups" :key="group.key" :label="group.label">
            <div class="dictionary-editor">
              <div v-if="policy.clarificationConfig[group.key].length" class="dictionary-tags">
                <ElTag
                  v-for="(alias, index) in policy.clarificationConfig[group.key]"
                  :key="`${group.key}-${alias}`"
                  closable
                  @close="removeClarificationAlias(group.key, index)"
                >
                  {{ alias }}
                </ElTag>
              </div>
              <ElInput
                v-model="clarificationDrafts[group.key]"
                :maxlength="clarificationAliasMaxLength"
                clearable
                placeholder="输入词汇"
                @keyup.enter="addClarificationAlias(group.key)"
              >
                <template #append>
                  <ElButton :aria-label="`添加${group.label}`" @click="addClarificationAlias(group.key)">
                    <ElIcon><component :is="Plus" /></ElIcon>
                  </ElButton>
                </template>
              </ElInput>
            </div>
          </ElFormItem>
        </ElForm>
      </ElTabPane>

      <ElTabPane label="协作 Agent" name="collaborators">
        <div class="toolbar">
          <ElButton type="primary" class="create-action action-button" @click="openCollaboratorDialog()">
            <ElIcon class="button-glyph"><component :is="Connection" /></ElIcon>
            <span>新增协作 Agent</span>
          </ElButton>
        </div>
        <ElTable :data="collaborators" border>
          <ElTableColumn label="协作 Agent" min-width="180">
            <template #default="{ row }">
              <div class="strong">{{ row.collaboratorDataAgent?.name || row.collaboratorAgentId }}</div>
              <div class="muted">{{ formatAgentType(row.collaboratorDataAgent?.agentType) }}</div>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="roleName" label="协作角色" width="140" />
          <ElTableColumn prop="capabilityDescription" label="能力描述" min-width="220" show-overflow-tooltip />
          <ElTableColumn label="路由规则" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">{{ routingRuleSummary(row.routingRules) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="priority" label="优先级" width="90" />
          <ElTableColumn label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="row.enabled ? 'success' : 'info'">
                {{ row.enabled ? '启用' : '停用' }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="96" fixed="right">
            <template #default="{ row }">
              <div class="table-actions">
                <ElTooltip content="编辑" placement="top">
                  <ElButton class="icon-action action-edit" text aria-label="编辑" @click="openCollaboratorDialog(row)">
                    <ElIcon><component :is="Edit" /></ElIcon>
                  </ElButton>
                </ElTooltip>
                <ElTooltip content="删除" placement="top">
                  <ElButton class="icon-action action-delete" text aria-label="删除" @click="deleteCollaborator(row)">
                    <ElIcon><component :is="Delete" /></ElIcon>
                  </ElButton>
                </ElTooltip>
              </div>
            </template>
          </ElTableColumn>
        </ElTable>
      </ElTabPane>

      <ElTabPane label="调试预览" name="preview">
        <RoutePreviewPanel :agent-id="agentId" :target-types="['SKILL', 'COLLABORATOR']" />
      </ElTabPane>

      <ElTabPane label="调用记录" name="runs">
        <ElTable :data="runs" border @row-click="openRunTrace">
          <ElTableColumn prop="id" label="ID" width="90" />
          <ElTableColumn prop="query" label="问题" min-width="220" show-overflow-tooltip />
          <ElTableColumn prop="status" label="状态" width="100" />
          <ElTableColumn label="总耗时" width="110">
            <template #default="{ row }">
              {{ formatMs(row.totalMs) }}
            </template>
          </ElTableColumn>
          <ElTableColumn label="路由耗时" width="110">
            <template #default="{ row }">
              {{ formatMs(row.routeMs) }}
            </template>
          </ElTableColumn>
          <ElTableColumn prop="startedAt" label="开始时间" width="180" />
          <ElTableColumn prop="finishedAt" label="结束时间" width="180" />
          <ElTableColumn label="操作" width="96" fixed="right">
            <template #default="{ row }">
              <ElTooltip :content="canViewCallChain ? '查看详情' : '需要调用链路查看权限'" placement="top">
                <span>
                  <ElButton link type="primary" :disabled="!canViewCallChain" @click.stop="openRunTrace(row)">
                    详情
                  </ElButton>
                </span>
              </ElTooltip>
            </template>
          </ElTableColumn>
        </ElTable>
      </ElTabPane>
    </ElTabs>

    <ElDrawer v-model="traceDrawerVisible" title="编排调用详情" size="54%" destroy-on-close>
      <ElSkeleton v-if="traceLoading" animated :rows="10" />
      <ElAlert v-else-if="traceError" :title="traceError" type="warning" :closable="false" show-icon />
      <div v-else-if="selectedTrace.run" class="trace-detail-panel">
        <div class="metric-grid">
          <div class="metric-card">
            <span>状态</span>
            <strong>{{ selectedTrace.run.status || '-' }}</strong>
          </div>
          <div class="metric-card">
            <span>总耗时</span>
            <strong>{{ formatMs(selectedTrace.run.totalMs) }}</strong>
          </div>
          <div class="metric-card">
            <span>路由耗时</span>
            <strong>{{ formatMs(selectedTrace.run.routeMs) }}</strong>
          </div>
          <div class="metric-card">
            <span>协作者耗时</span>
            <strong>{{ formatMs(selectedTrace.run.collaboratorMs) }}</strong>
          </div>
          <div class="metric-card">
            <span>汇总耗时</span>
            <strong>{{ formatMs(selectedTrace.run.summaryMs) }}</strong>
          </div>
          <div class="metric-card">
            <span>协作者数量</span>
            <strong>{{ selectedTrace.run.collaboratorCount ?? selectedTrace.steps?.length ?? 0 }}</strong>
          </div>
        </div>

        <div class="trace-meta">
          <span>Run: {{ selectedTrace.run.id }}</span>
          <span>Request: {{ selectedTrace.run.runtimeRequestId || '-' }}</span>
        </div>

        <ElTable :data="selectedTrace.steps || []" border class="trace-step-table">
          <ElTableColumn prop="stepNo" label="#" width="56" />
          <ElTableColumn prop="collaboratorAgentId" label="协作 Agent" width="120" />
          <ElTableColumn prop="task" label="任务" min-width="160" show-overflow-tooltip />
          <ElTableColumn prop="reason" label="原因" min-width="160" show-overflow-tooltip />
          <ElTableColumn prop="status" label="状态" width="90" />
          <ElTableColumn label="耗时" width="100">
            <template #default="{ row }">{{ formatMs(row.durationMs) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="childRuntimeRequestId" label="子 Request" min-width="180" show-overflow-tooltip />
          <ElTableColumn label="ReAct" width="100">
            <template #default="{ row }">{{ formatMs(row.reactMs) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="toolCount" label="工具" width="70" />
          <ElTableColumn prop="toolFailCount" label="失败" width="70" />
          <ElTableColumn prop="errorMessage" label="错误信息" min-width="180" show-overflow-tooltip />
        </ElTable>
      </div>
      <ElEmpty v-else description="暂无编排调用详情" />
    </ElDrawer>

    <ElDialog v-model="collaboratorDialogVisible" title="协作 Agent" width="760px">
      <ElForm label-width="110px">
        <ElFormItem label="协作 Agent">
          <ElSelect
            v-model="collaboratorForm.collaboratorAgentId"
            class="agent-select"
            filterable
            clearable
            :loading="agentOptionsLoading"
            placeholder="请选择协作 Agent"
            no-data-text="暂无可选协作 Agent"
            @change="handleCollaboratorAgentChange"
          >
            <ElOption
              v-for="agent in agentOptions"
              :key="agent.id"
              :label="formatAgentOptionLabel(agent)"
              :value="agent.id"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="协作角色">
          <ElInput v-model="collaboratorForm.roleName" placeholder="如：订单查询专家" />
        </ElFormItem>
        <ElFormItem label="能力描述">
          <ElInput v-model="collaboratorForm.capabilityDescription" type="textarea" :rows="3" />
        </ElFormItem>
        <ElDivider content-position="left">路由规则</ElDivider>
        <RouteRuleEditor
          :model-value="routingRulesSource(collaboratorForm.routingRules)"
          context="collaborator"
          @update:model-value="updateCollaboratorRoutingRules"
        />
        <ElFormItem label="优先级">
          <ElInputNumber v-model="collaboratorForm.priority" :min="0" />
          <span class="form-unit">数值越大越优先，仅在相关性同分时生效</span>
        </ElFormItem>
        <ElFormItem label="启用">
          <ElSwitch v-model="collaboratorForm.enabled" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="collaboratorDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="collaboratorSaving" :disabled="!routingRulesValid" @click="saveCollaborator">
          保存
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script lang="ts">
import { computed, defineComponent, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Check, Connection, Delete, Edit, Plus } from '@element-plus/icons-vue';
import { AGENT_TYPE, formatAgentType, normalizeAgentType } from '@/views/ai-agent/constants/agentTypes';
import agentService, { type Agent } from '@/views/ai-agent/services/agent';
import type {
  AgentCollaborator,
  AgentOrchestrationPolicy,
  OrchestrationClarificationConfig,
  OrchestrationRun,
  OrchestrationTrace
} from '@/views/ai-agent/services/agentOrchestration';
import orchestrationService from '@/views/ai-agent/services/agentOrchestration';
import { canShowDiagnosticButton } from '@/views/ai-agent/services/permission';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import RoutePreviewPanel from '@/views/ai-agent/components/routing/RoutePreviewPanel.vue';
import RouteRuleEditor from '@/views/ai-agent/components/routing/RouteRuleEditor.vue';
import {
  emptyRouteRules,
  parseRouteRules,
  routeRulesPayloadForSave,
  ROUTE_RULE_FIELDS,
  type RouteRules
} from '@/views/ai-agent/utils/routeRules';

type AgentOption = Agent & {
  id: string;
};

type EditableAgentCollaborator = Omit<AgentCollaborator, 'routingRules'> & {
  routingRules?: unknown;
};

const toStringAgentId = (id: Agent['id']): string | undefined => {
  if (id === undefined || id === null || id === '') {
    return undefined;
  }
  return String(id);
};

const toAgentOption = (agent?: Agent | null): AgentOption | null => {
  if (!agent) {
    return null;
  }
  const id = toStringAgentId(agent.id);
  return id === undefined ? null : { ...agent, id };
};

const trimText = (value?: string) => value?.trim() || '';

const routingRulesSource = (value: unknown) => (value === undefined ? emptyRouteRules() : value);

const emptyClarificationConfig = (): OrchestrationClarificationConfig => ({
  timeAliases: [],
  explicitMetricAliases: [],
  ambiguousMetricAliases: [],
  orderingMetricAliases: []
});

type ClarificationConfigKey = keyof OrchestrationClarificationConfig;

const clarificationGroups: Array<{ key: ClarificationConfigKey; label: string }> = [
  { key: 'timeAliases', label: '时间别名' },
  { key: 'explicitMetricAliases', label: '明确指标' },
  { key: 'ambiguousMetricAliases', label: '需要澄清的指标' },
  { key: 'orderingMetricAliases', label: '可排序指标' }
];

const clarificationConflictKeys: Partial<Record<ClarificationConfigKey, ClarificationConfigKey>> = {
  explicitMetricAliases: 'ambiguousMetricAliases',
  ambiguousMetricAliases: 'explicitMetricAliases'
};

const clarificationAliasMaxCount = 100;
const clarificationAliasMaxLength = 64;

const normalizeAlias = (value: string) => value.replace(/\u3000/g, ' ').trim();

const normalizeAliases = (values?: string[]) => {
  const distinct = new Map<string, string>();
  (values || []).forEach(value => {
    const normalized = normalizeAlias(value);
    if (normalized) {
      distinct.set(normalized.toLowerCase(), normalized);
    }
  });
  return Array.from(distinct.values());
};

const normalizeClarificationConfig = (
  value?: Partial<OrchestrationClarificationConfig>
): OrchestrationClarificationConfig => ({
  timeAliases: normalizeAliases(value?.timeAliases),
  explicitMetricAliases: normalizeAliases(value?.explicitMetricAliases),
  ambiguousMetricAliases: normalizeAliases(value?.ambiguousMetricAliases),
  orderingMetricAliases: normalizeAliases(value?.orderingMetricAliases)
});

export default defineComponent({
  name: 'AgentOrchestrationConfig',
  components: { RoutePreviewPanel, RouteRuleEditor },
  props: {
    agentId: {
      type: String,
      required: true
    }
  },
  setup(props) {
    const activeTab = ref('policy');
    const policySaving = ref(false);
    const collaboratorSaving = ref(false);
    const collaboratorDialogVisible = ref(false);
    const collaborators = ref<AgentCollaborator[]>([]);
    const collaboratorsLoaded = ref(false);
    const agentOptions = ref<AgentOption[]>([]);
    const agentOptionsLoading = ref(false);
    const agentOptionsLoaded = ref(false);
    const runs = ref<OrchestrationRun[]>([]);
    const runsLoaded = ref(false);
    const selectedTrace = ref<OrchestrationTrace>({});
    const traceDrawerVisible = ref(false);
    const traceLoading = ref(false);
    const traceError = ref('');
    const canViewCallChain = ref(false);
    const policy = reactive<AgentOrchestrationPolicy & { clarificationConfig: OrchestrationClarificationConfig }>({
      clarificationConfig: emptyClarificationConfig()
    });
    const clarificationDrafts = reactive<Record<ClarificationConfigKey, string>>({
      timeAliases: '',
      explicitMetricAliases: '',
      ambiguousMetricAliases: '',
      orderingMetricAliases: ''
    });

    const collaboratorForm = reactive<EditableAgentCollaborator>({
      routingRules: emptyRouteRules(),
      priority: 100,
      enabled: true
    });
    const collaboratorRoutingRulesDirty = ref(false);
    const collaboratorRoutingRulesParseResult = computed(() =>
      parseRouteRules(routingRulesSource(collaboratorForm.routingRules))
    );
    const routingRulesValid = computed(() => {
      const parsed = collaboratorRoutingRulesParseResult.value;
      return parsed.status === 'valid' && !parsed.rules.allowFlowAutoSelect;
    });
    const updateCollaboratorRoutingRules = (rules: RouteRules) => {
      collaboratorForm.routingRules = rules;
      collaboratorRoutingRulesDirty.value = true;
    };

    const addClarificationAlias = (key: ClarificationConfigKey) => {
      const alias = normalizeAlias(clarificationDrafts[key]);
      if (!alias) {
        clarificationDrafts[key] = '';
        return true;
      }
      if (alias.length > clarificationAliasMaxLength) {
        ElMessage.warning(`单个词汇不能超过 ${clarificationAliasMaxLength} 个字符`);
        return false;
      }
      const aliases = policy.clarificationConfig[key];
      if (aliases.length >= clarificationAliasMaxCount) {
        ElMessage.warning(`每组最多配置 ${clarificationAliasMaxCount} 个词汇`);
        return false;
      }
      const normalizedAlias = alias.toLowerCase();
      if (aliases.some(item => item.toLowerCase() === normalizedAlias)) {
        ElMessage.warning('词汇已存在');
        clarificationDrafts[key] = '';
        return true;
      }
      const conflictKey = clarificationConflictKeys[key];
      if (conflictKey && policy.clarificationConfig[conflictKey].some(item => item.toLowerCase() === normalizedAlias)) {
        ElMessage.warning('同一词汇不能同时属于明确指标和需要澄清的指标');
        return false;
      }
      aliases.push(alias);
      clarificationDrafts[key] = '';
      return true;
    };

    const removeClarificationAlias = (key: ClarificationConfigKey, index: number) => {
      policy.clarificationConfig[key].splice(index, 1);
    };

    const validateClarificationConfig = () => {
      for (const group of clarificationGroups) {
        const aliases = policy.clarificationConfig[group.key];
        if (aliases.length > clarificationAliasMaxCount) {
          return `${group.label}最多配置 ${clarificationAliasMaxCount} 个词汇`;
        }
        if (aliases.some(alias => alias.length > clarificationAliasMaxLength)) {
          return `${group.label}中的单个词汇不能超过 ${clarificationAliasMaxLength} 个字符`;
        }
      }
      const ambiguousMetrics = new Set(
        policy.clarificationConfig.ambiguousMetricAliases.map(alias => alias.toLowerCase())
      );
      if (policy.clarificationConfig.explicitMetricAliases.some(alias => ambiguousMetrics.has(alias.toLowerCase()))) {
        return '同一词汇不能同时属于明确指标和需要澄清的指标';
      }
      return '';
    };

    const shouldShowAgentOption = (agent: AgentOption) =>
      agent.id !== String(props.agentId) && normalizeAgentType(agent.agentType) !== AGENT_TYPE.ORCHESTRATOR;

    const appendAgentOption = (agent?: Agent | null) => {
      const option = toAgentOption(agent);
      if (!option) {
        return;
      }
      if (!agentOptions.value.some(item => item.id === option.id)) {
        agentOptions.value.push(option);
      }
    };

    const loadAgentOptions = async (selectedAgent?: Agent | null) => {
      appendAgentOption(selectedAgent);
      if (agentOptionsLoaded.value || agentOptionsLoading.value) {
        return;
      }

      agentOptionsLoading.value = true;
      try {
        const agents = await agentService.list();
        agentOptions.value = agents
          .map(toAgentOption)
          .filter((agent): agent is AgentOption => Boolean(agent))
          .filter(shouldShowAgentOption);
        appendAgentOption(selectedAgent);
        agentOptionsLoaded.value = true;
      } catch {
        ElMessage.error('获取协作 Agent 列表失败');
      } finally {
        agentOptionsLoading.value = false;
      }
    };

    const formatAgentOptionLabel = (agent: AgentOption) => agent.name || `Agent ${agent.id}`;

    const routingRuleSummary = (value?: AgentCollaborator['routingRules']) => {
      const parsed = parseRouteRules(routingRulesSource(value));
      if (parsed.status === 'invalid') {
        return '规则配置异常';
      }
      const rules = parsed.rules;
      const labels = {
        exact: '精确',
        phrases: '短语',
        aliases: '别名',
        positiveExamples: '正例',
        positivePatterns: '通配正向',
        negativeExamples: '软反例',
        hardExcludes: '硬排除',
        hardExcludePatterns: '通配硬排除'
      };
      const parts = ROUTE_RULE_FIELDS
        .map(key => ({ key, count: rules[key].length }))
        .filter(item => item.count > 0)
        .map(item => `${labels[item.key]} ${item.count}`);
      return parts.join(' · ') || '未配置';
    };

    const formatMs = (value?: number | null) => {
      if (typeof value !== 'number' || !Number.isFinite(value)) {
        return '-';
      }
      return `${value} ms`;
    };

    const handleCollaboratorAgentChange = (agentId?: string) => {
      const selectedAgent = agentOptions.value.find(agent => agent.id === String(agentId));
      if (!selectedAgent) {
        return;
      }
      collaboratorForm.capabilityDescription = trimText(selectedAgent.description);
    };

    const loadPolicy = async () => {
      const loadedPolicy = await orchestrationService.getPolicy(props.agentId);
      canViewCallChain.value = canShowDiagnosticButton('dataagent:call-chain:view');
      Object.assign(policy, loadedPolicy, {
        clarificationConfig: normalizeClarificationConfig(loadedPolicy.clarificationConfig)
      });
    };

    const loadCollaborators = async () => {
      collaborators.value = await orchestrationService.listCollaborators(props.agentId);
      collaboratorsLoaded.value = true;
    };

    const loadRuns = async () => {
      runs.value = await orchestrationService.listRuns(props.agentId);
      runsLoaded.value = true;
    };

    const ensureActiveTabLoaded = async () => {
      if (activeTab.value === 'collaborators' && !collaboratorsLoaded.value) {
        await loadCollaborators();
      }
      if (activeTab.value === 'runs' && !runsLoaded.value) {
        await loadRuns();
      }
    };

    const savePolicy = async () => {
      for (const group of clarificationGroups) {
        if (!addClarificationAlias(group.key)) {
          return;
        }
      }
      policy.clarificationConfig = normalizeClarificationConfig(policy.clarificationConfig);
      const clarificationError = validateClarificationConfig();
      if (clarificationError) {
        ElMessage.warning(clarificationError);
        return;
      }
      policySaving.value = true;
      try {
        const payload: AgentOrchestrationPolicy = {
          ...policy,
          clarificationConfig: normalizeClarificationConfig(policy.clarificationConfig)
        };
        const updatedPolicy = await orchestrationService.updatePolicy(props.agentId, payload);
        Object.assign(policy, updatedPolicy, {
          clarificationConfig: normalizeClarificationConfig(updatedPolicy.clarificationConfig)
        });
        ElMessage.success('编排规则已保存');
      } finally {
        policySaving.value = false;
      }
    };

    const openCollaboratorDialog = (row?: AgentCollaborator) => {
      const routingRules = routingRulesSource(row?.routingRules);
      collaboratorRoutingRulesDirty.value = false;
      Object.assign(collaboratorForm, {
        id: row?.id,
        collaboratorAgentId: row?.collaboratorAgentId,
        roleName: row?.roleName || '',
        capabilityDescription: row?.capabilityDescription || '',
        routingRules,
        priority: row?.priority ?? 100,
        enabled: row?.enabled ?? true
      });
      loadAgentOptions(row?.collaboratorDataAgent);
      collaboratorDialogVisible.value = true;
    };

    const saveCollaborator = async () => {
      if (!collaboratorForm.collaboratorAgentId) {
        ElMessage.warning('请选择协作 Agent');
        return;
      }
      const parsedRoutingRules = collaboratorRoutingRulesParseResult.value;
      if (parsedRoutingRules.status === 'invalid' || parsedRoutingRules.rules.allowFlowAutoSelect) {
        ElMessage.warning('请修正协作者路由规则');
        return;
      }
      collaboratorSaving.value = true;
      try {
        const payload: AgentCollaborator = {
          ...collaboratorForm,
          routingRules: routeRulesPayloadForSave(parsedRoutingRules, collaboratorRoutingRulesDirty.value)
        };
        if (collaboratorForm.id) {
          await orchestrationService.updateCollaborator(props.agentId, collaboratorForm.id, payload);
        } else {
          await orchestrationService.createCollaborator(props.agentId, payload);
        }
        collaboratorDialogVisible.value = false;
        await loadCollaborators();
        ElMessage.success('协作 Agent 已保存');
      } catch (error) {
        ElMessage.error(extractApiErrorMessage(error, '协作 Agent 保存失败，原配置未变更'));
      } finally {
        collaboratorSaving.value = false;
      }
    };

    const deleteCollaborator = async (row: AgentCollaborator) => {
      if (!row.id) return;
      await ElMessageBox.confirm('确认删除该协作 Agent 配置？', '删除确认', { type: 'warning' });
      await orchestrationService.deleteCollaborator(props.agentId, row.id);
      await loadCollaborators();
    };

    const openRunTrace = async (row: OrchestrationRun) => {
      if (!canViewCallChain.value) {
        ElMessage.warning('需要调用链路查看权限');
        return;
      }
      if (!row.id) return;
      traceDrawerVisible.value = true;
      traceLoading.value = true;
      traceError.value = '';
      try {
        selectedTrace.value = await orchestrationService.getRunTrace(props.agentId, row.id);
      } catch (error: any) {
        selectedTrace.value = {};
        traceError.value = error?.response?.status === 403 ? '需要调用链路查看权限' : '加载编排调用详情失败';
      } finally {
        traceLoading.value = false;
      }
    };

    watch(activeTab, () => {
      ensureActiveTabLoaded();
    });

    onMounted(async () => {
      await loadPolicy();
    });

    return {
      activeTab,
      addClarificationAlias,
      Check,
      clarificationAliasMaxLength,
      clarificationDrafts,
      clarificationGroups,
      Connection,
      collaboratorDialogVisible,
      collaboratorForm,
      collaboratorSaving,
      collaborators,
      agentOptions,
      agentOptionsLoading,
      canViewCallChain,
      deleteCollaborator,
      Delete,
      Edit,
      formatMs,
      formatAgentOptionLabel,
      formatAgentType,
      handleCollaboratorAgentChange,
      openRunTrace,
      openCollaboratorDialog,
      policy,
      Plus,
      removeClarificationAlias,
      routingRulesValid,
      routingRuleSummary,
      routingRulesSource,
      policySaving,
      runs,
      saveCollaborator,
      savePolicy,
      selectedTrace,
      traceDrawerVisible,
      traceError,
      traceLoading,
      updateCollaboratorRoutingRules
    };
  }
});
</script>

<style scoped>
.panel-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
  border-bottom: 1px solid #edf0f5;
  padding-bottom: 18px;
}

.panel-header h2 {
  margin: 0 0 6px;
  font-size: 20px;
  color: #1f2937;
}

.panel-header p,
.muted {
  margin: 0;
  color: #667085;
  font-size: 13px;
}

.policy-form {
  max-width: 760px;
}

.toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.strong {
  color: #1f2937;
  font-weight: 600;
}

.form-unit {
  margin-left: 8px;
  color: #667085;
}

.agent-select {
  width: 100%;
}

.dictionary-editor {
  width: 100%;
}

.dictionary-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.run-detail {
  margin-top: 16px;
}

.run-detail h3 {
  margin: 16px 0 10px;
  font-size: 15px;
  color: #1f2937;
}

.trace-detail-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: 10px;
}

.metric-card {
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 10px 12px;
  background: #fff;
}

.metric-card span {
  display: block;
  margin-bottom: 6px;
  color: #667085;
  font-size: 12px;
}

.metric-card strong {
  color: #1f2937;
  font-size: 15px;
}

.trace-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: #667085;
  font-size: 12px;
}

.trace-meta span {
  border: 1px solid #e5e7eb;
  border-radius: 999px;
  padding: 4px 8px;
  background: #f8fafc;
}

.trace-step-table {
  width: 100%;
}
</style>
