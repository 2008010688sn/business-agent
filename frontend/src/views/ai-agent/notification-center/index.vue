<template>
  <BaseLayout class="notification-center-shell">
    <main class="notification-center">
      <ElCard class="notification-toolbar-card">
        <header class="notification-toolbar">
          <div>
            <h1>AI 通知中心</h1>
            <p>连接器、目标、模板、授权和发送记录集中管理。</p>
          </div>
          <div class="toolbar-actions">
            <ElButton :icon="Refresh" @click="reloadAll">刷新</ElButton>
            <ElButton type="primary" :icon="Plus" @click="openConnectorDialog()">新建连接器</ElButton>
          </div>
        </header>
      </ElCard>

      <section class="notification-layout">
        <aside class="connector-list">
          <div class="connector-list-header">
            <span class="font-size-16px">连接器</span>
            <ElTag size="small" type="info">{{ connectors.length }}</ElTag>
          </div>
          <ElEmpty v-if="!connectors.length && !loading.connectors" description="暂无连接器" />
          <button
            v-for="connector in connectors"
            :key="connector.connectorCode"
            class="connector-item"
            :class="{ active: connector.connectorCode === selectedConnectorCode }"
            type="button"
            @click="selectConnector(connector.connectorCode)"
          >
            <span class="connector-name">{{ connector.connectorName || connector.connectorCode }}</span>
            <span class="connector-meta">
              <ElTag size="small">{{ connector.provider }}</ElTag>
              <ElTag size="small" effect="plain">{{ connector.channelType }}</ElTag>
            </span>
            <span class="connector-code">{{ connector.connectorCode }}</span>
          </button>
        </aside>

        <section class="connector-detail">
          <ElEmpty v-if="!selectedConnector" description="请选择连接器" />
          <template v-else>
            <div class="detail-header">
              <div>
                <div class="font-size-16px font-bold">{{ selectedConnector.connectorName }}</div>
                <div class="detail-meta">
                  <ElTag>{{ selectedConnector.provider }}</ElTag>
                  <ElTag effect="plain">{{ selectedConnector.channelType }}</ElTag>
                  <ElTag :type="selectedConnector.status === 'enabled' ? 'success' : 'info'">
                    {{ selectedConnector.status || 'enabled' }}
                  </ElTag>
                </div>
              </div>
              <div class="toolbar-actions">
                <ElButton :icon="Connection" @click="testConnector">测试连接</ElButton>
                <ElButton :icon="Position" @click="openSendDialog">测试发送</ElButton>
                <ElButton :icon="Edit" @click="openConnectorDialog(selectedConnector)">编辑</ElButton>
                <ElButton :icon="Delete" type="danger" plain @click="deleteConnector(selectedConnector)">删除</ElButton>
              </div>
            </div>

            <ElTabs v-model="activeTab" class="detail-tabs">
              <ElTabPane label="基础配置" name="basic" class="basic-tab-pane">
                <div class="basic-grid">
                  <div class="kv">
                    <span>连接器编码</span>
                    <strong>{{ selectedConnector.connectorCode }}</strong>
                  </div>
                  <div class="kv">
                    <span>认证方式</span>
                    <strong>{{ selectedConnector.authType || '-' }}</strong>
                  </div>
                  <div class="kv">
                    <span>凭据引用</span>
                    <strong>{{ selectedConnector.credentialRef || '-' }}</strong>
                  </div>
                  <div class="kv">
                    <span>租户</span>
                    <strong>{{ selectedConnector.tenantId || '-' }}</strong>
                  </div>
                </div>
                <JsonObjectEditor :model-value="selectedConnector.config || {}" :rows="10" readonly />
              </ElTabPane>

              <ElTabPane label="通知目标" name="targets" class="table-tab-pane">
                <TableHeader title="通知目标" @create="openTargetDialog()" @reload="loadTargets" />
                <div class="notification-table-fill">
                  <ElTable v-loading="loading.targets" :data="targets" height="100%" border>
                    <ElTableColumn prop="targetAlias" label="别名" min-width="160" show-overflow-tooltip />
                    <ElTableColumn prop="targetName" label="名称" min-width="150" show-overflow-tooltip />
                    <ElTableColumn prop="targetType" label="类型" width="110" />
                    <ElTableColumn prop="resolverCode" label="动态解析" min-width="140" show-overflow-tooltip />
                    <ElTableColumn prop="status" label="状态" width="90" />
                    <ElTableColumn label="操作" width="150" fixed="right">
                      <template #default="{ row }">
                        <ElButton link type="primary" @click="openTargetDialog(row)">编辑</ElButton>
                        <ElButton link type="danger" @click="deleteTarget(row)">删除</ElButton>
                      </template>
                    </ElTableColumn>
                  </ElTable>
                </div>
              </ElTabPane>

              <ElTabPane label="通知模板" name="templates" class="table-tab-pane">
                <TableHeader title="通知模板" @create="openTemplateDialog()" @reload="loadTemplates" />
                <div class="notification-table-fill">
                  <ElTable v-loading="loading.templates" :data="templates" height="100%" border>
                    <ElTableColumn prop="templateCode" label="编码" min-width="160" show-overflow-tooltip />
                    <ElTableColumn prop="templateName" label="名称" min-width="150" show-overflow-tooltip />
                    <ElTableColumn prop="messageType" label="消息类型" width="110" />
                    <ElTableColumn prop="platformTemplateId" label="平台模板ID" min-width="160" show-overflow-tooltip />
                    <ElTableColumn prop="riskLevel" label="风险" width="90" />
                    <ElTableColumn label="确认" width="80">
                      <template #default="{ row }">
                        <ElTag :type="row.confirmRequired ? 'warning' : 'info'" size="small">
                          {{ row.confirmRequired ? '需要' : '可免' }}
                        </ElTag>
                      </template>
                    </ElTableColumn>
                    <ElTableColumn label="操作" width="150" fixed="right">
                      <template #default="{ row }">
                        <ElButton link type="primary" @click="openTemplateDialog(row)">编辑</ElButton>
                        <ElButton link type="danger" @click="deleteTemplate(row)">删除</ElButton>
                      </template>
                    </ElTableColumn>
                  </ElTable>
                </div>
              </ElTabPane>

              <ElTabPane label="授权范围" name="authorizations" class="table-tab-pane">
                <TableHeader title="授权范围" @create="openAuthorizationDialog()" @reload="loadAuthorizations" />
                <div class="notification-table-fill">
                  <ElTable v-loading="loading.authorizations" :data="visibleAuthorizations" height="100%" border>
                    <ElTableColumn prop="agentId" label="Agent" width="110" />
                    <ElTableColumn prop="skillCode" label="Skill" min-width="160" show-overflow-tooltip />
                    <ElTableColumn prop="skillVersionId" label="Skill 版本" width="110" />
                    <ElTableColumn prop="resourceKey" label="工具资源" min-width="190" show-overflow-tooltip />
                    <ElTableColumn prop="targetAlias" label="目标" min-width="140" show-overflow-tooltip />
                    <ElTableColumn prop="templateCode" label="模板" min-width="140" show-overflow-tooltip />
                    <ElTableColumn prop="confirmPolicy" label="确认策略" width="120" />
                    <ElTableColumn prop="status" label="状态" width="90" />
                    <ElTableColumn label="操作" width="150" fixed="right">
                      <template #default="{ row }">
                        <ElButton link type="primary" @click="openAuthorizationDialog(row)">编辑</ElButton>
                        <ElButton link type="danger" @click="deleteAuthorization(row)">删除</ElButton>
                      </template>
                    </ElTableColumn>
                  </ElTable>
                </div>
              </ElTabPane>

              <ElTabPane label="发送日志" name="deliveries" class="table-tab-pane">
                <div class="log-filters">
                  <ElInput v-model="deliveryFilters.deliveryId" clearable placeholder="deliveryId" />
                  <ElInput v-model="deliveryFilters.agentId" clearable placeholder="agentId" />
                  <ElInput v-model="deliveryFilters.skillCode" clearable placeholder="skillCode" />
                  <ElInput v-model="deliveryFilters.skillVersionId" clearable placeholder="skillVersionId" />
                  <ElInput v-model="deliveryFilters.targetAlias" clearable placeholder="targetAlias" />
                  <ElInput v-model="deliveryFilters.templateCode" clearable placeholder="templateCode" />
                  <ElButton :icon="Search" type="primary" @click="loadDeliveries">查询</ElButton>
                </div>
                <div class="notification-table-fill">
                  <ElTable v-loading="loading.deliveries" :data="visibleDeliveries" height="100%" border>
                    <ElTableColumn prop="deliveryId" label="deliveryId" min-width="210" show-overflow-tooltip />
                    <ElTableColumn prop="status" label="状态" width="120" />
                    <ElTableColumn prop="targetAlias" label="目标" min-width="140" show-overflow-tooltip />
                    <ElTableColumn prop="templateCode" label="模板" min-width="140" show-overflow-tooltip />
                    <ElTableColumn prop="platformCode" label="平台码" width="110" />
                    <ElTableColumn prop="errorMessage" label="失败原因" min-width="180" show-overflow-tooltip />
                    <ElTableColumn prop="createTime" label="时间" min-width="170" show-overflow-tooltip />
                  </ElTable>
                </div>
              </ElTabPane>
            </ElTabs>
          </template>
        </section>
      </section>
    </main>

    <ElDialog v-model="connectorDialog.visible" :title="connectorDialogTitle" width="680px">
      <ElForm label-width="110px">
        <ElFormItem label="编码"><ElInput v-model="connectorForm.connectorCode" /></ElFormItem>
        <ElFormItem label="名称"><ElInput v-model="connectorForm.connectorName" /></ElFormItem>
        <ElFormItem label="平台">
          <ElSelect v-model="connectorForm.provider" @change="syncDefaultChannel">
            <ElOption v-for="item in providerOptions" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="通道">
          <ElSelect v-model="connectorForm.channelType">
            <ElOption v-for="item in channelOptions" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="认证方式"><ElInput v-model="connectorForm.authType" /></ElFormItem>
        <ElFormItem label="凭据引用"><ElInput v-model="connectorForm.credentialRef" /></ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="connectorForm.status">
            <ElOption label="enabled" value="enabled" />
            <ElOption label="disabled" value="disabled" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="连接配置">
          <JsonObjectEditor
            :model-value="connectorForm.config"
            :rows="8"
            @update:model-value="updateConnectorConfig"
            @validity-change="jsonValidity.connector = $event"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="connectorDialog.visible = false">取消</ElButton>
        <ElButton type="primary" :disabled="!jsonValidity.connector" @click="saveConnector">保存</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="targetDialog.visible" :title="targetDialogTitle" width="680px">
      <ElForm label-width="110px">
        <ElFormItem label="别名"><ElInput v-model="targetForm.targetAlias" /></ElFormItem>
        <ElFormItem label="名称"><ElInput v-model="targetForm.targetName" /></ElFormItem>
        <ElFormItem label="类型">
          <ElSelect v-model="targetForm.targetType">
            <ElOption v-for="item in targetTypeOptions" :key="item" :label="item" :value="item" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="动态解析"><ElInput v-model="targetForm.resolverCode" /></ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="targetForm.status">
            <ElOption label="enabled" value="enabled" />
            <ElOption label="disabled" value="disabled" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="目标配置">
          <JsonObjectEditor
            :model-value="targetForm.targetConfig"
            :rows="8"
            @update:model-value="updateTargetConfig"
            @validity-change="jsonValidity.target = $event"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="targetDialog.visible = false">取消</ElButton>
        <ElButton type="primary" :disabled="!jsonValidity.target" @click="saveTarget">保存</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="templateDialog.visible" :title="templateDialogTitle" width="760px">
      <ElForm label-width="120px">
        <ElFormItem label="编码"><ElInput v-model="templateForm.templateCode" /></ElFormItem>
        <ElFormItem label="名称"><ElInput v-model="templateForm.templateName" /></ElFormItem>
        <ElFormItem label="消息类型"><ElInput v-model="templateForm.messageType" /></ElFormItem>
        <ElFormItem label="平台模板ID"><ElInput v-model="templateForm.platformTemplateId" /></ElFormItem>
        <ElFormItem label="标题模板"><ElInput v-model="templateForm.titleTemplate" /></ElFormItem>
        <ElFormItem label="内容模板">
          <ElInput v-model="templateForm.contentTemplate" type="textarea" :rows="5" />
        </ElFormItem>
        <ElFormItem label="风险级别">
          <ElSelect v-model="templateForm.riskLevel">
            <ElOption v-for="item in riskLevelOptions" :key="item" :label="item" :value="item" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="强制确认"><ElSwitch v-model="templateForm.confirmRequired" /></ElFormItem>
        <ElFormItem label="变量 Schema">
          <JsonObjectEditor
            v-model="templateForm.variableSchema"
            :rows="8"
            @validity-change="jsonValidity.template = $event"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="templateDialog.visible = false">取消</ElButton>
        <ElButton type="primary" :disabled="!jsonValidity.template" @click="saveTemplate">保存</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="authorizationDialog.visible" :title="authorizationDialogTitle" width="720px">
      <ElForm label-width="130px">
        <ElFormItem label="Agent ID"><ElInput v-model="authorizationForm.agentId" clearable /></ElFormItem>
        <ElFormItem label="Skill 编码"><ElInput v-model="authorizationForm.skillCode" clearable /></ElFormItem>
        <ElFormItem label="Skill 版本"><ElInput v-model="authorizationForm.skillVersionId" clearable /></ElFormItem>
        <ElFormItem label="工具资源"><ElInput v-model="authorizationForm.resourceKey" clearable /></ElFormItem>
        <ElFormItem label="目标范围">
          <ElSelect v-model="authorizationForm.targetAlias" clearable @visible-change="handleTargetOptionsVisible">
            <ElOption label="全部目标" value="" />
            <ElOption v-for="item in targetOptions" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="模板范围">
          <ElSelect v-model="authorizationForm.templateCode" clearable @visible-change="handleTemplateOptionsVisible">
            <ElOption label="全部模板" value="" />
            <ElOption v-for="item in templateOptions" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="确认策略">
          <ElSelect v-model="authorizationForm.confirmPolicy">
            <ElOption v-for="item in confirmPolicyOptions" :key="item" :label="item" :value="item" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="过期时间"><ElInput v-model="authorizationForm.expireTime" clearable /></ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="authorizationForm.status">
            <ElOption label="enabled" value="enabled" />
            <ElOption label="disabled" value="disabled" />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="authorizationDialog.visible = false">取消</ElButton>
        <ElButton type="primary" @click="saveAuthorization">保存</ElButton>
      </template>
    </ElDialog>

    <ElDialog v-model="sendDialog.visible" title="测试发送" width="720px">
      <ElForm label-width="120px">
        <ElFormItem label="Skill 编码"><ElInput v-model="sendForm.skillCode" /></ElFormItem>
        <ElFormItem label="Skill 版本"><ElInput v-model="sendForm.skillVersionId" /></ElFormItem>
        <ElFormItem label="工具资源"><ElInput v-model="sendForm.resourceKey" /></ElFormItem>
        <ElFormItem label="目标">
          <ElSelect v-model="sendForm.targetAlias" filterable @visible-change="handleTargetOptionsVisible">
            <ElOption v-for="item in targetOptions" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="模板">
          <ElSelect v-model="sendForm.templateCode" filterable @visible-change="handleTemplateOptionsVisible">
            <ElOption v-for="item in templateOptions" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="幂等键"><ElInput v-model="sendForm.idempotencyKey" /></ElFormItem>
        <ElFormItem label="已确认"><ElSwitch v-model="sendForm.confirmed" /></ElFormItem>
        <ElFormItem label="变量">
          <JsonObjectEditor v-model="sendForm.variables" :rows="8" @validity-change="jsonValidity.send = $event" />
        </ElFormItem>
      </ElForm>
      <div v-if="sendResult" class="preview-result">
        <ElTag :type="sendResult.status === 'SENT' ? 'success' : sendResult.status === 'DENIED' ? 'danger' : 'warning'">
          {{ sendResult.status }}
        </ElTag>
        <span>{{ sendResult.message }}</span>
        <p v-if="sendResult.preview?.summary">{{ sendResult.preview.summary }}</p>
      </div>
      <template #footer>
        <ElButton @click="sendDialog.visible = false">关闭</ElButton>
        <ElButton :disabled="!jsonValidity.send" @click="previewNotification">预览</ElButton>
        <ElButton type="primary" :disabled="!jsonValidity.send" @click="sendNotification">发送</ElButton>
      </template>
    </ElDialog>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, onMounted, reactive, ref, watch } from 'vue';
