<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="permission-config-tab">
    <div class="permission-header">
      <div>
        <span class="section-kicker">IDENTITY & ACCESS</span>
        <h2>权限配置</h2>
        <p>管理数字员工的执行身份与 IAM 角色；技能和 Grant USE 仍由全局权限中心管理。</p>
      </div>
      <ElButton :loading="loading" @click="refresh"><ElIcon><Refresh /></ElIcon>刷新</ElButton>
    </div>
    <PrincipalPermissionPanel :key="`${String(employee.id)}-${panelKey}`" :employee="employee" @changed="handleChanged" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import type { DigitalEmployee } from '@/views/ai-agent/services/digitalEmployee';
import PrincipalPermissionPanel from './principal-permission-panel.vue';

defineOptions({ name: 'EmployeePermissionConfigTab' });

const props = defineProps<{
  employee: DigitalEmployee;
}>();

const emit = defineEmits<{ changed: [] }>();
const loading = ref(false);
const panelKey = ref(0);

function handleChanged() {
  emit('changed');
}

async function refresh() {
  loading.value = true;
  try {
    panelKey.value += 1;
    await Promise.resolve(props.employee.id);
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.permission-config-tab { display: flex; flex-direction: column; gap: 16px; }
.permission-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding-bottom: 4px; }
.section-kicker { display: block; margin-bottom: 4px; color: var(--el-color-primary); font-size: 11px; font-weight: 700; letter-spacing: 0.08em; }
.permission-header h2 { margin: 0; color: var(--el-text-color-primary); font-size: 20px; }
.permission-header p { margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
@media (max-width: 640px) { .permission-header { flex-direction: column; } }
</style>
