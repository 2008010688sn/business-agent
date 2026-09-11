<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <BaseLayout class="employee-shell">
    <main class="employee-page">
      <header class="gallery-header">
        <div class="gallery-heading">
          <div class="gallery-eyebrow"><span class="eyebrow-dot"></span> AI WORKFORCE</div>
          <h1>数字员工</h1>
          <p class="employee-subtitle">把可复用的数字同事集中在这里，查看状态、配置身份并进入工作台。</p>
        </div>
        <div class="employee-header-actions">
          <ElTooltip content="刷新列表" placement="bottom">
            <ElButton v-if="canQuery" circle :loading="loading" aria-label="刷新列表" @click="loadEmployees">
              <ElIcon><Refresh /></ElIcon>
            </ElButton>
          </ElTooltip>
          <ElButton v-if="canManage" type="primary" @click="openCreateDialog">
            <ElIcon><Plus /></ElIcon>
            新建员工
          </ElButton>
        </div>
      </header>

      <section class="gallery-toolbar" aria-label="数字员工筛选">
        <div class="toolbar-search">
          <ElInput
            v-model="query.keyword"
            clearable
            placeholder="搜索员工名称或编码"
            @keyup.enter="handleSearch"
          >
            <template #prefix><ElIcon><Search /></ElIcon></template>
          </ElInput>
          <ElButton v-if="canQuery" type="primary" :loading="loading" @click="handleSearch">搜索</ElButton>
        </div>
        <nav class="status-tabs" aria-label="员工状态">
          <button
            v-for="tab in statusTabs"
            :key="tab.value || 'all'"
            type="button"
            class="status-tab"
            :class="{ active: query.status === tab.value }"
            @click="setStatus(tab.value)"
          >
            {{ tab.label }}
          </button>
        </nav>
        <div class="toolbar-meta">
          <span class="result-count">共 {{ page.total }} 位</span>
          <span class="view-switch" aria-label="视图切换">
            <ElTooltip content="卡片视图" placement="bottom">
              <ElButton
                circle
                text
                :type="viewMode === 'grid' ? 'primary' : 'default'"
                aria-label="卡片视图"
                @click="viewMode = 'grid'"
              >
                <ElIcon><Grid /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip content="列表视图" placement="bottom">
              <ElButton
                circle
                text
                :type="viewMode === 'list' ? 'primary' : 'default'"
                aria-label="列表视图"
                @click="viewMode = 'list'"
              >
                <ElIcon><List /></ElIcon>
              </ElButton>
            </ElTooltip>
          </span>
        </div>
      </section>

      <ElAlert v-if="listingInstallHint" class="listing-install-alert" type="info" show-icon :closable="false">
        <div class="listing-hint-content">
          <span>已从技能市场选中一个能力，请打开目标员工详情，在「能力」标签完成安装。</span>
          <ElButton link type="primary" @click="dismissListingHint">知道了</ElButton>
        </div>
      </ElAlert>

      <section v-if="viewMode === 'grid'" v-loading="loading" class="employee-gallery" aria-live="polite">
        <article
          v-for="(row, index) in employeeRows"
          :key="row.id || `${row.employeeCode || 'employee'}-${index}`"
          class="employee-card"
          tabindex="0"
          @click="openDetail(row)"
          @keydown.enter="openDetail(row)"
        >
          <div class="employee-card-head">
            <div class="employee-avatar" :class="`avatar-tone-${avatarTone(row, index)}`">
              <span>{{ employeeInitials(row) }}</span>
              <i class="status-dot" :class="`status-dot-${String(row.status || '').toLowerCase()}`"></i>
            </div>
            <ElDropdown v-if="canManage" trigger="click" @command="command => handleCardAction(command, row)">
              <ElButton circle text class="card-menu-button" aria-label="更多操作" @click.stop>
                <ElIcon><MoreFilled /></ElIcon>
              </ElButton>
              <template #dropdown>
                <ElDropdownMenu>
                  <ElDropdownItem v-if="isEditable(row)" command="edit">编辑</ElDropdownItem>
                  <ElDropdownItem v-if="row.status === 'DISABLED' || row.status === 'DRAFT'" command="enable">
                    启用
                  </ElDropdownItem>
                  <ElDropdownItem v-if="row.status === 'ENABLED'" command="disable">停用</ElDropdownItem>
                  <ElDropdownItem v-if="row.status === 'DISABLED'" command="archive">封存</ElDropdownItem>
                  <ElDropdownItem v-if="row.status === 'DRAFT'" command="delete" divided>删除</ElDropdownItem>
                </ElDropdownMenu>
              </template>
            </ElDropdown>
          </div>

          <div class="employee-card-body">
            <div class="employee-card-title-row">
              <h2>{{ row.employeeName || '未命名员工' }}</h2>
              <ElTag :type="statusTagType(row.status)" effect="light" size="small">{{ statusLabel(row.status) }}</ElTag>
            </div>
            <p class="employee-code">{{ row.employeeCode || `ID: ${row.id ?? '-'}` }}</p>
            <p class="employee-description">{{ row.description || '暂未填写员工描述' }}</p>
            <div class="employee-card-meta">
              <span v-if="row.jobTitle" class="meta-chip"><ElIcon><Briefcase /></ElIcon>{{ row.jobTitle }}</span>
              <span class="meta-chip"><ElIcon><Connection /></ElIcon>{{ row.sourceAgentId ? `DataAgent #${row.sourceAgentId}` : '空白创建' }}</span>
              <span class="meta-chip"><ElIcon><Cpu /></ElIcon>{{ row.modelCount ?? 0 }} 模型 · {{ row.capabilityCount ?? 0 }} 能力</span>
              <ElTooltip :content="principalTooltip(row)" placement="top">
                <span class="meta-chip"><span class="meta-dot" :class="`principal-dot-${String(row.principalStatus || '').toLowerCase()}`"></span>{{ principalLabel(row.principalStatus) }}</span>
              </ElTooltip>
            </div>
          </div>

          <footer class="employee-card-footer">
            <span class="created-time">创建于 {{ formatDateTime(row.createTime) }}</span>
            <ElButton v-if="canQuery" link type="primary" @click.stop="openDetail(row)">查看详情 <ElIcon><ArrowRight /></ElIcon></ElButton>
          </footer>
        </article>
        <ElEmpty v-if="!loading && employeeRows.length === 0" description="暂无数字员工" />
      </section>

      <section v-else v-loading="loading" class="employee-list-view">
        <ElTable :data="employeeRows" border stripe row-key="id" empty-text="暂无数字员工">
          <ElTableColumn label="员工" min-width="220" fixed="left" show-overflow-tooltip>
            <template #default="{ row }">
              <div class="list-employee-cell">
                <div class="list-avatar" :class="`avatar-tone-${avatarTone(row, 0)}`">{{ employeeInitials(row) }}</div>
                <div>
                  <div class="cell-strong">{{ row.employeeName || '-' }}</div>
                  <span class="cell-muted">{{ row.employeeCode || `ID: ${row.id ?? '-'}` }}</span>
                </div>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="状态" width="90">
            <template #default="{ row }">
              <ElTag :type="statusTagType(row.status)" effect="light" size="small">{{ statusLabel(row.status) }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="Principal" width="100">
            <template #default="{ row }">
              <ElTooltip :content="principalTooltip(row)" placement="top">
                <ElTag :type="principalTagType(row.principalStatus)" effect="light" size="small">{{ principalLabel(row.principalStatus) }}</ElTag>
              </ElTooltip>
            </template>
          </ElTableColumn>
          <ElTableColumn label="岗位" width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ row.jobTitle || '-' }}</template>
          </ElTableColumn>
          <ElTableColumn prop="description" label="描述" min-width="180" show-overflow-tooltip />
          <ElTableColumn label="创建时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
          </ElTableColumn>
          <ElTableColumn label="操作" width="240" fixed="right" align="center">
            <template #default="{ row }">
              <ElButton v-if="canQuery" link type="primary" @click="openDetail(row)">详情</ElButton>
              <ElButton v-if="canManage && isEditable(row)" link type="primary" @click="openEditDialog(row)">编辑</ElButton>
              <ElButton v-if="canManage && (row.status === 'DISABLED' || row.status === 'DRAFT')" link type="success" @click="handleEnable(row)">启用</ElButton>
              <ElButton v-if="canManage && row.status === 'ENABLED'" link type="warning" @click="handleDisable(row)">停用</ElButton>
              <ElButton v-if="canManage && row.status === 'DISABLED'" link type="info" @click="handleArchive(row)">封存</ElButton>
              <ElButton v-if="canManage && row.status === 'DRAFT'" link type="danger" @click="handleDelete(row)">删除</ElButton>
            </template>
          </ElTableColumn>
        </ElTable>
      </section>

      <div class="employee-pagination">
        <ElPagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50, 100]"
          :total="page.total"
          @size-change="handleSizeChange"
          @current-change="loadEmployees"
        />
      </div>

      <EmployeeFormDialog
        v-model:visible="formDialogVisible"
        :mode="formMode"
        :employee="activeEmployee"
        @saved="handleFormSaved"
      />
    </main>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { ArrowRight, Briefcase, Connection, Cpu, Grid, List, MoreFilled, Plus, Refresh, Search } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import { haveAuth } from '@/mixins/userAuth.js';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployee, EmployeeStatus } from '@/views/ai-agent/services/digitalEmployee';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import {
  EMPLOYEE_MANAGE_PERMISSION,
  EMPLOYEE_QUERY_PERMISSION,
  EMPLOYEE_STATUS_LABELS,
  EMPLOYEE_STATUS_TAG_TYPES,
  PRINCIPAL_STATUS_LABELS,
  PRINCIPAL_STATUS_TAG_TYPES
} from './employee-support';
import EmployeeFormDialog from './components/employee-form-dialog.vue';