import { Delete, Edit, Connection, Plus, Position, Refresh, Search } from '@element-plus/icons-vue';
import { ElButton, ElMessage, ElMessageBox } from 'element-plus';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import notificationService from '@/views/ai-agent/services/notification';
import type {
  NotificationAuthorization,
  NotificationConnector,
  NotificationDelivery,
  NotificationSendRequest,
  NotificationSendResponse,
  NotificationTarget,
  NotificationTemplate
} from '@/views/ai-agent/services/notification';

const TableHeader = defineComponent({
  name: 'TableHeader',
  props: {
    title: { type: String, required: true }
  },
  emits: ['create', 'reload'],
  setup(props, { emit }) {
    return () =>
      h('div', { class: 'table-header' }, [
        h('strong', props.title),
        h('div', { class: 'toolbar-actions' }, [
          h(ElButton, { icon: Refresh, onClick: () => emit('reload') }, () => '刷新'),
          h(ElButton, { type: 'primary', icon: Plus, onClick: () => emit('create') }, () => '新增')
        ])
      ]);
  }
});

const providerOptions = [
  { label: '钉钉', value: 'DINGTALK' },
  { label: '企业微信', value: 'WECOM' },
  { label: '微信公众号', value: 'WECHAT_MP' },
  { label: '微信小程序', value: 'WECHAT_MINI' },
  { label: '短信', value: 'SMS' }
];

