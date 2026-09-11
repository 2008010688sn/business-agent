<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="release-tab">
    <section class="lifecycle-section">
      <div class="section-head lifecycle-head">
        <div>
          <h3 class="section-title">发布与生命周期</h3>
          <p class="section-desc">发布生产配置后，才能启用数字员工；停用和封存也在此处统一管理。</p>
        </div>
        <ElTag :type="employeeStatusTagType" effect="light" size="small">{{ employeeStatusLabel }}</ElTag>
      </div>

      <div class="lifecycle-track" aria-label="数字员工生命周期">
        <div class="lifecycle-step" :class="{ 'is-current': lifecycleStep >= 1 }">
          <span class="lifecycle-dot">1</span>
          <span>草稿</span>
        </div>
        <span class="lifecycle-line" :class="{ 'is-complete': lifecycleStep >= 2 }" />
        <div class="lifecycle-step" :class="{ 'is-current': lifecycleStep >= 2 }">
          <span class="lifecycle-dot">2</span>
          <span>已发布</span>
        </div>
        <span class="lifecycle-line" :class="{ 'is-complete': lifecycleStep >= 3 }" />
        <div class="lifecycle-step" :class="{ 'is-current': lifecycleStep >= 3 }">
          <span class="lifecycle-dot">3</span>
          <span>已启用</span>
        </div>
      </div>

      <ElAlert
        v-if="canManage && canEnableEmployee && !hasPublishedProduction"
        class="lifecycle-alert"
        type="warning"
        :closable="false"
        show-icon
        title="请先发布生产配置"
        description="当前没有可用的生产 PUBLISHED Release，发布并激活生产配置后才能启用员工。"
      />

      <div v-if="canManage" class="lifecycle-actions">
        <ElTooltip
          v-if="canEnableEmployee"
          :disabled="hasPublishedProduction"
          content="请先发布并激活生产配置"
          placement="top"
        >
          <span>
            <ElButton
              type="success"
              size="small"
              :loading="lifecycleLoading"
              :disabled="!hasPublishedProduction"
              @click="handleEnable"
            >
              启用员工
            </ElButton>
          </span>
        </ElTooltip>
        <ElButton
          v-if="employee.status === 'ENABLED'"
          type="warning"
          size="small"
          :loading="lifecycleLoading"
          @click="handleDisable"
        >
          停用员工
        </ElButton>
        <ElButton
          v-if="employee.status === 'DISABLED'"
          type="info"
          size="small"
          :loading="lifecycleLoading"
          @click="handleArchive"
        >
          封存员工
        </ElButton>
        <span v-if="employee.status === 'ARCHIVED'" class="cell-muted">已封存，生命周期操作只读</span>
      </div>
    </section>

    <!-- 发布版本管理（/digital-employee-releases） -->
    <section class="release-section">
      <div class="section-head">
        <h3 class="section-title">发布版本</h3>
        <div class="section-head-actions">
          <ElButton v-if="canManage" type="primary" size="small" :loading="publishingCurrent" @click="handlePublishCurrentConfig">
            发布当前配置
          </ElButton>
          <ElButton v-if="canManage" type="primary" size="small" :loading="creating" @click="() => handleCreateDraft()">
            从当前草稿创建发布
          </ElButton>
          <ElButton size="small" :loading="releasesLoading" @click="loadReleases">刷新</ElButton>
        </div>
      </div>
      <p class="section-desc">
        生命周期：草稿 →（Seal 冻结能力清单快照）→ 已封版 →（Publish）→ 已发布 →（Retire）→ 已退役；仅已发布版本可被部署激活。
      </p>

      <ElTable v-loading="releasesLoading" :data="releaseRows" border stripe size="small" empty-text="暂无发布版本">
        <ElTableColumn label="版本" width="80">
          <template #default="{ row }">{{ row.releaseNo != null ? `v${row.releaseNo}` : '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }">
            <ElTag :type="releaseTagType(row.status)" effect="light" size="small">
              {{ releaseLabel(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="spec_hash" width="160">
          <template #default="{ row }">
            <ElTooltip v-if="row.specHash" :content="row.specHash" placement="top">
              <span class="hash-chip">{{ shortHash(row.specHash) }}</span>
            </ElTooltip>
            <span v-else>-</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="封版时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.sealedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="发布时间" width="160">
          <template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="来源" width="120">
          <template #default="{ row }">{{ row.sourceType || '-' }}</template>
        </ElTableColumn>
        <ElTableColumn label="操作" width="220" fixed="right" align="center">
          <template #default="{ row }">
            <ElButton link type="primary" @click="openSnapshot(row)">快照</ElButton>
            <ElButton v-if="canManage && row.status === 'DRAFT'" link type="warning" @click="handleSeal(row)">
              封版
            </ElButton>
            <ElButton v-if="canManage && row.status === 'SEALED'" link type="success" @click="handlePublish(row)">
              发布
            </ElButton>
            <ElButton v-if="canManage && row.status === 'PUBLISHED'" link type="danger" @click="handleRetire(row)">
              退役
            </ElButton>
            <ElButton v-if="canManage && row.status === 'RETIRED'" link @click="handleCopyFrom(row)">复制草稿</ElButton>
          </template>
        </ElTableColumn>
      </ElTable>
    </section>

    <!-- 环境部署（/digital-employees/{id}/deployments） -->
    <section class="release-section">
      <div class="section-head">
        <h3 class="section-title">环境部署</h3>
        <div class="section-head-actions">
          <ElSelect v-model="environment" size="small" class="env-select" @change="loadDeployments">
            <ElOption
              v-for="item in DEPLOYMENT_ENVIRONMENT_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
          <ElButton size="small" :loading="deploymentsLoading" @click="loadDeployments">刷新</ElButton>
        </div>
      </div>

      <ElDescriptions :column="3" border size="small" class="mb-12">
        <ElDescriptionsItem label="当前激活 Release">
          {{ currentDeployment?.activeReleaseId ? releaseLabelById(currentDeployment.activeReleaseId) : '未初始化' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="回滚参照（previous）">
          {{ currentDeployment?.previousReleaseId ? releaseLabelById(currentDeployment.previousReleaseId) : '-' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="deployment_version">
          {{ currentDeployment?.deploymentVersion ?? 0 }}（CAS 基准）
        </ElDescriptionsItem>
      </ElDescriptions>

      <div v-if="canManage" class="deploy-actions">
        <ElSelect
          v-model="activateReleaseId"
          filterable
          clearable
          placeholder="选择要激活的已发布版本"
          size="small"
          class="deploy-select"
        >
          <ElOption
            v-for="row in publishedReleases"
            :key="String(row.id)"
            :label="`v${row.releaseNo ?? '-'}（${String(row.id)}）`"
            :value="String(row.id)"
          />
        </ElSelect>
        <ElButton type="primary" size="small" :loading="deploying" :disabled="!activateReleaseId" @click="handleActivate">
          激活到{{ environmentLabel }}
        </ElButton>
        <ElTooltip content="回滚到 previousReleaseId 指向版本；无历史版本时后端拒绝" placement="top">
          <ElButton
            type="warning"
            size="small"
            plain
            :loading="rollingBack"
            :disabled="!currentDeployment?.previousReleaseId"
            @click="handleRollback"
          >
            回滚
          </ElButton>
        </ElTooltip>
      </div>
    </section>

    <!-- 快照查看（只读 JSON viewer） -->
    <ElDialog v-model="snapshotVisible" title="发布快照（Seal 冻结，只读）" width="720px" destroy-on-close>
      <ElDescriptions :column="2" border size="small" class="mb-12">
        <ElDescriptionsItem label="版本">{{ activeSnapshot?.releaseNo ?? '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="spec_hash">{{ activeSnapshot?.specHash || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="schema_version">{{ activeSnapshot?.schemaVersion || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="封版人">{{ activeSnapshot?.sealedBy || '-' }}</ElDescriptionsItem>
      </ElDescriptions>
      <pre class="snapshot-block">{{ snapshotText }}</pre>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import dayjs from 'dayjs';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployee, DigitalEmployeeDeployment } from '@/views/ai-agent/services/digitalEmployee';
import digitalEmployeeReleaseService from '@/views/ai-agent/services/digitalEmployeeRelease';
import type { DigitalEmployeeRelease } from '@/views/ai-agent/services/digitalEmployeeRelease';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import {
  DEPLOYMENT_ENVIRONMENT_LABELS,
  DEPLOYMENT_ENVIRONMENT_OPTIONS,
  EMPLOYEE_STATUS_LABELS,
  EMPLOYEE_STATUS_TAG_TYPES,
  EMPLOYEE_RELEASE_STATUS_LABELS,
  EMPLOYEE_RELEASE_STATUS_TAG_TYPES
} from '../../employee-support';

defineOptions({ name: 'EmployeeReleaseTab' });

const props = defineProps<{
  employee: DigitalEmployee;
  canManage: boolean;
}>();

const emit = defineEmits<{
  changed: [];
}>();

const releaseRows = ref<DigitalEmployeeRelease[]>([]);
const releasesLoading = ref(false);
const creating = ref(false);
const publishingCurrent = ref(false);
const deploymentsLoading = ref(false);
const deploying = ref(false);
const rollingBack = ref(false);
const lifecycleLoading = ref(false);
const environment = ref('PRODUCTION');
const deployments = ref<DigitalEmployeeDeployment[]>([]);
const activateReleaseId = ref('');
const snapshotVisible = ref(false);
const activeSnapshot = ref<DigitalEmployeeRelease | null>(null);

const environmentLabel = computed(() => DEPLOYMENT_ENVIRONMENT_LABELS[environment.value] || environment.value);

/** 当前环境部署行（未初始化时无该环境行） */
const currentDeployment = computed(() => {
  return deployments.value.find(item => item.environment === environment.value) ?? null;
});

const productionDeployment = computed(() => {
  return deployments.value.find(item => item.environment === 'PRODUCTION') ?? null;
});

/** 可激活的版本：仅 PUBLISHED 状态 */
const publishedReleases = computed(() => releaseRows.value.filter(row => row.status === 'PUBLISHED' && row.id));

const activeProductionRelease = computed(() => {
  const releaseId = productionDeployment.value?.activeReleaseId;
  return releaseId ? releaseRows.value.find(row => String(row.id) === String(releaseId)) : undefined;
});

const hasPublishedProduction = computed(
  () =>
    productionDeployment.value?.status === 'ACTIVE' &&
    Boolean(productionDeployment.value?.activeReleaseId) &&
    activeProductionRelease.value?.status === 'PUBLISHED'
);

const employeeStatus = computed(() => props.employee.status || 'DRAFT');
const canEnableEmployee = computed(() => employeeStatus.value === 'DRAFT' || employeeStatus.value === 'DISABLED');
const employeeStatusLabel = computed(() => EMPLOYEE_STATUS_LABELS[employeeStatus.value] || employeeStatus.value);
const employeeStatusTagType = computed(() => EMPLOYEE_STATUS_TAG_TYPES[employeeStatus.value] || 'info');
const lifecycleStep = computed(() => {
  if (employeeStatus.value === 'ARCHIVED') return 3;
  if (employeeStatus.value === 'ENABLED') return 3;
  return hasPublishedProduction.value ? 2 : 1;
});

const releaseLabel = (status?: string) => EMPLOYEE_RELEASE_STATUS_LABELS[status || ''] || status || '-';
const releaseTagType = (status?: string) => EMPLOYEE_RELEASE_STATUS_TAG_TYPES[status || ''] || 'info';

const releaseLabelById = (releaseId: string) => {
  const matched = releaseRows.value.find(row => String(row.id) === String(releaseId));
  return matched ? `v${matched.releaseNo ?? '-'}（${releaseId}）` : `Release #${releaseId}`;
};

const shortHash = (hash: string) => (hash.length > 14 ? `${hash.slice(0, 14)}…` : hash);

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

/** 快照 JSON 只读格式化展示 */
const snapshotText = computed(() => {
  const snapshot = activeSnapshot.value?.snapshot;
  if (!snapshot) return '-';
  try {
    return JSON.stringify(JSON.parse(snapshot), null, 2);
  } catch {
    return snapshot;
  }
});

watch(
  () => props.employee.id,
  () => {
    releaseRows.value = [];
    deployments.value = [];
    activateReleaseId.value = '';
    loadReleases();
    loadDeployments();
  }
);

onMounted(() => {
  loadReleases();
  loadDeployments();
});

/** 发布版本分页（POST /digital-employee-releases/page，按员工过滤；拉取首页较大页便于版本选择） */
async function loadReleases() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  releasesLoading.value = true;
  try {
    const response = await digitalEmployeeReleaseService.fetchPage({
      employeeId,
      current: 1,
      size: 50
    });
    releaseRows.value = response.data;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '发布版本查询失败'));
  } finally {
    releasesLoading.value = false;
  }
}

/** 全部环境部署行（GET /{id}/deployments） */
async function loadDeployments() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  deploymentsLoading.value = true;
  try {
    deployments.value = await digitalEmployeeService.listDeployments(employeeId);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '部署信息查询失败'));
  } finally {
    deploymentsLoading.value = false;
  }
}

async function handlePublishCurrentConfig() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  publishingCurrent.value = true;
  try {
    await digitalEmployeeService.publishCurrentConfig(employeeId);
    ElMessage.success('已发布当前配置并激活生产');
    emit('changed');
    await Promise.all([loadReleases(), loadDeployments()]);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '发布当前配置失败'));
  } finally {
    publishingCurrent.value = false;
  }
}

