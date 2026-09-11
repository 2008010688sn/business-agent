<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="memory-tab">
    <ElAlert
      class="mb-12"
      type="info"
      :closable="false"
      title="数字员工记忆"
      description="四类范围隔离查询：员工共享（WORKSPACE，subjectId=员工ID）、员工与用户、会话、任务情景。共享记忆走按主体导出清单；用户侧走 items/query。agentId 使用员工 ID（运行时合成主体）。"
    />

    <div class="filter-row mb-12">
      <ElSelect v-model="scope" class="filter-select" @change="handleSearch">
        <ElOption v-for="item in MEMORY_SCOPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
      </ElSelect>
      <ElInput
        v-if="scope === 'SESSION'"
        v-model="sessionSubjectId"
        placeholder="会话 ID（SESSION 的 subjectId）"
        clearable
        class="filter-input"
        @keyup.enter="handleSearch"
      />
      <ElButton type="primary" :loading="loading" @click="handleSearch">查询</ElButton>
    </div>

    <ElTable v-loading="loading" :data="rows" border stripe empty-text="该范围暂无记忆">
      <ElTableColumn label="类型" width="110">
        <template #default="{ row }">{{ row.memoryType || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn prop="summary" label="摘要" min-width="280" show-overflow-tooltip />
      <ElTableColumn label="范围" width="140">
        <template #default="{ row }">{{ scopeLabel(row.subjectType) }}</template>
      </ElTableColumn>
      <ElTableColumn label="主体" width="180" show-overflow-tooltip>
        <template #default="{ row }">{{ row.subjectId || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="110">
        <template #default="{ row }">{{ row.status || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="来源会话" width="160" show-overflow-tooltip>
        <template #default="{ row }">{{ row.sourceSessionId || '-' }}</template>
      </ElTableColumn>
      <ElTableColumn label="最近使用" width="170">
        <template #default="{ row }">{{ formatDateTime(row.lastUsedTime) }}</template>
      </ElTableColumn>
    </ElTable>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import dayjs from 'dayjs';
import agentMemoryService from '@/views/ai-agent/services/agentMemory';
import type { AgentMemoryItem, MemoryScope } from '@/views/ai-agent/services/agentMemory';
import type { DigitalEmployee } from '@/views/ai-agent/services/digitalEmployee';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { MEMORY_SCOPE_OPTIONS } from '../../employee-support';

defineOptions({ name: 'EmployeeMemoryTab' });

const props = defineProps<{
  employee: DigitalEmployee;
}>();

const scope = ref<MemoryScope>('WORKSPACE');
const sessionSubjectId = ref('');
const rows = ref<AgentMemoryItem[]>([]);
const loading = ref(false);

const scopeLabel = (value?: string) => MEMORY_SCOPE_OPTIONS.find(item => item.value === value)?.label || value || '-';

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

watch(
  () => props.employee.id,
  () => {
    rows.value = [];
    loadMemories();
  }
);

onMounted(loadMemories);

function handleSearch() {
  loadMemories();
}

async function loadMemories() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  if (scope.value === 'SESSION' && !sessionSubjectId.value.trim()) {
    rows.value = [];
    ElMessage.warning('查询会话记忆请先填写会话 ID');
    return;
  }
  loading.value = true;
  try {
    if (scope.value === 'WORKSPACE' || scope.value === 'SESSION') {
      const subjectId = scope.value === 'WORKSPACE' ? employeeId : sessionSubjectId.value.trim();
      rows.value = await agentMemoryService.listMemoriesBySubject(employeeId, scope.value, subjectId);
    } else {
      rows.value = await agentMemoryService.listMemories(employeeId, { scope: scope.value });
    }
  } catch (error) {
    rows.value = [];
    ElMessage.error(extractApiErrorMessage(error, '记忆查询失败'));
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.filter-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.filter-select {
  width: 160px;
}

.filter-input {
  width: 260px;
}

.mb-12 {
  margin-bottom: 12px;
}
</style>
