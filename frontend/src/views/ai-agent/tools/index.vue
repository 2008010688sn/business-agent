<template>
  <BaseLayout class="tool-center-shell">
    <main class="tool-center-page">
      <ElCard>
        <header class="page-toolbar">
          <div>
            <h1>工具中心</h1>
            <p>管理 MCP/API 资源与不可变发布版本，写工具只能由确定性 FLOW 调用。</p>
          </div>
          <div class="toolbar-actions">
            <ElButton :loading="syncing" @click="syncMcp">
              <ElIcon><Refresh /></ElIcon>
              同步 MCP
            </ElButton>
            <ElButton type="primary" @click="openCreate">
              <ElIcon><Plus /></ElIcon>
              新建工具
            </ElButton>
          </div>
        </header>
      </ElCard>

      <ElCard class="tool-center-list-card">
        <section class="filter-panel">
          <ElForm :model="query" label-width="70px" @submit.prevent="loadPage">
            <ElRow :gutter="24">
              <ElCol :span="6">
                <ElFormItem label="搜索">
                  <ElInput v-model="query.keyword" clearable placeholder="名称、编码或服务" @keyup.enter="loadPage" />
                </ElFormItem>
              </ElCol>
              <ElCol :span="6">
                <ElFormItem label="类型">
                  <ElSelect v-model="query.resourceType" clearable placeholder="全部">
                    <ElOption
                      v-for="type in resourceTypes"
                      :key="type"
                      :label="resourceTypeLabel(type)"
                      :value="type"
                    />
                  </ElSelect>
                </ElFormItem>
              </ElCol>
              <ElCol :span="6">
                <ElFormItem label="状态">
                  <ElSelect v-model="query.status" clearable placeholder="全部">
                    <ElOption label="启用" value="enabled" />
                    <ElOption label="停用" value="disabled" />
                  </ElSelect>
                </ElFormItem>
              </ElCol>
              <ElButton type="primary" @click="loadPage">查询</ElButton>
              <ElButton @click="resetQuery">重置</ElButton>
            </ElRow>
          </ElForm>
        </section>

        <div class="tool-center-table-wrap">
          <ElTable v-loading="loading" :data="rows" stripe border row-key="resourceKey" height="100%">
            <ElTableColumn label="工具" min-width="260">
              <template #default="{ row }">
                <div class="primary-text">{{ row.resourceName || row.toolName }}</div>
                <div class="code-text">{{ row.resourceKey }}</div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="类型" width="130">
              <template #default="{ row }">{{ resourceTypeLabel(row.resourceType) }}</template>
            </ElTableColumn>
            <ElTableColumn label="服务 / 工具" min-width="220">
              <template #default="{ row }">
                <span>{{ row.serverCode || row.serviceName || '-' }}</span>
                <span v-if="row.toolName">/ {{ row.toolName }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="104">
              <template #default="{ row }">
                <ElTag :type="row.enabled && row.status !== 'disabled' ? 'success' : 'info'">
                  {{ row.enabled && row.status !== 'disabled' ? '启用' : '停用' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="displayOrder" label="排序" width="84" />
            <ElTableColumn label="操作" width="208" fixed="right">
              <template #default="{ row }">
                <div class="row-actions">
                  <ElTooltip content="详情与版本" placement="top">
                    <ElButton link type="primary" aria-label="详情与版本" @click="openDetail(row)">
                      <ElIcon><View /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip content="编辑资源" placement="top">
                    <ElButton link aria-label="编辑资源" @click="openEdit(row)">
                      <ElIcon><Edit /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip content="发布版本" placement="top">
                    <ElButton link type="success" aria-label="发布版本" @click="openPublish(row)">
                      <ElIcon><Promotion /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip :content="deleteToolDisabledReason(row)" placement="top">
                    <ElButton
                      link
                      type="danger"
                      aria-label="删除工具"
                      :disabled="!canDeleteTool(row)"
                      @click="deleteTool(row)"
                    >
                      <ElIcon><Delete /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                </div>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>
        <ElPagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          class="page-pagination"
          background
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50]"
          :total="total"
          @change="loadPage"
        />
      </ElCard>
    </main>

    <ElDialog
      v-model="resourceVisible"
      :title="editingKey ? `编辑工具 · ${editingKey}` : '新建工具'"
      width="min(900px, 94vw)"
      destroy-on-close
    >
      <ElForm
        ref="resourceFormRef"
        :model="resourceForm"
        :rules="resourceRules"
        label-position="top"
        class="resource-form-grid"
      >
        <ElFormItem label="资源编码" prop="resourceKey">
          <ElInput v-model="resourceForm.resourceKey" :disabled="Boolean(editingKey)" placeholder="service.toolName" />
        </ElFormItem>
        <ElFormItem label="资源名称" prop="resourceName"><ElInput v-model="resourceForm.resourceName" /></ElFormItem>
        <ElFormItem label="资源类型" prop="resourceType">
          <ElSelect v-model="resourceForm.resourceType" class="full-width">
            <ElOption v-for="type in resourceTypes" :key="type" :label="resourceTypeLabel(type)" :value="type" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSwitch v-model="resourceForm.enabled" inline-prompt active-text="启用" inactive-text="停用" />
        </ElFormItem>
        <ElFormItem v-if="showsResourceField('serverCode')" label="MCP 服务" prop="serverCode">
          <ElSelect
            v-model="resourceForm.serverCode"
            class="full-width"
            clearable
            filterable
            :loading="mcpServersLoading"
            placeholder="请选择 MCP 服务"
            @change="handleMcpServerChange"
            @clear="handleMcpServerClear"
            @visible-change="handleMcpServerVisible"
          >
            <ElOption
              v-for="server in mcpServers"
              :key="server.serverCode"
              :label="formatMcpServerLabel(server)"
              :value="server.serverCode"
            >
              <div class="mcp-server-option">
                <span class="mcp-server-code">{{ server.serverCode }}</span>
                <span class="mcp-server-name">{{ server.serviceName || '-' }}</span>
              </div>
            </ElOption>
          </ElSelect>
        </ElFormItem>
        <ElFormItem v-if="showsResourceField('serviceName')" label="服务名">
          <ElInput
            v-model="resourceForm.serviceName"
            :disabled="resourceForm.resourceType === 'MCP_TOOL'"
            placeholder="选择服务后自动带出"
          />
        </ElFormItem>
        <ElFormItem v-if="showsResourceField('toolName')" label="工具名">
          <ElInput v-model="resourceForm.toolName" />
        </ElFormItem>
        <ElFormItem v-if="showsResourceField('httpMethod')" label="HTTP 方法">
          <ElSelect v-model="resourceForm.httpMethod" class="full-width">
            <ElOption
              v-for="method in ['GET', 'POST', 'PUT', 'DELETE']"
              :key="method"
              :label="method"
              :value="method"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem v-if="showsResourceField('baseUrl')" label="基础地址">
          <ElInput v-model="resourceForm.baseUrl" />
        </ElFormItem>
        <ElFormItem v-if="showsResourceField('endpointUrl')" label="端点">
          <ElInput v-model="resourceForm.endpointUrl" />
        </ElFormItem>
        <ElFormItem label="排序">
          <ElInputNumber v-model="resourceForm.displayOrder" :min="0" controls-position="right" />
        </ElFormItem>
        <ElFormItem label="扩展配置（JSON）" class="span-2">
          <JsonObjectEditor v-model="resourceForm.extConfig" :rows="8" @validity-change="resourceJsonValid = $event" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="resourceVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" :disabled="!resourceJsonValid" @click="saveResource">保存</ElButton>
      </template>
    </ElDialog>

    <ElDialog
      v-model="publishVisible"
      :title="`发布工具版本 · ${publishingResource?.resourceKey || ''}`"
      width="min(920px, 94vw)"
      destroy-on-close
    >
      <ElAlert
        type="warning"
        :closable="false"
        show-icon
        title="发布版本是不可变快照；已发布 Skill 会继续使用固定版本。"
      />
      <ElForm :model="publishForm" label-position="top" class="publish-form-grid">
        <ElFormItem label="访问模式">
          <ElSegmented v-model="publishForm.accessMode" :options="accessModeOptions" @change="handleAccessMode" />
        </ElFormItem>
        <ElFormItem label="暴露模式">
          <ElSegmented v-model="publishForm.exposureMode" :options="exposureOptions" />
        </ElFormItem>
        <ElFormItem label="权限编码">
          <ElInput v-model="publishForm.permissionCode" placeholder="例如 demand:create" />
        </ElFormItem>
        <ElFormItem label="超时（毫秒）">
          <ElInputNumber v-model="publishForm.timeoutMs" :min="1000" :max="300000" controls-position="right" />
        </ElFormItem>
        <ElFormItem label="执行确认">
          <ElSwitch v-model="publishForm.confirmRequired" :disabled="publishForm.accessMode === 'WRITE'" />
        </ElFormItem>
        <ElFormItem label="幂等要求"><ElSwitch v-model="publishForm.idempotencyRequired" /></ElFormItem>
        <ElFormItem label="输入结构（JSON Schema）" class="span-2">
          <JsonObjectEditor
            v-model="publishForm.inputSchema"
            :rows="9"
            @validity-change="publishJsonValidity.inputSchema = $event"
          />
        </ElFormItem>
        <ElFormItem label="输出结构（JSON Schema）" class="span-2">
          <JsonObjectEditor
            v-model="publishForm.outputSchema"
            :rows="9"
            @validity-change="publishJsonValidity.outputSchema = $event"
          />
        </ElFormItem>
        <ElFormItem label="运行时参数映射" class="span-2">
          <JsonObjectEditor
            v-model="runtimeMappings"
            :rows="5"
            placeholder='{"idempotencyKey":"request.idempotencyKey"}'
            @validity-change="publishJsonValidity.runtimeMappings = $event"
          />
        </ElFormItem>
        <ElFormItem label="返回值映射" class="span-2">
          <JsonObjectEditor
            v-model="publishForm.responseMappings"
            :rows="5"
            @validity-change="publishJsonValidity.responseMappings = $event"
          />
        </ElFormItem>
        <ElFormItem label="敏感字段" class="span-2">
          <ElSelect
            v-model="publishForm.sensitiveFields"
            multiple
            allow-create
            filterable
            class="full-width"
            placeholder="输入字段名后回车"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="publishVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="publishing" :disabled="!publishJsonValid" @click="publishVersion">
          发布版本
        </ElButton>
      </template>
    </ElDialog>

    <ElDrawer v-model="detailVisible" title="工具详情" size="min(760px, 94vw)" destroy-on-close>
      <template v-if="detail">
        <ElDescriptions :column="2" border>
          <ElDescriptionsItem label="名称">{{ detail.resource.resourceName }}</ElDescriptionsItem>
          <ElDescriptionsItem label="编码">
            <span class="code-text">{{ detail.resource.resourceKey }}</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="类型">{{ resourceTypeLabel(detail.resource.resourceType) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="服务">
            {{ detail.resource.serverCode || detail.resource.serviceName || '-' }}
          </ElDescriptionsItem>
        </ElDescriptions>
        <h3 class="version-title">已发布版本</h3>
        <ElTable :data="detail.versions" border stripe>
          <ElTableColumn label="版本" width="90">
            <template #default="{ row }">v{{ row.versionNo }}</template>
          </ElTableColumn>
          <ElTableColumn label="访问" width="90">
            <template #default="{ row }">{{ accessModeLabel(row.accessMode) }}</template>
          </ElTableColumn>
          <ElTableColumn label="暴露" width="120">
            <template #default="{ row }">{{ exposureModeLabel(row.exposureMode) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="permissionCode" label="权限" min-width="150" />
          <ElTableColumn label="确认 / 幂等" min-width="130">
            <template #default="{ row }">
              {{ row.confirmRequired ? '确认' : '免确认' }} / {{ row.idempotencyRequired ? '幂等' : '非幂等' }}
            </template>
          </ElTableColumn>
          <ElTableColumn prop="publishedAt" label="发布时间" min-width="170" />
          <ElTableColumn label="操作" width="128" fixed="right">
            <template #default="{ row }">
              <ElTooltip
                :content="row.accessMode === 'READ' ? '测试只读版本' : '写工具禁止在工具中心测试'"
                placement="top"
              >
                <ElButton
                  link
                  type="primary"
                  aria-label="测试工具版本"
                  :disabled="row.accessMode !== 'READ'"
                  @click="openToolTest(row)"
                >
                  <ElIcon><VideoPlay /></ElIcon>
                </ElButton>
              </ElTooltip>
              <ElTooltip content="复制为新版本" placement="top">
                <ElButton link aria-label="复制为新版本" @click="copyVersionForPublish(row)">
                  <ElIcon><CopyDocument /></ElIcon>
                </ElButton>
              </ElTooltip>
            </template>
          </ElTableColumn>
        </ElTable>
      </template>
    </ElDrawer>

    <ElDrawer
      v-model="testVisible"
      :title="`工具测试 · ${detail?.resource.resourceKey || ''}`"
      size="min(720px, 94vw)"
      destroy-on-close
    >
      <ElAlert
        type="info"
        :closable="false"
        show-icon
        :title="`固定版本 v${testingVersion?.versionNo || ''}，仅执行 READ 工具并保留当前用户与数据权限。`"
      />
      <ElForm label-position="top" class="test-form">
        <ElFormItem label="调用参数">
          <JsonObjectEditor v-model="testArguments" :rows="12" @validity-change="testArgumentsValid = $event" />
        </ElFormItem>
        <ElButton type="primary" :loading="testing" :disabled="!testArgumentsValid" @click="runToolTest">
          执行测试
        </ElButton>
        <ElFormItem v-if="testResult" label="脱敏结果" class="test-result">
          <ElInput :model-value="JSON.stringify(testResult, null, 2)" type="textarea" :rows="14" readonly />
        </ElFormItem>
      </ElForm>
    </ElDrawer>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, toRaw } from 'vue';
import { CopyDocument, Delete, Edit, Plus, Promotion, Refresh, VideoPlay, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import toolService, {
  type McpServer,
  type ToolDetail,
  type ToolResource,
  type ToolVersion,
  type ToolVersionPublishPayload
} from '@/views/ai-agent/services/tool';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';

defineOptions({ name: 'ToolCenter' });
type ResourceConfigField = 'serverCode' | 'serviceName' | 'baseUrl' | 'toolName' | 'endpointUrl' | 'httpMethod';
interface ResourceTypeConfig {
  label: string;
  fields: ResourceConfigField[];
}
const resourceConfigFields: ResourceConfigField[] = [
  'serverCode',
  'serviceName',
  'baseUrl',
  'toolName',
  'endpointUrl',
  'httpMethod'
];
const resourceTypeConfigs: Record<string, ResourceTypeConfig> = {
  MCP_TOOL: { label: 'MCP 工具', fields: ['serverCode', 'serviceName', 'toolName'] },
  INTERNAL_API: { label: '内部接口', fields: ['serviceName', 'httpMethod', 'endpointUrl'] },
  HTTP: { label: 'HTTP 接口', fields: ['baseUrl', 'httpMethod', 'endpointUrl'] },
  WEBHOOK: { label: 'Webhook', fields: ['baseUrl', 'httpMethod', 'endpointUrl'] }
};
const manualSourceType = 'MANUAL';
const mcpToolResourceType = 'MCP_TOOL';
const resourceTypes = Object.keys(resourceTypeConfigs);
const accessModeOptions = [
  { label: '只读', value: 'READ' },
  { label: '写入', value: 'WRITE' }
];
const exposureModeLabels: Record<string, string> = {
  MODEL: '模型可调用',
  FLOW_ONLY: '仅流程调用'
};
const resourceTypeLabel = (value: string) => resourceTypeConfigs[value]?.label || value;
const accessModeLabel = (value: string) => (value === 'READ' ? '只读' : value === 'WRITE' ? '写入' : value);
const exposureModeLabel = (value: string) => exposureModeLabels[value] || value;
const query = reactive({ current: 1, size: 20, keyword: '', resourceType: '', status: '' });
const rows = ref<ToolResource[]>([]);
const total = ref(0);
const loading = ref(false);
const syncing = ref(false);
const mcpServers = ref<McpServer[]>([]);
const mcpServersLoaded = ref(false);
const mcpServersLoading = ref(false);
const saving = ref(false);
const publishing = ref(false);
const resourceVisible = ref(false);
const publishVisible = ref(false);
const detailVisible = ref(false);
const editingKey = ref('');
const publishingResource = ref<ToolResource | null>(null);
const detail = ref<ToolDetail | null>(null);
const testVisible = ref(false);
const testingVersion = ref<ToolVersion | null>(null);
const testing = ref(false);
const testArguments = ref<Record<string, unknown>>({});
const testResult = ref<Record<string, unknown> | null>(null);
const resourceJsonValid = ref(true);
const testArgumentsValid = ref(true);
const resourceFormRef = ref<FormInstance>();
const emptyResource = (): ToolResource => ({
  resourceType: 'MCP_TOOL',
  resourceKey: '',
  resourceName: '',
  serverCode: '',
  serviceName: '',
  baseUrl: '',
  toolName: '',
  endpointUrl: '',
  httpMethod: 'POST',
  enabled: true,
  status: 'enabled',
  displayOrder: 0,
  extConfig: {}
});
const normalizeObjectField = (value: unknown) =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
const normalizeText = (value: unknown) => (value === null || value === undefined ? '' : String(value).trim());
const getToolSourceType = (row: ToolResource) => {
  const extConfig = normalizeObjectField(row.extConfig);
  const sourceType = [row.sourceType, row.source, extConfig.sourceType, extConfig.source]
    .map(normalizeText)
    .find(Boolean);
  return sourceType ? sourceType.toUpperCase() : '';
};
const isMcpTool = (row: ToolResource) => row.resourceType?.toUpperCase() === mcpToolResourceType;
const canDeleteTool = (row: ToolResource) => {
  if (isMcpTool(row)) {
    return false;
  }
  const sourceType = getToolSourceType(row);
  return !sourceType || sourceType === manualSourceType;
};
const deleteToolDisabledReason = (row: ToolResource) => {
  if (isMcpTool(row)) {
    return 'MCP Tool 由同步目录维护，不支持在工具中心删除';
  }
  const sourceType = getToolSourceType(row);
  if (sourceType && sourceType !== manualSourceType) {
    return '非手动配置的工具不支持删除';
  }
  return '删除工具';
};
const normalizeResourceForm = (row?: ToolResource): ToolResource => {
  const base = emptyResource();
  if (!row) {
    return base;
  }

  const cloned = JSON.parse(JSON.stringify(toRaw(row))) as Partial<ToolResource>;

  return {
    ...base,
    ...cloned,
    headerTemplate: normalizeObjectField(cloned.headerTemplate),
    paramMapping: normalizeObjectField(cloned.paramMapping),
    requestTemplate: normalizeObjectField(cloned.requestTemplate),
    responseMapping: normalizeObjectField(cloned.responseMapping),
    extConfig: normalizeObjectField(cloned.extConfig),
    enabled: cloned.enabled ?? base.enabled,
    status: cloned.status ?? base.status,
    displayOrder: cloned.displayOrder ?? base.displayOrder,
    httpMethod: cloned.httpMethod || base.httpMethod,
    resourceType: cloned.resourceType || base.resourceType,
    resourceKey: cloned.resourceKey || base.resourceKey,
    resourceName: cloned.resourceName || base.resourceName
  };
};
const resourceForm = reactive<ToolResource>(emptyResource());
const visibleResourceFields = computed(() => new Set(resourceTypeConfigs[resourceForm.resourceType]?.fields ?? []));
const showsResourceField = (field: ResourceConfigField) => visibleResourceFields.value.has(field);
const formatMcpServerLabel = (server: McpServer) =>
  server.serviceName ? `${server.serverCode} · ${server.serviceName}` : server.serverCode;
const loadMcpServers = async (force = false) => {
  if (mcpServersLoading.value || (!force && mcpServersLoaded.value)) return;
  mcpServersLoading.value = true;
  try {
    mcpServers.value = await toolService.listMcpServers();
    mcpServersLoaded.value = true;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '加载 MCP 服务失败'));
  } finally {
    mcpServersLoading.value = false;
  }
};
const applyMcpServerSelection = (serverCode: string, clearMissing = true) => {
  const selected = mcpServers.value.find(server => server.serverCode === serverCode);
  if (selected) {
    resourceForm.serviceName = selected.serviceName || '';
  } else if (!serverCode || clearMissing) {
    resourceForm.serviceName = '';
  }
};
const handleMcpServerVisible = (visible: boolean) => {
  if (visible) {
    loadMcpServers();
  }
};
const handleMcpServerChange = (value: string | number | boolean | undefined) => {
  applyMcpServerSelection(typeof value === 'string' ? value : '');
};
const handleMcpServerClear = () => {
  resourceForm.serviceName = '';
};
const buildResourcePayload = (): ToolResource => {
  const payload = normalizeResourceForm(resourceForm);
  resourceConfigFields.forEach(field => {
    if (!visibleResourceFields.value.has(field)) payload[field] = undefined;
  });
  payload.source = undefined;
  payload.sourceType = undefined;
  if (!editingKey.value && !isMcpTool(payload) && !getToolSourceType(payload)) {
    payload.extConfig = { ...payload.extConfig, sourceType: manualSourceType };
  }
  return payload;
};
const resourceRules: FormRules = {
  resourceKey: [{ required: true, message: '请输入资源编码', trigger: 'blur' }],
  resourceName: [{ required: true, message: '请输入资源名称', trigger: 'blur' }],
  resourceType: [{ required: true }],
  serverCode: [{ required: true, message: '请选择 MCP 服务', trigger: 'change' }]
};
const emptyPublish = (): ToolVersionPublishPayload => ({
  accessMode: 'READ',
  exposureMode: 'MODEL',
  permissionCode: '',
  confirmRequired: false,
  idempotencyRequired: false,
  timeoutMs: 30000,
  inputSchema: {},
  outputSchema: {},
  runtimeParamMappings: {},
  responseMappings: {},
  sensitiveFields: []
});
const parseObjectValue = (value: unknown): Record<string, unknown> => {
  if (value && typeof value === 'object' && !Array.isArray(value)) return JSON.parse(JSON.stringify(value));
  if (typeof value !== 'string' || !value.trim()) return {};
  try {
    return normalizeObjectField(JSON.parse(value));
  } catch {
    return {};
  }
};
const parseSensitiveFields = (value: unknown): string[] => {
  if (Array.isArray(value)) return value.map(item => String(item));
  if (typeof value !== 'string' || !value.trim()) return [];
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? parsed.map(item => String(item)) : [];
  } catch {
    return value
      .split(',')
      .map(item => item.trim())
      .filter(Boolean);
  }
};
const publishForm = reactive<ToolVersionPublishPayload>(emptyPublish());
const publishJsonValidity = reactive({
  inputSchema: true,
  outputSchema: true,
  runtimeMappings: true,
  responseMappings: true
});
const publishJsonValid = computed(() => Object.values(publishJsonValidity).every(Boolean));
const runtimeMappings = computed<Record<string, unknown>>({
  get: () => publishForm.runtimeParamMappings,
  set: value => {
    publishForm.runtimeParamMappings = Object.fromEntries(
      Object.entries(value).map(([key, item]) => [key, String(item)])
    );
  }
});
const exposureOptions = computed(() =>
  (publishForm.accessMode === 'WRITE' ? ['FLOW_ONLY'] : ['MODEL', 'FLOW_ONLY']).map(value => ({
    label: exposureModeLabel(value),
    value
  }))
);

const resetPublishJsonValidity = () => {
  Object.keys(publishJsonValidity).forEach(key => {
    publishJsonValidity[key as keyof typeof publishJsonValidity] = true;
  });
};

const loadPage = async () => {
  loading.value = true;
  try {
    const page = await toolService.page(query);
    rows.value = page.data;
    total.value = page.total;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '加载工具失败'));
  } finally {
    loading.value = false;
  }
};
const resetQuery = () => {
  Object.assign(query, { current: 1, keyword: '', resourceType: '', status: '' });
  loadPage();
};
const openCreate = () => {
  editingKey.value = '';
  Object.assign(resourceForm, emptyResource());
  resourceJsonValid.value = true;
  resourceVisible.value = true;
  loadMcpServers();
};
const openEdit = (row: ToolResource) => {
  editingKey.value = row.resourceKey;
  Object.assign(resourceForm, normalizeResourceForm(row));
  resourceJsonValid.value = true;
  resourceVisible.value = true;
  if (resourceForm.resourceType === 'MCP_TOOL') {
    loadMcpServers().then(() => applyMcpServerSelection(resourceForm.serverCode || '', false));
  }
};
const saveResource = async () => {
  if (!resourceJsonValid.value) {
    ElMessage.warning('请先修正扩展配置中的 JSON 格式错误');
    return;
  }
  if (!(await resourceFormRef.value?.validate().catch(() => false))) return;
  saving.value = true;
  try {
    const payload = buildResourcePayload();
    if (editingKey.value) await toolService.modify(editingKey.value, payload);
    else await toolService.create(payload);
    ElMessage.success('工具资源已保存');
    resourceVisible.value = false;
    await loadPage();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '保存工具失败'));
  } finally {
    saving.value = false;
  }
};
const openPublish = (row: ToolResource) => {
  publishingResource.value = row;
  Object.assign(publishForm, emptyPublish());
  resetPublishJsonValidity();
  publishVisible.value = true;
};
const copyVersionForPublish = (version: ToolVersion) => {
  if (!detail.value) return;
  publishingResource.value = detail.value.resource;
  const runtimeParamMappings = parseObjectValue(version.runtimeParamMappings);
  Object.assign(publishForm, {
    accessMode: version.accessMode === 'WRITE' ? 'WRITE' : 'READ',
    exposureMode: version.exposureMode === 'FLOW_ONLY' ? 'FLOW_ONLY' : 'MODEL',
    permissionCode: version.permissionCode || '',
    confirmRequired: Boolean(version.confirmRequired),
    idempotencyRequired: Boolean(version.idempotencyRequired),
    timeoutMs: version.timeoutMs || 30000,
    inputSchema: parseObjectValue(version.inputSchema),
    outputSchema: parseObjectValue(version.outputSchema),
    runtimeParamMappings: Object.fromEntries(
      Object.entries(runtimeParamMappings).map(([key, value]) => [key, String(value)])
    ),
    responseMappings: parseObjectValue(version.responseMappings),
    sensitiveFields: parseSensitiveFields(version.sensitiveFields)
  });
  resetPublishJsonValidity();
  detailVisible.value = false;
  publishVisible.value = true;
};
const handleAccessMode = () => {
  if (publishForm.accessMode === 'WRITE') {
    publishForm.exposureMode = 'FLOW_ONLY';
    publishForm.confirmRequired = true;
    publishForm.idempotencyRequired = true;
  } else {
    publishForm.confirmRequired = false;
  }
};
const publishVersion = async () => {
  if (!publishingResource.value) return;
  if (!publishJsonValid.value) {
    ElMessage.warning('请先修正工具版本配置中的 JSON 格式错误');
    return;
  }
  if (publishForm.accessMode === 'WRITE' && !publishForm.permissionCode?.trim()) {
    ElMessage.warning('写工具必须配置权限编码');
    return;
  }
  publishing.value = true;
  try {
    await toolService.publish(publishingResource.value.resourceKey, publishForm);
    ElMessage.success('工具版本已发布');
    publishVisible.value = false;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '发布工具版本失败'));
  } finally {
    publishing.value = false;
  }
};
const deleteTool = async (row: ToolResource) => {
  if (!canDeleteTool(row)) {
    ElMessage.warning(deleteToolDisabledReason(row));
    return;
  }
  try {
    await ElMessageBox.confirm(`确认删除工具「${row.resourceName || row.resourceKey}」？`, '删除工具', {
      type: 'warning'
    });
  } catch {
    return;
  }

  try {
    await toolService.delete(row.resourceKey);
    ElMessage.success('工具已删除');
    await loadPage();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '删除工具失败'));
  }
};
const openDetail = async (row: ToolResource) => {
  detailVisible.value = true;
  detail.value = null;
  try {
    detail.value = await toolService.detail(row.resourceKey);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '加载工具详情失败'));
  }
};
const openToolTest = (version: ToolVersion) => {
  if (version.accessMode !== 'READ') return;
  testingVersion.value = version;
  testArguments.value = {};
  testResult.value = null;
  testArgumentsValid.value = true;
  testVisible.value = true;
};
const runToolTest = async () => {
  if (!detail.value || !testingVersion.value) return;
  if (!testArgumentsValid.value) {
    ElMessage.warning('请先修正调用参数中的 JSON 格式错误');
    return;
  }
  testing.value = true;
  try {
    testResult.value = await toolService.test(detail.value.resource.resourceKey, {
      resourceVersionId: testingVersion.value.id,
      arguments: testArguments.value
    });
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '工具测试失败'));
  } finally {
    testing.value = false;
  }
};
const syncMcp = async () => {
  syncing.value = true;
  try {
    await toolService.syncMcp();
    await loadMcpServers(true);
    ElMessage.success('MCP 工具同步完成');
    await loadPage();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '同步 MCP 工具失败'));
  } finally {
    syncing.value = false;
  }
};
onMounted(() => {
  loadPage();
});
</script>

