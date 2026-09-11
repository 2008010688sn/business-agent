<template>
  <ElDialog
    :model-value="modelValue"
    title="平台审核"
    width="520px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
    @open="handleOpen"
  >
    <ElDescriptions v-if="listing" :column="1" border class="mb-12px">
      <ElDescriptionsItem label="条目名称">{{ listing.listingName }}</ElDescriptionsItem>
      <ElDescriptionsItem label="发布者">{{ listing.publisherName || '-' }}</ElDescriptionsItem>
      <ElDescriptionsItem label="风险等级">{{ marketRiskLevelLabel(listing.riskLevel) }}</ElDescriptionsItem>
    </ElDescriptions>
    <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
      <ElFormItem label="审核结论" prop="conclusion">
        <ElRadioGroup v-model="form.conclusion">
          <ElRadio value="APPROVED">通过</ElRadio>
          <ElRadio value="REJECTED">驳回</ElRadio>
        </ElRadioGroup>
      </ElFormItem>
      <ElFormItem label="审核意见" prop="opinion">
        <ElInput
          v-model="form.opinion"
          type="textarea"
          :rows="3"
          :placeholder="form.conclusion === 'REJECTED' ? '驳回必须填写审核意见' : '审核意见（选填）'"
        />
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton :type="form.conclusion === 'REJECTED' ? 'danger' : 'primary'" :loading="submitting" @click="submit">
        {{ form.conclusion === 'REJECTED' ? '确认驳回' : '确认通过' }}
      </ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import skillMarketService, { marketRiskLevelLabel } from '@/views/ai-agent/services/skillMarket';
import type { MarketReviewConclusion, SkillMarketListing } from '@/views/ai-agent/services/skillMarket';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';

const props = defineProps<{
  modelValue: boolean;
  listing: SkillMarketListing | null;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'reviewed'): void;
}>();

const formRef = ref<FormInstance>();
const submitting = ref(false);
const form = reactive<{ conclusion: MarketReviewConclusion; opinion: string }>({
  conclusion: 'APPROVED',
  opinion: ''
});

const opinionValidator = (_rule: unknown, value: string | undefined, callback: (error?: Error) => void) => {
  if (form.conclusion === 'REJECTED' && (!value || !value.trim())) {
    callback(new Error('驳回时审核意见必填'));
    return;
  }
  callback();
};

const rules: FormRules = {
  conclusion: [{ required: true, message: '请选择审核结论', trigger: 'change' }],
  opinion: [{ validator: opinionValidator, trigger: 'blur' }]
};

function handleOpen() {
  Object.assign(form, { conclusion: 'APPROVED', opinion: '' });
  formRef.value?.clearValidate();
}

async function submit() {
  if (!props.listing) {
    return;
  }
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }
  if (form.conclusion === 'REJECTED') {
    try {
      await ElMessageBox.confirm(
        `确认驳回条目「${props.listing.listingName}」？发布者修改后可重新提交审核。`,
        '驳回确认',
        { type: 'warning', confirmButtonText: '确认驳回', cancelButtonText: '取消' }
      );
    } catch {
      return;
    }
  }
  submitting.value = true;
  try {
    await skillMarketService.review(props.listing.id, {
      conclusion: form.conclusion,
      opinion: form.opinion || undefined
    });
    ElMessage.success(form.conclusion === 'APPROVED' ? '条目已审核通过' : '条目已驳回');
    emit('update:modelValue', false);
    emit('reviewed');
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '提交审核结论失败'));
    }
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.mb-12px {
  margin-bottom: 12px;
}
</style>
