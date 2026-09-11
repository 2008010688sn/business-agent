<template>
  <div class="agent-visibility-config">
    <section class="visibility-section">
      <div class="section-head">
        <div>
          <h3>可见性策略</h3>
          <p>控制用户对话页是否能看到该 Agent，不改变运行、工具、数据或管理权限。</p>
        </div>
        <ElButton type="primary" :loading="policySaving" @click="savePolicy">保存策略</ElButton>
      </div>
      <ElForm :model="policyForm" label-width="140px" class="policy-form">
        <ElFormItem label="对话页可见范围">
          <ElSelect v-model="policyForm.conversationScope">
            <ElOption label="仅授权可见" value="GRANT_ONLY" />
            <ElOption label="租户内可见" value="TENANT" />
            <ElOption label="团队授权可见" value="TEAM" />
            <ElOption label="权限授权可见" value="PERMISSION" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="目录可见范围">
          <ElSelect v-model="policyForm.catalogScope">
            <ElOption label="隐藏" value="HIDDEN" />
            <ElOption label="租户内可见" value="TENANT" />
            <ElOption label="团队授权可见" value="TEAM" />
            <ElOption label="权限授权可见" value="PERMISSION" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="申请模式">
          <ElSelect v-model="policyForm.applyMode">
            <ElOption label="不开放申请" value="DISABLED" />
            <ElOption label="自动通过" value="AUTO_APPROVE" />
            <ElOption label="需要审批" value="APPROVAL_REQUIRED" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="审批模式">
          <ElSelect v-model="policyForm.approvalMode">
            <ElOption label="本地轻量审批" value="LOCAL" />
            <ElOption label="项目 Workflow" value="WORKFLOW" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem v-if="policyForm.approvalMode === 'WORKFLOW'" label="流程编码">
          <ElInput v-model="policyForm.workflowFlowCode" placeholder="AGENT_VISIBILITY_APPLY" />
        </ElFormItem>
        <ElFormItem label="默认授权天数">
          <ElInputNumber v-model="policyForm.defaultGrantDays" :min="0" :max="3650" controls-position="right" />
          <span class="form-hint">0 或不填表示长期有效</span>
        </ElFormItem>
        <ElFormItem label="风险等级">
          <ElInput v-model="policyForm.riskLevel" placeholder="NORMAL" />
        </ElFormItem>
        <ElFormItem label="策略状态">
          <ElSwitch v-model="policyEnabled" active-text="启用" inactive-text="禁用" inline-prompt />
        </ElFormItem>
      </ElForm>
    </section>

    <section class="visibility-section">
      <div class="section-head">
        <div>
          <h3>授权列表</h3>
          <p>授权主体名称会冗余保存，列表展示不需要反查用户或组织。</p>
        </div>
        <ElButton type="primary" @click="openGrantDialog">新增授权</ElButton>
      </div>
      <div class="table-toolbar">
        <ElSelect v-model="grantQuery.subjectType" clearable placeholder="主体类型" @change="reloadGrantsFirstPage">
          <ElOption label="用户" value="USER" />
          <ElOption label="团队" value="TEAM" />
          <ElOption label="权限" value="PERMISSION" />
          <ElOption label="租户" value="TENANT" />
        </ElSelect>
        <ElInput
          v-model="grantQuery.subjectName"
          clearable
          placeholder="主体名称"
          @keyup.enter="reloadGrantsFirstPage"
          @clear="reloadGrantsFirstPage"
        />
        <ElButton :loading="grantsLoading" @click="reloadGrantsFirstPage">查询</ElButton>
      </div>
      <ElTable v-loading="grantsLoading" :data="grants" empty-text="暂无授权记录">
        <ElTableColumn prop="subjectType" label="主体类型" width="110" />
        <ElTableColumn prop="subjectId" label="主体 ID" min-width="160" show-overflow-tooltip />
        <ElTableColumn prop="subjectName" label="主体名称" min-width="160" show-overflow-tooltip />
        <ElTableColumn prop="sourceType" label="来源" width="120" />
        <ElTableColumn label="有效期" min-width="170">
          <template #default="{ row }">{{ row.expireTime ? formatTime(row.expireTime) : '长期有效' }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="row.status === 'ACTIVE' ? 'success' : 'info'" effect="light">{{ row.status || '-' }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <ElButton v-if="row.status === 'ACTIVE'" type="danger" text @click="deleteGrant(row)">删除</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      <div class="table-pagination">
        <ElPagination
          v-model:current-page="grantQuery.current"
          v-model:page-size="grantQuery.size"
          layout="total, sizes, prev, pager, next"
          :page-sizes="[10, 20, 50]"
          :total="grantTotal"
          @size-change="reloadGrantsFirstPage"
          @current-change="loadGrants"
        />
      </div>
    </section>

    <section class="visibility-section">
      <div class="section-head">
        <div>
          <h3>申请记录</h3>
          <p>所有 Agent 可见性申请统一在全局治理页查看和处理，避免多个审批入口产生状态竞争。</p>
        </div>
        <ElButton type="primary" @click="goApplicationGovernance">查看申请记录</ElButton>
      </div>
    </section>

    <ElDialog v-model="grantDialogVisible" title="新增授权" width="560px" :close-on-click-modal="false">
      <ElForm :model="grantForm" label-position="top">
        <ElFormItem label="主体类型" required>
          <ElSelect v-model="grantForm.subjectType" @change="handleGrantSubjectTypeChange">
            <ElOption label="用户" value="USER" />
            <ElOption label="团队" value="TEAM" />
            <ElOption label="权限" value="PERMISSION" />
            <ElOption label="租户" value="TENANT" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="授权主体" required>
          <ElSelect
            v-model="grantForm.subjectId"
            class="subject-select"
            clearable
            filterable
            remote
            reserve-keyword
            :loading="subjectOptionsLoading"
            :placeholder="grantSubjectPlaceholder"
            :remote-method="searchGrantSubjects"
            @change="handleGrantSubjectChange"
            @clear="clearGrantSubject"
            @visible-change="handleGrantSubjectVisible"
          >
            <ElOption
              v-for="item in subjectOptions"
              :key="`${grantForm.subjectType}-${item.value}`"
              :label="item.label"
              :value="item.value"
            >
              <div class="subject-option">
                <span class="subject-option__label">{{ item.label }}</span>
                <small v-if="item.description" class="subject-option__description">{{ item.description }}</small>
              </div>
            </ElOption>
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="主体名称">
          <ElInput v-model="grantForm.subjectName" disabled placeholder="选择授权主体后自动带出" />
        </ElFormItem>
        <ElFormItem label="过期时间">
          <ElDatePicker
            v-model="grantForm.expireTime"
            class="grant-expire-picker"
            type="datetime"
            format="YYYY-MM-DD HH:mm:ss"
            placeholder="请选择过期时间，留空长期有效"
            clearable
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="grantDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="grantSaving" @click="submitGrant">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import agentVisibilityService, {
  type AgentVisibilityGrant,
  type AgentVisibilityPolicy,
  type AgentVisibilitySubjectOption,
  type AgentVisibilitySubjectType
} from '@/views/ai-agent/services/visibility';
import type { AgentId } from '@/views/ai-agent/services/agent';