function requireStateVersion(): number | null {
  const version = props.employee.stateVersion;
  if (version === null || version === undefined) {
    ElMessage.warning('缺少 stateVersion（CAS 基准），请刷新详情后重试');
    return null;
  }
  return version;
}

async function handleEnable() {
  const employeeId = props.employee.id;
  const stateVersion = requireStateVersion();
  if (!employeeId || stateVersion === null || !hasPublishedProduction.value) return;
  try {
    await ElMessageBox.confirm(
      '启用后员工可承接对话与任务；rollout 开启时后端校验 Principal READY。确认启用？',
      '启用确认',
      { type: 'warning', confirmButtonText: '启用', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runLifecycleAction(
    () => digitalEmployeeService.enable(employeeId, stateVersion),
    '员工已启用',
    '启用失败'
  );
}

async function handleDisable() {
  const employeeId = props.employee.id;
  const stateVersion = requireStateVersion();
  if (!employeeId || stateVersion === null) return;
  try {
    await ElMessageBox.confirm(
      '停用后对话与触发器执行暂停，重新启用需再次通过 Principal 就绪校验。确认停用？',
      '停用确认',
      { type: 'warning', confirmButtonText: '停用', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runLifecycleAction(
    () => digitalEmployeeService.disable(employeeId, stateVersion),
    '员工已停用',
    '停用失败'
  );
}

async function handleArchive() {
  const employeeId = props.employee.id;
  const stateVersion = requireStateVersion();
  if (!employeeId || stateVersion === null) return;
  try {
    await ElMessageBox.confirm(
      '封存后档案只读（DISABLED → ARCHIVED，CAS 防覆盖），不可逆。确认封存？',
      '封存确认',
      { type: 'warning', confirmButtonText: '封存', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runLifecycleAction(
    () => digitalEmployeeService.archive(employeeId, stateVersion),
    '员工已封存',
    '封存失败'
  );
}

async function runLifecycleAction(action: () => Promise<void>, successText: string, failText: string) {
  lifecycleLoading.value = true;
  try {
    await action();
    ElMessage.success(successText);
    emit('changed');
    await Promise.all([loadReleases(), loadDeployments()]);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, failText));
  } finally {
    lifecycleLoading.value = false;
  }
}

/** 创建发布草稿（POST /digital-employee-releases/{employeeId}/create；baseReleaseId 空 = 从当前草稿装配） */
async function handleCreateDraft(baseReleaseId?: string) {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  try {
    await ElMessageBox.confirm(
      baseReleaseId
        ? `将基于 Release ${baseReleaseId} 复制生成新的草稿版本。确认创建？`
        : '将从员工当前草稿配置（含启用能力清单）创建新的 DRAFT 发布版本。确认创建？',
      '创建发布草稿',
      { type: 'info', confirmButtonText: '创建', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  creating.value = true;
  try {
    await digitalEmployeeReleaseService.createDraft(employeeId, baseReleaseId ? { baseReleaseId } : undefined);
    ElMessage.success('发布草稿已创建');
    await loadReleases();
    emit('changed');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '发布草稿创建失败'));
  } finally {
    creating.value = false;
  }
}

/** 复制历史 Release 为新草稿 */
function handleCopyFrom(row: DigitalEmployeeRelease) {
  if (!row.id) return;
  handleCreateDraft(row.id);
}

/** 封版（POST /{releaseId}/seal；以 Seal 时刻草稿+启用能力重新装配快照并冻结） */
async function handleSeal(row: DigitalEmployeeRelease) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(
      `封版 v${row.releaseNo ?? '-'} 将以当前草稿与启用能力清单重新装配快照并冻结（DRAFT → SEALED，CAS）。确认封版？`,
      '封版确认',
      { type: 'warning', confirmButtonText: '封版', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runReleaseAction(() => digitalEmployeeReleaseService.seal(row.id as string), '已封版', '封版失败');
}

/** 发布（POST /{releaseId}/publish；SEALED → PUBLISHED） */
async function handlePublish(row: DigitalEmployeeRelease) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(
      `发布 v${row.releaseNo ?? '-'} 后该版本可被部署激活（SEALED → PUBLISHED，CAS）。确认发布？`,
      '发布确认',
      { type: 'warning', confirmButtonText: '发布', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runReleaseAction(() => digitalEmployeeReleaseService.publish(row.id as string), '已发布', '发布失败');
}

/** 退役（POST /{releaseId}/retire；仍被任务/部署引用时后端拒绝） */
async function handleRetire(row: DigitalEmployeeRelease) {
  if (!row.id) return;
  try {
    await ElMessageBox.confirm(
      `退役 v${row.releaseNo ?? '-'} 后不再可被激活；仍被任务定义或员工部署引用时后端会拒绝。确认退役？`,
      '退役确认',
      { type: 'warning', confirmButtonText: '退役', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  await runReleaseAction(() => digitalEmployeeReleaseService.retire(row.id as string), '已退役', '退役失败');
}

/** Release 状态机操作统一提交 */
async function runReleaseAction(action: () => Promise<void>, successText: string, failText: string) {
  try {
    await action();
    ElMessage.success(successText);
    await loadReleases();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, failText));
  }
}

/** 激活部署（POST /{id}/deployments/activate；deployment_version CAS，未初始化传 0） */
async function handleActivate() {
  const employeeId = props.employee.id;
  const releaseId = activateReleaseId.value;
  if (!employeeId || !releaseId) return;
  const expectVersion = currentDeployment.value?.deploymentVersion ?? 0;
  try {
    await ElMessageBox.confirm(
      `将 Release ${releaseId} 激活到「${environmentLabel.value}」环境（expectVersion=${expectVersion}，CAS 防并发重复部署）。确认激活？`,
      '激活确认',
      { type: 'warning', confirmButtonText: '激活', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  deploying.value = true;
  try {
    await digitalEmployeeService.activateDeployment(employeeId, releaseId, environment.value, expectVersion);
    ElMessage.success('部署已激活');
    activateReleaseId.value = '';
    await Promise.all([loadDeployments(), loadReleases()]);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '部署激活失败'));
  } finally {
    deploying.value = false;
  }
}

/** 回滚（POST /{id}/deployments/rollback；回滚到 previousReleaseId，CAS） */
async function handleRollback() {
  const employeeId = props.employee.id;
  const current = currentDeployment.value;
  if (!employeeId || !current?.previousReleaseId) return;
  const expectVersion = current.deploymentVersion ?? 0;
  try {
    await ElMessageBox.confirm(
      `将「${environmentLabel.value}」环境回滚到 Release ${current.previousReleaseId}（expectVersion=${expectVersion}，CAS）。确认回滚？`,
      '回滚确认',
      { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  rollingBack.value = true;
  try {
    await digitalEmployeeService.rollbackDeployment(employeeId, environment.value, expectVersion);
    ElMessage.success('已回滚');
    await Promise.all([loadDeployments(), loadReleases()]);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '回滚失败'));
  } finally {
    rollingBack.value = false;
  }
}

function openSnapshot(row: DigitalEmployeeRelease) {
  activeSnapshot.value = row;
  snapshotVisible.value = true;
}
</script>

<style scoped>
.release-tab {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.lifecycle-section {
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
}

.lifecycle-head {
  margin-bottom: 14px;
}

.lifecycle-track {
  display: flex;
  align-items: center;
  max-width: 620px;
  margin: 4px 0 16px;
}

.lifecycle-step {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  white-space: nowrap;
}

.lifecycle-step.is-current {
  color: var(--el-color-primary);
  font-weight: 600;
}

.lifecycle-dot {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border: 1px solid var(--el-border-color);
  border-radius: 50%;
  background: var(--el-fill-color-light);
  font-size: 11px;
}

.lifecycle-step.is-current .lifecycle-dot {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.lifecycle-line {
  flex: 1;
  min-width: 28px;
  height: 1px;
  margin: 0 10px;
  background: var(--el-border-color);
}

.lifecycle-line.is-complete {
  background: var(--el-color-primary-light-5);
}

.lifecycle-alert {
  margin-bottom: 12px;
}

.lifecycle-actions {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.release-section {
  padding-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.release-section:last-child {
  border-bottom: none;
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
}

.section-head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.section-title {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
}

.section-desc {
  margin: 0 0 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.env-select {
  width: 160px;
}

.deploy-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.deploy-select {
  width: 280px;
}

.hash-chip {
  cursor: default;
  color: var(--el-color-primary);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.snapshot-block {
  max-height: 420px;
  margin: 0;
  overflow: auto;
  padding: 10px 12px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.mb-12 {
  margin-bottom: 12px;
}

@media (max-width: 640px) {
  .lifecycle-section {
    padding: 14px;
  }

  .lifecycle-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .lifecycle-track {
    width: 100%;
  }

  .lifecycle-line {
    min-width: 12px;
    margin: 0 5px;
  }
}
</style>