defineOptions({ name: 'DigitalEmployeesPage' });

/** 详情页路由（字符串路径导航，路由注册由 gen-route 统一生成） */
const DETAIL_ROUTE_PATH = '/ai-agent/digital-employees/detail';

const router = useRouter();
const route = useRoute();
const listingInstallHint = computed(() => {
  const raw = route.query.listingId;
  return String(Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '')).trim();
});

const canQuery = computed(() => haveAuth(EMPLOYEE_QUERY_PERMISSION));
const canManage = computed(() => haveAuth(EMPLOYEE_MANAGE_PERMISSION));

const query = reactive<{ keyword: string; status: EmployeeStatus | '' }>({ keyword: '', status: '' });
const page = reactive({ current: 1, size: 10, total: 0 });
const employeeRows = ref<DigitalEmployee[]>([]);
const loading = ref(false);
const viewMode = ref<'grid' | 'list'>('grid');
const formDialogVisible = ref(false);
const formMode = ref<'create' | 'edit'>('create');
const activeEmployee = ref<DigitalEmployee | null>(null);

const statusTabs: Array<{ label: string; value: EmployeeStatus | '' }> = [
  { label: '全部', value: '' },
  { label: '启用', value: 'ENABLED' },
  { label: '草稿', value: 'DRAFT' },
  { label: '停用', value: 'DISABLED' },
  { label: '已封存', value: 'ARCHIVED' }
];

