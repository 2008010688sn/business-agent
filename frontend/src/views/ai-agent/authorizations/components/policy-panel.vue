<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="policy-panel">
    <!-- 授权模板列表 -->
    <ElCard class="mb-8px" shadow="never">
      <template #header>
        <div class="card-header">
          <span>授权模板</span>
          <span class="card-header-sub">预设策略模板，可直接作为创建策略的输入（defaultPolicyJson 严格校验契约一致）</span>
        </div>
      </template>
      <div v-loading="templatesLoading" class="template-grid">
        <div v-for="item in templates" :key="item.code" class="template-card">
          <div class="template-card-head">
            <ElTag size="small" effect="plain">{{ item.code }}</ElTag>
            <ElButton
              v-if="canManage"
              link
              type="primary"
              size="small"
              @click="createPolicyFromTemplate(item)"
            >
              基于此模板新建
            </ElButton>
          </div>
          <pre class="template-json">{{ formatJson(item.defaultPolicyJson) }}</pre>
        </div>
        <ElEmpty v-if="!templatesLoading && templates.length === 0" description="暂无授权模板" :image-size="60" />
      </div>
    </ElCard>

    <!-- 策略主档列表 -->
    <ElCard shadow="never">
      <template #header>
        <div class="card-header">
          <span>策略管理</span>
          <div class="card-header-actions">
            <ElButton v-if="canQuery" :loading="loading" size="small" @click="loadPolicies">
              <ElIcon><Refresh /></ElIcon>
              刷新
            </ElButton>
            <ElButton v-if="canManage" type="primary" size="small" @click="openCreateDialog()">
              <ElIcon><Plus /></ElIcon>
              新建策略
            </ElButton>
          </div>
        </div>
      </template>

      <section class="filter-panel" style="margin-bottom: 12px; flex-shrink: 0;">
        <ElForm label-width="70px" inline>
          <ElFormItem label="编码">
            <ElInput v-model="query.code" placeholder="策略编码（模糊）" clearable @keyup.enter="handleSearch" />
          </ElFormItem>
          <ElFormItem label="名称">
            <ElInput v-model="query.name" placeholder="策略名称（模糊）" clearable @keyup.enter="handleSearch" />
          </ElFormItem>
          <ElFormItem label="状态">
            <ElSelect v-model="query.status" clearable placeholder="全部" style="width: 140px">
              <ElOption v-for="item in POLICY_STATUS_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="模板">
            <ElSelect v-model="query.templateCode" clearable placeholder="全部模板" style="width: 200px">
              <ElOption v-for="item in templates" :key="item.code" :label="item.code" :value="item.code" />
            </ElSelect>
          </ElFormItem>
          <ElButton v-if="canQuery" type="primary" :loading="loading" @click="handleSearch">
            <ElIcon><Search /></ElIcon>
            查询
          </ElButton>
          <ElButton v-if="canQuery" @click="handleReset">
            <ElIcon><Refresh /></ElIcon>
            重置
          </ElButton>
        </ElForm>
      </section>

      <div class="table-container">
        <ElTable v-loading="loading" :data="policyRows" border stripe row-key="id" empty-text="暂无策略">
        <ElTableColumn label="策略编码" min-width="180" fixed="left" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="cell-strong">{{ row.code || '-' }}</div>
            <span class="cell-muted">ID: {{ row.id || '-' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="name" label="策略名称" min-width="160" show-overflow-tooltip />
        <ElTableColumn label="来源模板" width="160">
          <template #default="{ row }">{{ row.templateCode || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90" align="center">
          <template #default="{ row }">
            <ElTag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="当前发布版本" width="110" align="center">
          <template #default="{ row }">
            <span v-if="row.currentVersionId">已发布</span>
            <span v-else class="cell-muted">未发布</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="创建时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="280" fixed="right" align="center">
          <template #default="{ row }">
            <ElButton v-if="canQuery" link type="primary" @click="openDetail(row)">详情</ElButton>
            <ElButton v-if="canManage && isDraft(row)" link type="primary" @click="openEditDialog(row)">编辑</ElButton>
            <ElButton v-if="canManage && isDraft(row)" link type="success" @click="handlePublish(row)">发布</ElButton>
            <ElButton v-if="canManage && !isDraft(row)" link type="primary" @click="handleCreateDraft(row)">开新草稿</ElButton>
            <ElButton v-if="canManage && isPublished(row)" link type="warning" @click="handleRetire(row)">停用</ElButton>
            <ElButton v-if="canManage && isRetired(row)" link type="success" @click="handleEnable(row)">启用</ElButton>
            <ElButton v-if="canManage && !isPublished(row)" link type="danger" @click="handleDelete(row)">删除</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
      </div>

      <div class="panel-pagination">
        <ElPagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50, 100]"
          :total="page.total"
          @size-change="handleSizeChange"
          @current-change="loadPolicies"
        />
      </div>
    </ElCard>

    <AuthorizationPolicyFormDialog
      v-model:visible="formDialogVisible"
      :mode="formMode"
      :policy="activePolicyDetail"
      :preset-template-code="presetTemplateCode"
      @saved="loadPolicies"
    />

    <AuthorizationPolicyDetailDrawer v-model:visible="detailVisible" :policy-id="activePolicyId" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Plus, Refresh, Search } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import { haveAuth } from '@/mixins/userAuth.js';
