<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="overview-summary-tab">
    <section class="summary-grid" aria-label="员工摘要">
      <article class="summary-card summary-card-primary">
        <span class="summary-label">员工状态</span>
        <strong>{{ statusLabel(employee.status) }}</strong>
        <span class="summary-hint">{{ employee.employeeCode || `ID: ${employee.id ?? '-'}` }}</span>
      </article>
      <article class="summary-card">
        <span class="summary-label">可用模型</span>
        <strong>{{ employee.modelCount ?? 0 }}</strong>
        <span class="summary-hint">员工级 CHAT 配置</span>
      </article>
      <article class="summary-card">
        <span class="summary-label">已绑定能力</span>
        <strong>{{ employee.capabilityCount ?? 0 }}</strong>
        <span class="summary-hint">包含历史绑定</span>
      </article>
      <article class="summary-card">
        <span class="summary-label">Principal</span>
        <strong>{{ principalLabel(employee.principalStatus) }}</strong>
        <span class="summary-hint">{{ employee.iamPrincipalId || '尚未开通' }}</span>
      </article>
      <article class="summary-card">
        <span class="summary-label">近 7 日工具调用</span>
        <strong>{{ budgetToolCalls }}</strong>
        <span class="summary-hint">本员工运行成本，详见运行</span>
      </article>
    </section>

    <ElAlert
      v-if="needsRelease"
      class="summary-alert"
      type="warning"
      show-icon
      :closable="false"
      title="草稿配置尚未进入生产"
      description="模型、能力或档案变更保存在草稿中，请前往发布与生命周期模块生成并激活新的生产配置。"
    />

    <ElAlert
      v-if="digest?.summary"
      class="summary-alert"
      type="info"
      show-icon
      :closable="false"
      :title="`负责人日报（${digest.digestDate || '-'}）`"
      :description="digest.summary"
    />

    <section class="summary-section">
      <div class="section-heading">
        <div>
          <span class="section-kicker">PROFILE</span>
          <h2>员工档案</h2>
        </div>
        <ElButton link type="primary" @click="goTo('basic-info')">查看基础信息</ElButton>
      </div>
      <div class="profile-grid">
        <div>
          <span class="profile-label">岗位</span>
          <strong>{{ employee.jobTitle || '未设置岗位' }}</strong>
        </div>
        <div>
          <span class="profile-label">来源</span>
          <strong>{{ employee.sourceAgentId ? `DataAgent #${employee.sourceAgentId}` : '空白创建' }}</strong>
        </div>
        <div>
          <span class="profile-label">最近修改</span>
          <strong>{{ formatDateTime(employee.lastModifyTime) }}</strong>
        </div>
        <div>
          <span class="profile-label">草稿修订</span>
          <strong>v{{ employee.draftRevision ?? '-' }}</strong>
        </div>
      </div>
      <p class="profile-description">{{ employee.description || '暂未填写员工描述' }}</p>
    </section>

    <section class="summary-section">
      <div class="section-heading">
        <div>
          <span class="section-kicker">DEPLOYMENT</span>
          <h2>当前部署</h2>
        </div>
        <ElButton link type="primary" @click="goTo('release')">管理发布与生命周期</ElButton>
      </div>
      <div v-loading="deploymentsLoading" class="deployment-grid">
        <div v-for="item in deploymentRows" :key="item.environment" class="deployment-card">
          <div class="deployment-card-head">
            <span>{{ item.label }}</span>
            <ElTag :type="item.status === 'ACTIVE' ? 'success' : 'info'" effect="light" size="small">
              {{ item.statusLabel }}
            </ElTag>
          </div>
          <strong>{{ item.release || '未部署' }}</strong>
          <span>deployment version {{ item.version ?? '-' }}</span>
        </div>
      </div>
    </section>

    <section class="summary-section quick-links">
      <div class="section-heading">
        <div>
          <span class="section-kicker">NEXT STEP</span>
          <h2>配置入口</h2>
        </div>
      </div>
      <div class="quick-link-grid">
        <button v-for="item in quickLinks" :key="item.name" type="button" class="quick-link" @click="goTo(item.name)">
          <span class="quick-link-icon"><ElIcon><component :is="item.icon" /></ElIcon></span>
          <span><strong>{{ item.label }}</strong><small>{{ item.description }}</small></span>
          <ElIcon class="quick-link-arrow"><ArrowRight /></ElIcon>
        </button>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import dayjs from 'dayjs';
import { ArrowRight, Lock, MagicStick, Setting, Upload } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type {
  DigitalEmployee,
  DigitalEmployeeDeployment,
  EmployeeDigest
} from '@/views/ai-agent/services/digitalEmployee';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import {
  EMPLOYEE_STATUS_LABELS,
  PRINCIPAL_STATUS_LABELS
} from '../../employee-support';

defineOptions({ name: 'EmployeeOverviewSummaryTab' });

const props = defineProps<{ employee: DigitalEmployee }>();
const router = useRouter();
const deploymentsLoading = ref(false);
const deployments = ref<DigitalEmployeeDeployment[]>([]);
const digest = ref<EmployeeDigest | null>(null);
const budgetToolCalls = ref('-');

const quickLinks = [
  { name: 'model-config', label: '模型配置', description: '维护员工可用模型', icon: Setting },
  { name: 'capability', label: '员工能力', description: '从技能市场安装能力', icon: MagicStick },
  { name: 'permission-config', label: '权限配置', description: '管理执行身份与角色', icon: Lock },
  { name: 'release', label: '发布与生命周期', description: '发布配置并管理员工状态', icon: Upload }
];