const channelMap: Record<string, Array<{ label: string; value: string }>> = {
  DINGTALK: [
    { label: '钉钉机器人', value: 'DINGTALK_ROBOT' },
    { label: '钉钉工作通知', value: 'DINGTALK_WORK_NOTICE' }
  ],
  WECOM: [
    { label: '企业微信群机器人', value: 'WECOM_ROBOT' },
    { label: '企业微信应用消息', value: 'WECOM_APP' }
  ],
  WECHAT_MP: [{ label: '公众号模板消息', value: 'WECHAT_MP_TEMPLATE' }],
  WECHAT_MINI: [{ label: '小程序订阅消息', value: 'WECHAT_MINI_SUBSCRIBE' }],
  SMS: [{ label: '阿里云短信', value: 'SMS_ALIYUN' }]
};

const targetTypeOptions = ['GROUP', 'USER', 'DEPARTMENT', 'TAG', 'OPENID', 'PHONE', 'DYNAMIC'];
const riskLevelOptions = ['LOW', 'MEDIUM', 'HIGH'];
const confirmPolicyOptions = ['ALWAYS', 'SESSION_ONCE', 'NONE'];

const connectors = ref<NotificationConnector[]>([]);
const targets = ref<NotificationTarget[]>([]);
const templates = ref<NotificationTemplate[]>([]);
const authorizations = ref<NotificationAuthorization[]>([]);
const deliveries = ref<NotificationDelivery[]>([]);
const selectedConnectorCode = ref('');
const activeTab = ref('targets');
const sendResult = ref<NotificationSendResponse | null>(null);
const loadedTabs = reactive({
  targets: false,
  templates: false,
  authorizations: false,
  deliveries: false
});