/** 后端仅封存态不可修改；启用态修改会记录为新的员工草稿配置。 */
const isEditable = (row: DigitalEmployee) =>
  row.status === 'DRAFT' || row.status === 'DISABLED' || row.status === 'ENABLED';

const statusLabel = (status?: string) => EMPLOYEE_STATUS_LABELS[status || ''] || status || '-';
const statusTagType = (status?: string) => EMPLOYEE_STATUS_TAG_TYPES[status || ''] || 'info';
const principalLabel = (status?: string) => PRINCIPAL_STATUS_LABELS[status || ''] || status || '-';
const principalTagType = (status?: string) => PRINCIPAL_STATUS_TAG_TYPES[status || ''] || 'info';

const principalTooltip = (row: DigitalEmployee) => {
  const status = principalLabel(row.principalStatus);
  const principalId = row.iamPrincipalId || '未开通';
  const revision =
    row.principalRevision !== null && row.principalRevision !== undefined
      ? `authRevision=${row.principalRevision}`
      : 'authRevision=-';
  return `${status} · ${principalId} · ${revision}`;
};

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

const employeeInitials = (row: DigitalEmployee) => {
  const name = (row.employeeName || '数').trim();
  return name.slice(0, 2);
};

const avatarTone = (row: DigitalEmployee, index: number) => {
  const source = row.id || row.employeeCode || row.employeeName || String(index);
  const hash = Array.from(source).reduce((total, character) => total + character.charCodeAt(0), 0);
  return hash % 5;
};

