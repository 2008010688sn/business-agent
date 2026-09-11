<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <section class="trigger-panel">
    <div class="panel-toolbar">
      <span class="panel-hint">触发类型创建后不可变更；SCHEDULE 按触发器时区计算 cron。</span>
      <div class="panel-actions">
        <ElButton size="small" :loading="loading" @click="loadTriggers">
          <ElIcon><Refresh /></ElIcon>
          刷新
        </ElButton>
        <ElButton v-if="canManage" size="small" type="primary" @click="openCreateDialog">
          <ElIcon><Plus /></ElIcon>
          新建触发器
        </ElButton>
      </div>
    </div>

    <ElTable v-loading="loading" :data="triggers" border stripe row-key="id" empty-text="暂无触发器">
      <ElTableColumn label="类型" width="110">
        <template #default="{ row }">
          <ElTag :type="triggerTagType(row.triggerType)" effect="light" size="small">
            {{ triggerTypeText(row.triggerType) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="配置摘要" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ configSummary(row) }}</template>
      </ElTableColumn>
      <ElTableColumn label="时区" width="130">
        <template #default="{ row }">{{ row.timezone || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="错过策略" width="100">
        <template #default="{ row }">{{ MISFIRE_POLICY_LABELS[row.misfirePolicy || ''] || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="并发策略" width="100">
        <template #default="{ row }">{{ CONCURRENCY_POLICY_LABELS[row.concurrencyPolicy || ''] || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="90">
        <template #default="{ row }">
          <ElTag :type="row.status === 'enabled' ? 'success' : 'info'" effect="light" size="small">
            {{ TASK_STATUS_LABELS[row.status || ''] || row.status || '-' }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="下次执行" width="160">
        <template #default="{ row }">{{ formatDateTime(row.nextFireTime) }}</template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="280" fixed="right" align="center">
        <template #default="{ row }">
          <template v-if="canManage">
            <ElTooltip content="编辑" placement="top">
              <ElButton text type="primary" @click="openEditDialog(row)">
                <ElIcon><Edit /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip :content="row.status === 'enabled' ? '停用' : '启用'" placement="top">
              <ElButton text :type="row.status === 'enabled' ? 'warning' : 'success'" @click="toggleStatus(row)">
                <ElIcon><SwitchButton /></ElIcon>
              </ElButton>
            </ElTooltip>
            <ElTooltip v-if="row.triggerType === 'API'" content="生成/轮换签名密钥" placement="top">
              <ElButton text type="warning" @click="rotateSecret(row)">
                <ElIcon><Key /></ElIcon>
              </ElButton>
            </ElTooltip>
          </template>
          <ElTooltip
            v-if="canTrigger && isConsoleManualTriggerType(row.triggerType)"
            :content="row.status === 'enabled' ? '立即执行一次' : '请先启用触发器'"
            placement="top"
          >
            <ElButton text type="success" :disabled="row.status !== 'enabled'" @click="runOnce(row)">
              <ElIcon><VideoPlay /></ElIcon>
            </ElButton>
          </ElTooltip>
          <ElTooltip v-if="canTrigger && row.triggerType === 'API'" content="外部签名触发" placement="top">
            <ElButton text type="primary" @click="openManualTrigger(row)">
              <ElIcon><Promotion /></ElIcon>
            </ElButton>
          </ElTooltip>
          <ElTooltip v-if="canManage" content="删除" placement="top">
            <ElButton text type="danger" @click="removeTrigger(row)">
              <ElIcon><Delete /></ElIcon>
            </ElButton>
          </ElTooltip>
        </template>
      </ElTableColumn>
    </ElTable>

    <TriggerFormDialog
      v-model:visible="formDialogVisible"
      :definition-id="definitionId"
      :trigger="editingTrigger"
      @saved="handleFormSaved"
    />

    <ElDialog v-model="secretDialogVisible" title="API 签名密钥（仅本次展示）" width="560px" destroy-on-close>
      <ElAlert
        type="warning"
        :closable="false"
        show-icon
        class="mb-12"
        title="明文密钥仅本次返回，关闭后无法再查看；旧密钥已即刻失效，请立即复制并妥善保存。"
      />
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem label="签名算法">{{ secretResult?.algorithm || 'HMAC-SHA256' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="密钥明文">
          <code class="secret-text">{{ secretResult?.secret || '-' }}</code>
        </ElDescriptionsItem>
      </ElDescriptions>
      <template #footer>
        <ElButton type="primary" @click="copySecret">复制密钥</ElButton>
        <ElButton @click="secretDialogVisible = false">我已保存，关闭</ElButton>
      </template>
    </ElDialog>

    <ManualTriggerDialog
      v-model:visible="manualTriggerVisible"
      :definition-id="definitionId"
      :triggers="triggers"
      :preselect-trigger-id="manualTriggerId"
      @triggered="emit('runCreated')"
    />
  </section>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete, Edit, Key, Plus, Promotion, Refresh, SwitchButton, VideoPlay } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import agentTaskService, { CONFIG_API_SECRET } from '@/views/ai-agent/services/agentTask';
import type {
  AgentTaskApiSecretResponse,
  AgentTaskId,
  AgentTaskTrigger,
  TaskStatus
} from '@/views/ai-agent/services/agentTask';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import {
  CONCURRENCY_POLICY_LABELS,
  MISFIRE_POLICY_LABELS,
  TASK_STATUS_LABELS,
  TRIGGER_TYPE_LABELS,
  TRIGGER_TYPE_TAG_TYPES,
  concurrentSkipToast,
  isConsoleManualTriggerType,
  isNil,
  truncateText
} from '../task-support';
import TriggerFormDialog from './trigger-form-dialog.vue';
import ManualTriggerDialog from './manual-trigger-dialog.vue';

defineOptions({ name: 'TaskTriggerPanel' });

const props = defineProps<{
  definitionId: AgentTaskId | '';
  /** ai-agent:task:manage —— 触发器增删改、轮换密钥 */
  canManage: boolean;
  /** ai-agent:task:trigger —— 手动发起运行 */
  canTrigger: boolean;
}>();

const emit = defineEmits<{
  changed: [];
  runCreated: [];
}>();

const triggers = ref<AgentTaskTrigger[]>([]);
const loading = ref(false);
const formDialogVisible = ref(false);
const secretDialogVisible = ref(false);
const manualTriggerVisible = ref(false);
const editingTrigger = ref<AgentTaskTrigger | null>(null);
const secretResult = ref<AgentTaskApiSecretResponse | null>(null);
const manualTriggerId = ref<AgentTaskId | ''>('');

const triggerTypeText = (type?: string) => TRIGGER_TYPE_LABELS[type || ''] || type || '-';
const triggerTagType = (type?: string) => TRIGGER_TYPE_TAG_TYPES[type || ''] || 'info';

const configText = (row: AgentTaskTrigger, key: string): string => {
  const value = row.triggerConfig?.[key];
  return isNil(value) ? '' : String(value);
};

const configSummary = (row: AgentTaskTrigger): string => {
  switch (row.triggerType) {
    case 'SCHEDULE':
      return `cron: ${configText(row, 'cron') || '-'}`;
    case 'EVENT': {
      const tag = configText(row, 'eventTag');
      return `topic: ${configText(row, 'eventTopic') || '-'}${tag ? ` / tag: ${tag}` : ''}`;
    }
    case 'API':
      return configText(row, CONFIG_API_SECRET) ? '签名密钥已配置' : '签名密钥未配置（请先生成）';
    case 'IM': {
      const provider = configText(row, 'provider');
      const connector = configText(row, 'connectorCode');
      return provider || connector ? `${provider || '-'} / ${truncateText(connector)}` : '按连接器投递';
    }
    case 'CHAT':
      return '会话内显式发起';
    default:
      return '-';
  }
};

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

async function loadTriggers() {
  if (!props.definitionId) return;
  loading.value = true;
  try {
    triggers.value = await agentTaskService.listTriggers(props.definitionId);
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '触发器列表查询失败'));
  } finally {
    loading.value = false;
  }
}

