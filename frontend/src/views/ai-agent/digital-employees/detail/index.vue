<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <BaseLayout class="employee-detail-shell">
    <main v-if="employee" class="employee-detail-page">
      <ElCard class="profile-card">
        <section class="profile-header">
          <ElButton link class="back-button" @click="backToList">
            <ElIcon><ArrowLeft /></ElIcon>
            返回列表
          </ElButton>
          <div class="profile-main">
            <div class="employee-avatar" aria-hidden="true">{{ employeeInitials }}</div>
            <div class="profile-copy">
              <div class="profile-name-row">
                <h1>{{ employee.employeeName || '-' }}</h1>
                <ElTag :type="statusTagType(employee.status)" effect="light" size="small">
                  {{ statusLabel(employee.status) }}
                </ElTag>
              </div>
              <p class="profile-subtitle">
                {{ employee.employeeCode || `ID: ${employee.id ?? '-'}` }}
                <template v-if="employee.jobTitle"> · {{ employee.jobTitle }}</template>
                <template v-if="employee.sourceAgentId"> · 来源 DataAgent #{{ employee.sourceAgentId }}</template>
              </p>
              <div class="profile-meta">
                <span>模型 {{ employee.modelCount ?? 0 }}</span>
                <span>能力 {{ employee.capabilityCount ?? 0 }}</span>
                <span>草稿 v{{ employee.draftRevision ?? '-' }}</span>
                <span>更新于 {{ formatDateTime(employee.lastModifyTime) }}</span>
              </div>
            </div>
          </div>
        </section>
      </ElCard>

      <ElCard class="detail-tabs-card mt-8px">
        <div class="detail-workspace">
          <aside class="detail-sidebar" aria-label="数字员工详情导航">
            <nav class="detail-nav">
              <section v-for="group in navGroups" :key="group.label" class="nav-group">
                <h2 class="nav-group-title">{{ group.label }}</h2>
                <button
                  v-for="item in group.items"
                  :key="item.name"
                  type="button"
                  class="nav-item"
                  :class="{ 'is-active': activeTab === item.name }"
                  role="tab"
                  :aria-selected="activeTab === item.name"
                  @click="selectTab(item.name)"
                >
                  <ElIcon><component :is="item.icon" /></ElIcon>
                  <span>{{ item.label }}</span>
                </button>
              </section>
            </nav>
          </aside>

          <section class="detail-content" aria-live="polite">
            <ElTabs v-model="activeTab" class="detail-tabs">
              <ElTabPane label="概览" name="overview">
                <OverviewSummaryTab :employee="employee" />
              </ElTabPane>
              <ElTabPane label="基础信息" name="basic-info" lazy>
                <BasicInfoTab :employee="employee" :can-manage="canManage" :loading="loading" @changed="loadDetail" />
              </ElTabPane>
              <ElTabPane label="模型配置" name="model-config" lazy>
                <ModelConfigTab :employee="employee" :can-manage="canManage" @changed="loadDetail" />
              </ElTabPane>
              <ElTabPane label="能力" name="capability" lazy>
                <CapabilityTab
                  :employee="employee"
                  :can-manage="canManage"
                  :focus-listing-id="listingId"
                  @changed="loadDetail"
                />
              </ElTabPane>
              <ElTabPane label="权限配置" name="permission-config" lazy>
                <PermissionConfigTab :employee="employee" @changed="loadDetail" />
              </ElTabPane>
              <ElTabPane label="发布与生命周期" name="release" lazy>
                <ReleaseTab :employee="employee" :can-manage="canManage" @changed="loadDetail" />
              </ElTabPane>
              <ElTabPane label="对话" name="conversation" lazy>
                <ConversationTab :employee="employee" :can-use="canUse" />
              </ElTabPane>
              <ElTabPane label="任务" name="task" lazy><TaskTab :employee="employee" /></ElTabPane>
              <ElTabPane label="记忆" name="memory" lazy><MemoryTab :employee="employee" /></ElTabPane>
              <ElTabPane label="运行" name="runtime" lazy><RuntimeTab :employee="employee" /></ElTabPane>
              <ElTabPane label="进化" name="evolution" lazy><EvolutionTab :employee="employee" /></ElTabPane>
            </ElTabs>
          </section>
        </div>
      </ElCard>
    </main>

    <ElEmpty v-else-if="!loading" description="未找到数字员工（可能已删除或无权限查看）">
      <ElButton type="primary" @click="backToList">返回列表</ElButton>
    </ElEmpty>
  </BaseLayout>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import {
  ArrowLeft,
  ChatDotRound,
  Clock,
  Lock,
  MagicStick,
  Monitor,
  List,
  Setting,
  TrendCharts,
  Upload,
  User
} from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import { haveAuth } from '@/mixins/userAuth.js';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployee } from '@/views/ai-agent/services/digitalEmployee';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import {
  EMPLOYEE_MANAGE_PERMISSION,
  EMPLOYEE_STATUS_LABELS,
  EMPLOYEE_STATUS_TAG_TYPES,
  EMPLOYEE_USE_PERMISSION
} from '../employee-support';
import OverviewSummaryTab from './components/overview-summary-tab.vue';
import BasicInfoTab from './components/basic-info-tab.vue';
import ConversationTab from './components/conversation-tab.vue';
import CapabilityTab from './components/capability-tab.vue';
import ModelConfigTab from './components/model-config-tab.vue';
import PermissionConfigTab from './components/permission-config-tab.vue';
import ReleaseTab from './components/release-tab.vue';
import TaskTab from './components/task-tab.vue';
import MemoryTab from './components/memory-tab.vue';
import RuntimeTab from './components/runtime-tab.vue';
import EvolutionTab from './components/evolution-tab.vue';

