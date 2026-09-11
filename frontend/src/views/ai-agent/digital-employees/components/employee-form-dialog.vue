<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDialog
    :model-value="visible"
    :title="isCreate ? '新建数字员工' : '编辑数字员工'"
    :width="isCreate ? '760px' : '640px'"
    :close-on-click-modal="false"
    destroy-on-close
    @update:model-value="handleVisibleChange"
  >
    <ElAlert
      v-if="!isCreate && !isEditableStatus"
      class="mb-12"
      type="warning"
      :closable="false"
      title="已封存档案只读。"
    />

    <template v-if="isCreate">
      <ElSteps :active="createStep" align-center class="mb-16" finish-status="success">
        <ElStep title="档案" />
        <ElStep title="来源" />
      </ElSteps>
    </template>

    <ElForm ref="formRef" :model="form" :rules="formRules" label-width="110px">
      <template v-if="!isCreate || createStep === 0">
        <ElFormItem v-if="isCreate" label="岗位模板">
          <ElRadioGroup v-model="form.templateCode" @change="handleTemplateChange">
            <ElRadioButton value="">空白创建</ElRadioButton>
            <ElRadioButton
              v-for="item in templates"
              :key="item.templateCode"
              :value="item.templateCode || ''"
            >
              {{ item.displayName || item.templateCode }}
            </ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
        <ElAlert
          v-if="selectedTemplate"
          class="mb-12"
          type="info"
          :closable="false"
          :title="`${selectedTemplate.displayName}：预填岗位与提示词，不绑定未发布技能，草稿期不创建任务`"
        >
          <div class="template-hint">
            <p v-if="selectedTemplate.recommendedSkills?.length">
              推荐技能（请到技能市场安装已发布版本后再绑定）：
              {{ selectedTemplate.recommendedSkills.map(item => item.displayName).join('、') }}
            </p>
            <p v-if="selectedTemplate.recommendedTask">
              发布生产后建议创建「{{ selectedTemplate.recommendedTask.taskName }}」。{{
                selectedTemplate.recommendedTask.triggerHint
              }}
            </p>
          </div>
        </ElAlert>
        <ElFormItem label="员工名称" prop="employeeName">
          <ElInput v-model="form.employeeName" maxlength="64" show-word-limit placeholder="如：客服助手" />
        </ElFormItem>
        <ElFormItem v-if="isCreate" label="员工编码">
          <ElInput v-model="form.employeeCode" maxlength="64" placeholder="租户内唯一；不填由服务端生成" />
        </ElFormItem>
        <ElFormItem label="岗位">
          <ElInput v-model="form.jobTitle" maxlength="64" placeholder="position 标签，仅展示用" />
        </ElFormItem>
        <ElFormItem label="员工描述">
          <ElInput v-model="form.description" type="textarea" :rows="2" maxlength="500" show-word-limit />
        </ElFormItem>
        <ElFormItem label="业务负责人">
          <ElInput v-model="form.managerUserId" placeholder="真人 userId，仅用于通知，不参与授权" />
        </ElFormItem>
        <ElFormItem label="审批人">
          <ElInput v-model="form.approverUserId" placeholder="真人 userId；不得与创建人相同" />
        </ElFormItem>
        <ElFormItem label="自治级别">
          <ElSelect v-model="form.autonomyLevel" placeholder="默认 ASSISTED（需审批）">
            <ElOption v-for="item in AUTONOMY_LEVEL_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </ElSelect>
        </ElFormItem>
      </template>

      <template v-if="isCreate && createStep === 1">
        <ElAlert
          class="mb-12"
          type="info"
          :closable="false"
          title="运行时身份是数字员工自身，不是 DataAgent。"
          description="空白创建从零配置草稿；复制只记录指定 DataAgent 作为来源，不会把 live DataAgent 当成对话主体。模型和能力在创建后独立配置。"
        />
        <ElFormItem label="来源方式">
          <ElRadioGroup v-model="form.sourceMode" @change="handleSourceModeChange">
            <ElRadioButton value="blank">空白创建</ElRadioButton>
            <ElRadioButton value="copy">复制 DataAgent 活体</ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
        <template v-if="form.sourceMode === 'copy'">
          <ElFormItem label="DataAgent" required>
            <ElSelect
              v-model="form.sourceAgentId"
              filterable
              clearable
              placeholder="选择要复制的 DataAgent"
              :loading="agentOptionsLoading"
              style="width: 100%"
              @change="handleSourceAgentChange"
            >
              <ElOption
                v-for="item in agentOptions"
                :key="String(item.id)"
                :label="item.name || String(item.id)"
                :value="String(item.id)"
              />
            </ElSelect>
          </ElFormItem>
          <p v-if="importHint" class="field-tip">{{ importHint }}</p>
        </template>
      </template>

    </ElForm>

    <template #footer>
      <ElButton @click="handleVisibleChange(false)">取消</ElButton>
      <ElButton v-if="isCreate && createStep > 0" @click="createStep -= 1">上一步</ElButton>
      <ElButton v-if="isCreate && createStep < 1" type="primary" @click="goNextStep">下一步</ElButton>
      <ElButton
        v-if="!isCreate || createStep === 1"
        type="primary"
        :loading="submitting"
        :disabled="!isCreate && !isEditableStatus"
        @click="submit"
      >
        {{ isCreate ? '创建' : '保存' }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type {
  DigitalEmployee,
  DigitalEmployeeCreateRequest,
  DigitalEmployeeModifyRequest,
  EmployeeJobTemplate
} from '@/views/ai-agent/services/digitalEmployee';
import agentService from '@/views/ai-agent/services/agent';
import type { Agent } from '@/views/ai-agent/services/agent';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { AUTONOMY_LEVEL_OPTIONS } from '../employee-support';

defineOptions({ name: 'EmployeeFormDialog' });

const props = defineProps<{
  visible: boolean;
  mode: 'create' | 'edit';
  employee: DigitalEmployee | null;
}>();

const emit = defineEmits<{
  'update:visible': [boolean];
  saved: [employee?: DigitalEmployee];
}>();

interface EmployeeFormModel {
  employeeName: string;
  employeeCode: string;
  jobTitle: string;
  description: string;
  managerUserId: string;
  approverUserId: string;
  autonomyLevel: string;
  sourceMode: 'blank' | 'copy';
  sourceAgentId: string;
  templateCode: string;
}

const isCreate = computed(() => props.mode === 'create');
/** 后端约束：ARCHIVED 只读；草稿 / 停用 / 启用均可改草稿，启用态须再发布才进生产 */
const isEditableStatus = computed(() => {
  const status = props.employee?.status;
  return status === 'DRAFT' || status === 'DISABLED' || status === 'ENABLED';
});

const submitting = ref(false);
const createStep = ref(0);
const formRef = ref<FormInstance | null>(null);
const form = reactive<EmployeeFormModel>(defaultForm());
const importHint = ref('');

const agentOptions = ref<Agent[]>([]);
const agentOptionsLoading = ref(false);
const templates = ref<EmployeeJobTemplate[]>([]);
const selectedTemplate = computed(() =>
  templates.value.find(item => item.templateCode === form.templateCode) || null
);

const formRules: FormRules = {
  employeeName: [{ required: true, message: '请填写员工名称', trigger: 'blur' }]
};

function defaultForm(): EmployeeFormModel {
  return {
    employeeName: '',
    employeeCode: '',
    jobTitle: '',
    description: '',
    managerUserId: '',
    approverUserId: '',
    autonomyLevel: 'ASSISTED',
    sourceMode: 'blank',
    sourceAgentId: '',
    templateCode: '',
  };
}

watch(
  () => props.visible,
  visible => {
    if (!visible) return;
    createStep.value = 0;
    importHint.value = '';
    formRef.value?.clearValidate();
    if (isCreate.value || !props.employee) {
      Object.assign(form, defaultForm());
      loadCreateOptions();
      return;
    }
    const employee = props.employee;
    Object.assign(form, defaultForm(), {
      employeeName: employee.employeeName || '',
      jobTitle: employee.jobTitle || '',
      description: employee.description || '',
      managerUserId: employee.managerUserId || '',
      approverUserId: employee.approverUserId || '',
      autonomyLevel: employee.autonomyLevel || 'ASSISTED',
    });
  },
  { immediate: true }
);

async function loadCreateOptions() {
  agentOptionsLoading.value = true;
  try {
    const [agents, jobTemplates] = await Promise.all([
      agentService.list(),
      digitalEmployeeService.listTemplates().catch(() => [])
    ]);
    agentOptions.value = agents;
    templates.value = jobTemplates;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '创建向导选项加载失败'));
  } finally {
    agentOptionsLoading.value = false;
  }
}