const loading = reactive({
  connectors: false,
  targets: false,
  templates: false,
  authorizations: false,
  deliveries: false
});

const connectorDialog = reactive({ visible: false, mode: 'create' as 'create' | 'edit' });
const targetDialog = reactive({ visible: false, mode: 'create' as 'create' | 'edit' });
const templateDialog = reactive({ visible: false, mode: 'create' as 'create' | 'edit' });
const authorizationDialog = reactive({ visible: false, mode: 'create' as 'create' | 'edit' });
const sendDialog = reactive({ visible: false });
const connectorConfigTouched = ref(false);
const targetConfigTouched = ref(false);
const jsonValidity = reactive({ connector: true, target: true, template: true, send: true });

const selectedConnector = computed(() =>
  connectors.value.find(item => item.connectorCode === selectedConnectorCode.value)
);

const connectorForm = reactive<NotificationConnector>(emptyConnector());
const targetForm = reactive<NotificationTarget>(emptyTarget());
const templateForm = reactive<NotificationTemplate>(emptyTemplate());
const authorizationForm = reactive<NotificationAuthorization>(emptyAuthorization());
const sendForm = reactive<NotificationSendRequest>(emptySendRequest());
const deliveryFilters = reactive<Record<string, string>>({
  deliveryId: '',
  agentId: '',
  skillCode: '',
  skillVersionId: '',
  targetAlias: '',
  templateCode: '',
  resourceKey: ''
});

