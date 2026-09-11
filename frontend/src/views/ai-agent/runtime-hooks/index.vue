<template>
  <BaseLayout class="runtime-hooks-shell">
    <main class="runtime-hooks-page">
      <ElCard>
        <header class="page-toolbar">
          <div>
            <h1>事件触发中心</h1>
            <p>{{ pageDescription }}</p>
          </div>
          <div class="toolbar-actions">
            <ElButton :loading="loading || logLoading || optionLoading" @click="reload">刷新</ElButton>
            <ElButton type="primary" @click="openEditor()">新建自动化</ElButton>
          </div>
        </header>
      </ElCard>
      <ElCard class="runtime-hooks-content-card">
        <ElTabs v-model="activeTab">
          <ElTabPane label="自动化规则" name="hooks">
            <div class="runtime-tab-panel">
              <section class="runtime-filter-panel">
                <ElForm :model="filters" label-width="70px">
                  <ElRow :gutter="24">
                    <ElCol :span="6">
                      <ElFormItem label="事件类型">
                        <ElSelect v-model="filters.eventType" clearable filterable placeholder="事件类型">
                          <ElOption
                            v-for="event in eventOptions"
                            :key="event.value"
                            :label="event.label"
                            :value="event.value"
                          />
                        </ElSelect>
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="6">
                      <ElFormItem label="Agent">
                        <ElSelect
                          v-model="filters.agentId"
                          clearable
                          filterable
                          placeholder="Agent"
                          @visible-change="handleAgentOptionsVisible"
                        >
                          <ElOption
                            v-for="agent in agents"
                            :key="agent.id || ''"
                            :label="agentLabel(agent)"
                            :value="agent.id || ''"
                          />
                        </ElSelect>
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="6">
                      <ElFormItem label="Skill">
                        <ElSelect
                          v-model="filters.skillCode"
                          clearable
                          filterable
                          placeholder="Skill"
                          @visible-change="handleSkillOptionsVisible"
                        >
                          <ElOption
                            v-for="skill in skills"
                            :key="skill.skillCode"
                            :label="skillLabel(skill)"
                            :value="skill.skillCode"
                          />
                        </ElSelect>
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="6">
                      <ElFormItem label="触发">
                        <ElSelect
                          v-model="filters.resourceKey"
                          clearable
                          filterable
                          placeholder="触发工具/资源"
                          @visible-change="handleResourceOptionsVisible"
                        >
                          <ElOption
                            v-for="resource in allResourceOptions"
                            :key="resource.resourceKey"
                            :label="resource.label"
                            :value="resource.resourceKey"
                          />
                        </ElSelect>
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="24">
                      <div class="flex justify-end">
                        <ElButton type="primary" @click="handleHookSearch">
                          <ElIcon><Search /></ElIcon>
                          查询
                        </ElButton>
                        <ElButton @click="resetHookFilters">
                          <ElIcon><Refresh /></ElIcon>
                          重置
                        </ElButton>
                      </div>
                    </ElCol>
                  </ElRow>
                </ElForm>
              </section>
              <div class="runtime-table-wrap">
                <ElTable v-loading="loading" :data="hooks" border stripe row-key="hookCode" height="100%">
                  <ElTableColumn prop="hookCode" label="规则编码" min-width="190" show-overflow-tooltip />
                  <ElTableColumn prop="hookName" label="名称" min-width="150" show-overflow-tooltip />
                  <ElTableColumn prop="eventType" label="事件" min-width="210" show-overflow-tooltip />
                  <ElTableColumn label="绑定范围" min-width="280" show-overflow-tooltip>
                    <template #default="{ row }">{{ scopeText(row) }}</template>
                  </ElTableColumn>
                  <ElTableColumn label="动作" min-width="190" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.actionType }} / {{ toolKeyText(row) }}</template>
                  </ElTableColumn>
                  <ElTableColumn label="策略" width="150">
                    <template #default="{ row }">
                      <ElTag size="small" :type="row.asyncEnabled === false ? 'info' : 'success'">异步</ElTag>
                      <ElTag size="small" :type="row.continueOnError === false ? 'warning' : 'info'">失败不阻塞</ElTag>
                    </template>
                  </ElTableColumn>
                  <ElTableColumn prop="status" label="状态" width="100" />
                  <ElTableColumn label="操作" width="150" fixed="right">
                    <template #default="{ row }">
                      <ElButton link type="primary" @click="openEditor(row)">编辑</ElButton>
                      <ElButton link type="danger" @click="removeHook(row)">删除</ElButton>
                    </template>
                  </ElTableColumn>
                </ElTable>
              </div>
              <div class="tab-pagination">
                <ElPagination
                  v-model:current-page="hookPage.current"
                  v-model:page-size="hookPage.size"
                  background
                  layout="total, sizes, prev, pager, next, jumper"
                  :page-sizes="[10, 20, 50, 100]"
                  :total="hookPage.total"
                  @size-change="handleHookSizeChange"
                  @current-change="loadHooks"
                />
              </div>
            </div>
          </ElTabPane>

          <ElTabPane label="执行日志" name="logs">
            <div class="runtime-tab-panel">
              <section class="runtime-filter-panel">
                <ElForm :model="logFilters" label-width="70px">
                  <ElRow :gutter="24">
                    <ElCol :span="6">
                      <ElFormItem label="规则编码">
                        <ElInput v-model="logFilters.hookCode" clearable placeholder="规则编码" />
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="6">
                      <ElFormItem label="事件类型">
                        <ElSelect v-model="logFilters.eventType" clearable filterable placeholder="事件类型">
                          <ElOption
                            v-for="event in eventOptions"
                            :key="event.value"
                            :label="event.label"
                            :value="event.value"
                          />
                        </ElSelect>
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="6">
                      <ElFormItem label="状态">
                        <ElInput v-model="logFilters.status" clearable placeholder="状态" />
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="6">
                      <ElFormItem label="Agent">
                        <ElSelect
                          v-model="logFilters.agentId"
                          clearable
                          filterable
                          placeholder="Agent"
                          @visible-change="handleAgentOptionsVisible"
                        >
                          <ElOption
                            v-for="agent in agents"
                            :key="agent.id || ''"
                            :label="agentLabel(agent)"
                            :value="agent.id || ''"
                          />
                        </ElSelect>
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="6">
                      <ElFormItem label="Skill">
                        <ElSelect
                          v-model="logFilters.skillCode"
                          clearable
                          filterable
                          placeholder="Skill"
                          @visible-change="handleSkillOptionsVisible"
                        >
                          <ElOption
                            v-for="skill in skills"
                            :key="skill.skillCode"
                            :label="skillLabel(skill)"
                            :value="skill.skillCode"
                          />
                        </ElSelect>
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="6">
                      <ElFormItem label="触发">
                        <ElInput v-model="logFilters.resourceKey" clearable placeholder="触发工具/资源" />
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="6">
                      <ElFormItem label="RequestId">
                        <ElInput v-model="logFilters.runtimeRequestId" clearable placeholder="runtimeRequestId" />
                      </ElFormItem>
                    </ElCol>
                    <ElCol :span="6">
                      <ElButton type="primary" :loading="logLoading" @click="handleLogSearch">
                        <ElIcon><Search /></ElIcon>
                        查询
                      </ElButton>
                      <ElButton @click="resetLogFilters">
                        <ElIcon><Refresh /></ElIcon>
                        重置
                      </ElButton>
                    </ElCol>
                  </ElRow>
                </ElForm>
              </section>
              <div class="runtime-table-wrap">
                <ElTable v-loading="logLoading" :data="logs" border stripe row-key="id" height="100%">
                  <ElTableColumn prop="hookCode" label="Hook" min-width="180" show-overflow-tooltip />
                  <ElTableColumn prop="eventType" label="事件" min-width="190" show-overflow-tooltip />
                  <ElTableColumn prop="status" label="状态" width="100" />
                  <ElTableColumn prop="toolKey" label="Tool" min-width="180" show-overflow-tooltip />
                  <ElTableColumn prop="resourceKey" label="触发工具/资源" min-width="190" show-overflow-tooltip />
                  <ElTableColumn
                    prop="runtimeRequestId"
                    label="runtimeRequestId"
                    min-width="190"
                    show-overflow-tooltip
                  />
                  <ElTableColumn prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip />
                  <ElTableColumn prop="elapsedMs" label="耗时" width="90" />
                  <ElTableColumn prop="createTime" label="时间" min-width="170" show-overflow-tooltip />
                </ElTable>
              </div>
              <div class="tab-pagination">
                <ElPagination
                  v-model:current-page="logPage.current"
                  v-model:page-size="logPage.size"
                  background
                  layout="total, sizes, prev, pager, next, jumper"
                  :page-sizes="[10, 20, 50, 100]"
                  :total="logPage.total"
                  @size-change="handleLogSizeChange"
                  @current-change="loadLogs"
                />
              </div>
            </div>
          </ElTabPane>
        </ElTabs>
      </ElCard>
    </main>

    <ElDrawer v-model="editorVisible" :title="editingId ? '编辑自动化' : '新建自动化'" size="min(920px, 92vw)">
      <ElForm :model="form" label-position="top">
        <div class="grid-two">
          <ElFormItem label="规则编码">
            <ElInput v-model="form.hookCode" placeholder="customer_order_notify" />
          </ElFormItem>
          <ElFormItem label="名称"><ElInput v-model="form.hookName" /></ElFormItem>
        </div>

        <div class="grid-two">
          <ElFormItem label="事件类型">
            <ElSelect v-model="form.eventType" filterable class="full-width">
              <ElOption v-for="event in eventOptions" :key="event.value" :label="event.label" :value="event.value" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="form.status" class="full-width">
              <ElOption label="启用" value="enabled" />
              <ElOption label="停用" value="disabled" />
            </ElSelect>
          </ElFormItem>
        </div>

        <section class="form-section">
          <h3>绑定范围</h3>
          <div class="grid-two">
            <ElFormItem label="绑定类型">
              <ElSelect v-model="scopeType" class="full-width" @change="handleScopeTypeChange">
                <ElOption label="全局规则" value="GLOBAL" />
                <ElOption label="Agent" value="AGENT" />
                <ElOption label="Skill" value="SKILL" />
                <ElOption label="指定工具/资源" value="RESOURCE" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem v-if="scopeType === 'AGENT'" label="Agent">
              <ElSelect
                v-model="form.agentId"
                clearable
                filterable
                class="full-width"
                placeholder="选择 Agent"
                @visible-change="handleAgentOptionsVisible"
              >
                <ElOption
                  v-for="agent in agents"
                  :key="agent.id || ''"
                  :label="agentLabel(agent)"
                  :value="agent.id || ''"
                />
              </ElSelect>
            </ElFormItem>
            <ElFormItem v-if="scopeType === 'SKILL'" label="Skill">
              <ElSelect
                v-model="form.skillCode"
                clearable
                filterable
                class="full-width"
                placeholder="选择 Skill"
                @visible-change="handleSkillOptionsVisible"
              >
                <ElOption
                  v-for="skill in skills"
                  :key="skill.skillCode"
                  :label="skillLabel(skill)"
                  :value="skill.skillCode"
                />
              </ElSelect>
            </ElFormItem>
            <ElFormItem v-if="scopeType === 'SKILL'" label="Skill 版本">
              <ElInput v-model="form.skillVersionId" placeholder="固定发布版本 ID" />
            </ElFormItem>
          </div>
          <ElFormItem v-if="scopeType === 'SKILL' || scopeType === 'RESOURCE'" label="触发工具/资源">
            <ElSelect
              v-model="form.resourceKey"
              clearable
              filterable
              class="full-width"
              placeholder="留空表示整个范围"
              @visible-change="handleResourceOptionsVisible"
            >
              <ElOption
                v-for="resource in scopedResourceOptions"
                :key="resource.resourceKey"
                :label="resource.label"
                :value="resource.resourceKey"
              />
            </ElSelect>
          </ElFormItem>
          <ElAlert class="form-tip" type="info" :closable="false" show-icon :title="scopeTip" />
        </section>

        <section class="form-section">
          <h3>执行动作</h3>
          <div class="action-block">
            <h4>动作设置</h4>
            <div class="grid-two">
              <ElFormItem label="动作类型">
                <ElSelect v-model="actionMode" class="full-width">
                  <ElOption label="发送消息通知" value="NOTIFICATION" />
                  <ElOption label="高级工具调用" value="ADVANCED" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="执行策略">
                <div class="switches">
                  <ElSwitch v-model="form.asyncEnabled" active-text="异步" />
                  <ElSwitch v-model="form.continueOnError" active-text="失败不阻塞" />
                </div>
              </ElFormItem>
            </div>
          </div>

          <template v-if="actionMode === 'NOTIFICATION'">
            <div class="action-block">
              <h4>通知配置</h4>
              <div class="grid-two">
                <ElFormItem label="Hook 动作工具">
                  <ElInput :model-value="NOTIFICATION_TOOL_KEY" disabled />
                </ElFormItem>
                <ElFormItem label="已确认发送">
                  <ElSwitch v-model="notificationForm.confirmed" active-text="是" inactive-text="否" />
                </ElFormItem>
                <ElFormItem label="通知目标">
                  <ElSelect
                    v-model="notificationForm.targetAlias"
                    clearable
                    filterable
                    class="full-width"
                    @visible-change="handleNotificationOptionsVisible"
                  >
                    <ElOption
                      v-for="target in notificationTargets"
                      :key="target.targetAlias || ''"
                      :label="targetLabel(target)"
                      :value="target.targetAlias || ''"
                    />
                  </ElSelect>
                </ElFormItem>
                <ElFormItem label="通知模板">
                  <ElSelect
                    v-model="notificationForm.templateCode"
                    clearable
                    filterable
                    class="full-width"
                    @visible-change="handleNotificationOptionsVisible"
                  >
                    <ElOption
                      v-for="template in notificationTemplates"
                      :key="template.templateCode || ''"
                      :label="templateLabel(template)"
                      :value="template.templateCode || ''"
                    />
                  </ElSelect>
                </ElFormItem>
              </div>
              <div v-if="selectedNotificationTemplate" class="template-summary">
                <div class="summary-heading">
                  <strong>
                    {{ selectedNotificationTemplate.templateName || selectedNotificationTemplate.templateCode }}
                  </strong>
                  <ElTag size="small" effect="plain">{{ selectedNotificationTemplate.templateCode }}</ElTag>
                </div>
                <dl class="summary-list">
                  <template v-if="selectedNotificationTemplate.titleTemplate">
                    <dt>标题模板</dt>
                    <dd>{{ selectedNotificationTemplate.titleTemplate }}</dd>
                  </template>
                  <dt>内容模板</dt>
                  <dd>{{ selectedNotificationTemplate.contentTemplate || '-' }}</dd>
                  <dt>必填变量</dt>
                  <dd>
                    <template v-if="templateRequiredFields.length">
                      <ElTag
                        v-for="field in templateRequiredFields"
                        :key="field"
                        size="small"
                        :type="missingRequiredVariables.includes(field) ? 'danger' : 'success'"
                        effect="plain"
                      >
                        {{ field }}
                      </ElTag>
                    </template>
                    <span v-else>-</span>
                  </dd>
                </dl>
                <p class="summary-note">完整消息内容在通知模板页面维护；这里选择模板并配置变量来源。</p>
              </div>
            </div>

            <div class="action-block">
              <h4>模板变量映射</h4>
              <p class="field-help">
                这里不是最终消息正文，而是把运行时事件数据填入通知模板。可用示例：output.message、output.data.xxx、input.parameters.xxx。
              </p>
              <div v-if="templateVariableRows.length" class="variable-list">
                <div v-for="row in templateVariableRows" :key="row.name" class="variable-row">
                  <span class="variable-name">{{ row.name }}</span>
                  <ElTag v-if="row.required" size="small" type="danger" effect="plain">必填</ElTag>
                  <span v-if="row.description" class="variable-desc">{{ row.description }}</span>
                  <code class="variable-value">{{ row.value || '未配置' }}</code>
                </div>
              </div>
              <ElFormItem label="变量 JSON" class="variable-editor-item">
                <JsonObjectEditor
                  v-model="notificationForm.variables"
                  :rows="14"
                  @validity-change="jsonValidity.notificationVariables = $event"
                />
              </ElFormItem>
              <ElAlert
                v-if="missingRequiredVariables.length"
                class="form-tip"
                type="warning"
                :closable="false"
                show-icon
                :title="`模板必填变量未配置映射：${missingRequiredVariables.join('、')}`"
              />
              <ElAlert
                v-if="notificationWarnings.length"
                class="form-tip"
                type="warning"
                :closable="false"
                show-icon
                :title="notificationWarnings.join('；')"
              />
            </div>
          </template>

          <ElCollapse v-model="advancedPanels" class="advanced-scope">
            <ElCollapseItem title="高级 JSON 配置" name="actionConfig">
              <JsonObjectEditor
                v-if="actionMode === 'ADVANCED'"
                v-model="form.actionConfig"
                :rows="12"
                @validity-change="jsonValidity.actionConfig = $event"
              />
              <pre v-else class="config-preview">{{ notificationActionPreview }}</pre>
            </ElCollapseItem>
          </ElCollapse>
        </section>
      </ElForm>
      <template #footer>
        <ElButton @click="editorVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" :disabled="!activeJsonValid" @click="saveHook">保存</ElButton>
      </template>
    </ElDrawer>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh, Search } from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import agentService, { type Agent } from '@/views/ai-agent/services/agent';
