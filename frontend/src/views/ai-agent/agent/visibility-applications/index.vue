<template>
  <BaseLayout class="agent-visibility-application-shell">
    <main class="agent-visibility-application-page">
      <ElCard>
        <header class="page-toolbar">
          <div>
            <h1>智能体审批管理</h1>
            <p>集中处理用户对话页 Agent 可见性申请，并查看全部申请记录。</p>
          </div>
          <ElButton :loading="loading" @click="reloadFirstPage">
            <ElIcon><Refresh /></ElIcon>
            刷新
          </ElButton>
        </header>
      </ElCard>

      <ElCard class="agent-visibility-content-card">
        <ElTabs v-model="activeTab" class="agent-visibility-tabs" @tab-change="handleTabChange">
          <ElTabPane label="待审批" name="pending" />
          <ElTabPane label="申请记录" name="records" />
        </ElTabs>

        <ElForm :model="query" label-width="70px" class="agent-visibility-filter">
          <ElRow :gutter="24">
            <ElCol :span="6">
              <ElFormItem label="Agent">
                <ElSelect
                  v-model="query.agentId"
                  clearable
                  filterable
                  remote
                  reserve-keyword
                  :loading="agentOptionsLoading"
                  :remote-method="searchAgentOptions"
                  placeholder="搜索 Agent 名称或 ID"
                  @change="handleAgentChange"
                  @visible-change="handleAgentDropdownVisible"
                >
                  <ElOption
                    v-for="option in agentOptions"
                    :key="option.id"
                    :label="agentOptionLabel(option)"
                    :value="option.id || ''"
                  />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="申请人">
                <ElInput
                  v-model="query.applicantNickName"
                  clearable
                  placeholder="申请人昵称"
                  @keyup.enter="reloadFirstPage"
                  @clear="reloadFirstPage"
                />
              </ElFormItem>
            </ElCol>
            <ElCol v-if="activeTab === 'records'" :span="6">
              <ElFormItem label="状态">
                <ElSelect v-model="query.status" clearable placeholder="全部状态" @change="reloadFirstPage">
                  <ElOption label="审批中" value="PENDING" />
                  <ElOption label="已通过" value="APPROVED" />
                  <ElOption label="已拒绝" value="REJECTED" />
                  <ElOption label="已取消" value="CANCELED" />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="审批模式">
                <ElSelect v-model="query.approvalMode" clearable placeholder="全部模式" @change="reloadFirstPage">
                  <ElOption label="本地审批" value="LOCAL" />
                  <ElOption label="流程审批" value="WORKFLOW" />
                </ElSelect>
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="业务编码">
                <ElInput
                  v-model="query.businessCode"
                  clearable
                  placeholder="业务编码"
                  @keyup.enter="reloadFirstPage"
                  @clear="reloadFirstPage"
                />
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElFormItem label="提交时间">
                <ElDatePicker
                  v-model="query.submitTimeRange"
                  type="datetimerange"
                  value-format="YYYY-MM-DDTHH:mm:ssZ"
                  range-separator="至"
                  start-placeholder="开始时间"
                  end-placeholder="结束时间"
                  clearable
                  @change="reloadFirstPage"
                />
              </ElFormItem>
            </ElCol>
            <ElCol :span="6">
              <ElButton type="primary" @click="reloadFirstPage">
                <ElIcon><Search /></ElIcon>
                查询
              </ElButton>
              <ElButton @click="resetQuery">
                <ElIcon><Refresh /></ElIcon>
                重置
              </ElButton>
            </ElCol>
          </ElRow>
        </ElForm>

        <div class="agent-visibility-table-wrap">
          <ElTable v-loading="loading" border stripe :data="applications" height="100%" empty-text="暂无申请记录">
            <ElTableColumn prop="agentName" label="Agent" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="agent-cell">
                  <ElButton type="primary" link @click="goAgent(row)">{{ row.agentName || '-' }}</ElButton>
                  <span>ID: {{ row.agentId || '-' }}</span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="applicantNickName" label="申请人" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">
                <div class="agent-cell">
                  <strong>{{ row.applicantNickName || '-' }}</strong>
                  <span>{{ row.applicantUserId || '-' }}</span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="reason" label="申请理由" min-width="240" show-overflow-tooltip />
            <ElTableColumn label="授权天数" width="100">
              <template #default="{ row }">{{ row.grantDays && row.grantDays > 0 ? row.grantDays : '长期' }}</template>
            </ElTableColumn>
            <ElTableColumn label="审批模式" width="110">
              <template #default="{ row }">{{ approvalModeLabel(row.approvalMode) }}</template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="110">
              <template #default="{ row }">
                <ElTag :type="applicationStatusTag(row.status)" effect="light">
                  {{ applicationStatusLabel(row.status) }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="approverNickName" label="审批人" min-width="120" show-overflow-tooltip />
            <ElTableColumn prop="workflowInstanceId" label="流程实例" min-width="150" show-overflow-tooltip />
            <ElTableColumn prop="businessCode" label="业务编码" min-width="220" show-overflow-tooltip />
            <ElTableColumn label="提交时间" min-width="170">
              <template #default="{ row }">{{ formatTime(row.submitTime || row.createTime) }}</template>
            </ElTableColumn>
            <ElTableColumn label="完成时间" min-width="170">
              <template #default="{ row }">{{ formatTime(row.finishTime) || '-' }}</template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <ElButton type="primary" text @click="openDetailDrawer(row)">详情</ElButton>
                <template v-if="canLocalAudit(row)">
                  <ElButton type="primary" text @click="openAuditDialog(row, 'approve')">通过</ElButton>
                  <ElButton type="danger" text @click="openAuditDialog(row, 'reject')">拒绝</ElButton>
                </template>
                <ElButton v-else-if="canOpenWorkflow(row)" type="primary" text @click="goWorkflow(row)">
                  去流程审批
                </ElButton>
              </template>
            </ElTableColumn>
          </ElTable>
        </div>

        <div class="tab-pagination">
          <ElPagination
            v-model:current-page="query.current"
            v-model:page-size="query.size"
            layout="total, sizes, prev, pager, next"
            :page-sizes="[10, 20, 50, 100]"
            :total="total"
            @size-change="reloadFirstPage"
            @current-change="loadApplications"
          />
        </div>
      </ElCard>

      <ElDialog v-model="auditDialogVisible" :title="auditAction === 'approve' ? '通过申请' : '拒绝申请'" width="520px">
        <ElForm label-position="top">
          <ElFormItem label="审批意见">
            <ElInput v-model="auditForm.approvalComment" type="textarea" :rows="4" maxlength="500" show-word-limit />
          </ElFormItem>
          <ElFormItem v-if="auditAction === 'approve'" label="授权天数">
            <ElInputNumber v-model="auditForm.grantDays" :min="0" :max="3650" controls-position="right" />
            <span class="form-hint">不填沿用申请天数，0 表示长期有效</span>
          </ElFormItem>
        </ElForm>
        <template #footer>
          <ElButton @click="auditDialogVisible = false">取消</ElButton>
          <ElButton
            :type="auditAction === 'approve' ? 'primary' : 'danger'"
            :loading="auditSaving"
            @click="submitAudit"
          >
            确定
          </ElButton>
        </template>
      </ElDialog>

      <ElDrawer v-model="detailDrawerVisible" title="申请详情" size="520px">
        <ElDescriptions v-if="selectedApplication" :column="1" border>
          <ElDescriptionsItem label="Agent">{{ selectedApplication.agentName || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="Agent ID">{{ selectedApplication.agentId || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="申请人">{{ selectedApplication.applicantNickName || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="申请人 ID">{{ selectedApplication.applicantUserId || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="申请理由">{{ selectedApplication.reason || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="申请时长">
            {{
              selectedApplication.grantDays && selectedApplication.grantDays > 0
                ? selectedApplication.grantDays
                : '长期'
            }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="审批模式">
            {{ approvalModeLabel(selectedApplication.approvalMode) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="申请状态">
            {{ applicationStatusLabel(selectedApplication.status) }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="流程编码">{{ selectedApplication.workflowFlowCode || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="流程实例">{{ selectedApplication.workflowInstanceId || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="流程状态">{{ selectedApplication.workflowStatus || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="业务编码">{{ selectedApplication.businessCode || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="审批人">{{ selectedApplication.approverNickName || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="审批意见">{{ selectedApplication.approvalComment || '-' }}</ElDescriptionsItem>
          <ElDescriptionsItem label="提交时间">
            {{ formatTime(selectedApplication.submitTime || selectedApplication.createTime) || '-' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="完成时间">
            {{ formatTime(selectedApplication.finishTime) || '-' }}
          </ElDescriptionsItem>
        </ElDescriptions>
      </ElDrawer>
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import agentVisibilityService, {
  type AgentVisibilityAgentOption,
  type AgentVisibilityApplication,
  type AgentVisibilityApplicationPageQuery
} from '@/views/ai-agent/services/visibility';

defineOptions({ name: 'AgentVisibilityApplications' });

const VISIBILITY_APPLICATION_ROUTE_NAME = 'ai-agent_agent_visibility-applications';

const route = useRoute();
const router = useRouter();

type AuditAction = 'approve' | 'reject';
type ApplicationTab = 'pending' | 'records';
type QueryState = {
  current: number;
  size: number;
  agentId: string;
  applicantNickName: string;
  status: string;
  approvalMode: string;
  businessCode: string;
  submitTimeRange: string[] | null;
};

const loading = ref(false);
const auditSaving = ref(false);
const agentOptionsLoading = ref(false);
const activeTab = ref<ApplicationTab>('pending');
const auditDialogVisible = ref(false);
const detailDrawerVisible = ref(false);
const auditAction = ref<AuditAction>('approve');
const selectedApplication = ref<AgentVisibilityApplication | null>(null);
const agentOptions = ref<AgentVisibilityAgentOption[]>([]);
const applications = ref<AgentVisibilityApplication[]>([]);
const total = ref(0);
const query = reactive<QueryState>({
  current: 1,
  size: 10,
  agentId: '',
  applicantNickName: '',
  status: 'PENDING',
  approvalMode: '',
  businessCode: '',
  submitTimeRange: []
});
const auditForm = reactive({
  approvalComment: '',
  grantDays: undefined as number | undefined
});

const firstQueryValue = (value: unknown) => {
  if (Array.isArray(value)) {
    return value[0] ? String(value[0]) : '';
  }
  return value === undefined || value === null ? '' : String(value);
};

const routeQuerySignature = () =>
  [
    String(route.name || ''),
    firstQueryValue(route.query.tab),
    firstQueryValue(route.query.agentId),
    firstQueryValue(route.query.status)
  ].join('|');

const applyRouteQuery = () => {
  activeTab.value = firstQueryValue(route.query.tab) === 'records' ? 'records' : 'pending';
  query.agentId = firstQueryValue(route.query.agentId);
  query.status = activeTab.value === 'pending' ? 'PENDING' : firstQueryValue(route.query.status);
};

const buildPageQuery = (): AgentVisibilityApplicationPageQuery => {
  const [submitTimeStart, submitTimeEnd] = Array.isArray(query.submitTimeRange) ? query.submitTimeRange : [];
  return {
    current: query.current,
    size: query.size,
    agentId: query.agentId.trim() || undefined,
    applicantNickName: query.applicantNickName.trim() || undefined,
    status: activeTab.value === 'pending' ? 'PENDING' : query.status || undefined,
    approvalMode: query.approvalMode || undefined,
    businessCode: query.businessCode.trim() || undefined,
    submitTimeStart,
    submitTimeEnd
  };
};

const agentOptionLabel = (option: AgentVisibilityAgentOption) => {
  const name = option.name || '-';
  const id = option.id || '-';
  return `${name}（${id}）`;
};

const mergeAgentOptions = (nextOptions: AgentVisibilityAgentOption[]) => {
  const optionMap = new Map<string, AgentVisibilityAgentOption>();
  for (const option of agentOptions.value) {
    if (option.id && option.id === query.agentId) {
      optionMap.set(option.id, option);
    }
  }
  for (const option of nextOptions) {
    if (option.id) {
      optionMap.set(option.id, option);
    }
  }
  agentOptions.value = Array.from(optionMap.values());
};

const loadAgentOptions = async (keyword = '', agentId = '') => {
  agentOptionsLoading.value = true;
  try {
    const page = await agentVisibilityService.queryApplicationAgentOptionsPage({
      current: 1,
      size: 20,
      agentId: agentId || undefined,
      keyword: agentId ? undefined : keyword.trim() || undefined
    });
    mergeAgentOptions(page.data);
  } catch (error) {
    console.error('加载 Agent 选项失败:', error);
    ElMessage.error('加载 Agent 选项失败');
  } finally {
    agentOptionsLoading.value = false;
  }
};

const searchAgentOptions = (keyword: string) => {
  loadAgentOptions(keyword).catch(error => {
    console.error('加载 Agent 选项失败:', error);
  });
};

const handleAgentDropdownVisible = (visible: boolean) => {
  if (!visible || agentOptions.value.length > 0) {
    return;
  }
  searchAgentOptions('');
};

const handleAgentChange = (value?: string) => {
  query.agentId = value ? String(value) : '';
  reloadFirstPage();
};

const handleTabChange = (name: string | number) => {
  activeTab.value = name === 'records' ? 'records' : 'pending';
  query.status = activeTab.value === 'pending' ? 'PENDING' : '';
  reloadFirstPage();
};

const loadApplications = async () => {
  loading.value = true;
  try {
    const page = await agentVisibilityService.queryApplicationsPage(buildPageQuery());
    applications.value = page.data;
    total.value = page.total;
    query.current = page.pageNum || query.current;
    query.size = page.pageSize || query.size;
  } catch (error) {
    console.error('加载 Agent 申请列表失败:', error);
    ElMessage.error('加载申请列表失败');
  } finally {
    loading.value = false;
  }
};

const reloadFirstPage = () => {
  query.current = 1;
  loadApplications().catch(error => {
    console.error('加载 Agent 申请列表失败:', error);
  });
};

const resetQuery = () => {
  Object.assign(query, {
    current: 1,
    size: query.size,
    agentId: '',
    applicantNickName: '',
    status: activeTab.value === 'pending' ? 'PENDING' : '',
    approvalMode: '',
    businessCode: '',
    submitTimeRange: []
  });
  loadApplications().catch(error => {
    console.error('加载 Agent 申请列表失败:', error);
  });
};

const canLocalAudit = (row: AgentVisibilityApplication) => {
  return activeTab.value === 'pending' && row.status === 'PENDING' && row.approvalMode === 'LOCAL';
};

const canOpenWorkflow = (row: AgentVisibilityApplication) => {
  return activeTab.value === 'pending' && row.status === 'PENDING' && row.approvalMode === 'WORKFLOW';
};

const openAuditDialog = (row: AgentVisibilityApplication, action: AuditAction) => {
  selectedApplication.value = row;
  auditAction.value = action;
  auditForm.approvalComment = '';
  auditForm.grantDays = undefined;
  auditDialogVisible.value = true;
};

const submitAudit = async () => {
  const application = selectedApplication.value;
  if (!application?.id) {
    return;
  }
  auditSaving.value = true;
  try {
    if (auditAction.value === 'approve') {
      await agentVisibilityService.approveApplication(application.id, {
        approvalComment: auditForm.approvalComment.trim(),
        grantDays: auditForm.grantDays
      });
      ElMessage.success('申请已通过');
    } else {
      await agentVisibilityService.rejectApplication(application.id, {
        approvalComment: auditForm.approvalComment.trim()
      });
      ElMessage.success('申请已拒绝');
    }
    auditDialogVisible.value = false;
    await loadApplications();
  } catch (error) {
    console.error('处理 Agent 申请失败:', error);
    ElMessage.error('处理申请失败');
  } finally {
    auditSaving.value = false;
  }
};

const openDetailDrawer = (row: AgentVisibilityApplication) => {
  selectedApplication.value = row;
  detailDrawerVisible.value = true;
};

const goAgent = (row: AgentVisibilityApplication) => {
  if (!row.agentId) {
    ElMessage.warning('Agent ID 无效');
    return;
  }
  router.push({
    name: 'ai-agent_agent_detail',
    query: {
      id: String(row.agentId),
      tab: 'visibility-config'
    }
  });
};

const goWorkflow = (row: AgentVisibilityApplication) => {
  if (!row.workflowInstanceId) {
    ElMessage.warning('流程实例 ID 为空');
    return;
  }
  router.push({
    path: '/workflow/my-task',
    query: {
      id: String(row.workflowInstanceId)
    }
  });
};

const formatTime = (value?: string) => {
  if (!value) {
    return '';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString();
};

const applicationStatusLabel = (status?: string) => {
  const map: Record<string, string> = {
    PENDING: '审批中',
    APPROVED: '已通过',
    REJECTED: '已拒绝',
    CANCELED: '已取消'
  };
  return map[status || ''] || status || '-';
};

const applicationStatusTag = (status?: string) => {
  const map: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
    PENDING: 'warning',
    APPROVED: 'success',
    REJECTED: 'danger',
    CANCELED: 'info'
  };
  return map[status || ''] || 'info';
};

const approvalModeLabel = (mode?: string) => {
  const map: Record<string, string> = {
    LOCAL: '本地审批',
    WORKFLOW: '流程审批'
  };
  return map[mode || ''] || mode || '-';
};

watch(routeQuerySignature, signature => {
  if (!signature.startsWith(`${VISIBILITY_APPLICATION_ROUTE_NAME}|`)) {
    return;
  }
  applyRouteQuery();
  query.current = 1;
  loadApplications().catch(error => {
    console.error('加载 Agent 申请列表失败:', error);
  });
});

onMounted(() => {
  applyRouteQuery();
  loadApplications().catch(error => {
    console.error('加载 Agent 申请列表失败:', error);
  });
});
</script>

<style scoped>
.agent-visibility-application-shell {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.agent-visibility-application-page {
  box-sizing: border-box;
  display: flex;
  overflow: hidden;
  flex-direction: column;
  gap: 8px;
  height: calc(100dvh - 104px);
  min-height: 0;
}

.agent-visibility-content-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.agent-visibility-content-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.page-toolbar,
.filter-actions {
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

.agent-visibility-tabs {
  flex: 0 0 auto;
}

.agent-visibility-tabs :deep(.el-tabs__header) {
  margin: 0;
}

.agent-visibility-filter {
  flex: 0 0 auto;
  margin-bottom: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
  padding-top: 10px;
}

.agent-visibility-filter :deep(.el-form-item) {
  margin-bottom: 10px;
}

.agent-visibility-filter :deep(.el-input),
.agent-visibility-filter :deep(.el-select),
.agent-visibility-filter :deep(.el-date-editor) {
  width: 100%;
}

.filter-actions {
  flex-wrap: nowrap;
}

.agent-visibility-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.agent-visibility-table-wrap :deep(.el-table) {
  height: 100%;
}

.agent-cell {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.agent-cell span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 18px;
}

.agent-cell strong {
  color: var(--el-text-color-primary);
  font-weight: 500;
}

.tab-pagination {
  display: flex;
  flex: 0 0 auto;
  justify-content: flex-end;
  padding-top: 12px;
}

.form-hint {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@media (max-width: 768px) {
  .agent-visibility-application-page {
    height: auto;
    min-height: calc(100dvh - 96px);
  }

  .page-toolbar {
    align-items: stretch;
    flex-direction: column;
  }
}
</style>
