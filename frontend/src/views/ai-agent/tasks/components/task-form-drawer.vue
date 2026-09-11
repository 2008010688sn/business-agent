<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDrawer
    :model-value="visible"
    :title="isCreate ? '新建任务定义' : '编辑任务定义'"
    size="560px"
    destroy-on-close
    @update:model-value="handleVisibleChange"
  >
    <ElForm ref="formRef" :model="form" :rules="formRules" label-width="118px" label-position="right">
      <!-- PR-8: 任务钉选数字员工 + 其 PUBLISHED 发布版本；创建后员工不可改绑（后端 ModifyReq 无 digitalEmployeeId） -->
      <ElFormItem v-if="isCreate" label="数字员工" prop="digitalEmployeeId">
        <ElSelect
          v-model="form.digitalEmployeeId"
          clearable
          filterable
          placeholder="请选择钉选的数字员工（仅 ENABLED）"
          :loading="employeeOptionsLoading"
          @change="handleEmployeeChange"
        >
          <ElOption
            v-for="item in employeeOptions"
            :key="String(item.id)"
            :label="employeeOptionLabel(item)"
            :value="String(item.id)"
          />
        </ElSelect>
        <div class="field-tip">自动任务以该员工的受限执行身份运行，禁止使用创建者长期 Token</div>
      </ElFormItem>
      <ElFormItem v-else label="数字员工">
        <div class="employee-readonly">
          <span class="cell-strong">{{ employeeLabelById(form.digitalEmployeeId) }}</span>
          <span class="cell-muted">ID: {{ form.digitalEmployeeId || '-' }}</span>
        </div>
        <div class="field-tip">创建后不可改绑数字员工，仅可切换同一员工下的发布版本</div>
      </ElFormItem>
      <TaskReleaseSelect
        v-model="form.employeeReleaseId"
        :employee-id="form.digitalEmployeeId"
        :required="isCreate"
        release-prop="employeeReleaseId"
      />
      <ElFormItem label="任务名称" prop="taskName">
        <ElInput v-model="form.taskName" clearable maxlength="128" show-word-limit />
      </ElFormItem>
      <ElFormItem label="任务描述">
        <ElInput v-model="form.taskDescription" type="textarea" :rows="3" maxlength="1024" show-word-limit />
      </ElFormItem>
      <ElFormItem label="任务类型">
        <ElSelect
          v-model="form.taskType"
          filterable
          allow-create
          default-first-option
          clearable
          placeholder="选择或输入业务分类"
        >
          <ElOption label="报表（REPORT）" value="REPORT" />
          <ElOption label="监控（MONITOR）" value="MONITOR" />
          <ElOption label="操作（OPERATION）" value="OPERATION" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="高风险写任务">
        <ElSwitch v-model="form.highRiskWrite" />
      </ElFormItem>
      <ElAlert
        v-if="form.highRiskWrite"
        type="warning"
        :closable="false"
        show-icon
        class="mb-12"
        title="高风险写任务强制按「需审批（ASSISTED）」执行：写动作必须经人工审批后才会执行，自治级别不可改为全自动。"
      />
      <ElFormItem label="自治级别">
        <ElSelect v-model="autonomyLevelModel" :disabled="form.highRiskWrite" placeholder="缺省为需审批（ASSISTED）">
          <ElOption v-for="item in AUTONOMY_LEVEL_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem v-if="!isCreate" label="状态">
        <ElSelect v-model="form.status">
          <ElOption label="启用（enabled）" value="enabled" />
          <ElOption label="停用（disabled）" value="disabled" />
        </ElSelect>
      </ElFormItem>

      <template v-if="isCreate">
        <ElFormItem label="任务参数快照">
          <JsonObjectEditor v-model="form.paramsSnapshot" :rows="4" @validity-change="jsonValidity.params = $event" />
        </ElFormItem>
        <ElFormItem label="提示词快照">
          <JsonObjectEditor v-model="form.promptSnapshot" :rows="4" @validity-change="jsonValidity.prompt = $event" />
        </ElFormItem>
      </template>
      <template v-else>
        <ElFormItem label="调整快照">
          <ElSwitch v-model="editSnapshots" />
          <span class="form-hint">开启后提交参数/提示词快照，将生成新的不可变任务版本</span>
        </ElFormItem>
        <template v-if="editSnapshots">
          <ElFormItem label="任务参数快照">
            <JsonObjectEditor v-model="form.paramsSnapshot" :rows="4" @validity-change="jsonValidity.params = $event" />
          </ElFormItem>
          <ElFormItem label="提示词快照">
            <JsonObjectEditor v-model="form.promptSnapshot" :rows="4" @validity-change="jsonValidity.prompt = $event" />
          </ElFormItem>
        </template>
      </template>
    </ElForm>

    <template #footer>
      <ElButton @click="handleVisibleChange(false)">取消</ElButton>
      <ElButton type="primary" :loading="submitting" :disabled="!jsonValid" @click="submit">
        {{ isCreate ? '创建任务' : '保存' }}
      </ElButton>
    </template>
  </ElDrawer>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import agentTaskService from '@/views/ai-agent/services/agentTask';