<style scoped>
.tool-center-shell,
.tool-center-page {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}
.tool-center-page {
  gap: 8px;
}

.page-toolbar,
.toolbar-actions {
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

.row-actions {
  display: flex;
  gap: 8px;
}

.filter-panel {
  margin-bottom: 10px;
}

.filter-panel :deep(.el-form-item) {
  margin-bottom: 10px;
}

.filter-panel :deep(.el-input),
.filter-panel :deep(.el-select) {
  width: 100%;
}

.filter-panel :deep(.el-form-item__content) {
  gap: 8px;
  flex-wrap: nowrap;
}

.tool-center-list-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.tool-center-list-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.tool-center-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.tool-center-table-wrap :deep(.el-table) {
  height: 100%;
}

.primary-text {
  font-weight: 600;
}
.code-text {
  color: var(--el-text-color-secondary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
.page-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
.resource-form-grid,
.publish-form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
  margin-top: 14px;
}
.span-2 {
  grid-column: 1/-1;
}
.full-width {
  width: 100%;
}
.mcp-server-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.mcp-server-code {
  font-weight: 600;
}
.mcp-server-name {
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.version-title {
  margin: 22px 0 10px;
  font-size: 16px;
}
.test-form {
  margin-top: 16px;
}
.test-result {
  margin-top: 16px;
}
@media (max-width: 820px) {
  .page-toolbar,
  .filter-panel {
    align-items: stretch;
    flex-direction: column;
  }
  .resource-form-grid,
  .publish-form-grid {
    grid-template-columns: 1fr;
  }
  .span-2 {
    grid-column: auto;
  }
}
</style>
