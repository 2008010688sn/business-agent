<template>
  <ElDialog v-model="visible" title="申请更多 Agent" width="920px" destroy-on-close>
    <div class="agent-catalog-toolbar">
      <ElInput
        v-model="query.keyword"
        class="catalog-keyword"
        clearable
        placeholder="搜索 Agent"
        @keyup.enter="reloadFirstPage"
        @clear="reloadFirstPage"
      >
        <template #prefix>
          <ElIcon><Search /></ElIcon>
        </template>
      </ElInput>
      <ElSelect
        v-model="query.status"
        class="catalog-status"
        clearable
        placeholder="全部状态"
        @change="reloadFirstPage"
      >
        <ElOption v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </ElSelect>
      <ElButton :loading="loading" @click="reloadFirstPage">
        <ElIcon><Refresh /></ElIcon>
        刷新
      </ElButton>
    </div>

    <ElTable v-loading="loading" :data="rows" height="420" empty-text="暂无可申请的 Agent">
      <ElTableColumn label="Agent" min-width="260">
        <template #default="{ row }">
          <div class="catalog-agent-cell">
            <div class="catalog-agent-name">{{ row.agent?.name || '未命名智能体' }}</div>
            <div class="catalog-agent-desc">{{ row.agent?.description || row.agent?.tags || '-' }}</div>
          </div>
        </template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="120">
        <template #default="{ row }">
          <ElTag :type="statusTagType(row.visibilityStatus)" effect="light">
            {{ statusLabel(row.visibilityStatus) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="申请方式" width="140">
        <template #default="{ row }">
          {{ approvalModeLabel(row.approvalMode) }}
        </template>
      </ElTableColumn>
      <ElTableColumn label="说明" min-width="180">
        <template #default="{ row }">
          <span class="catalog-muted">{{ row.disabledReason || applyModeLabel(row.applyMode) }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="120" fixed="right">
        <template #default="{ row }">
          <ElButton v-if="row.canApply" type="primary" size="small" @click="openApplyDialog(row)">申请</ElButton>
          <span v-else class="catalog-muted">-</span>
        </template>
      </ElTableColumn>
    </ElTable>

    <div class="catalog-pagination">
      <ElPagination
        v-model:current-page="query.current"
        v-model:page-size="query.size"
        layout="total, sizes, prev, pager, next"
        :page-sizes="[10, 20, 50]"
        :total="total"
        @size-change="reloadFirstPage"
        @current-change="loadCatalog"
      />
    </div>

    <ElDialog
      v-model="applyDialogVisible"
      title="提交 Agent 申请"
      width="520px"
      append-to-body
      :close-on-click-modal="false"
    >
      <ElForm label-position="top">
        <ElFormItem label="Agent">
          <ElInput :model-value="selectedItem?.agent?.name || '-'" disabled />
        </ElFormItem>
        <ElFormItem label="申请理由" required>
          <ElInput
            v-model="applyForm.reason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="请填写使用场景或业务原因"
          />
        </ElFormItem>
        <ElFormItem label="申请时长">
          <ElInputNumber v-model="applyForm.grantDays" :min="0" :max="3650" :step="1" controls-position="right" />
          <span class="grant-days-hint">0 或不填表示长期有效</span>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="applyDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitApplication">提交申请</ElButton>
      </template>
    </ElDialog>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { Refresh, Search } from '@element-plus/icons-vue';
import agentVisibilityService, {
  type AgentUserCatalogItem,
  type AgentUserCatalogPageQuery
} from '@/views/ai-agent/services/visibility';
import type { AgentId } from '@/views/ai-agent/services/agent';

defineOptions({ name: 'AgentVisibilityCatalogDialog' });

const props = defineProps<{
  modelValue: boolean;
}>();

const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  applied: [agentId?: AgentId];
}>();

const visible = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value)
});