defineOptions({ name: 'DigitalEmployeeDetailPage' });

const LIST_ROUTE_PATH = '/ai-agent/digital-employees';
const TAB_NAMES = [
  'overview',
  'basic-info',
  'model-config',
  'capability',
  'permission-config',
  'release',
  'conversation',
  'task',
  'memory',
  'runtime',
  'evolution'
] as const;
type TabName = (typeof TAB_NAMES)[number];

const route = useRoute();
const router = useRouter();
const canManage = computed(() => haveAuth(EMPLOYEE_MANAGE_PERMISSION));
const canUse = computed(() => haveAuth(EMPLOYEE_USE_PERMISSION));
const employee = ref<DigitalEmployee | null>(null);
const loading = ref(false);
const activeTab = ref<TabName>('overview');

const listingId = computed(() => {
  const raw = route.query.listingId;
  return String(Array.isArray(raw) ? (raw[0] ?? '') : (raw ?? '')).trim() || undefined;
});

const navGroups = [
  {
    label: '基本资料',
    items: [
      { name: 'overview' as TabName, label: '概览', icon: Monitor },
      { name: 'basic-info' as TabName, label: '基础信息', icon: User }
    ]
  },
  {
    label: '员工配置',
    items: [
      { name: 'model-config' as TabName, label: '模型配置', icon: Setting },
      { name: 'capability' as TabName, label: '能力', icon: MagicStick },
      { name: 'permission-config' as TabName, label: '权限配置', icon: Lock },
      { name: 'release' as TabName, label: '发布与生命周期', icon: Upload }
    ]
  },
  {
    label: '运行与观察',
    items: [
      { name: 'conversation' as TabName, label: '对话', icon: ChatDotRound },
      { name: 'task' as TabName, label: '任务', icon: List },
      { name: 'memory' as TabName, label: '记忆', icon: Clock },
      { name: 'runtime' as TabName, label: '运行', icon: Monitor },
      { name: 'evolution' as TabName, label: '进化', icon: TrendCharts }
    ]
  }
];

const statusLabel = (status?: string) => EMPLOYEE_STATUS_LABELS[status || ''] || status || '-';
const statusTagType = (status?: string) => EMPLOYEE_STATUS_TAG_TYPES[status || ''] || 'info';
const employeeInitials = computed(() => {
  const name = employee.value?.employeeName?.trim() || '数';
  return name.slice(0, 2);
});

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
};

function queryString(value: unknown) {
  return String(Array.isArray(value) ? (value[0] ?? '') : (value ?? '')).trim();
}

function resolveActiveTab(): TabName {
  const requested = queryString(route.query.tab) as TabName;
  if (TAB_NAMES.includes(requested)) return requested;
  if (!requested && listingId.value) return 'capability';
  if (!requested && queryString(route.query.runtimeRunId)) return 'runtime';
  return 'overview';
}

function syncActiveTab() {
  activeTab.value = resolveActiveTab();
}

function selectTab(tab: TabName) {
  activeTab.value = tab;
  router.replace({ query: { ...route.query, tab } });
}

const resolveEmployeeId = (): string => queryString(route.query.id);

async function loadDetail() {
  const employeeId = resolveEmployeeId();
  if (!employeeId || !/^\d+$/.test(employeeId)) {
    employee.value = null;
    return;
  }
  loading.value = true;
  try {
    employee.value = await digitalEmployeeService.fetchDetail(employeeId);
  } catch (error) {
    console.error('加载数字员工详情失败, employeeId=%s:', employeeId, error);
    if (shouldShowLocalApiError(error)) ElMessage.error(extractApiErrorMessage(error, '数字员工详情加载失败'));
    employee.value = null;
  } finally {
    loading.value = false;
  }
}

