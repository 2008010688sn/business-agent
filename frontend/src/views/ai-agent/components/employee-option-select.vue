<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElSelect
    :model-value="modelValue"
    filterable
    clearable
    :disabled="disabled"
    :placeholder="placeholder"
    :loading="loading"
    :style="{ width }"
    @update:model-value="emit('update:modelValue', String($event || ''))"
    @change="emit('change', String($event || ''))"
  >
    <ElOption
      v-for="item in options"
      :key="String(item.id)"
      :label="optionLabel(item)"
      :value="String(item.id)"
    />
  </ElSelect>
</template>

<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployeeOption } from '@/views/ai-agent/services/digitalEmployee';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';

defineOptions({ name: 'EmployeeOptionSelect' });

const props = withDefaults(
  defineProps<{
    modelValue?: string;
    status?: string;
    placeholder?: string;
    disabled?: boolean;
    width?: string;
  }>(),
  {
    modelValue: '',
    placeholder: '选择数字员工',
    width: '260px'
  }
);

const emit = defineEmits<{
  'update:modelValue': [string];
  change: [string];
}>();

const options = ref<DigitalEmployeeOption[]>([]);
const loading = ref(false);

const optionLabel = (item: DigitalEmployeeOption) => {
  const name = item.employeeName || `员工 #${item.id ?? '-'}`;
  return item.employeeCode ? `${name}（${item.employeeCode}）` : name;
};

async function loadOptions() {
  loading.value = true;
  try {
    options.value = await digitalEmployeeService.listOptions(undefined, props.status);
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '数字员工选项加载失败'));
    }
    options.value = [];
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.status,
  () => {
    loadOptions();
  }
);

onMounted(loadOptions);
</script>