import toolService, { type ToolResource } from '@/views/ai-agent/services/tool';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import notificationService, {
  type NotificationAuthorization,
  type NotificationTarget,
  type NotificationTemplate
} from '@/views/ai-agent/services/notification';
import platformService, { type RuntimeHook, type RuntimeHookLog } from '@/views/ai-agent/services/platform';
import skillService, { type AgentSkillToolRef, type SkillCatalogItem } from '@/views/ai-agent/services/skill';

type ScopeType = 'GLOBAL' | 'AGENT' | 'SKILL' | 'RESOURCE';
type ActionMode = 'NOTIFICATION' | 'ADVANCED';

type RuntimeHookForm = Omit<
  RuntimeHook,
  'agentId' | 'skillCode' | 'skillVersionId' | 'resourceKey' | 'actionConfig'
> & {
  agentId?: string;
  skillCode?: string;
  skillVersionId?: string;
  resourceKey?: string;
  actionConfig: Record<string, unknown>;
};

interface ResourceOption {
  resourceKey: string;
  label: string;
}

interface TemplateVariableRow {
  name: string;
  required: boolean;
  description: string;
  value: string;
}

const eventOptions = [
  { label: 'Skill 成功 / AFTER_SKILL_SUCCESS', value: 'AFTER_SKILL_SUCCESS' },
  { label: 'Skill 失败 / AFTER_SKILL_FAILED', value: 'AFTER_SKILL_FAILED' },
  { label: '资源成功 / AFTER_RESOURCE_SUCCESS', value: 'AFTER_RESOURCE_SUCCESS' },
  { label: 'Agent 成功 / AFTER_AGENT_SUCCESS', value: 'AFTER_AGENT_SUCCESS' },
  { label: 'Agent 失败 / AFTER_AGENT_FAILED', value: 'AFTER_AGENT_FAILED' },
  { label: '工具成功 / AFTER_TOOL_SUCCESS', value: 'AFTER_TOOL_SUCCESS' },
  { label: '工具失败 / AFTER_TOOL_FAILED', value: 'AFTER_TOOL_FAILED' }
];