function setStatus(status: EmployeeStatus | '') {
  if (query.status === status) return;
  query.status = status;
  handleSearch();
}

function dismissListingHint() {
  const nextQuery = { ...route.query };
  delete nextQuery.listingId;
  router.replace({ query: nextQuery });
}

function handleCardAction(command: string, row: DigitalEmployee) {
  if (command === 'edit') openEditDialog(row);
  if (command === 'enable') handleEnable(row);
  if (command === 'disable') handleDisable(row);
  if (command === 'archive') handleArchive(row);
  if (command === 'delete') handleDelete(row);
}

async function loadEmployees() {
  loading.value = true;
  try {
    const response = await digitalEmployeeService.fetchPage({
      keyword: query.keyword.trim(),
      status: query.status,
      current: page.current,
      size: page.size
    });
    employeeRows.value = response.data;
    page.total = response.total;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '数字员工列表查询失败'));
  } finally {
    loading.value = false;
  }
}

function handleSearch() {
  page.current = 1;
  loadEmployees();
}

function handleReset() {
  query.keyword = '';
  query.status = '';
  handleSearch();
}

function handleSizeChange() {
  page.current = 1;
  loadEmployees();
}

function openDetail(row: DigitalEmployee) {
  if (!row.id) return;
  const query: Record<string, string> = { id: row.id };
  if (listingInstallHint.value) query.listingId = listingInstallHint.value;
  router.push({ path: DETAIL_ROUTE_PATH, query });
}

function openCreateDialog() {
  formMode.value = 'create';
  activeEmployee.value = null;
  formDialogVisible.value = true;
}

function openEditDialog(row: DigitalEmployee) {
  formMode.value = 'edit';
  activeEmployee.value = row;
  formDialogVisible.value = true;
}

