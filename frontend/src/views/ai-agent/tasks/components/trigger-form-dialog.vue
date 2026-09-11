<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDialog
    :model-value="visible"
    :title="trigger ? '编辑触发器' : '新建触发器'"
    width="680px"
    destroy-on-close
    @update:model-value="handleVisibleChange"
  >
    <ElForm label-width="110px">
      <ElFormItem label="触发类型" required>
        <ElSelect v-model="form.triggerType" :disabled="Boolean(trigger)" placeholder="选择触发类型">
          <ElOption
            v-for="item in TRIGGER_TYPE_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
            :disabled="Boolean(item.unimplemented)"
          />
        </ElSelect>
      </ElFormItem>

      <template v-if="form.triggerType === 'SCHEDULE'">
        <ElFormItem label="cron 表达式" required :error="cronError">
          <div class="cron-row">
            <ElInput v-model="form.cron" clearable placeholder="Spring 6 段 cron，如 0 0 9 * * *" @blur="checkCron" />
            <ElSelect v-model="cronTemplate" placeholder="常用模板" class="cron-template" @change="applyCronTemplate">
              <ElOption v-for="item in CRON_TEMPLATES" :key="item.value" :label="item.label" :value="item.value" />
            </ElSelect>
          </div>
          <span class="field-hint">前端仅做 5/6 段格式检查，语义以后端 Spring CronExpression 校验为准。</span>
        </ElFormItem>
        <ElFormItem label="时区（IANA）">
          <ElSelect v-model="form.timezone" filterable allow-create default-first-option placeholder="Asia/Shanghai">
            <ElOption v-for="item in TIMEZONE_OPTIONS" :key="item" :label="item" :value="item" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="错过策略">
          <ElSelect v-model="form.misfirePolicy">
            <ElOption
              v-for="item in MISFIRE_POLICY_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="并发策略">
          <ElSelect v-model="form.concurrencyPolicy">
            <ElOption
              v-for="item in CONCURRENCY_POLICY_OPTIONS"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </ElSelect>
        </ElFormItem>
      </template>

      <template v-else-if="form.triggerType === 'EVENT'">
        <ElAlert
          type="info"
          :closable="false"
          show-icon
          class="mb-12"
          title="事件由 RocketMQ 投递，幂等键 = 触发器 ID + 事件 eventId：同一事件重复投递只产生一次运行。"
        />
        <ElFormItem label="事件主题" required>
          <ElInput v-model="form.eventTopic" clearable placeholder="eventTopic，如 zeus-order-signed" />
        </ElFormItem>
        <ElFormItem label="事件标签">
          <ElInput v-model="form.eventTag" clearable placeholder="eventTag（可选）" />
        </ElFormItem>
      </template>

      <template v-else-if="form.triggerType === 'API'">
        <ElAlert type="info" :closable="false" show-icon class="mb-12">
          <template #title>
            外部系统调用方式：POST /ai/agent-tasks/{任务ID}/triggers/{触发器ID}/runs/create。 请求头 Idempotency-Key
            必填（重放返回已有运行）；并携带签名三件套 X-Timestamp（epoch 毫秒，±5 分钟）、X-Nonce（一次性随机串）、
            X-Signature = HMAC-SHA256(secret, timestamp + "\n" + nonce + "\n" + 请求体原文) 小写 hex。
            签名密钥保存后在触发器列表「生成/轮换签名密钥」获取，明文仅展示一次。
          </template>
        </ElAlert>
      </template>

      <template v-else-if="form.triggerType === 'IM'">
        <ElAlert
          type="info"
          :closable="false"
          show-icon
          class="mb-12"
          title="IM 触发本轮未开通：枚举已预留，连接器接入见「IM 连接器」页，请勿当作已通。"
        />
        <ElFormItem label="IM 平台">
          <ElInput v-model="form.imProvider" clearable placeholder="provider，如 dingtalk / wecom / feishu" />
        </ElFormItem>
        <ElFormItem label="连接器编码">
          <ElInput v-model="form.imConnectorCode" clearable placeholder="connectorCode（IM 连接器页配置）" />
        </ElFormItem>
      </template>

      <template v-else-if="form.triggerType === 'CHAT'">
        <ElAlert
          type="info"
          :closable="false"
          show-icon
          class="mb-12"
          title="会话触发本轮未开通：枚举已预留，请勿当作已通。"
        />
      </template>

      <ElFormItem label="状态">
        <ElSelect v-model="form.status">
          <ElOption label="启用（enabled）" value="enabled" />
          <ElOption label="停用（disabled）" value="disabled" />
        </ElSelect>
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="handleVisibleChange(false)">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="submitTrigger">保存</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import agentTaskService, { DEFAULT_TIMEZONE } from '@/views/ai-agent/services/agentTask';
import type {
  AgentTaskId,
  AgentTaskTrigger,
  AgentTaskTriggerCreateRequest,
  AgentTaskTriggerModifyRequest,
  TaskConcurrencyPolicy,
  TaskMisfirePolicy,
  TaskStatus,
  TaskTriggerType
} from '@/views/ai-agent/services/agentTask';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import {
  CONCURRENCY_POLICY_OPTIONS,
  CRON_TEMPLATES,
  MISFIRE_POLICY_OPTIONS,
  TIMEZONE_OPTIONS,
  TRIGGER_TYPE_OPTIONS,
  validateCronBasic
} from '../task-support';

defineOptions({ name: 'TriggerFormDialog' });