const NOTIFICATION_TOOL_KEY = 'notification.send';
const placeholder = (path: string) => `\${${path}}`;

const route = useRoute();
const activeTab = ref('hooks');
const loading = ref(false);
const logLoading = ref(false);
const optionLoading = ref(false);
const saving = ref(false);
const editorVisible = ref(false);
const editingId = ref<string>('');
const scopeType = ref<ScopeType>('GLOBAL');
const actionMode = ref<ActionMode>('NOTIFICATION');
const jsonValidity = reactive({ notificationVariables: true, actionConfig: true });
const activeJsonValid = computed(() =>
  actionMode.value === 'NOTIFICATION' ? jsonValidity.notificationVariables : jsonValidity.actionConfig
);
const advancedPanels = ref<string[]>([]);
const hooks = ref<RuntimeHook[]>([]);
const logs = ref<RuntimeHookLog[]>([]);
const hookPage = reactive({
  current: 1,
  size: 20,
  total: 0
});
const logPage = reactive({
  current: 1,
  size: 20,
  total: 0
});
const agents = ref<Agent[]>([]);
const skills = ref<SkillCatalogItem[]>([]);
const executionResources = ref<ToolResource[]>([]);
const notificationTargets = ref<NotificationTarget[]>([]);
const notificationTemplates = ref<NotificationTemplate[]>([]);
const notificationAuthorizations = ref<NotificationAuthorization[]>([]);
const skillToolRefs = ref<Record<string, AgentSkillToolRef[]>>({});
const loadedTabs = reactive({
  hooks: false,
  logs: false
});
const optionsLoaded = reactive({
  agents: false,
  skills: false,
  resources: false,
  notifications: false
});