async function handleEnable(row: DigitalEmployee) {
  if (!row.id || row.stateVersion === null || row.stateVersion === undefined) return;
  try {
    await ElMessageBox.confirm(
      `启用「${row.employeeName || row.id}」：rollout 开启时后端校验 Principal READY（未就绪自动触发开通重试）。确认启用？`,
      '启用确认',
      { type: 'warning', confirmButtonText: '启用', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runRowAction(() => digitalEmployeeService.enable(row.id as string, row.stateVersion as number), '员工已启用', '启用失败');
}

async function handleDisable(row: DigitalEmployee) {
  if (!row.id || row.stateVersion === null || row.stateVersion === undefined) return;
  try {
    await ElMessageBox.confirm(
      `停用后「${row.employeeName || row.id}」的对话与触发器执行将暂停；重新启用需再次通过 Principal 就绪校验。确认停用？`,
      '停用确认',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runRowAction(() => digitalEmployeeService.disable(row.id as string, row.stateVersion as number), '员工已停用', '停用失败');
}

async function handleArchive(row: DigitalEmployee) {
  if (!row.id || row.stateVersion === null || row.stateVersion === undefined) return;
  try {
    await ElMessageBox.confirm(
      `封存「${row.employeeName || row.id}」后档案只读（DISABLED → ARCHIVED，CAS 防覆盖）。确认封存？`,
      '封存确认',
      { type: 'warning', confirmButtonText: '封存', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runRowAction(() => digitalEmployeeService.archive(row.id as string, row.stateVersion as number), '员工已封存', '封存失败');
}

async function handleDelete(row: DigitalEmployee) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(
      `删除「${row.employeeName || row.id}」仅限草稿且无发布版本记录（软删保留审计，后端校验）。确认删除？`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runRowAction(() => digitalEmployeeService.remove(row.id as string), '员工已删除', '删除失败');
}

/** 行级状态机操作统一提交（state_version CAS：版本冲突时后端报错，重新拉取列表即可恢复） */
async function runRowAction(action: () => Promise<void>, successText: string, failText: string) {
  try {
    await action();
    ElMessage.success(successText);
    await loadEmployees();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, failText));
  }
}

function handleFormSaved(employee?: DigitalEmployee) {
  loadEmployees();
  if (formMode.value === 'create' && employee?.id) {
    const query: Record<string, string> = {
      id: employee.id,
      tab: listingInstallHint.value ? 'capability' : 'model-config'
    };
    if (listingInstallHint.value) query.listingId = listingInstallHint.value;
    router.push({ path: DETAIL_ROUTE_PATH, query });
  }
}

onMounted(loadEmployees);
</script>

<style scoped>
.employee-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  width: 100%;
  min-height: 0;
}

.employee-page {
  display: flex;
  overflow: auto;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
  padding: 22px 24px 18px;
  background: #f7f8fa;
}

.gallery-header,
.gallery-toolbar,
.employee-card,
.employee-list-view,
.employee-pagination {
  border: 1px solid #e7e9ee;
  background: #fff;
}

.gallery-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 20px 22px;
  border-radius: 8px;
}

.gallery-heading {
  min-width: 0;
}

.gallery-eyebrow {
  display: flex;
  align-items: center;
  gap: 7px;
  color: #718096;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.eyebrow-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #4f8f6b;
  box-shadow: 0 0 0 4px #e9f4ed;
}

.gallery-header h1 {
  margin: 8px 0 0;
  color: #1f2937;
  font-size: 26px;
  font-weight: 700;
  line-height: 1.2;
}

.employee-subtitle {
  max-width: 720px;
  margin: 7px 0 0;
  color: #6b7280;
  font-size: 13px;
  line-height: 1.5;
}

.employee-header-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.gallery-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px 20px;
  margin-top: 12px;
  padding: 10px 12px;
  border-radius: 8px;
}

.toolbar-search {
  display: flex;
  flex: 0 1 360px;
  gap: 8px;
}

.toolbar-search :deep(.el-input) {
  min-width: 180px;
}

.status-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  min-width: 0;
}

.status-tab {
  min-height: 30px;
  padding: 0 11px;
  border: 1px solid transparent;
  border-radius: 6px;
  color: #64748b;
  background: transparent;
  cursor: pointer;
  font-size: 13px;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.status-tab:hover {
  color: #2f6f4e;
  background: #f1f7f3;
}

.status-tab.active {
  border-color: #cfe5d7;
  color: #2f6f4e;
  background: #edf7f0;
  font-weight: 600;
}

.toolbar-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}

.result-count {
  color: #8490a3;
  font-size: 12px;
  white-space: nowrap;
}

.view-switch {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding-left: 8px;
  border-left: 1px solid #edf0f3;
}

.listing-install-alert {
  margin-top: 12px;
  border: 1px solid #dcebe2;
  background: #f6fbf7;
}

.listing-hint-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
}

.employee-gallery {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-content: start;
  gap: 14px;
  min-height: 220px;
  margin-top: 14px;
}

.employee-gallery :deep(.el-loading-mask) {
  border-radius: 8px;
}

.employee-card {
  display: flex;
  min-width: 0;
  min-height: 252px;
  flex-direction: column;
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.18s ease, box-shadow 0.18s ease, transform 0.18s ease;
}

.employee-card:hover,
.employee-card:focus-visible {
  border-color: #b9d7c3;
  box-shadow: 0 10px 24px rgba(25, 63, 43, 0.08);
  outline: none;
  transform: translateY(-2px);
}

.employee-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px 16px 0;
}