function openCreateDialog() {
  editingTrigger.value = null;
  formDialogVisible.value = true;
}

function openEditDialog(row: AgentTaskTrigger) {
  editingTrigger.value = row;
  formDialogVisible.value = true;
}

async function handleFormSaved() {
  await loadTriggers();
  emit('changed');
}

async function toggleStatus(row: AgentTaskTrigger) {
  if (!props.definitionId || !row.id) return;
  const next: TaskStatus = row.status === 'enabled' ? 'disabled' : 'enabled';
  try {
    await agentTaskService.modifyTrigger(props.definitionId, row.id, { status: next });
    ElMessage.success(next === 'enabled' ? '触发器已启用' : '触发器已停用');
    await loadTriggers();
    emit('changed');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '触发器状态切换失败'));
  }
}

async function removeTrigger(row: AgentTaskTrigger) {
  if (!props.definitionId || !row.id) return;
  try {
    await ElMessageBox.confirm(`删除后不可恢复，确认删除触发器 #${row.id}？`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    });
  } catch {
    return;
  }
  try {
    await agentTaskService.removeTrigger(props.definitionId, row.id);
    ElMessage.success('触发器已删除');
    await loadTriggers();
    emit('changed');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '触发器删除失败'));
  }
}

async function rotateSecret(row: AgentTaskTrigger) {
  if (!props.definitionId || !row.id) return;
  try {
    await ElMessageBox.confirm(
      '生成新密钥后旧密钥即刻失效，正在使用旧密钥的调用方会被拒绝。明文密钥仅展示一次，确认生成/轮换？',
      '生成/轮换签名密钥',
      { type: 'warning', confirmButtonText: '生成', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    secretResult.value = await agentTaskService.rotateApiSecret(props.definitionId, row.id);
    secretDialogVisible.value = true;
    await loadTriggers();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '签名密钥生成失败'));
  }
}

