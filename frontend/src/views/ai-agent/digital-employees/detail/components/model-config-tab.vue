<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 -->

<template>
  <div class="model-config-tab">
    <ElAlert
      class="model-hint"
      type="info"
      show-icon
      :closable="false"
      title="模型配置独立于能力"
      description="这里设置员工可用的对话模型和默认模型。修改的是员工草稿，保存后仍需发布当前配置才会进入生产。"
    />
    <ElAlert
      v-if="loadError"
      class="model-hint"
      type="error"
      show-icon
      :closable="false"
      title="配置加载失败"
      description="请先刷新并成功加载完整配置，当前不会提交保存。"
    />
    <ElAlert
      v-if="routeLoadError"
      class="model-hint"
      type="warning"
      show-icon
      :closable="false"
      title="路由档案列表暂不可用"
      description="配置加载不完整，当前禁止保存。请先刷新并成功加载路由档案；已有路由档案会按历史配置保留。"
    />

    <section class="model-toolbar">
      <div>
        <h3>员工可用模型</h3>
        <p>展示平台中的 CHAT 模型。勾选后可供该员工使用；标记为可选的模型可在对话页切换。</p>
      </div>
      <div class="toolbar-actions">
        <ElButton :loading="loading" @click="load">刷新</ElButton>
        <ElButton
          v-if="canManage"
          type="primary"
          :loading="saving"
          :disabled="loadError || routeLoadError || loading"
          @click="save"
        >保存模型配置</ElButton>
      </div>
    </section>

    <ElForm label-width="110px" class="model-form">
      <ElFormItem label="默认模型">
        <ElSelect
          v-model="defaultModelConfigId"
          filterable
          clearable
          :disabled="!canManage || loading"
          placeholder="未设置时使用运行时默认模型"
          class="default-select"
        >
          <ElOption
            v-for="model in chatModels"
            :key="String(model.id)"
            :label="modelLabel(model)"
            :value="String(model.id)"
          />
        </ElSelect>
      </ElFormItem>
    </ElForm>

    <ElTable
      v-loading="loading"
      :data="modelRows"
      border
      stripe
      row-key="id"
      empty-text="暂无 CHAT 模型，请先在模型中心添加对话模型"
    >
      <ElTableColumn label="允许使用" width="110" align="center">
        <template #default="{ row }">
          <ElSwitch v-model="row.enabled" :disabled="!canManage" @change="syncDefaultModel" />
        </template>
      </ElTableColumn>
      <ElTableColumn label="对话页可切换" width="140" align="center">
        <template #default="{ row }">
          <ElSwitch v-model="row.userSelectable" :disabled="!canManage || !row.enabled" />
        </template>
      </ElTableColumn>
      <ElTableColumn label="模型" min-width="260" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="model-name">{{ row.modelName }}</div>
          <div class="model-meta">{{ row.provider }} · {{ row.baseUrl || '默认地址' }}</div>
        </template>
      </ElTableColumn>
      <ElTableColumn label="平台默认" width="110" align="center">
        <template #default="{ row }">
          <ElTag :type="row.isActive ? 'success' : 'info'" effect="plain" size="small">
            {{ row.isActive ? '是' : '否' }}
          </ElTag>
        </template>
      </ElTableColumn>
    </ElTable>

    <section class="runtime-section">
      <div class="section-title-row">
        <div>
          <h3>运行配置</h3>
          <p>路由、提示词和预算与模型配置一起保存到员工草稿。</p>
        </div>
      </div>
      <ElForm label-width="110px" class="runtime-form">
        <ElFormItem label="路由档案">
          <ElSelect v-model="runtime.routeProfileId" filterable clearable :disabled="!canManage || loading" placeholder="跟随租户当前激活画像">
          <ElOption
            v-if="runtime.routeProfileId && !hasRouteOption(runtime.routeProfileId)"
            :label="`当前配置（${runtime.routeProfileId}，历史）`"
            :value="runtime.routeProfileId"
          />
          <ElOption v-for="item in routeOptions" :key="String(item.id)" :label="`${item.profileName || '路由档案'}（${item.status}）`" :value="String(item.id)" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="系统提示词">
          <ElInput v-model="runtime.systemInstruction" type="textarea" :rows="4" maxlength="8000" show-word-limit :disabled="!canManage || loading" />
        </ElFormItem>
        <ElFormItem label="开场白">
          <ElInput v-model="runtime.greeting" type="textarea" :rows="2" maxlength="500" :disabled="!canManage || loading" />
        </ElFormItem>
        <ElFormItem label="预算">
          <div class="budget-row">
            <ElInputNumber v-model="runtime.maxPromptTokens" :min="0" :disabled="!canManage || loading" controls-position="right" />
            <span>Prompt tokens</span>
            <ElInputNumber v-model="runtime.maxModelCalls" :min="0" :disabled="!canManage || loading" controls-position="right" />
            <span>模型调用</span>
            <ElInputNumber v-model="runtime.maxToolCalls" :min="0" :disabled="!canManage || loading" controls-position="right" />
            <span>工具调用</span>
          </div>
        </ElFormItem>
      </ElForm>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type {
  DigitalEmployee,
  EmployeeModelConfigItem,
  UpdateEmployeeModelConfigRequest
} from '@/views/ai-agent/services/digitalEmployee';
import modelConfigService, { isChatModelConfig } from '@/views/ai-agent/services/modelConfig';
import type { ModelConfig } from '@/views/ai-agent/services/modelConfig';
import routingService from '@/views/ai-agent/services/routing';
import type { RouteProfile } from '@/views/ai-agent/services/routing';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';