const channelOptions = computed(() => channelMap[connectorForm.provider || ''] || []);
const targetOptions = computed(() =>
  targets.value
    .filter(item => item.targetAlias)
    .map(item => ({ label: item.targetName || item.targetAlias || '', value: item.targetAlias || '' }))
);
const templateOptions = computed(() =>
  templates.value
    .filter(item => item.templateCode)
    .map(item => ({ label: item.templateName || item.templateCode || '', value: item.templateCode || '' }))
);
const connectorDialogTitle = computed(() => (connectorDialog.mode === 'create' ? '新建连接器' : '编辑连接器'));
const targetDialogTitle = computed(() => (targetDialog.mode === 'create' ? '新增通知目标' : '编辑通知目标'));
const templateDialogTitle = computed(() => (templateDialog.mode === 'create' ? '新增通知模板' : '编辑通知模板'));
const authorizationDialogTitle = computed(() =>
  authorizationDialog.mode === 'create' ? '新增授权范围' : '编辑授权范围'
);

const visibleAuthorizations = computed(() => {
  if (!loadedTabs.targets || !loadedTabs.templates) {
    return authorizations.value;
  }
  const targetAliases = new Set(targets.value.map(item => item.targetAlias).filter(Boolean));
  const templateCodes = new Set(templates.value.map(item => item.templateCode).filter(Boolean));
  return authorizations.value.filter(item => {
    const targetMatched = !item.targetAlias || targetAliases.has(item.targetAlias);
    const templateMatched = !item.templateCode || templateCodes.has(item.templateCode);
    return targetMatched || templateMatched;
  });
});

const visibleDeliveries = computed(() =>
  deliveries.value.filter(item => !selectedConnectorCode.value || item.connectorCode === selectedConnectorCode.value)
);

onMounted(async () => {
  await loadConnectors();
  await ensureActiveTabLoaded();
});

watch(activeTab, tab => {
  ensureActiveTabLoaded(tab);
});

async function reloadAll() {
  await loadConnectors();
  await refreshActiveTab();
}

async function loadConnectors() {
  loading.connectors = true;
  try {
    connectors.value = await notificationService.listConnectors();
    if (!connectors.value.some(item => item.connectorCode === selectedConnectorCode.value)) {
      selectedConnectorCode.value = connectors.value[0]?.connectorCode || '';
    }
  } catch (error) {
    connectors.value = [];
    selectedConnectorCode.value = '';
    showError(error, '加载连接器失败');
  } finally {
    loading.connectors = false;
  }
}

async function loadTargets() {
  if (!selectedConnectorCode.value) {
    targets.value = [];
    loadedTabs.targets = true;
    return;
  }
  loading.targets = true;
  try {
    targets.value = await notificationService.listTargets(selectedConnectorCode.value);
    loadedTabs.targets = true;
  } catch (error) {
    targets.value = [];
    showError(error, '加载通知目标失败');
  } finally {
    loading.targets = false;
  }
}

async function loadTemplates() {
  if (!selectedConnectorCode.value) {
    templates.value = [];
    loadedTabs.templates = true;
    return;
  }
  loading.templates = true;
  try {
    templates.value = await notificationService.listTemplates(selectedConnectorCode.value);
    loadedTabs.templates = true;
  } catch (error) {
    templates.value = [];
    showError(error, '加载通知模板失败');
  } finally {
    loading.templates = false;
  }
}

async function loadAuthorizations() {
  loading.authorizations = true;
  try {
    authorizations.value = await notificationService.listAuthorizations();
    loadedTabs.authorizations = true;
  } catch (error) {
    authorizations.value = [];
    showError(error, '加载授权范围失败');
  } finally {
    loading.authorizations = false;
  }
}

async function loadDeliveries() {
  loading.deliveries = true;
  try {
    deliveries.value = await notificationService.listDeliveries(compact(deliveryFilters));
    loadedTabs.deliveries = true;
  } catch (error) {
    deliveries.value = [];
    showError(error, '加载发送日志失败');
  } finally {
    loading.deliveries = false;
  }
}

async function selectConnector(connectorCode?: string) {
  selectedConnectorCode.value = connectorCode || '';
  markConnectorScopedTabsStale();
  await refreshActiveTab();
}