import authorizationService from '@/views/ai-agent/services/authorization';
import type { PolicyDetailResp, PolicyEntity, PolicyStatus, TemplateResp } from '@/views/ai-agent/services/authorization';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { AUTHORIZATION_MANAGE_PERMISSION, AUTHORIZATION_QUERY_PERMISSION, POLICY_STATUS_LABELS, POLICY_STATUS_OPTIONS, POLICY_STATUS_TAG_TYPES } from '../authorization-constants';
import AuthorizationPolicyFormDialog from './policy-form-dialog.vue';
import AuthorizationPolicyDetailDrawer from './policy-detail-drawer.vue';

defineOptions({ name: 'AuthorizationPolicyPanel' });

const canQuery = computed(() => haveAuth(AUTHORIZATION_QUERY_PERMISSION));
const canManage = computed(() => haveAuth(AUTHORIZATION_MANAGE_PERMISSION));

const query = reactive<{ code: string; name: string; status: PolicyStatus | ''; templateCode: string }>({
  code: '',
  name: '',
  status: '',
  templateCode: ''
});
const page = reactive({ current: 1, size: 10, total: 0 });
const policyRows = ref<PolicyEntity[]>([]);
const loading = ref(false);

const templates = ref<TemplateResp[]>([]);
const templatesLoading = ref(false);

const formDialogVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const activePolicyDetail = ref<PolicyDetailResp | null>(null);
const detailVisible = ref(false);
const activePolicyId = ref('');

const isDraft = (row: PolicyEntity) => row.status === 'DRAFT';
const isPublished = (row: PolicyEntity) => row.status === 'PUBLISHED';
const isRetired = (row: PolicyEntity) => row.status === 'RETIRED';

const statusLabel = (status?: string) => POLICY_STATUS_LABELS[status || ''] || status || '-';
const statusTagType = (status?: string) => POLICY_STATUS_TAG_TYPES[status || ''] || 'info';

const formatJson = (value?: string): string => {
  if (!value) return '';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
};

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

async function loadTemplates() {
  templatesLoading.value = true;
  try {
    templates.value = await authorizationService.listTemplates();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '授权模板加载失败'));
  } finally {
    templatesLoading.value = false;
  }
}

async function loadPolicies() {
  loading.value = true;
  try {
    const response = await authorizationService.pagePolicies({
      code: query.code,
      name: query.name,
      status: query.status,
      templateCode: query.templateCode,
      current: page.current,
      size: page.size
    });
    policyRows.value = response.data;
    page.total = response.total;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '策略列表查询失败'));
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  page.current = 1;
  loadPolicies();
}

function handleReset() {
  query.code = '';
  query.name = '';
  query.status = '';
  query.templateCode = '';
  handleSearch();
}

function handleSizeChange() {
  page.current = 1;
  loadPolicies();
}