function backToList() {
  router.push(LIST_ROUTE_PATH);
}

watch(() => route.query.id, loadDetail, { immediate: true });
watch(() => [route.query.tab, route.query.listingId, route.query.runtimeRunId], syncActiveTab, { immediate: true });
</script>

<style scoped>
.employee-detail-shell { display: flex; overflow: hidden; flex-direction: column; min-height: 0; }
.employee-detail-page { display: flex; overflow: hidden; flex: 1 1 0; flex-direction: column; min-height: 0; }
.profile-card, .detail-tabs-card { border-radius: 12px; }
.profile-header { display: flex; flex-direction: column; gap: 12px; }
.back-button { align-self: flex-start; }
.profile-main { display: flex; align-items: center; gap: 18px; min-width: 0; }
.employee-avatar { display: grid; flex: 0 0 72px; width: 72px; height: 72px; place-items: center; border-radius: 18px; background: linear-gradient(145deg, var(--el-color-primary-light-7), var(--el-color-primary-light-9)); color: var(--el-color-primary); font-size: 25px; font-weight: 700; }
.profile-copy { min-width: 0; }
.profile-name-row { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.profile-name-row h1 { margin: 0; color: var(--el-text-color-primary); font-size: 24px; line-height: 1.25; }
.profile-subtitle { margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 13px; }
.profile-meta { display: flex; flex-wrap: wrap; gap: 8px 16px; margin-top: 12px; color: var(--el-text-color-secondary); font-size: 12px; }
.detail-tabs-card { display: flex; overflow: hidden; flex: 1 1 0; min-height: 0; }
.detail-tabs-card :deep(.el-card__body) { display: flex; overflow: hidden; flex: 1 1 0; min-height: 0; padding: 0; }
.detail-workspace { display: flex; overflow: hidden; flex: 1 1 0; min-height: 0; }
.detail-sidebar { flex: 0 0 218px; overflow-y: auto; border-right: 1px solid var(--el-border-color-lighter); background: var(--el-fill-color-lighter); }
.detail-nav { display: flex; flex-direction: column; gap: 18px; padding: 18px 12px; }
.nav-group { display: flex; flex-direction: column; gap: 4px; }
.nav-group-title { margin: 0 10px 4px; color: var(--el-text-color-secondary); font-size: 11px; font-weight: 700; letter-spacing: 0.08em; }
.nav-item { display: flex; width: 100%; align-items: center; gap: 10px; padding: 10px 12px; border: 0; border-radius: 8px; background: transparent; color: var(--el-text-color-regular); cursor: pointer; font: inherit; text-align: left; transition: color 0.2s, background-color 0.2s, box-shadow 0.2s; }
.nav-item:hover { background: var(--el-fill-color); color: var(--el-color-primary); }
.nav-item.is-active { background: var(--el-bg-color); box-shadow: inset 3px 0 0 var(--el-color-primary), 0 1px 2px rgb(15 23 42 / 4%); color: var(--el-color-primary); font-weight: 600; }
.nav-item:focus-visible { outline: 2px solid var(--el-color-primary-light-5); outline-offset: 1px; }
.detail-content { display: flex; overflow: auto; flex: 1 1 0; min-width: 0; min-height: 0; padding: 20px; }
.detail-tabs { display: flex; flex: 1 1 0; flex-direction: column; min-width: 0; min-height: 0; }
.detail-tabs :deep(.el-tabs__header) { display: none; }
.detail-tabs :deep(.el-tabs__content) { overflow: visible; flex: 1 1 auto; min-height: 0; }
.detail-tabs :deep(.el-tab-pane) { min-height: 100%; }
.mt-8px { margin-top: 8px; }
@media (max-width: 760px) {
  .profile-main { align-items: flex-start; }
  .profile-name-row h1 { font-size: 21px; }
  .detail-workspace { flex-direction: column; }
  .detail-sidebar { flex: 0 0 auto; overflow: hidden; border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter); }
  .detail-nav { flex-direction: row; gap: 6px; overflow-x: auto; padding: 8px; }
  .nav-group { flex-direction: row; flex: 0 0 auto; gap: 4px; }
  .nav-group-title { display: none; }
  .nav-item { width: auto; flex: 0 0 auto; white-space: nowrap; }
  .detail-content { padding: 14px; }
}
@media (max-width: 480px) {
  .profile-main { gap: 12px; }
  .employee-avatar { flex-basis: 56px; width: 56px; height: 56px; border-radius: 14px; font-size: 20px; }
  .profile-meta { gap: 6px 10px; }
}
</style>