defineOptions({ name: 'AgentVisibilityConfig' });

const props = defineProps<{
  agentId: AgentId;
}>();

type GrantFormState = {
  subjectType: AgentVisibilitySubjectType;
  subjectId: string;
  subjectName: string;
  expireTime: Date | string | null;
};

const router = useRouter();

const policySaving = ref(false);
const grantsLoading = ref(false);
const grantSaving = ref(false);
const grantDialogVisible = ref(false);
const subjectOptionsLoading = ref(false);
const grants = ref<AgentVisibilityGrant[]>([]);
const subjectOptions = ref<AgentVisibilitySubjectOption[]>([]);
const grantTotal = ref(0);
let subjectOptionsRequestId = 0;

const policyForm = reactive<AgentVisibilityPolicy>({
  conversationScope: 'GRANT_ONLY',
  catalogScope: 'TENANT',
  applyMode: 'APPROVAL_REQUIRED',
  approvalMode: 'LOCAL',
  workflowFlowCode: 'AGENT_VISIBILITY_APPLY',
  riskLevel: 'NORMAL',
  defaultGrantDays: undefined,
  approverConfigJson: '',
  status: 'ENABLED'
});
const grantQuery = reactive({
  current: 1,
  size: 10,
  subjectType: '',
  subjectName: '',
  status: 'ACTIVE'
});
const grantForm = reactive<GrantFormState>({
  subjectType: 'USER',
  subjectId: '',
  subjectName: '',
  expireTime: null
});