function openCreateDialog() {
  formMode.value = 'create';
  activePolicyDetail.value = null;
  presetTemplateCode.value = '';
  formDialogVisible.value = true;
}

/** 基于模板新建：预选模板并打开创建对话框（对话框内按模板填充默认 JSON，仍可手工修改）。 */
function createPolicyFromTemplate(template: TemplateResp) {
  formMode.value = 'create';
  activePolicyDetail.value = null;
  presetTemplateCode.value = template.code || '';
  formDialogVisible.value = true;
}

const presetTemplateCode = ref('');

function openEditDialog(row: PolicyEntity) {
  if (!row.id) return;
  loading.value = true;
  authorizationService
    .fetchPolicyDetail(row.id)
    .then(detail => {
      formMode.value = 'edit';
      activePolicyDetail.value = detail;
      formDialogVisible.value = true;
    })
    .catch(error => {
      ElMessage.error(extractApiErrorMessage(error, '策略详情加载失败'));
    })
    .finally(() => {
      loading.value = false;
    });
}

function openDetail(row: PolicyEntity) {
  if (!row.id) return;
  activePolicyId.value = row.id;
  detailVisible.value = true;
}

async function handlePublish(row: PolicyEntity) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(
      `发布策略「${row.name || row.code}」后该版本不可变（CAS 校验最新草稿 hash），主档切换为已发布。确认发布？`,
      '发布确认',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runRowAction(() => authorizationService.publishPolicy(row.id as string), '发布成功', '发布失败');
}

async function handleCreateDraft(row: PolicyEntity) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(
      `将复制「${row.name || row.code}」当前发布版本内容为新草稿（主档回到草稿状态），用于已发布策略的变更流转。确认继续？`,
      '开新草稿',
      { type: 'info', confirmButtonText: '开新草稿', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runRowAction(() => authorizationService.createDraftFromCurrent(row.id as string), '已开新草稿', '开新草稿失败');
}

async function handleRetire(row: PolicyEntity) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(
      `停用「${row.name || row.code}」后策略转为已停用；存量绑定不受影响（运行时以绑定指向的版本为准）。确认停用？`,
      '停用确认',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runRowAction(() => authorizationService.retirePolicy(row.id as string), '停用成功', '停用失败');
}

async function handleEnable(row: PolicyEntity) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(`启用「${row.name || row.code}」要求存在已发布版本（已停用 → 已发布）。确认启用？`, '启用确认', {
      type: 'info',
      confirmButtonText: '启用',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  await runRowAction(() => authorizationService.enablePolicy(row.id as string), '启用成功', '启用失败');
}

async function handleDelete(row: PolicyEntity) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(
      `删除「${row.name || row.code}」将逻辑删除策略及其版本行；存在被绑定引用的版本时后端拒绝删除。确认删除？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runRowAction(() => authorizationService.deletePolicy(row.id as string), '删除成功', '删除失败');
}

/** 行级状态机操作统一提交：失败展示后端中文报错。 */
async function runRowAction(action: () => Promise<unknown>, successText: string, failText: string) {
  try {
    await action();
    ElMessage.success(successText);
    await loadPolicies();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, failText));
  }
}

onMounted(() => {
  if (canQuery.value) {
    loadTemplates();
    loadPolicies();
  }
});
</script>

<style scoped>
.policy-panel {
  width: 100%;
  min-width: 0;
}

.mb-8px {
  margin-bottom: 8px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-weight: 600;
}

.card-header-sub {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.card-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.template-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}

.template-card {
  display: flex;
  flex-direction: column;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: var(--el-fill-color-blank);
}

.template-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.template-json {
  max-height: 180px;
  margin: 0;
  overflow: auto;
  color: var(--el-text-color-regular);
  font-family: Consolas, Monaco, monospace;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
}

.filter-panel {
  flex: 0 0 auto;
  margin-bottom: 12px;
}

.filter-panel :deep(.el-form-item) {
  margin-bottom: 10px;
}

.panel-pagination {
  display: flex;
  flex: 0 0 auto;
  justify-content: flex-end;
  padding-top: 14px;
}

.table-container {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  overflow: auto;
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
