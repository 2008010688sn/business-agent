<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div v-if="employee" class="principal-permission-panel">
    <ElAlert class="mb-16" type="info" :closable="false" title="执行身份与角色">
      <p>数据范围来自员工执行身份绑定的 IAM 角色，SQL 改写跟 Principal 的数据权限走。</p>
      <p>工具/技能能力和 Grant USE 仍由全局权限中心 PAP 管理，不在本模块重复配置。</p>
      <ElButton type="primary" link @click="goAuthorizationCenter">打开该员工的权限中心</ElButton>
    </ElAlert>

    <ElDescriptions :column="1" border size="small" class="mb-16">
      <ElDescriptionsItem label="Principal ID">
        <span class="principal-id">{{ employee.iamPrincipalId || '未开通' }}</span>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="开通状态">
        <ElTag :type="principalTagType(employee.principalStatus)" effect="light" size="small">
          {{ principalLabel(employee.principalStatus) }}
        </ElTag>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="authRevision（本地缓存）">{{ employee.principalRevision ?? '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="灰度开关">
        {{ employee.rolloutEnabled === false ? '关闭（不可 PRINCIPAL）' : '开启 / 未回传' }}
      </ElDescriptionsItem>
    </ElDescriptions>

    <section v-if="canManageEmployee" class="section-block">
      <h3 class="section-title">Principal 开通</h3>
      <p class="section-desc">rollout 开启后由后端幂等开通；开通失败或待开通时可在此手动重试。</p>
      <ElButton type="primary" plain size="small" :loading="provisioning" :disabled="provisionDisabled" @click="handleProvision">
        {{ provisionButtonLabel }}
      </ElButton>
    </section>

    <section v-if="canManageEmployee" class="section-block">
      <h3 class="section-title">角色分配（全量替换）</h3>
      <p class="section-desc">角色决定员工可查询的数据范围；清空提交后降级为 MODEL_ONLY。</p>
      <ElSelect
        v-model="selectedRoleIds"
        multiple
        filterable
        clearable
        collapse-tags
        collapse-tags-tooltip
        placeholder="选择角色（可多选）"
        :loading="rolesLoading"
        :disabled="!hasPrincipal"
        class="role-select"
      >
        <ElOption v-for="role in roleOptions" :key="String(role.id)" :label="roleLabel(role)" :value="String(role.id)" />
      </ElSelect>
      <div class="section-actions">
        <ElButton type="primary" size="small" :loading="submittingRoles" :disabled="rolesLoading || !hasPrincipal" @click="submitRoles">
          {{ selectedRoleIds.length === 0 ? '提交（清空为 MODEL_ONLY）' : '提交角色集' }}
        </ElButton>
        <span v-if="!hasPrincipal" class="section-empty">请先开通 Principal</span>
        <span v-else-if="assignableRoles.length === 0 && !rolesLoading" class="section-empty">暂无可分配角色</span>
      </div>
    </section>

    <section v-if="canManageEmployee" class="section-block">
      <h3 class="section-title">Principal 状态</h3>
      <p class="section-desc">停用会踢出在线会话并递增 auth_revision；重新启用后恢复可签发。</p>
      <div class="section-actions">
        <ElButton type="warning" size="small" :loading="disabling" :disabled="!hasPrincipal" @click="disablePrincipal">停用 Principal</ElButton>
        <ElButton type="success" size="small" plain :loading="enabling" :disabled="!hasPrincipal" @click="enablePrincipal">启用 Principal</ElButton>
      </div>
    </section>

    <section class="section-block">
      <h3 class="section-title">有效权限预览</h3>
      <p class="section-desc">走 AI Facade 的 auth-preview，前端不调用 IAM /internal。</p>
      <ElButton size="small" :loading="previewLoading" :disabled="!hasPrincipal" @click="loadAuthPreview">刷新预览</ElButton>
      <div v-if="authPreview" class="preview-box">
        <p class="preview-meta">authRevision={{ authPreview.authRevision ?? '-' }}</p>
        <div v-if="authPreview.funcPermissions?.length" class="permission-tags">
          <ElTag v-for="code in authPreview.funcPermissions" :key="code" size="small" effect="plain">{{ code }}</ElTag>
        </div>
        <p v-else class="section-empty">当前无有效功能权限码（MODEL_ONLY，仅试对话）</p>
      </div>
    </section>

    <ElAlert class="mt-16" type="info" :closable="false" title="边界说明" description="角色替换与启停走员工 Principal API；不创建真人账号、不写 t_user、不调用 /internal/**。" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { haveAuth } from '@/mixins/userAuth.js';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployee, EmployeeAuthPreview, EmployeePrincipalRole } from '@/views/ai-agent/services/digitalEmployee';
import servicePrincipalService, { isRoleAssignable } from '@/views/ai-agent/services/servicePrincipal';
import type { RoleOption } from '@/views/ai-agent/services/servicePrincipal';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { EMPLOYEE_MANAGE_PERMISSION, PRINCIPAL_STATUS_LABELS, PRINCIPAL_STATUS_TAG_TYPES } from '../../employee-support';

defineOptions({ name: 'PrincipalPermissionPanel' });

const props = defineProps<{ employee: DigitalEmployee }>();
const emit = defineEmits<{ changed: [] }>();
const router = useRouter();

const canManageEmployee = computed(() => haveAuth(EMPLOYEE_MANAGE_PERMISSION));
const hasPrincipal = computed(() => Boolean(props.employee.iamPrincipalId));
const rolloutDisabled = computed(() => props.employee.rolloutEnabled === false);
const provisionDisabled = computed(() => props.employee.principalStatus === 'READY' || rolloutDisabled.value);
const provisionButtonLabel = computed(() => {
  if (props.employee.principalStatus === 'READY') return '已就绪';
  if (rolloutDisabled.value) return '灰度未开启';
  return '触发开通 / 重试';
});

const provisioning = ref(false);
const rolesLoading = ref(false);
const submittingRoles = ref(false);
const disabling = ref(false);
const enabling = ref(false);
const previewLoading = ref(false);
const assignableRoles = ref<RoleOption[]>([]);
const boundRoles = ref<EmployeePrincipalRole[]>([]);
const selectedRoleIds = ref<string[]>([]);
const authPreview = ref<EmployeeAuthPreview | null>(null);

const principalLabel = (status?: string) => PRINCIPAL_STATUS_LABELS[status || ''] || status || '-';
const principalTagType = (status?: string) => PRINCIPAL_STATUS_TAG_TYPES[status || ''] || 'info';
const roleOptions = computed(() => {
  const merged = new Map<string, RoleOption>();
  assignableRoles.value.forEach(role => role.id && merged.set(String(role.id), role));
  boundRoles.value.forEach(role => {
    const id = String(role.id || '');
    if (id && !merged.has(id)) merged.set(id, role);
  });
  return Array.from(merged.values());
});
const roleLabel = (role: RoleOption) => {
  const name = role.name || `角色 #${role.id ?? '-'}`;
  const code = role.code ? `（${role.code}）` : '';
  return `${name}${code}${isRoleAssignable(role) ? '' : ' · 当前已绑定但不可再分配'}`;
};

async function loadRoles() {
  rolesLoading.value = true;
  try {
    const catalog = await servicePrincipalService.listRoles();
    assignableRoles.value = catalog.filter(isRoleAssignable);
    const employeeId = props.employee.id;
    boundRoles.value = employeeId ? await digitalEmployeeService.listPrincipalRoles(employeeId) : [];
    selectedRoleIds.value = boundRoles.value.map(role => String(role.id)).filter(Boolean);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '角色列表加载失败'));
    assignableRoles.value = [];
    boundRoles.value = [];
    selectedRoleIds.value = [];
  } finally {
    rolesLoading.value = false;
  }
}