const policyEnabled = computed({
  get: () => policyForm.status !== 'DISABLED',
  set: value => {
    policyForm.status = value ? 'ENABLED' : 'DISABLED';
  }
});

const resolvedAgentId = computed(() => props.agentId);
const grantSubjectPlaceholder = computed(() => {
  const placeholderMap: Record<AgentVisibilitySubjectType, string> = {
    USER: '搜索用户昵称或账号',
    TEAM: '搜索团队名称',
    PERMISSION: '搜索权限编码',
    TENANT: '搜索租户名称'
  };
  return placeholderMap[grantForm.subjectType];
});

const loadPolicy = async () => {
  if (!resolvedAgentId.value) {
    return;
  }
  const policy = await agentVisibilityService.getPolicy(resolvedAgentId.value);
  Object.assign(policyForm, policy);
};

const savePolicy = async () => {
  if (!resolvedAgentId.value) {
    ElMessage.warning('Agent ID 无效');
    return;
  }
  policySaving.value = true;
  try {
    const saved = await agentVisibilityService.modifyPolicy(resolvedAgentId.value, {
      ...policyForm,
      workflowFlowCode: policyForm.workflowFlowCode || 'AGENT_VISIBILITY_APPLY'
    });
    Object.assign(policyForm, saved);
    ElMessage.success('策略已保存');
  } catch (error) {
    console.error('保存可见性策略失败:', error);
    ElMessage.error('保存策略失败');
  } finally {
    policySaving.value = false;
  }
};

const loadGrants = async () => {
  if (!resolvedAgentId.value) {
    return;
  }
  grantsLoading.value = true;
  try {
    const page = await agentVisibilityService.queryGrantsPage(resolvedAgentId.value, {
      current: grantQuery.current,
      size: grantQuery.size,
      subjectType: grantQuery.subjectType,
      subjectName: grantQuery.subjectName,
      status: grantQuery.status
    });
    grants.value = page.data;
    grantTotal.value = page.total;
    grantQuery.current = page.pageNum || grantQuery.current;
    grantQuery.size = page.pageSize || grantQuery.size;
  } catch (error) {
    console.error('加载授权列表失败:', error);
    ElMessage.error('加载授权列表失败');
  } finally {
    grantsLoading.value = false;
  }
};

const reloadGrantsFirstPage = () => {
  grantQuery.current = 1;
  loadGrants().catch(error => {
    console.error('加载授权列表失败:', error);
  });
};

const resetGrantSubjectOptions = () => {
  subjectOptionsRequestId += 1;
  subjectOptions.value = [];
  subjectOptionsLoading.value = false;
};

const clearGrantSubject = () => {
  grantForm.subjectId = '';
  grantForm.subjectName = '';
};

const loadGrantSubjects = async (keyword = '') => {
  const requestId = subjectOptionsRequestId + 1;
  subjectOptionsRequestId = requestId;
  subjectOptionsLoading.value = true;
  const subjectType = grantForm.subjectType;
  try {
    const options = await agentVisibilityService.queryGrantSubjectOptions(subjectType, keyword);
    if (requestId !== subjectOptionsRequestId || subjectType !== grantForm.subjectType) {
      return;
    }
    subjectOptions.value = options;
  } catch (error) {
    if (requestId === subjectOptionsRequestId) {
      console.error('加载授权主体选项失败:', error);
      ElMessage.error('加载授权主体选项失败');
    }
  } finally {
    if (requestId === subjectOptionsRequestId) {
      subjectOptionsLoading.value = false;
    }
  }
};

const searchGrantSubjects = (keyword: string) => {
  loadGrantSubjects(keyword).catch(error => {
    console.error('加载授权主体选项失败:', error);
  });
};