function handleTemplateChange() {
  const template = selectedTemplate.value;
  if (!template) {
    form.jobTitle = '';
    form.description = '';
    form.autonomyLevel = 'ASSISTED';
    return;
  }
  form.jobTitle = template.jobTitle || form.jobTitle;
  form.description = template.description || form.description;
  form.autonomyLevel = template.autonomyLevel || 'ASSISTED';
}

function handleSourceModeChange() {
  form.sourceAgentId = '';
  importHint.value = '';
}

async function handleSourceAgentChange() {
  importHint.value = '';
  if (!form.sourceAgentId) return;
  try {
    const agent = await agentService.get(form.sourceAgentId);
    if (!agent) {
      ElMessage.warning('DataAgent 不存在');
      return;
    }
    importHint.value = `已选择 DataAgent ${form.sourceAgentId} 作为来源。创建后请在数字员工详情中独立配置模型与技能市场能力。`;
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, 'DataAgent 活体加载失败'));
  }
}

async function goNextStep() {
  if (createStep.value === 0) {
    const valid = await formRef.value?.validate().catch(() => false);
    if (!valid) return;
  }
  if (createStep.value === 1 && form.sourceMode === 'copy') {
    if (!form.sourceAgentId) {
      ElMessage.warning('请选择要复制的 DataAgent');
      return;
    }
  }
  createStep.value += 1;
}

