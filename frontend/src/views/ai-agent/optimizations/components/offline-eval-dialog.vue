<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDialog
    :model-value="visible"
    title="发起候选离线评估"
    width="560px"
    destroy-on-close
    @update:model-value="handleVisibleChange"
  >
    <ElAlert
      type="warning"
      :closable="false"
      show-icon
      class="mb-12"
      title="执行意图由服务端强制为 DRY_RUN"
      :description="DRY_RUN_DESCRIPTION"
    />
    <ElForm label-width="110px">
      <ElFormItem label="候选版本">
        <ElInput :model-value="candidate?.candidateName || '-'" disabled />
      </ElFormItem>
      <ElFormItem label="评估集" required>
        <ElSelect
          v-model="form.suiteId"
          filterable
          clearable
          placeholder="选择离线评估用例集"
          :loading="optionsLoading"
          @visible-change="handleOptionsVisible"
        >
          <ElOption
            v-for="item in suiteOptions"
            :key="String(item.id)"
            :label="suiteLabel(item)"
            :value="item.id || ''"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="评估策略">
        <ElSelect
          v-model="form.policyId"
          filterable
          clearable
          placeholder="默认使用评估集策略或默认策略"
          :loading="optionsLoading"
          @visible-change="handleOptionsVisible"
        >
          <ElOption
            v-for="item in policyOptions"
            :key="String(item.id)"
            :label="policyLabel(item)"
            :value="item.id || ''"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="执行意图">
        <ElTag type="warning" effect="light">{{ executionIntentLabel(EXECUTION_INTENT_DRY_RUN) }}</ElTag>
        <span class="intent-hint">服务端强制，不可选择</span>
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="handleVisibleChange(false)">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="submit">发起离线评估</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import evaluationService from '@/views/ai-agent/services/evaluation';
import type { EvalPolicy, EvalSuite } from '@/views/ai-agent/services/evaluation';
import optimizationService from '@/views/ai-agent/services/optimization';
import type { OptCandidate, OptId } from '@/views/ai-agent/services/optimization';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import {
  DRY_RUN_DESCRIPTION,
  EXECUTION_INTENT_DRY_RUN,
  executionIntentLabel
} from '@/views/ai-agent/constants/execution-intent';

defineOptions({ name: 'OfflineEvalDialog' });

const props = defineProps<{
  visible: boolean;
  /** 待评估的优化候选 */
  candidate: OptCandidate | null;
}>();

const emit = defineEmits<{
  'update:visible': [boolean];
  /** 离线评估运行已创建（返回运行 ID 字符串，供父组件刷新详情） */
  started: [string];
}>();

const submitting = ref(false);
const optionsLoading = ref(false);
const optionsLoaded = ref(false);
const suiteOptions = ref<EvalSuite[]>([]);
const policyOptions = ref<EvalPolicy[]>([]);
const form = reactive<{ suiteId: OptId | ''; policyId: OptId | '' }>({ suiteId: '', policyId: '' });

function handleVisibleChange(visible: boolean) {
  emit('update:visible', visible);
}

function suiteLabel(item: EvalSuite) {
  return `${item.suiteName || '-'}（${item.id || '-'}）`;
}

function policyLabel(item: EvalPolicy) {
  return `${item.policyName || item.policyCode || '-'}（${item.id || '-'}）`;
}

function handleOptionsVisible(visible: boolean) {
  if (visible) {
    loadOptions();
  }
}

async function loadOptions(force = false) {
  if (optionsLoaded.value && !force) return;
  optionsLoading.value = true;
  try {
    const [suites, policies] = await Promise.all([
      evaluationService.querySuitesPage({ current: 1, size: 100, status: 'enabled' }),
      evaluationService.queryPoliciesPage({ current: 1, size: 100, status: 'enabled' })
    ]);
    suiteOptions.value = suites.data;
    policyOptions.value = policies.data;
    optionsLoaded.value = true;
  } catch (error) {
    ElMessage.warning(extractApiErrorMessage(error, '评估集/策略加载失败，请重试'));
  } finally {
    optionsLoading.value = false;
  }
}

async function submit() {
  if (!props.candidate?.id) {
    ElMessage.warning('候选信息缺失，请重新打开弹窗');
    return;
  }
  if (!form.suiteId) {
    ElMessage.warning('请选择评估集');
    return;
  }
  submitting.value = true;
  try {
    const run = await optimizationService.startCandidateOfflineEvaluation({
      candidateId: props.candidate.id,
      suiteId: form.suiteId,
      policyId: form.policyId
    });
    ElMessage.success(`离线评估已发起（DRY_RUN），运行 ID：${run.id || '-'}`);
    emit('update:visible', false);
    emit('started', run.id ? String(run.id) : '');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '离线评估发起失败'));
  } finally {
    submitting.value = false;
  }
}

watch(
  () => props.visible,
  visible => {
    if (!visible) return;
    Object.assign(form, { suiteId: '', policyId: '' });
    loadOptions();
  }
);
</script>

<style scoped>
.mb-12 {
  margin-bottom: 12px;
}

.intent-hint {
  margin-left: 8px;
  color: #94a3b8;
  font-size: 12px;
}
</style>