const deploymentRows = computed(() => {
  const find = (environment: string) => deployments.value.find(item => item.environment === environment);
  return [
    { environment: 'PRODUCTION', label: '生产环境', item: find('PRODUCTION') },
    { environment: 'SANDBOX', label: '沙箱环境', item: find('SANDBOX') }
  ].map(row => ({
    ...row,
    release: row.item?.activeReleaseId,
    version: row.item?.deploymentVersion,
    status: row.item?.status,
    statusLabel: row.item?.status === 'ACTIVE' ? '运行中' : row.item ? '未激活' : '未初始化'
  }));
});

const needsRelease = computed(() => {
  if (props.employee.status === 'DRAFT') return true;
  if (props.employee.status === 'ARCHIVED') return false;
  return deployments.value.find(item => item.environment === 'PRODUCTION')?.status !== 'ACTIVE';
});

const statusLabel = (status?: string) => EMPLOYEE_STATUS_LABELS[status || ''] || status || '-';
const principalLabel = (status?: string) => PRINCIPAL_STATUS_LABELS[status || ''] || status || '-';

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

function goTo(tab: string) {
  router.replace({ query: { ...router.currentRoute.value.query, tab } });
}

async function loadDeployments() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  deploymentsLoading.value = true;
  try {
    deployments.value = await digitalEmployeeService.listDeployments(employeeId);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '当前部署查询失败'));
    deployments.value = [];
  } finally {
    deploymentsLoading.value = false;
  }
}

async function loadDigest() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  try {
    digest.value = await digitalEmployeeService.fetchLatestDigest(employeeId);
  } catch {
    digest.value = null;
  }
}

async function loadBudget() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  try {
    const report = await digitalEmployeeService.fetchBudgetReport(employeeId);
    const amount = (report.totals || []).find(item => item.budgetType === 'TOOL_CALL')?.amount;
    if (amount == null || amount === '') {
      budgetToolCalls.value = '-';
      return;
    }
    const numeric = Number(amount);
    budgetToolCalls.value = Number.isFinite(numeric) ? numeric.toLocaleString() : String(amount);
  } catch {
    budgetToolCalls.value = '-';
  }
}

watch(
  () => props.employee.id,
  () => {
    loadDeployments();
    loadDigest();
    loadBudget();
  }
);
onMounted(() => {
  loadDeployments();
  loadDigest();
  loadBudget();
});
</script>

<style scoped>
.overview-summary-tab { display: flex; flex-direction: column; gap: 16px; }
.summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.summary-card, .summary-section { border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-bg-color); }
.summary-card { display: flex; min-height: 118px; flex-direction: column; justify-content: space-between; padding: 16px; }
.summary-card-primary { border-color: color-mix(in srgb, var(--el-color-primary) 35%, var(--el-border-color-lighter)); background: color-mix(in srgb, var(--el-color-primary) 6%, var(--el-bg-color)); }
.summary-label, .profile-label, .section-kicker { color: var(--el-text-color-secondary); font-size: 12px; }
.summary-card strong { color: var(--el-text-color-primary); font-size: 24px; line-height: 1.1; }
.summary-hint { overflow: hidden; color: var(--el-text-color-secondary); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.summary-alert { margin: 0; }
.summary-section { padding: 18px; }
.section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 16px; }
.section-kicker { display: block; margin-bottom: 4px; color: var(--el-color-primary); font-size: 11px; font-weight: 700; letter-spacing: 0.08em; }
.section-heading h2 { margin: 0; color: var(--el-text-color-primary); font-size: 17px; }
.profile-grid, .deployment-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.profile-grid > div { display: flex; min-width: 0; flex-direction: column; gap: 6px; padding: 12px; border-radius: 8px; background: var(--el-fill-color-light); }
.profile-grid strong { overflow: hidden; color: var(--el-text-color-primary); font-size: 14px; text-overflow: ellipsis; white-space: nowrap; }
.profile-description { margin: 14px 0 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.7; }
.deployment-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.deployment-card { display: flex; min-width: 0; flex-direction: column; gap: 8px; padding: 14px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.deployment-card-head { display: flex; align-items: center; justify-content: space-between; color: var(--el-text-color-secondary); font-size: 12px; }
.deployment-card strong { overflow: hidden; color: var(--el-text-color-primary); text-overflow: ellipsis; white-space: nowrap; }
.deployment-card > span:last-child { color: var(--el-text-color-secondary); font-size: 12px; }
.quick-link-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.quick-link { display: flex; min-width: 0; align-items: center; gap: 10px; padding: 12px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; background: transparent; color: inherit; cursor: pointer; text-align: left; transition: border-color 0.2s, background-color 0.2s; }
.quick-link:hover { border-color: var(--el-color-primary-light-5); background: var(--el-fill-color-light); }
.quick-link-icon { display: grid; flex: 0 0 30px; width: 30px; height: 30px; place-items: center; border-radius: 8px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.quick-link > span:nth-child(2) { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 3px; }
.quick-link strong, .quick-link small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.quick-link strong { font-size: 13px; }
.quick-link small { color: var(--el-text-color-secondary); font-size: 11px; }
.quick-link-arrow { color: var(--el-text-color-secondary); }
@media (max-width: 1100px) { .summary-grid, .profile-grid, .quick-link-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .summary-grid, .profile-grid, .deployment-grid, .quick-link-grid { grid-template-columns: 1fr; } .summary-section { padding: 14px; } }
</style>