async function copySecret() {
  const secret = secretResult.value?.secret;
  if (!secret) return;
  try {
    await navigator.clipboard.writeText(secret);
    ElMessage.success('密钥已复制，请妥善保存');
  } catch {
    ElMessage.warning('复制失败，请手动选择复制');
  }
}

function openManualTrigger(row: AgentTaskTrigger) {
  manualTriggerId.value = row.id ?? '';
  manualTriggerVisible.value = true;
}

async function runOnce(row: AgentTaskTrigger) {
  if (!props.definitionId || isNil(row.id)) return;
  try {
    await ElMessageBox.confirm(
      `立即按任务描述执行一次「${TRIGGER_TYPE_LABELS[row.triggerType || ''] || row.triggerType}」？不会改定时的下次执行时间。`,
      '立即执行一次',
      { type: 'warning', confirmButtonText: '执行', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    const run = await agentTaskService.manualTrigger(props.definitionId, row.id);
    const skipped = concurrentSkipToast(run);
    if (skipped) {
      ElMessage.warning(skipped);
    } else {
      ElMessage.success(`已受理，运行 ID ${run.id ?? '-'}`);
    }
    emit('runCreated');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '立即执行失败'));
  }
}

watch(
  () => props.definitionId,
  definitionId => {
    triggers.value = [];
    if (definitionId) {
      loadTriggers();
    }
  },
  { immediate: true }
);

defineExpose({ loadTriggers });
</script>

<style scoped>
.trigger-panel {
  display: grid;
  gap: 10px;
}

.panel-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.panel-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.panel-actions {
  display: flex;
  gap: 8px;
}

.secret-text {
  font-family: Consolas, Monaco, monospace;
  font-size: 13px;
  word-break: break-all;
}

.mb-12 {
  margin-bottom: 12px;
}
</style>
