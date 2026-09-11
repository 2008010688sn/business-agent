<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="overview-tab">
    <section class="info-section">
      <div class="section-heading">
        <div>
          <span class="section-kicker">PROFILE</span>
          <h2>基础信息</h2>
        </div>
        <ElButton v-if="canEdit" type="primary" plain size="small" @click="formDialogVisible = true">编辑档案</ElButton>
      </div>
      <ElDescriptions :column="2" border size="small" class="mb-16">
      <ElDescriptionsItem label="员工名称">{{ employee.employeeName || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="员工编码">{{ employee.employeeCode || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="岗位">{{ employee.jobTitle || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="自治级别">
        <ElTag :type="employee.autonomyLevel === 'AUTONOMOUS' ? 'success' : 'warning'" effect="light" size="small">
          {{ employee.autonomyLevel || 'ASSISTED' }}
        </ElTag>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="业务负责人">{{ employee.managerUserId || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="审批人">{{ employee.approverUserId || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="对话模型配置">{{ employee.modelConfigId || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="路由档案">{{ employee.routeProfileId || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="能力来源 DataAgent">
        {{ employee.sourceAgentId || '无（空白创建）' }}
        <span class="cell-muted"> · 仅溯源，运行时事实源是员工 Release</span>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="灰度开关">
        <ElTag :type="employee.rolloutEnabled === false ? 'warning' : 'success'" effect="light" size="small">
          {{ employee.rolloutEnabled === false ? '关闭（不可 PRINCIPAL）' : '开启' }}
        </ElTag>
      </ElDescriptionsItem>
      <ElDescriptionsItem label="草稿修订号">
        {{ employee.draftRevision ?? '-' }}（draftRevision，草稿每次变更 +1）
      </ElDescriptionsItem>
      <ElDescriptionsItem label="状态版本号">
        {{ employee.stateVersion ?? '-' }}（stateVersion，状态迁移 CAS 基准）
      </ElDescriptionsItem>
      <ElDescriptionsItem label="创建信息">
        {{ employee.createName || employee.createBy || '-' }} · {{ formatDateTime(employee.createTime) }}
      </ElDescriptionsItem>
      <ElDescriptionsItem label="最近修改">{{ formatDateTime(employee.lastModifyTime) }}</ElDescriptionsItem>
      <ElDescriptionsItem label="员工描述" :span="2">{{ employee.description || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="开场白" :span="2">{{ employee.greeting || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="系统提示词（草稿）" :span="2">
        <pre class="instruction-block">{{ employee.systemInstruction || '-' }}</pre>
      </ElDescriptionsItem>
      </ElDescriptions>
    </section>

    <EmployeeFormDialog
      v-model:visible="formDialogVisible"
      mode="edit"
      :employee="employee"
      @saved="emit('changed')"
    />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import dayjs from 'dayjs';
import type { DigitalEmployee } from '@/views/ai-agent/services/digitalEmployee';
import EmployeeFormDialog from '../../components/employee-form-dialog.vue';

defineOptions({ name: 'EmployeeBasicInfoTab' });

const props = defineProps<{
  employee: DigitalEmployee;
  canManage: boolean;
  loading?: boolean;
}>();

const emit = defineEmits<{
  changed: [];
}>();

const formDialogVisible = ref(false);
const canEdit = computed(() => props.canManage && props.employee.status !== 'ARCHIVED');

const formatDateTime = (value?: string) => {
  if (!value) return '-';
  const date = dayjs(value);
  return date.isValid() ? date.format('YYYY-MM-DD HH:mm:ss') : value;
};

</script>

<style scoped>
.info-section {
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-bg-color);
}

.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.section-kicker {
  display: block;
  margin-bottom: 4px;
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}

.section-heading h2 {
  margin: 0;
  color: var(--el-text-color-primary);
  font-size: 17px;
}

.instruction-block {
  max-height: 200px;
  margin: 0;
  overflow: auto;
  padding: 8px 10px;
  border-radius: 4px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.cell-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mb-16 {
  margin-bottom: 16px;
}

</style>