.employee-avatar,
.list-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.employee-avatar {
  position: relative;
  width: 52px;
  height: 52px;
  border-radius: 14px;
  font-size: 17px;
}

.avatar-tone-0 {
  background: #4f8062;
}

.avatar-tone-1 {
  background: #557a9c;
}

.avatar-tone-2 {
  background: #a46f54;
}

.avatar-tone-3 {
  background: #78659a;
}

.avatar-tone-4 {
  background: #3e8a87;
}

.status-dot {
  position: absolute;
  right: -3px;
  bottom: -3px;
  width: 12px;
  height: 12px;
  border: 3px solid #fff;
  border-radius: 50%;
  background: #94a3b8;
}

.status-dot-enabled {
  background: #36a269;
}

.status-dot-draft {
  background: #8b9aad;
}

.status-dot-disabled {
  background: #d49a32;
}

.status-dot-archived {
  background: #9b6d76;
}

.card-menu-button {
  color: #8a94a6;
}

.employee-card-body {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  padding: 16px;
}

.employee-card-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.employee-card-title-row h2 {
  overflow: hidden;
  min-width: 0;
  margin: 0;
  color: #1f2937;
  font-size: 16px;
  font-weight: 700;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.employee-code {
  overflow: hidden;
  margin: 3px 0 0;
  color: #8290a2;
  font-family: var(--font-family-mono, monospace);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.employee-description {
  display: -webkit-box;
  overflow: hidden;
  min-height: 42px;
  margin: 14px 0 0;
  color: #637083;
  font-size: 13px;
  line-height: 1.6;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.employee-card-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: auto;
  padding-top: 16px;
}

.meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
  padding: 4px 7px;
  border: 1px solid #edf0f3;
  border-radius: 5px;
  color: #657286;
  font-size: 11px;
  line-height: 1.2;
}

.meta-chip .el-icon {
  color: #789082;
}

.meta-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #a6b0bd;
}

.principal-dot-ready {
  background: #36a269;
}

.principal-dot-pending {
  background: #d49a32;
}

.principal-dot-failed {
  background: #d26b6b;
}

.employee-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 44px;
  padding: 0 12px 0 16px;
  border-top: 1px solid #f0f2f5;
}

.created-time {
  overflow: hidden;
  color: #98a2b3;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.employee-list-view {
  overflow: hidden;
  margin-top: 14px;
  border-radius: 8px;
}

.employee-list-view :deep(.el-table) {
  width: 100%;
}

.list-employee-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.list-avatar {
  width: 34px;
  height: 34px;
  flex: 0 0 auto;
  border-radius: 9px;
  font-size: 12px;
}

.cell-strong {
  overflow: hidden;
  color: #1f2937;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cell-muted {
  color: #64748b;
  font-size: 12px;
}

.employee-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 8px;
}

@media (max-width: 1180px) {
  .employee-gallery {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .employee-page {
    padding: 14px 12px;
  }

  .gallery-header {
    align-items: flex-start;
    flex-direction: column;
    padding: 16px;
  }

  .employee-header-actions {
    width: 100%;
    justify-content: flex-end;
  }

  .gallery-toolbar {
    align-items: stretch;
    flex-direction: column;
    gap: 10px;
  }

  .toolbar-search {
    flex-basis: auto;
    width: 100%;
  }

  .status-tabs {
    overflow-x: auto;
    flex-wrap: nowrap;
    padding-bottom: 2px;
  }

  .status-tab {
    flex: 0 0 auto;
  }

  .toolbar-meta {
    justify-content: space-between;
    margin-left: 0;
  }

  .employee-gallery {
    grid-template-columns: 1fr;
  }

  .employee-pagination {
    overflow-x: auto;
    justify-content: flex-start;
  }

  .listing-hint-content {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