function markConnectorScopedTabsStale() {
  targets.value = [];
  templates.value = [];
  deliveries.value = [];
  loadedTabs.targets = false;
  loadedTabs.templates = false;
  loadedTabs.deliveries = false;
}

async function refreshActiveTab(tab = activeTab.value) {
  if (tab === 'targets') await loadTargets();
  if (tab === 'templates') await loadTemplates();
  if (tab === 'authorizations') await loadAuthorizations();
  if (tab === 'deliveries') await loadDeliveries();
}

async function ensureActiveTabLoaded(tab = activeTab.value) {
  if (tab === 'targets' && !loadedTabs.targets) await loadTargets();
  if (tab === 'templates' && !loadedTabs.templates) await loadTemplates();
  if (tab === 'authorizations' && !loadedTabs.authorizations) await loadAuthorizations();
  if (tab === 'deliveries' && !loadedTabs.deliveries) await loadDeliveries();
}

async function ensureTargetsLoaded() {
  if (!loadedTabs.targets) {
    await loadTargets();
  }
}

async function ensureTemplatesLoaded() {
  if (!loadedTabs.templates) {
    await loadTemplates();
  }
}

function handleTargetOptionsVisible(visible: boolean) {
  if (visible) {
    ensureTargetsLoaded();
  }
}

function handleTemplateOptionsVisible(visible: boolean) {
  if (visible) {
    ensureTemplatesLoaded();
  }
}

function openConnectorDialog(row?: NotificationConnector) {
  connectorDialog.mode = row?.id ? 'edit' : 'create';
  connectorConfigTouched.value = false;
  jsonValidity.connector = true;
  assign(connectorForm, row || emptyConnector());
  connectorDialog.visible = true;
}

async function saveConnector() {
  if (!jsonValidity.connector) {
    ElMessage.warning('请先修正连接配置中的 JSON 格式错误');
    return;
  }
  const payload = { ...connectorForm };
  if (connectorDialog.mode === 'edit' && !connectorConfigTouched.value) {
    delete payload.config;
  }
  if (connectorForm.id) {
    await notificationService.updateConnector(connectorForm.id, payload);
  } else {
    await notificationService.createConnector(payload);
  }
  connectorDialog.visible = false;
  ElMessage.success('连接器已保存');
  await loadConnectors();
  markConnectorScopedTabsStale();
  await refreshActiveTab();
}

async function deleteConnector(row: NotificationConnector) {
  if (!row.id) return;
  await confirmDelete(`确认删除连接器 ${row.connectorName || row.connectorCode}？`);
  await notificationService.deleteConnector(row.id);
  ElMessage.success('连接器已删除');
  await loadConnectors();
  markConnectorScopedTabsStale();
  await refreshActiveTab();
}

async function testConnector() {
  if (!selectedConnector.value?.connectorCode) return;
  const result = await notificationService.testConnector(selectedConnector.value.connectorCode);
  if (result.success) {
    ElMessage.success(result.message || '连接器可用');
  } else {
    ElMessage.warning(result.message || '连接器测试未通过');
  }
}

function openTargetDialog(row?: NotificationTarget) {
  targetDialog.mode = row?.id ? 'edit' : 'create';
  targetConfigTouched.value = false;
  jsonValidity.target = true;
  assign(targetForm, row || emptyTarget());
  targetForm.connectorCode = selectedConnectorCode.value;
  targetForm.provider = selectedConnector.value?.provider;
  targetDialog.visible = true;
}

async function saveTarget() {
  if (!jsonValidity.target) {
    ElMessage.warning('请先修正目标配置中的 JSON 格式错误');
    return;
  }
  const payload = { ...targetForm };
  if (targetDialog.mode === 'edit' && !targetConfigTouched.value) {
    delete payload.targetConfig;
  }
  if (targetForm.id) {
    await notificationService.updateTarget(targetForm.id, payload);
  } else {
    await notificationService.createTarget(payload);
  }
  targetDialog.visible = false;
  ElMessage.success('通知目标已保存');
  await loadTargets();
}

async function deleteTarget(row: NotificationTarget) {
  if (!row.id) return;
  await confirmDelete(`确认删除目标 ${row.targetName || row.targetAlias}？`);
  await notificationService.deleteTarget(row.id);
  ElMessage.success('通知目标已删除');
  await loadTargets();
}

function openTemplateDialog(row?: NotificationTemplate) {
  templateDialog.mode = row?.id ? 'edit' : 'create';
  jsonValidity.template = true;
  assign(templateForm, row || emptyTemplate());
  templateForm.connectorCode = selectedConnectorCode.value;
  templateForm.provider = selectedConnector.value?.provider;
  templateDialog.visible = true;
}

async function saveTemplate() {
  if (!jsonValidity.template) {
    ElMessage.warning('请先修正变量结构中的 JSON 格式错误');
    return;
  }
  if (templateForm.id) {
    await notificationService.updateTemplate(templateForm.id, templateForm);
  } else {
    await notificationService.createTemplate(templateForm);
  }
  templateDialog.visible = false;
  ElMessage.success('通知模板已保存');
  await loadTemplates();
}