async function loadAuthPreview() {
  if (!props.employee.id || !hasPrincipal.value) return;
  previewLoading.value = true;
  try {
    authPreview.value = await digitalEmployeeService.previewPrincipalAuth(props.employee.id);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '有效权限预览失败'));
  } finally {
    previewLoading.value = false;
  }
}

async function handleProvision() {
  if (!props.employee.id) return;
  provisioning.value = true;
  try {
    await digitalEmployeeService.provisionPrincipal(props.employee.id);
    ElMessage.success('Principal 开通已触发');
    emit('changed');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, 'Principal 开通触发失败'));
  } finally {
    provisioning.value = false;
  }
}

async function submitRoles() {
  if (!props.employee.id || !hasPrincipal.value) {
    ElMessage.warning('Principal 尚未开通，请先触发开通');
    return;
  }
  const isEmpty = selectedRoleIds.value.length === 0;
  try {
    await ElMessageBox.confirm(
      isEmpty ? '当前选择为空，提交后将退化为 MODEL_ONLY。确认提交？' : `将全量替换为所选 ${selectedRoleIds.value.length} 个角色，确认提交？`,
      isEmpty ? '清空角色确认' : '替换角色确认',
      { type: 'warning', confirmButtonText: '确认提交', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  submittingRoles.value = true;
  try {
    await digitalEmployeeService.replacePrincipalRoles(props.employee.id, selectedRoleIds.value);
    ElMessage.success(isEmpty ? '角色已清空（MODEL_ONLY）' : '角色集已更新');
    emit('changed');
    await loadAuthPreview();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '角色替换失败'));
  } finally {
    submittingRoles.value = false;
  }
}

async function updatePrincipalStatus(status: 'ENABLED' | 'DISABLED') {
  if (!props.employee.id || !hasPrincipal.value) return;
  const disablingAction = status === 'DISABLED';
  try {
    await ElMessageBox.confirm(
      disablingAction ? '停用后 Principal 在线会话将被踢出，确认停用？' : '确认重新启用该 Principal？',
      disablingAction ? '停用 Principal' : '启用 Principal',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  const pending = disablingAction ? disabling : enabling;
  pending.value = true;
  try {
    await digitalEmployeeService.updatePrincipalStatus(props.employee.id, status);
    ElMessage.success(disablingAction ? 'Principal 已停用' : 'Principal 已启用');
    emit('changed');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, 'Principal 状态变更失败'));
  } finally {
    pending.value = false;
  }
}

const disablePrincipal = () => updatePrincipalStatus('DISABLED');
const enablePrincipal = () => updatePrincipalStatus('ENABLED');

function goAuthorizationCenter() {
  if (!props.employee.id) return;
  router.push({
    path: '/ai-agent/authorizations',
    query: { ownerType: 'DIGITAL_EMPLOYEE', ownerId: String(props.employee.id), tab: 'binding' }
  });
}

watch(() => props.employee.id, () => {
  authPreview.value = null;
  loadRoles();
  loadAuthPreview();
});
onMounted(() => {
  loadRoles();
  loadAuthPreview();
});
</script>

<style scoped>
.principal-permission-panel { display: flex; flex-direction: column; gap: 4px; }
.principal-id { font-family: Consolas, Monaco, monospace; font-size: 13px; }
.section-block { padding: 12px 0; border-top: 1px solid var(--el-border-color-lighter); }
.section-title { margin: 0 0 6px; color: var(--el-text-color-primary); font-size: 14px; font-weight: 600; }
.section-desc { margin: 0 0 8px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.section-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.section-empty { color: var(--el-text-color-secondary); font-size: 12px; }
.role-select { width: 100%; }
.preview-box { margin-top: 10px; }
.preview-meta { margin: 0 0 8px; color: var(--el-text-color-secondary); font-size: 12px; }
.permission-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.mb-16 { margin-bottom: 16px; }
.mt-16 { margin-top: 16px; }
</style>
