<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDialog
    :model-value="visible"
    title="手动触发任务运行"
    width="640px"
    destroy-on-close
    @update:model-value="handleVisibleChange"
  >
    <ElAlert
      type="info"
      :closable="false"
      show-icon
      class="mb-12"
      title="仅 API 触发器支持手动触发。后端强制验签：X-Timestamp / X-Nonce / X-Signature 由本页用你粘贴的签名密钥在浏览器本地计算，密钥不上传、不存储；Idempotency-Key 必填，重复触发同一 Key 返回已有运行。"
    />
    <ElForm label-width="130px">
      <ElFormItem label="API 触发器" required>
        <ElSelect v-model="form.triggerId" placeholder="选择 API 类型触发器" :disabled="Boolean(preselectTriggerId)">
          <ElOption
            v-for="item in apiTriggers"
            :key="String(item.id)"
            :label="`#${item.id}（${item.status === 'enabled' ? '启用' : '停用'}）`"
            :value="String(item.id)"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="Idempotency-Key" required>
        <div class="key-row">
          <ElInput v-model="form.idempotencyKey" clearable placeholder="幂等键，重放返回已有运行" />
          <ElButton @click="form.idempotencyKey = randomKey()">随机生成</ElButton>
        </div>
      </ElFormItem>
      <ElFormItem label="签名密钥" required>
        <ElInput
          v-model="form.secret"
          type="password"
          show-password
          clearable
          placeholder="粘贴生成密钥时保存的明文 secret（仅本地参与签名计算）"
        />
      </ElFormItem>
      <ElFormItem label="业务参数">
        <JsonObjectEditor
          v-model="form.params"
          :rows="4"
          placeholder="可选，作为 params 透传给任务运行"
          @validity-change="paramsValid = $event"
        />
      </ElFormItem>
    </ElForm>

    <section v-if="lastRun" class="trigger-result">
      <ElAlert type="success" :closable="false" show-icon>
        <template #title>
          已受理：运行 ID {{ lastRun.id ?? '-' }}，状态 {{ runStatusText(lastRun.runStatus) }}
          <template v-if="lastRun.runtimeRunId">，RuntimeRun {{ lastRun.runtimeRunId }}</template>
        </template>
      </ElAlert>
    </section>

    <template #footer>
      <ElButton @click="handleVisibleChange(false)">关闭</ElButton>
      <ElButton type="primary" :loading="submitting" :disabled="!paramsValid" @click="confirmAndTrigger">触发</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import JsonObjectEditor from '@/views/ai-agent/components/common/JsonObjectEditor.vue';
import agentTaskService from '@/views/ai-agent/services/agentTask';
import type { AgentTaskId, AgentTaskRun, AgentTaskTrigger } from '@/views/ai-agent/services/agentTask';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { RUN_STATUS_LABELS, hmacSha256Hex, randomKey } from '../task-support';

defineOptions({ name: 'ManualTriggerDialog' });

const props = defineProps<{
  visible: boolean;
  definitionId: AgentTaskId | '';
  triggers: AgentTaskTrigger[];
  preselectTriggerId?: AgentTaskId | '';
}>();

const emit = defineEmits<{
  'update:visible': [boolean];
  triggered: [];
}>();

const form = reactive({
  triggerId: '',
  idempotencyKey: '',
  secret: '',
  params: {} as Record<string, unknown>
});
const paramsValid = ref(true);
const submitting = ref(false);
const lastRun = ref<AgentTaskRun | null>(null);

const apiTriggers = computed(() => props.triggers.filter(item => item.triggerType === 'API'));

const runStatusText = (status?: string) => RUN_STATUS_LABELS[status || ''] || status || '-';

watch(
  () => props.visible,
  visible => {
    if (!visible) return;
    lastRun.value = null;
    paramsValid.value = true;
    Object.assign(form, {
      triggerId: props.preselectTriggerId ? String(props.preselectTriggerId) : '',
      idempotencyKey: randomKey(),
      secret: '',
      params: {}
    });
  },
  { immediate: true }
);

async function confirmAndTrigger() {
  if (!props.definitionId) return;
  if (!form.triggerId) {
    ElMessage.warning('请选择 API 触发器');
    return;
  }
  if (!form.idempotencyKey.trim()) {
    ElMessage.warning('请填写 Idempotency-Key');
    return;
  }
  if (!form.secret.trim()) {
    ElMessage.warning('请粘贴签名密钥（生成密钥时仅展示一次）');
    return;
  }
  if (!paramsValid.value) {
    ElMessage.warning('请先修正业务参数 JSON 格式错误');
    return;
  }
  try {
    await ElMessageBox.confirm(
      '确认手动触发一次任务运行？任务将按触发器绑定的版本与受限执行主体执行。',
      '手动触发确认',
      {
        type: 'warning',
        confirmButtonText: '触发',
        cancelButtonText: '取消'
      }
    );
  } catch {
    return;
  }
  submitting.value = true;
  try {
    // rawBody 参与签名，必须与实际发送的请求体字节完全一致，因此先序列化定稿。
    const rawBody = Object.keys(form.params).length ? JSON.stringify({ params: form.params }) : '';
    const timestamp = String(Date.now());
    const nonce = randomKey();
    const signature = await hmacSha256Hex(form.secret.trim(), `${timestamp}\n${nonce}\n${rawBody}`);
    const run = await agentTaskService.triggerRun(props.definitionId, form.triggerId, {
      idempotencyKey: form.idempotencyKey.trim(),
      timestamp,
      nonce,
      signature,
      rawBody
    });
    lastRun.value = run;
    ElMessage.success('触发成功');
    emit('triggered');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '手动触发失败'));
  } finally {
    submitting.value = false;
  }
}

function handleVisibleChange(value: boolean) {
  emit('update:visible', value);
}
</script>

<style scoped>
.key-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.key-row .el-input {
  flex: 1;
}

.trigger-result {
  margin-top: 4px;
}

.mb-12 {
  margin-bottom: 12px;
}
</style>
