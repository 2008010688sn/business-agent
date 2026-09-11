<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="pending-reconcile-panel">
    <ElAlert
      class="mb-12"
      type="warning"
      :closable="false"
      show-icon
      title="结果未知的外部副作用禁止自动重试。请先核对下游系统，再判定成功或失败以解除幂等键封锁。"
    />

    <div class="filter-actions mb-12">
      <ElButton type="primary" :loading="loading" @click="loadRows">刷新</ElButton>
    </div>

    <ElTable v-loading="loading" :data="rows" border stripe row-key="id" empty-text="暂无待对账调用">
      <ElTableColumn label="调用ID" width="190" fixed="left">
        <template #default="{ row }">
          <span class="mono-text">{{ row.id }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="140">
        <template #default="{ row }">
          <ElTag :type="row.state === 'RECONCILING' ? 'warning' : 'danger'" effect="light" size="small">
            {{ stateLabel(row.state) }}
          </ElTag>
        </template>
      </ElTableColumn>
      <ElTableColumn label="能力" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.capabilityHandle || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="类型" width="90">
        <template #default="{ row }">{{ row.invocationType || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="所属运行" width="180">
        <template #default="{ row }">
          <ElButton v-if="row.runId" link type="primary" class="mono-link" @click="emit('openRun', String(row.runId))">
            {{ row.runId }}
          </ElButton>
          <span v-else class="cell-muted">-</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="幂等键" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="mono-text">{{ row.idempotencyKey || '-' }}</span>
        </template>
      </ElTableColumn>
      <ElTableColumn label="参数摘要" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.requestDigest || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="外部回执" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.sideEffectReceipt || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="发出时间" width="170">
        <template #default="{ row }">{{ formatTime(row.sentAt || row.createTime) }}</template>
      </ElTableColumn>
      <ElTableColumn label="错误" min-width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.errorMessage || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn v-if="canReconcile" label="操作" width="160" fixed="right" align="center">
        <template #default="{ row }">
          <ElButton link type="success" :loading="actingId === row.id" @click="handleReconcile(row, true)">
            判定成功
          </ElButton>
          <ElButton link type="danger" :loading="actingId === row.id" @click="handleReconcile(row, false)">
            判定失败
          </ElButton>
        </template>
      </ElTableColumn>
    </ElTable>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import dayjs from 'dayjs';
import runtimeInvocationService from '@/views/ai-agent/services/runtimeInvocation';
import type { RuntimeInvocation } from '@/views/ai-agent/services/runtimeInvocation';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';

defineOptions({ name: 'PendingReconcilePanel' });

const props = defineProps<{
  canReconcile: boolean;
}>();

const emit = defineEmits<{
  openRun: [runId: string];
}>();

const loading = ref(false);
const rows = ref<RuntimeInvocation[]>([]);
const actingId = ref('');

const stateLabel = (state?: string) => {
  if (state === 'OUTCOME_UNKNOWN') return '结果未知';
  if (state === 'RECONCILING') return '对账中';
  return state || '-';
};

const formatTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

async function loadRows() {
  loading.value = true;
  try {
    rows.value = await runtimeInvocationService.listPendingReconcile();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '待对账列表加载失败'));
  } finally {
    loading.value = false;
  }
}

async function handleReconcile(row: RuntimeInvocation, success: boolean) {
  if (!row.id || !props.canReconcile) return;
  let comment = '';
  try {
    const result = await ElMessageBox.prompt(
      success
        ? '请填写核对到的外部单号或结论（可空）。判定成功后该幂等键可再次使用。'
        : '请填写失败原因。判定失败后该幂等键可再次使用。',
      success ? '判定外部副作用已生效' : '判定外部副作用未生效',
      {
        confirmButtonText: success ? '判定成功' : '判定失败',
        cancelButtonText: '取消',
        inputPlaceholder: success ? '如：下游单号 SO-9527' : '核对结论',
        inputType: 'textarea',
        type: success ? 'warning' : 'error'
      }
    );
    comment = String(result.value || '').trim();
  } catch {
    return;
  }
  actingId.value = String(row.id);
  try {
    await runtimeInvocationService.reconcile(row.id, success, comment || undefined);
    ElMessage.success(success ? '已判定成功，幂等键已解除封锁' : '已判定失败，幂等键已解除封锁');
    await loadRows();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '对账提交失败'));
  } finally {
    actingId.value = '';
  }
}

onMounted(loadRows);

defineExpose({ reload: loadRows });
</script>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.filter-actions {
  display: flex;
  justify-content: flex-end;
}

.mono-text,
.mono-link {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace;
  font-size: 12px;
}

.cell-muted {
  color: var(--el-text-color-secondary);
}
</style>