const filters = reactive<Record<string, string>>({
  eventType: '',
  agentId: '',
  skillCode: '',
  skillVersionId: '',
  resourceKey: ''
});

const logFilters = reactive<Record<string, string>>({
  hookCode: '',
  eventType: '',
  status: '',
  agentId: '',
  skillCode: '',
  skillVersionId: '',
  resourceKey: '',
  runtimeRequestId: ''
});

function resetHookFilters() {
  Object.assign(filters, {
    eventType: '',
    agentId: '',
    skillCode: '',
    skillVersionId: '',
    resourceKey: ''
  });
  hookPage.current = 1;
  loadHooks();
}

function resetLogFilters() {
  Object.assign(logFilters, {
    hookCode: '',
    eventType: '',
    status: '',
    agentId: '',
    skillCode: '',
    skillVersionId: '',
    resourceKey: '',
    runtimeRequestId: ''
  });
  logPage.current = 1;
  loadLogs();
}

const defaultNotificationVariables = () => ({
  title: '业务执行完成',
  summary: placeholder('output.message'),
  url: placeholder('output.data.detailUrl')
});

const defaultActionConfig = () => ({
  toolKey: NOTIFICATION_TOOL_KEY,
  targetAlias: '',
  templateCode: '',
  confirmed: true,
  variables: defaultNotificationVariables(),
  idempotencyKey: [
    'hook:',
    placeholder('runtimeRequestId'),
    ':notification.send:',
    placeholder('skillVersionId'),
    ':',
    placeholder('skillCode'),
    ':',
    placeholder('resourceKey')
  ].join('')
});

const notificationForm = reactive({
  targetAlias: '',
  templateCode: '',
  confirmed: true,
  variables: defaultNotificationVariables() as Record<string, unknown>
});

const queryText = (value: unknown) => {
  if (Array.isArray(value)) return queryText(value[0]);
  return typeof value === 'string' ? value.trim() : '';
};

const routeScope = () => ({
  eventType: queryText(route.query.eventType),
  agentId: queryText(route.query.agentId),
  skillCode: queryText(route.query.skillCode || route.query.skillId),
  skillVersionId: queryText(route.query.skillVersionId),
  resourceKey: queryText(route.query.resourceKey)
});