const loading = ref(false);
const submitting = ref(false);
const rows = ref<AgentUserCatalogItem[]>([]);
const total = ref(0);
const selectedItem = ref<AgentUserCatalogItem | null>(null);
const applyDialogVisible = ref(false);
const query = reactive<AgentUserCatalogPageQuery>({
  current: 1,
  size: 10,
  keyword: '',
  status: ''
});
const applyForm = reactive({
  reason: '',
  grantDays: undefined as number | undefined
});

const statusOptions = [
  { label: '已可见', value: 'VISIBLE' },
  { label: '可申请', value: 'APPLYABLE' },
  { label: '审批中', value: 'PENDING' },
  { label: '不可申请', value: 'NOT_APPLYABLE' }
];

const loadCatalog = async () => {
  loading.value = true;
  try {
    const page = await agentVisibilityService.queryUserCatalogPage({
      current: query.current,
      size: query.size,
      keyword: query.keyword,
      status: query.status
    });
    rows.value = page.data;
    total.value = page.total;
    query.current = page.pageNum || query.current;
    query.size = page.pageSize || query.size;
  } catch (error) {
    console.error('加载 Agent 目录失败:', error);
    ElMessage.error('加载 Agent 目录失败');
  } finally {
    loading.value = false;
  }
};

const reloadFirstPage = () => {
  query.current = 1;
  loadCatalog().catch(error => {
    console.error('加载 Agent 目录失败:', error);
  });
};

const openApplyDialog = (row: AgentUserCatalogItem) => {
  if (!row.agent?.id) {
    ElMessage.warning('Agent ID 无效');
    return;
  }
  selectedItem.value = row;
  applyForm.reason = '';
  applyForm.grantDays = undefined;
  applyDialogVisible.value = true;
};

const submitApplication = async () => {
  const agentId = selectedItem.value?.agent?.id;
  if (!agentId) {
    ElMessage.warning('Agent ID 无效');
    return;
  }
  const reason = applyForm.reason.trim();
  if (!reason) {
    ElMessage.warning('请填写申请理由');
    return;
  }
  submitting.value = true;
  try {
    await agentVisibilityService.createApplication(agentId, {
      reason,
      grantDays: applyForm.grantDays
    });
    ElMessage.success('申请已提交');
    applyDialogVisible.value = false;
    emit('applied', agentId);
    await loadCatalog();
  } catch (error) {
    console.error('提交 Agent 申请失败:', error);
    ElMessage.error('提交申请失败');
  } finally {
    submitting.value = false;
  }
};

const statusLabel = (status?: string) => {
  const map: Record<string, string> = {
    VISIBLE: '已可见',
    APPLYABLE: '可申请',
    PENDING: '审批中',
    NOT_APPLYABLE: '不可申请'
  };
  return map[status || ''] || status || '-';
};

const statusTagType = (status?: string) => {
  const map: Record<string, 'success' | 'primary' | 'warning' | 'info'> = {
    VISIBLE: 'success',
    APPLYABLE: 'primary',
    PENDING: 'warning',
    NOT_APPLYABLE: 'info'
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

const applyModeLabel = (mode?: string) => {
  const map: Record<string, string> = {
    DISABLED: '不开放申请',
    AUTO_APPROVE: '自动通过',
    APPROVAL_REQUIRED: '需要审批'
  };
  return map[mode || ''] || mode || '-';
};

watch(
  () => props.modelValue,
  value => {
    if (value) {
      reloadFirstPage();
    }
  }
);
</script>

<style scoped>
.agent-catalog-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.catalog-keyword {
  flex: 1 1 auto;
  min-width: 220px;
}

.catalog-status {
  flex: 0 0 150px;
}

.catalog-agent-cell {
  min-width: 0;
}

.catalog-agent-name {
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}

.catalog-agent-desc,
.catalog-muted,
.grant-days-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.catalog-agent-desc {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.catalog-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.grant-days-hint {
  margin-left: 10px;
}

@media (max-width: 768px) {
  .agent-catalog-toolbar {
    align-items: stretch;
    flex-direction: column;
  }

  .catalog-status {
    flex-basis: auto;
  }
}
</style>