defineOptions({ name: 'EmployeeModelConfigTab' });

const props = defineProps<{
  employee: DigitalEmployee;
  canManage: boolean;
}>();

const emit = defineEmits<{
  changed: [];
}>();

interface ModelRow extends ModelConfig {
  id: string;
  enabled: boolean;
  userSelectable: boolean;
}

const loading = ref(false);
const loadError = ref(false);
const saving = ref(false);
const chatModels = ref<ModelRow[]>([]);
const modelRows = ref<ModelRow[]>([]);
const employeeConfigs = ref<EmployeeModelConfigItem[]>([]);
const defaultModelConfigId = ref('');
const routeOptions = ref<RouteProfile[]>([]);
const routeLoadError = ref(false);
const runtime = reactive({
  routeProfileId: '',
  systemInstruction: '',
  greeting: '',
  maxPromptTokens: undefined as number | undefined,
  maxModelCalls: undefined as number | undefined,
  maxToolCalls: undefined as number | undefined
});

const modelLabel = (model: ModelConfig) => `${model.modelName || model.id || '-'}（${model.provider || '未知供应商'}）`;

const enabledRows = computed(() => modelRows.value.filter(row => row.enabled));

function buildRows(models: ModelRow[], configs: EmployeeModelConfigItem[]) {
  return models.map(model => {
    const config = configs.find(item => String(item.modelConfigId) === String(model.id));
    return {
      ...model,
      id: String(model.id),
      enabled: config?.enabled ?? config?.isDefault ?? false,
      userSelectable: config?.userSelectable ?? true
    };
  });
}

function syncDefaultModel() {
  if (defaultModelConfigId.value && !enabledRows.value.some(row => row.id === defaultModelConfigId.value)) {
    defaultModelConfigId.value = '';
  }
}

function syncFromConfigs(configs: EmployeeModelConfigItem[]) {
  employeeConfigs.value = configs;
  const defaultConfig = configs.find(item => item.isDefault && item.modelConfigId);
  defaultModelConfigId.value = defaultConfig?.modelConfigId ? String(defaultConfig.modelConfigId) : '';
  modelRows.value = buildRows(chatModels.value, configs);
}

function parsePolicy(raw?: string | Record<string, unknown>) {
  if (!raw) return {} as Record<string, unknown>;
  if (typeof raw === 'object') return { ...raw };
  try {
    const parsed = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : {};
  } catch {
    return {};
  }
}

function syncRuntimeFromEmployee() {
  const policy = parsePolicy(props.employee.executionPolicy);
  runtime.routeProfileId = props.employee.routeProfileId ? String(props.employee.routeProfileId) : '';
  runtime.systemInstruction = props.employee.systemInstruction || '';
  runtime.greeting = props.employee.greeting || '';
  runtime.maxPromptTokens = typeof policy.maxPromptTokens === 'number' ? policy.maxPromptTokens : undefined;
  runtime.maxModelCalls = typeof policy.maxModelCalls === 'number' ? policy.maxModelCalls : undefined;
  runtime.maxToolCalls = typeof policy.maxToolCalls === 'number' ? policy.maxToolCalls : undefined;
}

