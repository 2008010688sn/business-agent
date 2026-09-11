<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="grant-panel">
    <ElCard shadow="never">
      <template #header>
        <div class="card-header">
          <span>授权管理</span>
          <span class="card-header-sub">
            为主体（用户/团队/权限码/租户）授予 DISCOVER/USE 权限；同主体同权限幂等复用既有生效记录
          </span>
          <div class="card-header-actions">
            <ElButton v-if="canQuery" :loading="loading" size="small" @click="loadGrants">
              <ElIcon><Refresh /></ElIcon>
              刷新
            </ElButton>
            <ElButton v-if="canManage" type="primary" size="small" :disabled="!query.ownerId" @click="openCreateDialog">
              <ElIcon><Plus /></ElIcon>
              新增授权
            </ElButton>
          </div>
        </div>
      </template>

      <!-- 查询条件 -->
      <section class="filter-panel">
        <ElForm label-width="90px" inline>
          <ElFormItem label="授权对象">
            <ElSelect v-model="query.ownerType" style="width: 160px" @change="handleOwnerTypeChange">
              <ElOption v-for="item in OWNER_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="对象 ID">
            <ElSelect
              v-if="query.ownerType === 'DATA_AGENT'"
              v-model="query.ownerId"
              filterable
              clearable
              placeholder="选择数据智能体"
              :loading="agentOptionsLoading"
              style="width: 260px"
              @change="loadGrants"
            >
              <ElOption v-for="item in agentOptions" :key="String(item.id)" :label="item.name || String(item.id)" :value="String(item.id)" />
            </ElSelect>
            <EmployeeOptionSelect
              v-else
              v-model="query.ownerId"
              placeholder="选择数字员工"
              @change="handleEmployeeOwnerChange"
            />
          </ElFormItem>
          <ElButton v-if="canQuery" type="primary" :loading="loading" @click="loadGrants">
            <ElIcon><Search /></ElIcon>
            查询
          </ElButton>
        </ElForm>
      </section>

      <ElDescriptions
        v-if="query.ownerType === 'DIGITAL_EMPLOYEE' && employeeIdentity"
        title="执行身份（只读）"
        :column="3"
        border
        size="small"
        class="mb-12"
      >
        <ElDescriptionsItem label="员工">{{ employeeIdentity.employeeName || employeeIdentity.id }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Principal">{{ employeeIdentity.iamPrincipalId || '未开通' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Principal 状态">{{ employeeIdentity.principalStatus || '-' }}</ElDescriptionsItem>
      </ElDescriptions>

      <ElTable v-loading="loading" :data="grantRows" border stripe row-key="id" empty-text="暂无授权记录">
        <ElTableColumn label="授权主体" min-width="200" fixed="left">
          <template #default="{ row }">
            <div class="cell-strong">
              {{ subjectTypeLabel(row.subjectType) }}：{{ row.subjectName || row.subjectId || '-' }}
            </div>
            <span class="cell-muted">subjectId: {{ row.subjectId || '-' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="授权权限" width="110" align="center">
          <template #default="{ row }">
            <ElTag :type="row.permission === 'USE' ? 'success' : 'primary'" size="small">
              {{ permissionLabel(row.permission) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90" align="center">
          <template #default="{ row }">
            <ElTag :type="grantStatusTagType(row.status)" size="small">{{ grantStatusLabel(row.status) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="来源" width="100" align="center">
          <template #default="{ row }">{{ sourceTypeLabel(row.sourceType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="过期时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.expireTime) || '长期有效' }}</template>
        </ElTableColumn>
        <ElTableColumn label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <ElButton v-if="canManage" link type="danger" @click="handleDelete(row)">删除</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </ElCard>

    <!-- 新增授权对话框 -->
    <ElDialog v-model="createDialogVisible" title="新增授权" width="560px" :close-on-click-modal="false" destroy-on-close>
      <ElForm ref="formRef" :model="form" :rules="formRules" label-width="100px">
        <ElFormItem label="授权对象">
          <span>{{ ownerTypeLabel(query.ownerType) }}：{{ ownerDisplayName }}</span>
        </ElFormItem>
        <ElFormItem label="主体类型" prop="subjectType">
          <ElSelect v-model="form.subjectType" placeholder="选择授权主体类型" style="width: 100%">
            <ElOption v-for="item in GRANT_SUBJECT_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="主体 ID" prop="subjectId">
          <ElInput v-model="form.subjectId" placeholder="用户 ID / 团队 ID / 权限码 / 租户 ID" />
        </ElFormItem>
        <ElFormItem label="主体名称" prop="subjectName">
          <ElInput v-model="form.subjectName" placeholder="冗余展示用，可空" />
        </ElFormItem>
        <ElFormItem label="授权权限" prop="permission">
          <ElSelect v-model="form.permission" placeholder="选择授权权限" style="width: 100%">
            <ElOption v-for="item in GRANT_PERMISSION_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="有效天数" prop="expireDays">
          <ElInputNumber v-model="form.expireDays" :min="0" :step="1" placeholder="空为长期有效" style="width: 180px" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="createDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="handleSubmit">创建</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import { haveAuth } from '@/mixins/userAuth.js';
import agentService from '@/views/ai-agent/services/agent';
import type { Agent } from '@/views/ai-agent/services/agent';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployee } from '@/views/ai-agent/services/digitalEmployee';
import EmployeeOptionSelect from '@/views/ai-agent/components/employee-option-select.vue';
import authorizationService from '@/views/ai-agent/services/authorization';
import type {
  GrantEntity,
  GrantOwnerType,
  GrantPermission,
  GrantSubjectType
} from '@/views/ai-agent/services/authorization';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import {
  AUTHORIZATION_MANAGE_PERMISSION,
  AUTHORIZATION_QUERY_PERMISSION,
  GRANT_PERMISSION_LABELS,
  GRANT_PERMISSION_OPTIONS,
  GRANT_SOURCE_TYPE_LABELS,
  GRANT_STATUS_LABELS,
  GRANT_STATUS_TAG_TYPES,
  GRANT_SUBJECT_TYPE_LABELS,
  GRANT_SUBJECT_TYPE_OPTIONS,
  OWNER_TYPE_LABELS,
  OWNER_TYPE_OPTIONS
} from '../authorization-constants';

defineOptions({ name: 'AuthorizationGrantPanel' });

const canQuery = computed(() => haveAuth(AUTHORIZATION_QUERY_PERMISSION));
const canManage = computed(() => haveAuth(AUTHORIZATION_MANAGE_PERMISSION));
const route = useRoute();

const query = reactive<{ ownerType: GrantOwnerType; ownerId: string }>({ ownerType: 'DATA_AGENT', ownerId: '' });
const grantRows = ref<GrantEntity[]>([]);
const loading = ref(false);

const agentOptions = ref<Agent[]>([]);
const agentOptionsLoading = ref(false);
const employeeIdentity = ref<DigitalEmployee | null>(null);

const createDialogVisible = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();

const form = reactive<{
  subjectType: GrantSubjectType | '';
  subjectId: string;
  subjectName: string;
  permission: GrantPermission | '';
  expireDays?: number;
}>({
  subjectType: '',
  subjectId: '',
  subjectName: '',
  permission: '',
  expireDays: undefined
});

const formRules: FormRules = {
  subjectType: [{ required: true, message: '请选择主体类型', trigger: 'change' }],
  subjectId: [{ required: true, message: '请输入主体 ID', trigger: 'blur' }],
  permission: [{ required: true, message: '请选择授权权限', trigger: 'change' }]
};

const ownerDisplayName = computed(() => {
  if (!query.ownerId) return '-';
  if (query.ownerType === 'DIGITAL_EMPLOYEE') {
    return employeeIdentity.value?.employeeName || query.ownerId;
  }
  if (query.ownerType !== 'DATA_AGENT') return query.ownerId;
  const matched = agentOptions.value.find(item => String(item.id) === String(query.ownerId));
  return matched?.name || query.ownerId;
});

const subjectTypeLabel = (value?: string) => GRANT_SUBJECT_TYPE_LABELS[value || ''] || value || '-';
const permissionLabel = (value?: string) => GRANT_PERMISSION_LABELS[value || ''] || value || '-';
const grantStatusLabel = (value?: string) => GRANT_STATUS_LABELS[value || ''] || value || '-';
const grantStatusTagType = (value?: string) => GRANT_STATUS_TAG_TYPES[value || ''] || 'info';
const sourceTypeLabel = (value?: string) => GRANT_SOURCE_TYPE_LABELS[value || ''] || value || '-';
const ownerTypeLabel = (value?: string) => OWNER_TYPE_LABELS[value || ''] || value || '-';

const formatDateTime = (value?: string) => {
  if (!value) return '';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

function handleOwnerTypeChange() {
  query.ownerId = '';
  grantRows.value = [];
  employeeIdentity.value = null;
}

async function handleEmployeeOwnerChange() {
  employeeIdentity.value = null;
  if (!query.ownerId) {
    grantRows.value = [];
    return;
  }
  if (query.ownerType === 'DIGITAL_EMPLOYEE') {
    try {
      employeeIdentity.value = await digitalEmployeeService.fetchDetail(query.ownerId);
    } catch (error) {
      ElMessage.error(extractApiErrorMessage(error, '数字员工身份加载失败'));
    }
  }
  await loadGrants();
}

async function loadAgentOptions() {
  agentOptionsLoading.value = true;
  try {
    agentOptions.value = await agentService.list();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '智能体列表加载失败'));
  } finally {
    agentOptionsLoading.value = false;
  }
}

async function loadGrants() {
  if (!query.ownerId) {
    ElMessage.warning('请先选择/输入授权对象');
    return;
  }
  loading.value = true;
  try {
    grantRows.value = await authorizationService.listGrants(query.ownerType, query.ownerId);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '授权记录查询失败'));
  } finally {
    loading.value = false;
  }
}

function openCreateDialog() {
  form.subjectType = '';
  form.subjectId = '';
  form.subjectName = '';
  form.permission = '';
  form.expireDays = undefined;
  createDialogVisible.value = true;
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  try {
    await authorizationService.createGrant({
      ownerType: query.ownerType,
      ownerId: query.ownerId,
      subjectType: form.subjectType as GrantSubjectType,
      subjectId: form.subjectId,
      subjectName: form.subjectName || undefined,
      permission: form.permission as GrantPermission,
      expireDays: form.expireDays && form.expireDays > 0 ? form.expireDays : undefined
    });
    ElMessage.success('授权创建成功');
    createDialogVisible.value = false;
    await loadGrants();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '授权创建失败'));
  } finally {
    submitting.value = false;
  }
}

async function handleDelete(row: GrantEntity) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(
      `删除主体「${row.subjectName || row.subjectId}」的${permissionLabel(row.permission)}授权（逻辑删除）？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    await authorizationService.deleteGrant(row.id);
    ElMessage.success('删除成功');
    await loadGrants();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '删除失败'));
  }
}

onMounted(async () => {
  if (!canQuery.value) return;
  loadAgentOptions();
  applyOwnerFromRoute();
  if (query.ownerId) {
    await handleEmployeeOwnerChange();
  }
});

function applyOwnerFromRoute() {
  const ownerType = String(
    Array.isArray(route.query.ownerType) ? (route.query.ownerType[0] ?? '') : (route.query.ownerType ?? '')
  ).trim();
  const ownerId = String(
    Array.isArray(route.query.ownerId) ? (route.query.ownerId[0] ?? '') : (route.query.ownerId ?? '')
  ).trim();
  if (ownerType === 'DATA_AGENT' || ownerType === 'DIGITAL_EMPLOYEE') {
    query.ownerType = ownerType;
  }
  if (ownerId) {
    query.ownerId = ownerId;
  }
}
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-weight: 600;
}

.card-header-sub {
  flex: 1;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.card-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-panel {
  margin-bottom: 12px;
}

.mb-12 {
  margin-bottom: 12px;
}

.filter-panel :deep(.el-form-item) {
  margin-bottom: 10px;
}

.cell-strong {
  font-weight: 600;
  color: #111827;
}

.cell-muted {
  color: #64748b;
  font-size: 12px;
}
</style>
