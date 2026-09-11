<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <ElDialog
    v-model="visible"
    :title="isEdit ? '修改授权策略（仅草稿可改）' : '新建授权策略'"
    width="760px"
    :close-on-click-modal="false"
    destroy-on-close
    @closed="handleClosed"
  >
    <ElForm ref="formRef" :model="form" :rules="formRules" label-width="100px">
      <ElFormItem label="策略编码" prop="code">
        <ElInput
          v-model="form.code"
          :disabled="isEdit"
          placeholder="租户内唯一，如 order-agent-prod-policy"
          maxlength="64"
          show-word-limit
        />
      </ElFormItem>
      <ElFormItem label="策略名称" prop="name">
        <ElInput v-model="form.name" placeholder="如 订单智能体生产授权策略" maxlength="128" show-word-limit />
      </ElFormItem>
      <ElFormItem v-if="!isEdit" label="来源模板" prop="templateCode">
        <ElSelect v-model="form.templateCode" clearable placeholder="选择模板后可一键填充默认策略 JSON" @change="handleTemplateChange">
          <ElOption v-for="item in templates" :key="item.code" :label="item.code" :value="item.code" />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="策略 JSON" prop="policyJson">
        <div class="policy-json-wrap">
          <ElInput
            v-model="form.policyJson"
            type="textarea"
            :rows="12"
            placeholder="与 templateCode 至少提供一个；提供时优先使用并经后端严格校验（未知字段拒绝、schemaVersion=1）"
            spellcheck="false"
            class="policy-json-input"
          />
          <div class="policy-json-tips">
            后端按 PolicyValidator 严格校验：schemaVersion 必须为 1，未知字段直接拒绝；建议从模板默认 JSON 改起。
          </div>
        </div>
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="visible = false">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="handleSubmit">{{ isEdit ? '保存' : '创建' }}</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import authorizationService from '@/views/ai-agent/services/authorization';
import type { PolicyDetailResp, TemplateResp } from '@/views/ai-agent/services/authorization';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';

defineOptions({ name: 'AuthorizationPolicyFormDialog' });

const props = defineProps<{
  visible: boolean;
  mode: 'create' | 'edit';
  policy: PolicyDetailResp | null;
  /** 创建模式下预选的模板编码（从模板卡片“基于此模板新建”进入时传入） */
  presetTemplateCode?: string;
}>();

const emit = defineEmits<{
  (e: 'update:visible', value: boolean): void;
  (e: 'saved'): void;
}>();

const visible = computed({
  get: () => props.visible,
  set: value => emit('update:visible', value)
});

const isEdit = computed(() => props.mode === 'edit');

const formRef = ref<FormInstance>();
const submitting = ref(false);
const templates = ref<TemplateResp[]>([]);

const form = reactive({
  code: '',
  name: '',
  templateCode: '',
  policyJson: ''
});

const formRules: FormRules = {
  code: [{ required: true, message: '请输入策略编码', trigger: 'blur' }],
  name: [{ required: true, message: '请输入策略名称', trigger: 'blur' }],
  policyJson: [
    {
      validator: (_rule, value: string, callback) => {
        if (!isEdit.value && !value && !form.templateCode) {
          callback(new Error('策略 JSON 与来源模板至少提供一个'));
          return;
        }
        if (value) {
          try {
            JSON.parse(value);
          } catch {
            callback(new Error('策略 JSON 不是合法的 JSON 格式'));
            return;
          }
        }
        callback();
      },
      trigger: 'blur'
    }
  ]
};

watch(
  () => props.visible,
  async value => {
    if (!value) return;
    resetForm();
    if (isEdit.value && props.policy) {
      form.code = props.policy.code || '';
      form.name = props.policy.name || '';
      // 编辑时回填草稿 JSON（无草稿回填当前发布版），保存时覆盖草稿版本
      form.policyJson = props.policy.draftPolicyJson || props.policy.currentPolicyJson || '';
    }
    if (!isEdit.value) {
      await loadTemplates();
      // 模板卡片入口：预选模板并填充默认策略 JSON
      if (props.presetTemplateCode) {
        form.templateCode = props.presetTemplateCode;
        const matched = templates.value.find(item => item.code === props.presetTemplateCode);
        if (matched?.defaultPolicyJson) {
          form.policyJson = formatJson(matched.defaultPolicyJson);
        }
      }
    }
  }
);

async function loadTemplates() {
  try {
    templates.value = await authorizationService.listTemplates();
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, '授权模板列表加载失败'));
  }
}

/** 选择模板后一键填充默认策略 JSON（仍可手工修改，提交时后端严格校验）。 */
function handleTemplateChange(code: string) {
  const matched = templates.value.find(item => item.code === code);
  if (matched?.defaultPolicyJson) {
    form.policyJson = formatJson(matched.defaultPolicyJson);
  }
}

const formatJson = (value: string): string => {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
};

function resetForm() {
  form.code = '';
  form.name = '';
  form.templateCode = '';
  form.policyJson = '';
  formRef.value?.clearValidate();
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) return;

  submitting.value = true;
  try {
    if (isEdit.value && props.policy?.id) {
      await authorizationService.modifyPolicy(props.policy.id, {
        name: form.name,
        policyJson: form.policyJson
      });
      ElMessage.success('策略修改成功');
    } else {
      await authorizationService.createPolicy({
        code: form.code,
        name: form.name,
        templateCode: form.templateCode || undefined,
        policyJson: form.policyJson || undefined
      });
      ElMessage.success('策略创建成功');
    }
    visible.value = false;
    emit('saved');
  } catch (error) {
    ElMessage.error(extractApiErrorMessage(error, isEdit.value ? '策略修改失败' : '策略创建失败'));
  } finally {
    submitting.value = false;
  }
}

function handleClosed() {
  resetForm();
}
</script>

<style scoped>
.policy-json-wrap {
  width: 100%;
}

.policy-json-input :deep(textarea) {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
  line-height: 1.6;
}

.policy-json-tips {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