const props = defineProps<{
  visible: boolean;
  definitionId: AgentTaskId | '';
  /** 为空表示新建；编辑时触发类型不可变更（后端约束）。 */
  trigger: AgentTaskTrigger | null;
}>();

const emit = defineEmits<{
  'update:visible': [boolean];
  saved: [];
}>();

interface TriggerFormModel {
  triggerType: TaskTriggerType;
  cron: string;
  timezone: string;
  misfirePolicy: TaskMisfirePolicy;
  concurrencyPolicy: TaskConcurrencyPolicy;
  eventTopic: string;
  eventTag: string;
  imProvider: string;
  imConnectorCode: string;
  status: TaskStatus;
}

const form = reactive<TriggerFormModel>(defaultForm());
const submitting = ref(false);
const cronTemplate = ref('');
const cronError = ref('');

function defaultForm(): TriggerFormModel {
  return {
    triggerType: 'SCHEDULE',
    cron: '',
    timezone: DEFAULT_TIMEZONE,
    misfirePolicy: 'SKIP',
    concurrencyPolicy: 'FORBID',
    eventTopic: '',
    eventTag: '',
    imProvider: '',
    imConnectorCode: '',
    status: 'enabled'
  };
}

watch(
  () => props.visible,
  visible => {
    if (!visible) return;
    cronError.value = '';
    cronTemplate.value = '';
    const trigger = props.trigger;
    if (!trigger) {
      Object.assign(form, defaultForm());
      return;
    }
    Object.assign(form, defaultForm(), {
      triggerType: trigger.triggerType || 'SCHEDULE',
      cron: String(trigger.triggerConfig?.cron ?? ''),
      timezone: trigger.timezone || DEFAULT_TIMEZONE,
      misfirePolicy: trigger.misfirePolicy || 'SKIP',
      concurrencyPolicy: trigger.concurrencyPolicy || 'FORBID',
      eventTopic: String(trigger.triggerConfig?.eventTopic ?? ''),
      eventTag: String(trigger.triggerConfig?.eventTag ?? ''),
      imProvider: String(trigger.triggerConfig?.provider ?? ''),
      imConnectorCode: String(trigger.triggerConfig?.connectorCode ?? ''),
      status: trigger.status || 'enabled'
    });
  },
  { immediate: true }
);

function checkCron() {
  cronError.value = form.cron.trim() ? validateCronBasic(form.cron) : '';
}

function applyCronTemplate(value: string) {
  if (value) {
    form.cron = value;
    cronError.value = '';
  }
  cronTemplate.value = '';
}

/**
 * 按类型组装 triggerConfig。API 类型不提交 apiSecret：密钥只能经轮换接口生成，
 * 后端 sanitizeConfig 会在整体替换时带回已存密文，不会被冲掉。
 */
function buildTriggerConfig(): Record<string, unknown> {
  switch (form.triggerType) {
    case 'SCHEDULE':
      return { cron: form.cron.trim() };
    case 'EVENT': {
      const config: Record<string, unknown> = { eventTopic: form.eventTopic.trim() };
      if (form.eventTag.trim()) config.eventTag = form.eventTag.trim();
      return config;
    }
    case 'IM': {
      const config: Record<string, unknown> = {};
      if (form.imProvider.trim()) config.provider = form.imProvider.trim();
      if (form.imConnectorCode.trim()) config.connectorCode = form.imConnectorCode.trim();
      return config;
    }
    default:
      return {};
  }
}

function validateForm(): string {
  if (form.triggerType === 'SCHEDULE') {
    const error = validateCronBasic(form.cron);
    cronError.value = error;
    return error;
  }
  if (form.triggerType === 'EVENT' && !form.eventTopic.trim()) {
    return '请填写事件主题（eventTopic）';
  }
  return '';
}

async function submitTrigger() {
  if (!props.definitionId) return;
  if (!props.trigger?.id && (form.triggerType === 'CHAT' || form.triggerType === 'IM')) {
    ElMessage.warning('该触发类型本轮未开通');
    return;
  }
  const validationError = validateForm();
  if (validationError) {
    ElMessage.warning(validationError);
    return;
  }
  submitting.value = true;
  try {
    if (props.trigger?.id) {
      const payload: AgentTaskTriggerModifyRequest = {
        triggerConfig: buildTriggerConfig(),
        timezone: form.timezone,
        misfirePolicy: form.misfirePolicy,
        concurrencyPolicy: form.concurrencyPolicy,
        status: form.status
      };
      await agentTaskService.modifyTrigger(props.definitionId, props.trigger.id, payload);
      ElMessage.success('触发器已保存');
    } else {
      const payload: AgentTaskTriggerCreateRequest = {
        triggerType: form.triggerType,
        triggerConfig: buildTriggerConfig(),
        timezone: form.timezone,
        misfirePolicy: form.misfirePolicy,
        concurrencyPolicy: form.concurrencyPolicy,
        status: form.status
      };
      await agentTaskService.createTrigger(props.definitionId, payload);
      ElMessage.success(form.triggerType === 'API' ? '触发器已创建，请在列表中生成签名密钥' : '触发器已创建');
    }
    emit('update:visible', false);
    emit('saved');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '触发器保存失败'));
  } finally {
    submitting.value = false;
  }
}

function handleVisibleChange(value: boolean) {
  emit('update:visible', value);
}
</script>

<style scoped>
.cron-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.cron-row .el-input {
  flex: 1;
}

.cron-template {
  width: 150px;
  flex-shrink: 0;
}

.field-hint {
  display: block;
  width: 100%;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.mb-12 {
  margin-bottom: 12px;
}
</style>
