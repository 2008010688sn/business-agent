<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDialog
    v-model="dialogVisible"
    title="加入评估集"
    width="min(520px, 92vw)"
    append-to-body
    destroy-on-close
    @open="handleOpen"
  >
    <ElAlert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon class="dialog-alert">
      <ElButton link type="primary" :loading="suiteLoading" @click="loadSuites">重试</ElButton>
    </ElAlert>

    <ElForm ref="formRef" :model="form" :rules="rules" label-width="88px">
      <ElFormItem label="运行任务">
        <span class="run-query">{{ runQuery || '未记录任务描述' }}</span>
      </ElFormItem>
      <ElFormItem label="评估集" prop="suiteId">
        <ElSelect
          v-model="form.suiteId"
          filterable
          :loading="suiteLoading"
          placeholder="选择要归档到的评估集"
          class="full-width"
          :no-data-text="loadError ? '加载失败，未获取到评估集' : '暂无可用评估集'"
        >
          <ElOption v-for="item in suites" :key="String(item.id)" :label="item.suiteName" :value="String(item.id)" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="用例名称">
        <ElInput v-model="form.caseName" maxlength="128" show-word-limit placeholder="留空则由后端按运行自动命名" />
      </ElFormItem>
      <ElFormItem label="期望输出">
        <ElInput
          v-model="form.expectedOutput"
          type="textarea"
          :rows="4"
          placeholder="留空则取运行的最终结果作为参考输出"
        />
      </ElFormItem>
    </ElForm>

    <template #footer>
      <ElButton @click="dialogVisible = false">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="submit">加入</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import evaluationService from '@/views/ai-agent/services/evaluation';
import type { EvalSuite } from '@/views/ai-agent/services/evaluation';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';

defineOptions({ name: 'AddRunToEvalSuiteDialog' });

const props = defineProps<{
  visible: boolean;
  /** 持久运行时运行 ID，后端按 runtimeRunId 分支从 agent_runtime_run 取材 */
  runId: string;
  /** 运行的任务描述，仅用于确认弹窗里让人看清在归档哪一次运行 */
  runQuery?: string | null;
}>();

const emit = defineEmits<{
  (event: 'update:visible', value: boolean): void;
  (event: 'imported'): void;
}>();

const dialogVisible = ref(props.visible);
const formRef = ref<FormInstance | null>(null);
const suites = ref<EvalSuite[]>([]);
const suiteLoading = ref(false);
/** 评估集加载失败原因；非空时弹窗顶部常驻错误态，与「确实没有评估集」区分开 */
const loadError = ref('');
const submitting = ref(false);

const form = reactive({ suiteId: '', caseName: '', expectedOutput: '' });

const rules: FormRules = {
  suiteId: [{ required: true, message: '请选择评估集', trigger: 'change' }]
};

watch(
  () => props.visible,
  value => {
    dialogVisible.value = value;
  }
);

watch(dialogVisible, value => {
  emit('update:visible', value);
});

function handleOpen() {
  Object.assign(form, { suiteId: '', caseName: '', expectedOutput: '' });
  formRef.value?.clearValidate();
  loadSuites();
}

async function loadSuites() {
  suiteLoading.value = true;
  loadError.value = '';
  try {
    const page = await evaluationService.querySuitesPage({ current: 1, size: 100, status: 'enabled' });
    suites.value = page.data;
  } catch (error) {
    loadError.value = extractApiErrorMessage(error, '加载评估集失败');
    suites.value = [];
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(loadError.value);
    }
  } finally {
    suiteLoading.value = false;
  }
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;
  submitting.value = true;
  try {
    await evaluationService.importCase({
      suiteId: form.suiteId,
      runtimeRunId: props.runId,
      caseName: form.caseName.trim() || undefined,
      expectedOutput: form.expectedOutput.trim() || undefined
    });
    ElMessage.success('已加入评估集');
    dialogVisible.value = false;
    emit('imported');
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加入评估集失败'));
    }
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.dialog-alert {
  margin-bottom: 12px;
}

.full-width {
  width: 100%;
}

.run-query {
  color: var(--el-text-color-regular);
  line-height: 1.6;
  word-break: break-all;
}
</style>