const activeRouteScope = computed(routeScope);
const hasRouteScope = computed(() => {
  const scope = activeRouteScope.value;
  return Boolean(scope.agentId || scope.skillCode || scope.skillVersionId || scope.resourceKey);
});
const pageDescription = computed(() => {
  const scope = activeRouteScope.value;
  if (scope.skillCode) {
    return `为 Skill ${displaySkill(scope.skillCode)} 配置运行时自动化；适合 Skill 执行完成、工具成功或失败等事件。`;
  }
  if (scope.agentId) {
    return `为 Agent ${displayAgent(scope.agentId)} 配置运行时自动化；适合会话完成、工具成功或失败等生命周期事件。`;
  }
  return '为 Agent、Skill 或工具资源配置通用运行时自动化，例如执行成功后异步发送通知。';
});
const scopeTip = computed(() => {
  if (!hasRouteScope.value && scopeType.value === 'GLOBAL') {
    return '全局规则会匹配所有没有更具体范围限制的运行时事件，请谨慎启用。';
  }
  return '保存后规则会按所选 Agent、Skill 固定版本和触发工具/资源范围匹配运行时事件。';
});

const form = reactive<RuntimeHookForm>(emptyHook());

const allResourceOptions = computed<ResourceOption[]>(() =>
  executionResources.value
    .filter(resource => Boolean(resource.resourceKey))
    .map(resource => ({ resourceKey: resource.resourceKey || '', label: resourceLabel(resource) }))
);

const scopedResourceOptions = computed<ResourceOption[]>(() => {
  if (form.skillCode && skillToolRefs.value[String(form.skillCode)]) {
    return refsToResourceOptions(skillToolRefs.value[String(form.skillCode)]);
  }
  return allResourceOptions.value;
});

const selectedNotificationTemplate = computed(() =>
  notificationTemplates.value.find(template => template.templateCode === notificationForm.templateCode)
);

const templateRequiredFields = computed(() => readRequiredFields(selectedNotificationTemplate.value?.variableSchema));

const missingRequiredVariables = computed(() =>
  templateRequiredFields.value.filter(field => !hasConfiguredVariable(field, notificationForm.variables))
);

const templateVariableRows = computed<TemplateVariableRow[]>(() => {
  const schema = selectedNotificationTemplate.value?.variableSchema;
  const properties = isRecord(schema?.properties) ? schema.properties : {};
  const names = new Set<string>([
    ...templateRequiredFields.value,
    ...Object.keys(properties),
    ...Object.keys(notificationForm.variables || {})
  ]);
  return Array.from(names).map(name => {
    const property = properties[name];
    return {
      name,
      required: templateRequiredFields.value.includes(name),
      description: variableDescription(property),
      value: variableValueText(notificationForm.variables?.[name])
    };
  });
});

const notificationActionPreview = computed(() => JSON.stringify(notificationActionConfig(), null, 2));

const notificationWarnings = computed(() => {
  if (actionMode.value !== 'NOTIFICATION') {
    return [];
  }
  const messages: string[] = [];
  if (notificationForm.targetAlias && notificationForm.templateCode && !hasNotificationAuthorization()) {
    messages.push('未找到匹配的通知授权，运行时会记录发送失败');
  }
  return messages;
});

function defaultEventType(scope: ReturnType<typeof routeScope>) {
  if (scope.eventType) {
    return scope.eventType;
  }
  if (scope.skillCode && !scope.resourceKey) {
    return 'AFTER_SKILL_SUCCESS';
  }
  if (scope.agentId && !scope.skillCode && !scope.resourceKey) {
    return 'AFTER_AGENT_SUCCESS';
  }
  return 'AFTER_RESOURCE_SUCCESS';
}

function emptyHook(): RuntimeHookForm {
  const scope = routeScope();
  return {
    hookCode: '',
    hookName: '',
    eventType: defaultEventType(scope),
    agentId: scope.agentId || undefined,
    skillCode: scope.skillCode || undefined,
    skillVersionId: scope.skillVersionId || undefined,
    resourceKey: scope.resourceKey || undefined,
    actionType: 'TOOL_CALL',
    actionConfig: defaultActionConfig(),
    asyncEnabled: true,
    continueOnError: true,
    status: 'enabled',
    displayOrder: 0
  };
}

function resolveScopeType(hook: RuntimeHook): ScopeType {
  if (hook.skillCode) return 'SKILL';
  if (hook.agentId) return 'AGENT';
  if (hook.resourceKey) return 'RESOURCE';
  return 'GLOBAL';
}

function actionConfigRecord(hook: RuntimeHook): Record<string, unknown> {
  return hook.actionConfig && typeof hook.actionConfig === 'object' ? hook.actionConfig : {};
}

function syncNotificationFormFromActionConfig(hook: RuntimeHook) {
  const config = actionConfigRecord(hook);
  notificationForm.targetAlias = stringValue(config.targetAlias || config.target);
  notificationForm.templateCode = stringValue(config.templateCode || config.template);
  notificationForm.confirmed = config.confirmed !== false;
  notificationForm.variables =
    config.variables && typeof config.variables === 'object' && !Array.isArray(config.variables)
      ? copyRecord(config.variables as Record<string, unknown>)
      : defaultNotificationVariables();
}

function isNotificationAction(hook: RuntimeHook) {
  const config = actionConfigRecord(hook);
  const toolKey = stringValue(config.toolKey || config.resourceKey);
  return !toolKey || toolKey === NOTIFICATION_TOOL_KEY;
}

function notificationActionConfig() {
  return {
    ...defaultActionConfig(),
    targetAlias: notificationForm.targetAlias,
    templateCode: notificationForm.templateCode,
    confirmed: notificationForm.confirmed,
    variables: copyRecord(notificationForm.variables)
  };
}

function buildSavePayload(): RuntimeHook {
  return {
    ...form,
    agentId: form.agentId || undefined,
    skillCode: form.skillCode || undefined,
    skillVersionId: form.skillVersionId || undefined,
    resourceKey: form.resourceKey || undefined,
    actionType: 'TOOL_CALL',
    actionConfig: actionMode.value === 'NOTIFICATION' ? notificationActionConfig() : form.actionConfig
  };
}

function toolKeyText(row: RuntimeHook) {
  const value = row.actionConfig?.toolKey || row.actionConfig?.resourceKey;
  return typeof value === 'string' && value ? value : '-';
}

