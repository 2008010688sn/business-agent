<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="binding-panel">
    <ElCard shadow="never">
      <template #header>
        <div class="card-header">
          <span>策略绑定管理</span>
          <span class="card-header-sub">
            owner + 环境维度唯一绑定；更新需携带 expectedBindRevision 乐观锁，并发冲突时后端返回 400 与当前值
          </span>
        </div>
      </template>

      <!-- 查询条件 -->
      <section class="filter-panel">
        <ElForm label-width="90px" inline>
          <ElFormItem label="主体类型">
            <ElSelect v-model="query.ownerType" style="width: 160px" @change="handleOwnerTypeChange">
              <ElOption v-for="item in OWNER_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="主体">
            <ElSelect
              v-if="query.ownerType === 'DATA_AGENT'"
              v-model="query.ownerId"
              filterable
              clearable
              placeholder="选择数据智能体"
              :loading="agentOptionsLoading"
              style="width: 260px"
            >
              <ElOption v-for="item in agentOptions" :key="String(item.id)" :label="item.name || String(item.id)" :value="String(item.id)" />
            </ElSelect>
            <EmployeeOptionSelect v-else v-model="query.ownerId" placeholder="选择数字员工" @change="loadEmployeeIdentity" />
          </ElFormItem>
          <ElFormItem label="生效环境">
            <ElSelect v-model="query.environment" style="width: 140px">
              <ElOption v-for="item in ENVIRONMENT_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </ElFormItem>
          <ElButton v-if="canQuery" type="primary" :loading="loading" @click="loadBinding">
            <ElIcon><Search /></ElIcon>
            查询绑定
          </ElButton>
        </ElForm>
      </section>

      <ElDescriptions
        v-if="query.ownerType === 'DIGITAL_EMPLOYEE' && employeeIdentity"
        title="执行身份（只读）"
        :column="3"
        border
        size="small"
        class="binding-desc"
      >
        <ElDescriptionsItem label="员工">{{ employeeIdentity.employeeName || employeeIdentity.id }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Principal">{{ employeeIdentity.iamPrincipalId || '未开通' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="Principal 状态">{{ employeeIdentity.principalStatus || '-' }}</ElDescriptionsItem>
      </ElDescriptions>

      <!-- 当前绑定信息 -->
      <ElDescriptions v-if="bindingLoaded" :column="3" border size="small" class="binding-desc">
        <ElDescriptionsItem label="绑定状态">
          <ElTag v-if="binding.id" type="success" size="small">已绑定</ElTag>
          <ElTag v-else type="info" size="small">未绑定（保存即创建，bindRevision=0）</ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="策略版本 ID">{{ binding.policyVersionId || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="乐观锁版本（bindRevision）">{{ binding.bindRevision ?? '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="绑定 ID">{{ binding.id || '-' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="更新时间">{{ formatDateTime(binding.lastModifyTime) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="创建时间">{{ formatDateTime(binding.createTime) }}</ElDescriptionsItem>
      </ElDescriptions>

      <!-- 保存/改绑 -->
      <ElDivider content-position="left">{{ binding.id ? '改绑策略版本' : '新建绑定' }}</ElDivider>
      <ElForm label-width="90px" inline>
        <ElFormItem label="策略">
          <ElSelect
            v-model="upsert.policyId"
            filterable
            clearable
            placeholder="选择策略（加载其版本）"
            :loading="policyOptionsLoading"
            style="width: 280px"
            @change="handlePolicyChange"
          >
            <ElOption
              v-for="item in policyOptions"
              :key="String(item.id)"
              :label="`${item.code}（${item.name || '-'}）`"
              :value="String(item.id)"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="策略版本">
          <ElSelect
            v-model="upsert.policyVersionId"
            filterable
            placeholder="选择策略版本"
            :disabled="!upsert.policyId"
            :loading="versionsLoading"
            style="width: 280px"
          >
            <ElOption
              v-for="item in versionOptions"
              :key="String(item.id)"
              :label="`v${item.versionNo}${item.published ? '（已发布）' : '（草稿）'}`"
              :value="String(item.id)"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem v-if="binding.id" label="期望版本号">
          <ElInput v-model.number="upsert.expectedBindRevision" style="width: 140px" placeholder="bindRevision" />
        </ElFormItem>
        <ElButton
          v-if="canManage"
          type="primary"
          :loading="submitting"
          :disabled="!canSubmitUpsert"
          @click="handleUpsert"
        >
          {{ binding.id ? '保存改绑' : '创建绑定' }}
        </ElButton>
      </ElForm>
      <div v-if="binding.id" class="upsert-tip">
        已有绑定走乐观锁 CAS 更新：默认自动带入查询到的 bindRevision；保存冲突（400）时请重新查询绑定刷新后重试。
      </div>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Search } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import { haveAuth } from '@/mixins/userAuth.js';
import agentService from '@/views/ai-agent/services/agent';
import type { Agent } from '@/views/ai-agent/services/agent';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployee } from '@/views/ai-agent/services/digitalEmployee';
import EmployeeOptionSelect from '@/views/ai-agent/components/employee-option-select.vue';
import authorizationService from '@/views/ai-agent/services/authorization';
import type {
  BindingEntity,
  BindingOwnerType,
  Environment,
  PolicyEntity,
  PolicyVersionResp
} from '@/views/ai-agent/services/authorization';
import { extractApiErrorMessage, getResponseStatus } from '@/views/ai-agent/services/common';
import { AUTHORIZATION_MANAGE_PERMISSION, AUTHORIZATION_QUERY_PERMISSION, ENVIRONMENT_OPTIONS, OWNER_TYPE_OPTIONS } from '../authorization-constants';

defineOptions({ name: 'AuthorizationBindingPanel' });

const canQuery = computed(() => haveAuth(AUTHORIZATION_QUERY_PERMISSION));
const canManage = computed(() => haveAuth(AUTHORIZATION_MANAGE_PERMISSION));
const route = useRoute();

const query = reactive<{ ownerType: BindingOwnerType; ownerId: string; environment: Environment }>({
  ownerType: 'DATA_AGENT',
  ownerId: '',
  environment: 'SANDBOX'
});

const loading = ref(false);
const bindingLoaded = ref(false);
const binding = ref<BindingEntity>({});
const submitting = ref(false);

const agentOptions = ref<Agent[]>([]);
const agentOptionsLoading = ref(false);
const employeeIdentity = ref<DigitalEmployee | null>(null);

const policyOptions = ref<PolicyEntity[]>([]);
const policyOptionsLoading = ref(false);

const versionOptions = ref<PolicyVersionResp[]>([]);
const versionsLoading = ref(false);

const upsert = reactive<{ policyId: string; policyVersionId: string; expectedBindRevision?: number }>({
  policyId: '',
  policyVersionId: '',
  expectedBindRevision: undefined
});

const canSubmitUpsert = computed(() => Boolean(query.ownerId && query.ownerType && query.environment && upsert.policyVersionId));

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

function handleOwnerTypeChange() {
  query.ownerId = '';
  employeeIdentity.value = null;
}

async function loadEmployeeIdentity() {
  employeeIdentity.value = null;
  if (query.ownerType !== 'DIGITAL_EMPLOYEE' || !query.ownerId) return;
  try {
    employeeIdentity.value = await digitalEmployeeService.fetchDetail(query.ownerId);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '数字员工身份加载失败'));
  }
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

async function loadPolicyOptions() {
  policyOptionsLoading.value = true;
  try {
    const response = await authorizationService.pagePolicies({ current: 1, size: 100 });
    policyOptions.value = response.data;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '策略列表加载失败'));
  } finally {
    policyOptionsLoading.value = false;
  }
}