async function loadRouteOptions() {
  routeLoadError.value = false;
  try {
    const current = await routingService.currentProfile();
    const profiles = [current.active, current.working, current.rollback].filter((item): item is RouteProfile => Boolean(item?.id));
    const seen = new Set<string>();
    routeOptions.value = profiles.filter(item => {
      const id = String(item.id);
      if (seen.has(id)) return false;
      seen.add(id);
      return true;
    });
  } catch {
    routeOptions.value = [];
    routeLoadError.value = true;
  }
}

const hasRouteOption = (id: string) => routeOptions.value.some(item => String(item.id) === String(id));

async function load() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  loading.value = true;
  loadError.value = false;
  try {
    const [models, configs] = await Promise.all([
      modelConfigService.list(),
      digitalEmployeeService.listModelConfigs(employeeId)
    ]);
    chatModels.value = models
      .filter(item => isChatModelConfig(item) && item.id !== null && item.id !== undefined)
      .map(item => ({
        ...item,
        id: String(item.id),
        enabled: false,
        userSelectable: true
      }));
    syncFromConfigs(configs);
    syncRuntimeFromEmployee();
    await loadRouteOptions();
  } catch (error) {
    loadError.value = true;
    chatModels.value = [];
    modelRows.value = [];
    employeeConfigs.value = [];
    ElMessage.error(extractApiErrorMessage(error, '员工模型配置加载失败'));
  } finally {
    loading.value = false;
  }
}

async function save() {
  const employeeId = props.employee.id;
  if (!employeeId || !props.canManage || loadError.value || routeLoadError.value || loading.value) return;
  syncDefaultModel();
  const payload: UpdateEmployeeModelConfigRequest = {
    defaultModelConfigId: defaultModelConfigId.value || undefined,
    models: enabledRows.value.map(row => ({
      modelConfigId: row.id,
      userSelectable: row.userSelectable,
      enabled: true
    }))
  };
  saving.value = true;
  let modelsSaved = false;
  try {
    const configs = await digitalEmployeeService.updateModelConfigs(employeeId, payload);
    modelsSaved = true;
    syncFromConfigs(configs);
    const policy = parsePolicy(props.employee.executionPolicy);
    if (runtime.maxPromptTokens === null || runtime.maxPromptTokens === undefined) delete policy.maxPromptTokens;
    else policy.maxPromptTokens = runtime.maxPromptTokens;
    if (runtime.maxModelCalls === null || runtime.maxModelCalls === undefined) delete policy.maxModelCalls;
    else policy.maxModelCalls = runtime.maxModelCalls;
    if (runtime.maxToolCalls === null || runtime.maxToolCalls === undefined) delete policy.maxToolCalls;
    else policy.maxToolCalls = runtime.maxToolCalls;
    await digitalEmployeeService.modify(employeeId, {
      routeProfileId: runtime.routeProfileId || undefined,
      systemInstruction: runtime.systemInstruction,
      greeting: runtime.greeting,
      executionPolicy: policy
    });
    ElMessage.success('员工模型配置已保存（发布后生效）');
    emit('changed');
  } catch (error) {
    const message = extractApiErrorMessage(error, '员工模型配置保存失败');
    ElMessage.error(modelsSaved ? `模型列表已保存，但运行配置保存失败：${message}` : message);
  } finally {
    saving.value = false;
  }
}

watch(
  () => props.employee.id,
  () => {
    syncRuntimeFromEmployee();
    load().catch(() => undefined);
  }
);

onMounted(() => {
  load().catch(() => undefined);
});
</script>

<style scoped>
.model-config-tab {
  min-height: 100%;
}

.model-hint {
  margin-bottom: 16px;
}

.model-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.model-toolbar h3 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 16px;
}

.model-toolbar p {
  margin: 5px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.model-form {
  max-width: 620px;
}

.default-select {
  width: min(100%, 420px);
}

.model-name {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.model-meta {
  margin-top: 3px;
  overflow: hidden;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.runtime-section {
  margin-top: 18px;
  padding-top: 18px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.section-title-row h3 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 16px;
}

.section-title-row p {
  margin: 5px 0 14px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.runtime-form {
  max-width: 760px;
}

.runtime-form :deep(.el-select),
.runtime-form :deep(.el-input) {
  width: min(100%, 620px);
}

.budget-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.budget-row :deep(.el-input-number) {
  width: 145px;
}

.budget-row span {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@media (max-width: 720px) {
  .model-toolbar {
    flex-direction: column;
  }

  .toolbar-actions {
    width: 100%;
  }
}
</style>