async function deleteTemplate(row: NotificationTemplate) {
  if (!row.id) return;
  await confirmDelete(`确认删除模板 ${row.templateName || row.templateCode}？`);
  await notificationService.deleteTemplate(row.id);
  ElMessage.success('通知模板已删除');
  await loadTemplates();
}

async function openAuthorizationDialog(row?: NotificationAuthorization) {
  await Promise.all([ensureTargetsLoaded(), ensureTemplatesLoaded()]);
  authorizationDialog.mode = row?.id ? 'edit' : 'create';
  assign(authorizationForm, row || emptyAuthorization());
  authorizationDialog.visible = true;
}

async function saveAuthorization() {
  const payload = compact(authorizationForm) as NotificationAuthorization;
  if (authorizationForm.id) {
    await notificationService.updateAuthorization(authorizationForm.id, payload);
  } else {
    await notificationService.createAuthorization(payload);
  }
  authorizationDialog.visible = false;
  ElMessage.success('授权范围已保存');
  await loadAuthorizations();
}

async function deleteAuthorization(row: NotificationAuthorization) {
  if (!row.id) return;
  await confirmDelete('确认删除该授权范围？');
  await notificationService.deleteAuthorization(row.id);
  ElMessage.success('授权范围已删除');
  await loadAuthorizations();
}

async function openSendDialog() {
  await Promise.all([ensureTargetsLoaded(), ensureTemplatesLoaded()]);
  assign(sendForm, emptySendRequest());
  sendForm.targetAlias = targets.value[0]?.targetAlias;
  sendForm.templateCode = templates.value[0]?.templateCode;
  sendForm.idempotencyKey = randomKey();
  sendResult.value = null;
  jsonValidity.send = true;
  sendDialog.visible = true;
}

async function previewNotification() {
  if (!jsonValidity.send) {
    ElMessage.warning('请先修正发送变量中的 JSON 格式错误');
    return;
  }
  sendResult.value = await notificationService.preview(sendForm);
  ElMessage.success('预览已生成');
}

async function sendNotification() {
  if (!jsonValidity.send) {
    ElMessage.warning('请先修正发送变量中的 JSON 格式错误');
    return;
  }
  sendResult.value = await notificationService.send(sendForm);
  if (activeTab.value === 'deliveries') {
    await loadDeliveries();
  } else {
    loadedTabs.deliveries = false;
  }
  if (sendResult.value.status === 'SENT') {
    ElMessage.success('通知已发送');
  } else {
    ElMessage.warning(sendResult.value.message || '通知未发送');
  }
}

function syncDefaultChannel() {
  connectorForm.channelType = channelOptions.value[0]?.value;
}

function updateConnectorConfig(value: Record<string, unknown> | unknown[] | string) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return;
  connectorConfigTouched.value = true;
  connectorForm.config = value;
}

function updateTargetConfig(value: Record<string, unknown> | unknown[] | string) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return;
  targetConfigTouched.value = true;
  targetForm.targetConfig = value;
}

function emptyConnector(): NotificationConnector {
  return {
    connectorCode: '',
    connectorName: '',
    provider: 'DINGTALK',
    channelType: 'DINGTALK_ROBOT',
    authType: 'WEBHOOK',
    config: {},
    status: 'enabled',
    displayOrder: 0
  };
}

function emptyTarget(): NotificationTarget {
  return {
    targetAlias: '',
    targetName: '',
    targetType: 'GROUP',
    connectorCode: selectedConnectorCode.value,
    provider: selectedConnector.value?.provider,
    targetConfig: {},
    status: 'enabled',
    displayOrder: 0
  };
}

function emptyTemplate(): NotificationTemplate {
  return {
    templateCode: '',
    templateName: '',
    connectorCode: selectedConnectorCode.value,
    provider: selectedConnector.value?.provider,
    messageType: 'text',
    titleTemplate: '',
    contentTemplate: '',
    variableSchema: { type: 'object', required: [] },
    riskLevel: 'LOW',
    confirmRequired: true,
    status: 'enabled',
    displayOrder: 0
  };
}

function emptyAuthorization(): NotificationAuthorization {
  return {
    agentId: '',
    skillCode: '',
    skillVersionId: '',
    resourceKey: '',
    targetAlias: '',
    templateCode: '',
    confirmPolicy: 'ALWAYS',
    status: 'enabled',
    displayOrder: 0
  };
}

function emptySendRequest(): NotificationSendRequest {
  return {
    skillCode: '',
    skillVersionId: '',
    resourceKey: 'notification.send',
    targetAlias: '',
    templateCode: '',
    variables: {},
    idempotencyKey: randomKey(),
    confirmed: false
  };
}

function assign<T extends object>(target: T, source: object) {
  const record = target as Record<string, unknown>;
  Object.keys(record).forEach(key => Reflect.deleteProperty(record, key));
  Object.assign(target, JSON.parse(JSON.stringify(source || {})));
}

function compact(source: object) {
  return Object.entries(source).reduce<Record<string, unknown>>((result, [key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      result[key] = value;
    }
    return result;
  }, {});
}

function showError(error: unknown, fallback: string) {
  if (shouldShowLocalApiError(error)) {
    ElMessage.error(extractApiErrorMessage(error, fallback));
  }
}

function randomKey() {
  const random = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `notification-${random}`;
}