function scopeText(row: RuntimeHook) {
  return (
    [
      row.agentId ? `Agent=${displayAgent(String(row.agentId))}` : '',
      row.skillCode ? `Skill=${displaySkill(row.skillCode)}` : '',
      row.skillVersionId ? `版本=${row.skillVersionId}` : '',
      row.resourceKey ? `工具/资源=${displayResource(row.resourceKey)}` : ''
    ]
      .filter(Boolean)
      .join(' / ') || '全局自动化'
  );
}

function applyRouteScope() {
  const scope = routeScope();
  const scopeChanged =
    filters.eventType !== scope.eventType ||
    filters.agentId !== scope.agentId ||
    filters.skillCode !== scope.skillCode ||
    filters.skillVersionId !== scope.skillVersionId ||
    filters.resourceKey !== scope.resourceKey ||
    logFilters.eventType !== scope.eventType ||
    logFilters.agentId !== scope.agentId ||
    logFilters.skillCode !== scope.skillCode ||
    logFilters.skillVersionId !== scope.skillVersionId ||
    logFilters.resourceKey !== scope.resourceKey;
  filters.eventType = scope.eventType;
  filters.agentId = scope.agentId;
  filters.skillCode = scope.skillCode;
  filters.skillVersionId = scope.skillVersionId;
  filters.resourceKey = scope.resourceKey;
  logFilters.eventType = scope.eventType;
  logFilters.agentId = scope.agentId;
  logFilters.skillCode = scope.skillCode;
  logFilters.skillVersionId = scope.skillVersionId;
  logFilters.resourceKey = scope.resourceKey;
  if (scopeChanged) {
    hookPage.current = 1;
    logPage.current = 1;
  }
}

function handleHookSearch() {
  hookPage.current = 1;
  loadHooks();
}

function handleLogSearch() {
  logPage.current = 1;
  loadLogs();
}

function handleHookSizeChange() {
  hookPage.current = 1;
  loadHooks();
}

function handleLogSizeChange() {
  logPage.current = 1;
  loadLogs();
}

async function reload() {
  await refreshActiveTab();
}

async function loadPageData() {
  await refreshActiveTab();
}

async function loadAgents(force = false) {
  if (optionsLoaded.agents && !force) {
    return;
  }
  optionLoading.value = true;
  try {
    agents.value = await agentService.list();
    optionsLoaded.agents = true;
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载 Agent 选项失败'));
    }
  } finally {
    optionLoading.value = false;
  }
}

async function loadSkills(force = false) {
  if (optionsLoaded.skills && !force) {
    return;
  }
  optionLoading.value = true;
  try {
    skills.value = await skillService.listPublished();
    optionsLoaded.skills = true;
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载 Skill 选项失败'));
    }
  } finally {
    optionLoading.value = false;
  }
}

async function loadExecutionResources(force = false) {
  if (optionsLoaded.resources && !force) {
    return;
  }
  optionLoading.value = true;
  try {
    const page = await toolService.page({ current: 1, size: 500 });
    executionResources.value = page.data;
    optionsLoaded.resources = true;
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载执行资源失败'));
    }
  } finally {
    optionLoading.value = false;
  }
}

async function loadNotificationOptions(force = false) {
  if (optionsLoaded.notifications && !force) {
    return;
  }
  optionLoading.value = true;
  try {
    const [targets, templates, authorizations] = await Promise.all([
      notificationService.listTargets(),
      notificationService.listTemplates(),
      notificationService.listAuthorizations()
    ]);
    notificationTargets.value = targets;
    notificationTemplates.value = templates;
    notificationAuthorizations.value = authorizations;
    optionsLoaded.notifications = true;
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载通知选项失败'));
    }
  } finally {
    optionLoading.value = false;
  }
}

async function refreshActiveTab(tab = activeTab.value) {
  if (tab === 'logs') {
    await loadLogs();
    return;
  }
  await loadHooks();
}

async function ensureActiveTabLoaded(tab = activeTab.value) {
  if (tab === 'logs') {
    if (!loadedTabs.logs) await loadLogs();
    return;
  }
  if (!loadedTabs.hooks) await loadHooks();
}

function handleAgentOptionsVisible(visible: boolean) {
  if (visible) {
    loadAgents();
  }
}

function handleSkillOptionsVisible(visible: boolean) {
  if (visible) {
    loadSkills();
  }
}

function handleResourceOptionsVisible(visible: boolean) {
  if (visible) {
    loadExecutionResources();
  }
}

function handleNotificationOptionsVisible(visible: boolean) {
  if (visible) {
    loadNotificationOptions();
  }
}

async function loadHooks() {
  loading.value = true;
  try {
    const page = await platformService.pageRuntimeHooks({
      ...filters,
      current: hookPage.current,
      size: hookPage.size
    });
    hooks.value = page.data;
    hookPage.total = page.total;
    hookPage.current = page.pageNum || hookPage.current;
    hookPage.size = page.pageSize || hookPage.size;
    loadedTabs.hooks = true;
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载自动化规则失败'));
    }
    hooks.value = [];
    hookPage.total = 0;
  } finally {
    loading.value = false;
  }
}

async function loadLogs() {
  logLoading.value = true;
  try {
    const page = await platformService.pageRuntimeHookLogs({
      ...logFilters,
      current: logPage.current,
      size: logPage.size
    });
    logs.value = page.data;
    logPage.total = page.total;
    logPage.current = page.pageNum || logPage.current;
    logPage.size = page.pageSize || logPage.size;
    loadedTabs.logs = true;
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载自动化日志失败'));
    }
    logs.value = [];
    logPage.total = 0;
  } finally {
    logLoading.value = false;
  }
}

async function ensureSkillRefs(skillCode?: string) {
  if (!skillCode || skillToolRefs.value[skillCode]) {
    return;
  }
  try {
    skillToolRefs.value[skillCode] = await skillService.listToolRefs(skillCode);
  } catch {
    skillToolRefs.value[skillCode] = [];
  }
}