async function submit() {
  if (isCreate.value) {
    const valid = await formRef.value?.validate().catch(() => false);
    if (!valid) return;
  } else {
    const valid = await formRef.value?.validate().catch(() => false);
    if (!valid) return;
  }
  submitting.value = true;
  try {
    if (isCreate.value) {
      const payload: DigitalEmployeeCreateRequest = {
        employeeName: form.employeeName.trim(),
        employeeCode: form.employeeCode.trim() || undefined,
        jobTitle: form.jobTitle.trim() || undefined,
        description: form.description.trim() || undefined,
        systemInstruction: selectedTemplate.value?.systemInstruction || undefined,
        greeting: selectedTemplate.value?.greeting || undefined,
        managerUserId: form.managerUserId.trim() || undefined,
        approverUserId: form.approverUserId.trim() || undefined,
        autonomyLevel: (form.autonomyLevel as DigitalEmployeeCreateRequest['autonomyLevel']) || 'ASSISTED',
        sourceAgentId: form.sourceMode === 'copy' ? form.sourceAgentId.trim() || undefined : undefined,
        templateCode: form.templateCode.trim() || undefined
      };
      const created = await digitalEmployeeService.create(payload);
      ElMessage.success(
        form.templateCode
          ? '数字员工已按岗位模板创建（DRAFT）。发布生产后再创建每日快报任务，能力请到技能市场安装。'
          : '数字员工已创建（DRAFT），能力请到详情页从技能市场安装'
      );
      emit('saved', created);
    } else {
      const employeeId = props.employee?.id;
      if (!employeeId) return;
      const payload: DigitalEmployeeModifyRequest = {
        employeeName: form.employeeName.trim(),
        jobTitle: form.jobTitle.trim(),
        description: form.description.trim(),
        managerUserId: form.managerUserId.trim(),
        approverUserId: form.approverUserId.trim(),
        autonomyLevel: (form.autonomyLevel as DigitalEmployeeModifyRequest['autonomyLevel']) || undefined
      };
      await digitalEmployeeService.modify(employeeId, payload);
      ElMessage.success('数字员工已保存');
      emit('saved', props.employee || undefined);
    }
    emit('update:visible', false);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, isCreate.value ? '创建失败' : '保存失败'));
  } finally {
    submitting.value = false;
  }
}

function handleVisibleChange(value: boolean) {
  emit('update:visible', value);
}
</script>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.mb-16 {
  margin-bottom: 16px;
}

.field-tip {
  margin: -4px 0 8px 110px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.template-hint p {
  margin: 0 0 6px;
  line-height: 1.6;
}

.template-hint p:last-child {
  margin-bottom: 0;
}
</style>