import type {
  AgentTaskCreateRequest,
  AgentTaskDefinition,
  AgentTaskModifyRequest,
  DigitalEmployeeOption,
  TaskAutonomyLevel,
  TaskStatus
} from '@/views/ai-agent/services/agentTask';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import { AUTONOMY_LEVEL_OPTIONS, isNil } from '../task-support';
import TaskReleaseSelect from './task-release-select.vue';

defineOptions({ name: 'TaskFormDrawer' });

const props = defineProps<{
  visible: boolean;
  mode: 'create' | 'edit';
  task: AgentTaskDefinition | null;
}>();

const emit = defineEmits<{
  'update:visible': [boolean];
  saved: [];
}>();

interface TaskFormModel {
  digitalEmployeeId: string;
  employeeReleaseId: string;
  taskName: string;
  taskDescription: string;
  taskType: string;
  defaultAutonomyLevel: TaskAutonomyLevel | '';
  highRiskWrite: boolean;
  status: TaskStatus;
  paramsSnapshot: Record<string, unknown>;
  promptSnapshot: Record<string, unknown>;
}

const isCreate = computed(() => props.mode === 'create');
const route = useRoute();
const submitting = ref(false);
const editSnapshots = ref(false);
const formRef = ref<FormInstance | null>(null);
const form = reactive<TaskFormModel>(defaultForm());
const jsonValidity = reactive({ params: true, prompt: true });
const jsonValid = computed(() => jsonValidity.params && jsonValidity.prompt);
/** 数字员工选项（仅 ENABLED）：创建态钉选 + 编辑态名称回显共用 */
const employeeOptions = ref<DigitalEmployeeOption[]>([]);
const employeeOptionsLoading = ref(false);
const employeeNameMap = reactive(new Map<string, string>());

const formRules: FormRules = {
  digitalEmployeeId: [{ required: true, message: '请选择钉选的数字员工', trigger: 'change' }],
  taskName: [{ required: true, message: '请填写任务名称', trigger: 'blur' }]
};

/** 高风险写任务后端强制 ASSISTED，前端同步锁定展示，避免提交后产生落差。 */
const autonomyLevelModel = computed<TaskAutonomyLevel | ''>({
  get: () => (form.highRiskWrite ? 'ASSISTED' : form.defaultAutonomyLevel),
  set: value => {
    form.defaultAutonomyLevel = value;
  }
});

function defaultForm(): TaskFormModel {
  return {
    digitalEmployeeId: '',
    employeeReleaseId: '',
    taskName: '',
    taskDescription: '',
    taskType: '',
    defaultAutonomyLevel: 'ASSISTED',
    highRiskWrite: false,
    status: 'enabled',
    paramsSnapshot: {},
    promptSnapshot: {}
  };
}

const parseSnapshot = (value?: Record<string, unknown>): Record<string, unknown> => {
  return value && typeof value === 'object' ? { ...value } : {};
};

async function applyTemplatePrefill() {
  const templateCode = String(
    Array.isArray(route.query.templateCode)
      ? (route.query.templateCode[0] ?? '')
      : (route.query.templateCode ?? '')
  ).trim();
  if (!templateCode) return;
  try {
    const templates = await digitalEmployeeService.listTemplates();
    const template = templates.find(item => item.templateCode === templateCode);
    const task = template?.recommendedTask;
    if (!task) return;
    if (task.taskName) form.taskName = task.taskName;
    if (task.taskDescription) form.taskDescription = task.taskDescription;
    if (task.taskType) form.taskType = task.taskType;
    if (task.defaultAutonomyLevel) {
      form.defaultAutonomyLevel = task.defaultAutonomyLevel as TaskAutonomyLevel;
    }
    form.highRiskWrite = Boolean(task.highRiskWrite);
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '岗位模板任务预填失败'));
    }
  }
}

/** 员工选项仅加载一次并缓存名称映射；失败不阻塞表单（仍可按 ID 提交） */
async function loadEmployeeOptions() {
  if (employeeOptions.value.length > 0) return;
  employeeOptionsLoading.value = true;
  try {
    const options = await agentTaskService.fetchEmployeeOptions();
    employeeOptions.value = options;
    employeeNameMap.clear();
    options.forEach(item => {
      if (!isNil(item.id)) {
        employeeNameMap.set(String(item.id), item.employeeName || '');
      }
    });
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '数字员工选项加载失败'));
    }
  } finally {
    employeeOptionsLoading.value = false;
  }
}