async function openEditor(row?: RuntimeHook) {
  jsonValidity.notificationVariables = true;
  jsonValidity.actionConfig = true;
  Object.assign(form, emptyHook(), row ? JSON.parse(JSON.stringify(row)) : {});
  normalizeFormScope();
  editingId.value = row?.id || '';
  scopeType.value = resolveScopeType(form);
  actionMode.value = isNotificationAction(form) ? 'NOTIFICATION' : 'ADVANCED';
  advancedPanels.value = actionMode.value === 'ADVANCED' ? ['actionConfig'] : [];
  syncNotificationFormFromActionConfig(form);
  if (form.skillCode) {
    ensureSkillRefs(String(form.skillCode));
  }
  if (scopeType.value === 'RESOURCE' || form.resourceKey) {
    await loadExecutionResources();
  }
  if (actionMode.value === 'NOTIFICATION') {
    await loadNotificationOptions();
  }
  editorVisible.value = true;
}

async function saveHook() {
  if (!activeJsonValid.value) {
    ElMessage.warning('请先修正自动化配置中的 JSON 格式错误');
    return;
  }
  if (!form.hookCode || !form.eventType || !form.actionType) {
    ElMessage.warning('请填写规则编码、事件类型和执行动作');
    return;
  }
  if (actionMode.value === 'NOTIFICATION') {
    if (!notificationForm.targetAlias || !notificationForm.templateCode) {
      ElMessage.warning('请选择通知目标和通知模板');
      return;
    }
    if (missingRequiredVariables.value.length) {
      ElMessage.warning(`请配置模板必填变量：${missingRequiredVariables.value.join('、')}`);
      return;
    }
  }
  saving.value = true;
  try {
    const payload = buildSavePayload();
    if (editingId.value) {
      await platformService.updateRuntimeHook(editingId.value, payload);
    } else {
      await platformService.createRuntimeHook(payload);
    }
    ElMessage.success('自动化规则已保存');
    editorVisible.value = false;
    if (activeTab.value === 'hooks') {
      await loadHooks();
    } else {
      loadedTabs.hooks = false;
    }
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '保存自动化规则失败'));
    }
  } finally {
    saving.value = false;
  }
}

async function removeHook(row: RuntimeHook) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(`确认删除自动化规则 ${row.hookCode || ''}?`, '删除自动化规则', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  await platformService.deleteRuntimeHook(row.id);
  ElMessage.success('自动化规则已删除');
  await loadHooks();
}

function handleScopeTypeChange() {
  if (scopeType.value !== 'AGENT') {
    form.agentId = undefined;
  }
  if (scopeType.value !== 'SKILL') {
    form.skillCode = undefined;
    form.skillVersionId = undefined;
  }
  if (scopeType.value !== 'SKILL' && scopeType.value !== 'RESOURCE') {
    form.resourceKey = undefined;
  }
  if (scopeType.value === 'AGENT' && !form.eventType) {
    form.eventType = 'AFTER_AGENT_SUCCESS';
  }
  if (scopeType.value === 'AGENT') {
    loadAgents();
  }
  if (scopeType.value === 'SKILL') {
    form.eventType = 'AFTER_SKILL_SUCCESS';
    loadSkills();
  }
  if (scopeType.value === 'RESOURCE') {
    form.eventType = 'AFTER_RESOURCE_SUCCESS';
    loadExecutionResources();
  }
}

function normalizeFormScope() {
  form.agentId ??= undefined;
  form.skillCode ||= undefined;
  form.skillVersionId ||= undefined;
  form.resourceKey ||= undefined;
}

function hasNotificationAuthorization() {
  return notificationAuthorizations.value.some(auth => {
    if (auth.status === 'disabled') return false;
    if (auth.expireTime && new Date(auth.expireTime).getTime() <= Date.now()) return false;
    return (
      scopeMatches(auth.agentId, form.agentId) &&
      scopeMatches(auth.skillCode, form.skillCode) &&
      scopeMatches(auth.resourceKey, NOTIFICATION_TOOL_KEY) &&
      scopeMatches(auth.targetAlias, notificationForm.targetAlias) &&
      scopeMatches(auth.templateCode, notificationForm.templateCode)
    );
  });
}

function scopeMatches(configured: unknown, current: unknown) {
  const configuredText = stringValue(configured);
  const currentText = stringValue(current);
  if (currentText) {
    return !configuredText || configuredText === currentText;
  }
  return !configuredText;
}

function agentLabel(agent: Agent) {
  return [agent.name || '未命名 Agent', agent.id ? `ID ${agent.id}` : ''].filter(Boolean).join(' / ');
}

function skillLabel(skill: SkillCatalogItem) {
  return [skill.skillName || skill.skillCode, skill.skillCode].filter(Boolean).join(' / ');
}

function resourceLabel(resource: ToolResource) {
  return [resource.resourceName || resource.resourceKey, resource.resourceKey, resource.resourceType]
    .filter(Boolean)
    .join(' / ');
}

function isRuntimeHooksRoute() {
  return route.name === 'ai-agent_runtime-hooks';
}

function targetLabel(target: NotificationTarget) {
  return [target.targetName || target.targetAlias, target.targetAlias, target.connectorCode]
    .filter(Boolean)
    .join(' / ');
}

function templateLabel(template: NotificationTemplate) {
  return [template.templateName || template.templateCode, template.templateCode, template.connectorCode]
    .filter(Boolean)
    .join(' / ');
}

function displayAgent(agentId: string) {
  const agent = agents.value.find(item => String(item.id) === String(agentId));
  return agent ? agentLabel(agent) : agentId;
}

function displaySkill(skillCode: string) {
  const skill = skills.value.find(item => item.skillCode === skillCode);
  return skill ? skillLabel(skill) : skillCode;
}

function displayResource(resourceKey: string) {
  const option = allResourceOptions.value.find(item => item.resourceKey === resourceKey);
  return option ? option.label : resourceKey;
}

