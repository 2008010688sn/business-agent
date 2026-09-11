<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <OverviewTab :employee="employee" :can-manage="canManage" :loading="loading" @changed="emit('changed')" />
</template>

<script setup lang="ts">
import type { DigitalEmployee } from '@/views/ai-agent/services/digitalEmployee';
import OverviewTab from './overview-tab.vue';

defineOptions({ name: 'EmployeeBasicInfoPageTab' });

defineProps<{
  employee: DigitalEmployee;
  canManage: boolean;
  loading?: boolean;
}>();

const emit = defineEmits<{
  changed: [];
}>();
</script>
