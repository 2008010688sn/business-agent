<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElFormItem :label="label" :prop="releaseProp" :rules="releaseRules">
    <ElSelect
      :model-value="modelValue || ''"
      clearable
      filterable
      :placeholder="placeholder"
      class="w-full"
      :disabled="!employeeId"
      :loading="loading"
      no-data-text="该数字员工暂无已发布版本"
      @update:model-value="handleChange"
    >
      <ElOption v-for="item in options" :key="String(item.id)" :label="releaseLabel(item)" :value="String(item.id)" />
    </ElSelect>
    <div class="field-tip">任务按不可变员工发布版本执行，仅可选该数字员工处于「已发布（PUBLISHED）」状态的版本</div>
  </ElFormItem>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormItemRule } from 'element-plus';
import agentTaskService from '@/views/ai-agent/services/agentTask';
import type { AgentTaskId, EmployeeReleaseOption } from '@/views/ai-agent/services/agentTask';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import dayjs from 'dayjs';

defineOptions({ name: 'TaskReleaseSelect' });

const props = withDefaults(
  defineProps<{
    /** 选中的数字员工发布版本 ID（雪花串，一律按字符串处理） */
    modelValue?: string;
    /** 钉选的数字员工 ID（PR-8: 版本按员工过滤）；为空时选择器禁用 */
    employeeId?: AgentTaskId | '';
    /** 是否必填并内置校验规则 */
    required?: boolean;
    /** 父表单 model 中对应的字段名，传入后本项参与父表单校验 */
    releaseProp?: string;
    /** 表单项标签文案，默认「员工发布版本」 */
    label?: string;
  }>(),
  {
    label: '员工发布版本'
  }
);

const emit = defineEmits<{
  'update:modelValue': [string];
}>();

const loading = ref(false);
const options = ref<EmployeeReleaseOption[]>([]);

const placeholder = computed(() => (props.employeeId ? '请选择已发布版本' : '请先选择数字员工'));

const releaseRules = computed<FormItemRule[] | undefined>(() =>
  props.required ? [{ required: true, message: '请选择员工发布版本', trigger: 'change' }] : undefined
);

watch(
  () => props.employeeId,
  employeeId => {
    options.value = [];
    if (employeeId !== '' && employeeId !== undefined && employeeId !== null) {
      loadReleases(employeeId);
    }
  },
  { immediate: true }
);

async function loadReleases(employeeId: AgentTaskId) {
  loading.value = true;
  try {
    const response = await agentTaskService.fetchEmployeeReleaseOptions(employeeId);
    // 请求期间员工可能已被切换，丢弃过期响应
    if (props.employeeId === employeeId) {
      options.value = response;
    }
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载员工发布版本失败'));
    }
  } finally {
    loading.value = false;
  }
}

function handleChange(value: string) {
  emit('update:modelValue', value || '');
}

function releaseLabel(release: EmployeeReleaseOption): string {
  const version = release.releaseNo === undefined ? String(release.id ?? '') : `v${release.releaseNo}`;
  const publishedAt = release.publishedAt ? dayjs(release.publishedAt).format('YYYY-MM-DD HH:mm') : '';
  return publishedAt ? `${version} · 发布于 ${publishedAt}` : version;
}
</script>

<style scoped>
.w-full {
  width: 100%;
}

.field-tip {
  width: 100%;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
</style>