async function confirmDelete(message: string) {
  await ElMessageBox.confirm(message, '删除确认', {
    type: 'warning',
    confirmButtonText: '删除',
    cancelButtonText: '取消'
  });
}
</script>

<style scoped>
.notification-center-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.notification-center {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}

.notification-toolbar-card {
  flex: 0 0 auto;
}

.notification-toolbar,
.detail-header,
.table-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.notification-toolbar h1,
.detail-header h2 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 22px;
  font-weight: 600;
  line-height: 28px;
  letter-spacing: 0;
}

.notification-toolbar p {
  margin: 4px 0 0;
  color: #687387;
  font-size: 13px;
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.notification-layout {
  display: grid;
  grid-template-columns: minmax(260px, 300px) minmax(0, 1fr);
  gap: 8px;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.connector-list,
.connector-detail {
  overflow: hidden;
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.connector-list {
  display: flex;
  overflow-y: auto;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  scrollbar-color: transparent transparent;
  scrollbar-width: thin;
}

.connector-list-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.connector-item {
  display: block;
  width: 100%;
  min-height: 82px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
  padding: 12px;
  color: inherit;
  text-align: left;
  transition:
    border-color 0.18s ease,
    background 0.18s ease,
    box-shadow 0.18s ease;
}

.connector-item:hover,
.connector-item.active {
  border-color: var(--el-color-primary);
  background: color-mix(in srgb, var(--el-color-primary) 7%, var(--el-bg-color));
  box-shadow: 0 8px 18px rgb(64 158 255 / 12%);
}

.connector-item:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
}

.connector-name {
  display: block;
  min-width: 0;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.connector-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}

.connector-code {
  display: block;
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.connector-detail {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  padding: 16px;
  scrollbar-color: transparent transparent;
  scrollbar-width: thin;
}

.connector-list::-webkit-scrollbar,
.connector-detail::-webkit-scrollbar {
  width: 4px;
  height: 4px;
}

.connector-list::-webkit-scrollbar-button,
.connector-detail::-webkit-scrollbar-button {
  -webkit-appearance: none;
  appearance: none;
  display: none;
  width: 0;
  height: 0;
  background: transparent;
}

.connector-list::-webkit-scrollbar-corner,
.connector-list::-webkit-scrollbar-track-piece,
.connector-list::-webkit-scrollbar-track,
.connector-detail::-webkit-scrollbar-corner,
.connector-detail::-webkit-scrollbar-track-piece,
.connector-detail::-webkit-scrollbar-track {
  background: transparent;
}

.connector-list::-webkit-scrollbar-thumb,
.connector-detail::-webkit-scrollbar-thumb {
  border-radius: 999px;
  background-color: transparent;
}

.connector-list:hover,
.connector-detail:hover {
  scrollbar-color: rgba(144, 147, 153, 0.22) transparent;
}

.connector-list:hover::-webkit-scrollbar-thumb,
.connector-list:hover::-webkit-scrollbar-thumb:hover,
.connector-list:hover::-webkit-scrollbar-thumb:active,
.connector-detail:hover::-webkit-scrollbar-thumb,
.connector-detail:hover::-webkit-scrollbar-thumb:hover,
.connector-detail:hover::-webkit-scrollbar-thumb:active {
  background-color: rgba(144, 147, 153, 0.22);
}

.detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.detail-header {
  flex: 0 0 auto;
}

.detail-tabs {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column !important;
  margin-top: 12px;
  min-height: 0;
}

.detail-tabs :deep(.el-tabs__header) {
  order: 0;
  flex: 0 0 auto;
}

.detail-tabs :deep(.el-tabs__content) {
  order: 1;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.table-tab-pane {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.table-tab-pane .table-header,
.table-tab-pane .log-filters {
  flex: 0 0 auto;
}

.basic-tab-pane {
  overflow-y: auto;
  height: 100%;
  min-height: 0;
}

.notification-table-fill {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.basic-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 12px;
}

.kv {
  min-width: 0;
  border: 1px solid #edf0f5;
  border-radius: 8px;
  background: #fafbfe;
  padding: 10px 12px;
}

.kv span {
  display: block;
  color: #7a8497;
  font-size: 12px;
}

.kv strong {
  display: block;
  overflow: hidden;
  margin-top: 4px;
  color: #1f2a44;
  font-size: 14px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-header {
  margin-bottom: 10px;
  border-bottom: 0;
  padding: 0;
}

.table-header::after {
  display: none;
}

.log-filters {
  display: grid;
  grid-template-columns: repeat(5, minmax(120px, 1fr)) auto;
  gap: 8px;
  margin-bottom: 10px;
}

.preview-result {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border: 1px solid #e5e7ef;
  border-radius: 8px;
  background: #f8fafc;
  padding: 12px;
}

.preview-result p {
  margin: 0;
  color: #39445c;
  line-height: 1.6;
  white-space: pre-wrap;
}

@media (max-width: 1100px) {
  .notification-layout {
    grid-template-columns: 1fr;
  }

  .connector-list {
    max-height: 260px;
  }

  .basic-grid,
  .log-filters {
    grid-template-columns: 1fr 1fr;
  }
}
</style>