watch(
  () => props.visible,
  async visible => {
    if (!visible) return;
    jsonValidity.params = true;
    jsonValidity.prompt = true;
    editSnapshots.value = false;
    formRef.value?.clearValidate();
    loadEmployeeOptions();
    if (isCreate.value || !props.task) {
      Object.assign(form, defaultForm());
      const queryEmployeeId = String(
        Array.isArray(route.query.digitalEmployeeId)
          ? (route.query.digitalEmployeeId[0] ?? '')
          : (route.query.digitalEmployeeId ?? '')
      ).trim();
      if (queryEmployeeId) {
        form.digitalEmployeeId = queryEmployeeId;
      }
      await applyTemplatePrefill();
      return;
    }
    const task = props.task;
    Object.assign(form, defaultForm(), {
      digitalEmployeeId: String(task.digitalEmployeeId ?? ''),
      employeeReleaseId: String(task.employeeReleaseId ?? ''),
      taskName: task.taskName || '',
      taskDescription: task.taskDescription || '',
      taskType: task.taskType || '',
      defaultAutonomyLevel: task.defaultAutonomyLevel || 'ASSISTED',
      highRiskWrite: Boolean(task.highRiskWrite),
      status: task.status || 'enabled'
    });
  },
  { immediate: true }
);

/** 创建态：切换钉选员工后，旧员工下的发布版本对新人不适用，清空待重选 */
function handleEmployeeChange(employeeId: string) {
  form.employeeReleaseId = '';
  if (employeeId && !employeeNameMap.has(employeeId)) {
    employeeNameMap.set(employeeId, '');
  }
}

const employeeOptionLabel = (employee: DigitalEmployeeOption): string => {
  const name = employee.employeeName || `员工 #${employee.id ?? '-'}`;
  return employee.employeeCode ? `${name}（${employee.employeeCode}）` : name;
};

/** 编辑态员工名称回显：优先用选项缓存，未命中时降级展示 ID */
const employeeLabelById = (digitalEmployeeId: string): string => {
  if (!digitalEmployeeId) return '-';
  return employeeNameMap.get(digitalEmployeeId) || `员工 #${digitalEmployeeId}`;
};

const hasSnapshotContent = (value: Record<string, unknown>) => Object.keys(value).length > 0;

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  if (!jsonValid.value) {
    ElMessage.warning('请先修正快照 JSON 格式错误');
    return;
  }
  submitting.value = true;
  try {
    if (isCreate.value) {
      // 雪花 ID 超出 JS 安全整数范围，保持字符串提交，由后端 Jackson 转 Long。
      const payload: AgentTaskCreateRequest = {
        digitalEmployeeId: form.digitalEmployeeId.trim(),
        employeeReleaseId: form.employeeReleaseId.trim(),
        taskName: form.taskName.trim(),
        taskDescription: form.taskDescription.trim(),
        taskType: form.taskType.trim(),
        defaultAutonomyLevel: form.highRiskWrite ? 'ASSISTED' : form.defaultAutonomyLevel || undefined,
        highRiskWrite: form.highRiskWrite,
        paramsSnapshot: hasSnapshotContent(form.paramsSnapshot) ? form.paramsSnapshot : undefined,
        promptSnapshot: hasSnapshotContent(form.promptSnapshot) ? form.promptSnapshot : undefined
      };
      await agentTaskService.createTask(payload);
      ElMessage.success('任务已创建');
    } else {
      if (!props.task?.id) return;
      const payload: AgentTaskModifyRequest = {
        taskName: form.taskName.trim(),
        taskDescription: form.taskDescription.trim(),
        taskType: form.taskType.trim(),
        employeeReleaseId: form.employeeReleaseId.trim() || undefined,
        defaultAutonomyLevel: form.highRiskWrite ? 'ASSISTED' : form.defaultAutonomyLevel || undefined,
        highRiskWrite: form.highRiskWrite,
        status: form.status,
        paramsSnapshot:
          editSnapshots.value && hasSnapshotContent(form.paramsSnapshot) ? form.paramsSnapshot : undefined,
        promptSnapshot:
          editSnapshots.value && hasSnapshotContent(form.promptSnapshot) ? form.promptSnapshot : undefined
      };
      await agentTaskService.modifyTask(props.task.id, payload);
      ElMessage.success('任务已保存');
    }
    emit('update:visible', false);
    emit('saved');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, isCreate.value ? '任务创建失败' : '任务保存失败'));
  } finally {
    submitting.value = false;
  }
}

function handleVisibleChange(value: boolean) {
  emit('update:visible', value);
}
</script>

<style scoped>
.form-hint {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.field-tip {
  width: 100%;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.employee-readonly {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.cell-strong {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.cell-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mb-12 {
  margin-bottom: 12px;
}
</style>
