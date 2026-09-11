<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDrawer
    :model-value="visible"
    title="执行权限（Service Principal）"
    size="560px"
    destroy-on-close
    @update:model-value="value => emit('update:visible', value)"
  >
    <PrincipalPermissionPanel v-if="employee" :employee="employee" @changed="emit('changed')" />
  </ElDrawer>
</template>

<script setup lang="ts">
import type { DigitalEmployee } from '@/views/ai-agent/services/digitalEmployee';
import PrincipalPermissionPanel from '../detail/components/principal-permission-panel.vue';

defineOptions({ name: 'PrincipalPermissionDrawer' });

defineProps<{
  visible: boolean;
  employee: DigitalEmployee | null;
}>();

const emit = defineEmits<{
  'update:visible': [boolean];
  changed: [];
}>();
</script>