/** 查询当前绑定：无绑定为空对象（后端语义），前端据此区分创建/改绑。 */
async function loadBinding() {
  if (!query.ownerId) {
    ElMessage.warning('请先选择/输入绑定主体');
    return;
  }
  loading.value = true;
  try {
    const result = await authorizationService.fetchBinding(query.ownerType, query.ownerId, query.environment);
    binding.value = result || {};
    bindingLoaded.value = true;
    // 已有绑定时自动带入乐观锁版本，供 upsert CAS 校验
    upsert.expectedBindRevision = binding.value.id ? Number(binding.value.bindRevision ?? 0) : undefined;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '绑定查询失败'));
  } finally {
    loading.value = false;
  }
}

/** 选择策略后加载其版本列表（绑定指向具体策略版本，运行时以绑定版本为准）。 */
async function handlePolicyChange(policyId: string) {
  upsert.policyVersionId = '';
  versionOptions.value = [];
  if (!policyId) return;

  versionsLoading.value = true;
  try {
    versionOptions.value = (await authorizationService.listPolicyVersions(policyId)).filter(item => item.published);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '策略版本列表加载失败'));
  } finally {
    versionsLoading.value = false;
  }
}

async function handleUpsert() {
  if (!canSubmitUpsert.value) return;
  submitting.value = true;
  try {
    const saved = await authorizationService.upsertBinding({
      ownerType: query.ownerType,
      ownerId: query.ownerId,
      environment: query.environment,
      policyVersionId: upsert.policyVersionId,
      expectedBindRevision: binding.value.id ? upsert.expectedBindRevision : undefined
    });
    ElMessage.success(binding.value.id ? '改绑成功' : '绑定创建成功');
    binding.value = saved || {};
    upsert.expectedBindRevision = saved?.id ? Number(saved.bindRevision ?? 0) : undefined;
  } catch (error) {
    // 乐观锁冲突（后端 400 + 当前 bindRevision）：提示刷新重试而非静默覆盖
    const status = getResponseStatus(error);
    const message = extractApiErrorMessage(error, '保存失败');
    if (status === 400) {
      ElMessage.error(`${message}（并发冲突：请点击「查询绑定」刷新最新 bindRevision 后重试）`);
    } else {
      ElMessage.error(message);
    }
  } finally {
    submitting.value = false;
  }
}

onMounted(async () => {
  if (!canQuery.value) return;
  loadAgentOptions();
  loadPolicyOptions();
  applyOwnerFromRoute();
  if (query.ownerId) {
    await loadEmployeeIdentity();
    await loadBinding();
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
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 400;
}

.filter-panel {
  margin-bottom: 12px;
}

.filter-panel :deep(.el-form-item) {
  margin-bottom: 10px;
}

.binding-desc {
  margin-bottom: 16px;
}

.upsert-tip {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