function refsToResourceOptions(refs: AgentSkillToolRef[]) {
  return mergeResourceOptions(
    refs
      .filter(ref => Boolean(ref.resourceKey))
      .map(ref => {
        const resource = executionResources.value.find(item => item.resourceKey === ref.resourceKey);
        return {
          resourceKey: ref.resourceKey || '',
          label: resource ? resourceLabel(resource) : [ref.usage, ref.resourceKey].filter(Boolean).join(' / ')
        };
      })
  );
}

function mergeResourceOptions(options: ResourceOption[]) {
  const seen = new Set<string>();
  const result: ResourceOption[] = [];
  options.forEach(option => {
    if (!option.resourceKey || seen.has(option.resourceKey)) return;
    seen.add(option.resourceKey);
    result.push(option);
  });
  return result;
}

function readRequiredFields(schema?: Record<string, unknown>) {
  const required = schema?.required;
  if (!Array.isArray(required)) {
    return [];
  }
  return required.map(item => stringValue(item)).filter(Boolean);
}

function hasConfiguredVariable(field: string, variables: Record<string, unknown>) {
  if (!Object.hasOwn(variables || {}, field)) {
    return false;
  }
  const value = variables[field];
  return !(typeof value === 'string' && !value.trim());
}

function variableDescription(value: unknown) {
  if (!isRecord(value)) {
    return '';
  }
  return stringValue(value.description || value.title || value.label);
}

function variableValueText(value: unknown) {
  if (value === undefined || value === null) {
    return '';
  }
  if (typeof value === 'string') {
    return value;
  }
  return JSON.stringify(value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function copyRecord(value: Record<string, unknown>) {
  return JSON.parse(JSON.stringify(value || {})) as Record<string, unknown>;
}

function stringValue(value: unknown) {
  return value === undefined || value === null ? '' : String(value).trim();
}

watch(
  () => form.skillCode,
  value => {
    ensureSkillRefs(typeof value === 'string' ? value : '');
  }
);

watch(actionMode, value => {
  if (value === 'NOTIFICATION') {
    form.actionConfig = notificationActionConfig();
    loadNotificationOptions();
  }
});

watch(activeTab, value => {
  ensureActiveTabLoaded(value);
});

/**
 * KeepAlive 命中时首次进入会先后触发 mounted 与 activated，用该标记跳过首次 activated，
 * 避免首屏重复请求；未被缓存的场景 activated 不触发，首屏由 mounted 负责。
 */
let activatedAfterMount = false;

onMounted(() => {
  applyRouteScope();
  loadPageData();
});

onActivated(() => {
  if (!activatedAfterMount) {
    activatedAfterMount = true;
    return;
  }
  applyRouteScope();
  loadPageData();
});
</script>

<style scoped>
.runtime-hooks-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.runtime-hooks-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.runtime-hooks-content-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.runtime-hooks-content-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.runtime-hooks-content-card :deep(.el-tabs) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column-reverse;
  min-height: 0;
}

.runtime-hooks-content-card :deep(.el-tabs__content) {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.runtime-hooks-content-card :deep(.el-tab-pane) {
  height: 100%;
  min-height: 0;
}

.runtime-tab-panel {
  display: flex;
  overflow: hidden;
  height: 100%;
  min-height: 0;
  flex-direction: column;
}

.runtime-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.runtime-table-wrap :deep(.el-table) {
  height: 100%;
}

.page-toolbar,
.toolbar-actions,
.switches {
  display: flex;
  align-items: center;
  gap: 10px;
}

.page-toolbar {
  justify-content: space-between;
}

.page-toolbar h1 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 700;
  line-height: 28px;
  letter-spacing: 0;
}

.page-toolbar p {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.runtime-filter-panel {
  margin-bottom: 10px;
}

.tab-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.runtime-filter-panel :deep(.el-form-item) {
  margin-bottom: 10px;
}

.runtime-filter-panel :deep(.el-input),
.runtime-filter-panel :deep(.el-select) {
  width: 100%;
}

.runtime-filter-actions :deep(.el-form-item__content) {
  gap: 8px;
  flex-wrap: nowrap;
}

.form-section {
  margin-bottom: 12px;
}

.form-section h3 {
  margin: 0 0 12px;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 700;
}

.action-block {
  padding: 2px 0 14px;
}

.action-block + .action-block {
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 14px;
}

.action-block h4 {
  margin: 0 0 10px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 700;
}

.grid-two {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.template-summary {
  margin-top: 4px;
  padding-top: 12px;
  border-top: 1px dashed var(--el-border-color);
}

.summary-heading {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 10px;
}

.summary-list {
  display: grid;
  grid-template-columns: 86px minmax(0, 1fr);
  gap: 8px 12px;
  margin: 0;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.55;
}

.summary-list dt {
  color: var(--el-text-color-secondary);
}

.summary-list dd {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-width: 0;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.summary-note,
.field-help {
  margin: 8px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.variable-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin: 10px 0 12px;
}

.variable-row {
  display: grid;
  grid-template-columns: minmax(100px, 150px) auto minmax(120px, 1fr) minmax(180px, 1.2fr);
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.variable-name {
  color: var(--el-text-color-primary);
  font-weight: 600;
  word-break: break-word;
}

.variable-desc {
  color: var(--el-text-color-secondary);
  word-break: break-word;
}

.variable-value {
  min-width: 0;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  padding: 4px 6px;
  color: var(--el-text-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}

.variable-editor-item :deep(.json-object-editor),
.variable-editor-item :deep(.el-textarea) {
  width: 100%;
}

.config-preview {
  max-height: 320px;
  overflow: auto;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  padding: 12px;
  color: var(--el-text-color-primary);
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.form-tip,
.advanced-scope {
  margin-bottom: 12px;
}

.full-width {
  width: 100%;
}

@media (max-width: 900px) {
  .page-toolbar,
  .filters {
    align-items: stretch;
    flex-direction: column;
  }

  .filters :deep(.el-input),
  .filters :deep(.el-select),
  .grid-two,
  .summary-list,
  .variable-row {
    width: 100%;
    max-width: none;
    grid-template-columns: 1fr;
  }
}
</style>