const handleGrantSubjectVisible = (visible: boolean) => {
  if (!visible || subjectOptions.value.length > 0) {
    return;
  }
  searchGrantSubjects('');
};

const handleGrantSubjectTypeChange = () => {
  clearGrantSubject();
  resetGrantSubjectOptions();
  searchGrantSubjects('');
};

const handleGrantSubjectChange = (value?: string | number) => {
  const subjectId = value === undefined || value === null ? '' : String(value);
  grantForm.subjectId = subjectId;
  const selectedOption = subjectOptions.value.find(option => option.value === subjectId);
  grantForm.subjectName = selectedOption?.label || '';
};

const normalizeExpireTime = (value: Date | string | null) => {
  if (!value) {
    return undefined;
  }
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
};

const openGrantDialog = () => {
  Object.assign(grantForm, {
    subjectType: 'USER',
    subjectId: '',
    subjectName: '',
    expireTime: null
  });
  resetGrantSubjectOptions();
  grantDialogVisible.value = true;
  searchGrantSubjects('');
};

const submitGrant = async () => {
  if (!resolvedAgentId.value) {
    ElMessage.warning('Agent ID 无效');
    return;
  }
  if (!grantForm.subjectType || !grantForm.subjectId.trim()) {
    ElMessage.warning('请选择授权主体');
    return;
  }
  grantSaving.value = true;
  try {
    await agentVisibilityService.createGrant(resolvedAgentId.value, {
      subjectType: grantForm.subjectType,
      subjectId: grantForm.subjectId.trim(),
      subjectName: grantForm.subjectName.trim(),
      expireTime: normalizeExpireTime(grantForm.expireTime)
    });
    ElMessage.success('授权已创建');
    grantDialogVisible.value = false;
    await loadGrants();
  } catch (error) {
    console.error('创建授权失败:', error);
    ElMessage.error('创建授权失败');
  } finally {
    grantSaving.value = false;
  }
};

const deleteGrant = async (grant: AgentVisibilityGrant) => {
  if (!grant.id) {
    return;
  }
  try {
    await ElMessageBox.confirm('确定删除该可见性授权吗？删除后用户对话页将不再显示该 Agent。', '删除授权', {
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await agentVisibilityService.deleteGrant(grant.id);
    ElMessage.success('授权已删除');
    await loadGrants();
  } catch (error) {
    console.error('删除授权失败:', error);
    ElMessage.error('删除授权失败');
  }
};

const goApplicationGovernance = () => {
  if (!resolvedAgentId.value) {
    ElMessage.warning('Agent ID 无效');
    return;
  }
  router.push({
    name: 'ai-agent_agent_visibility-applications',
    query: {
      agentId: String(resolvedAgentId.value),
      tab: 'records'
    }
  });
};

const formatTime = (value?: string) => {
  if (!value) {
    return '-';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleString('zh-CN');
};

const loadAll = async () => {
  await Promise.all([loadPolicy(), loadGrants()]);
};

onMounted(() => {
  loadAll().catch(error => {
    console.error('加载可见性配置失败:', error);
  });
});

watch(
  () => props.agentId,
  () => {
    loadAll().catch(error => {
      console.error('加载可见性配置失败:', error);
    });
  }
);
</script>

<style scoped>
.agent-visibility-config {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.visibility-section {
  padding: 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.visibility-section:last-child {
  border-bottom: 0;
}

.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
}

.section-head h3 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 16px;
  font-weight: 700;
  line-height: 1.4;
}

.section-head p,
.form-hint {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.form-hint {
  margin-left: 10px;
}

.policy-form {
  max-width: 760px;
}

.table-toolbar {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 180px));
  gap: 10px;
  margin-bottom: 12px;
}

.table-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.subject-select,
.grant-expire-picker {
  width: 100%;
}

.subject-option {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
  line-height: 1.35;
}

.subject-option__label {
  color: var(--el-text-color-primary);
}

.subject-option__description {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@media (max-width: 768px) {
  .section-head {
    flex-direction: column;
  }

  .table-toolbar {
    grid-template-columns: 1fr;
  }
}
</style>
