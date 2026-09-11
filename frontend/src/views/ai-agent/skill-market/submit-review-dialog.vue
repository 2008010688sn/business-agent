<template>
  <ElDialog
    :model-value="modelValue"
    :title="isResubmit ? '重新提交审核' : '提交审核'"
    width="560px"
    destroy-on-close
    @update:model-value="emit('update:modelValue', $event)"
    @open="handleOpen"
  >
    <ElAlert
      class="mb-12px"
      type="info"
      show-icon
      :closable="false"
      title="提交后将对所选已发布 Skill 版本做不可变快照，条目进入「审核中」，期间不可修改。"
    />
    <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
      <ElFormItem label="引用的技能" prop="skillCode">
        <ElSelect
          v-model="form.skillCode"
          class="full-width"
          filterable
          :loading="skillsLoading"
          placeholder="选择已发布的技能"
          @change="handleSkillChange"
        >
          <ElOption
            v-for="item in publishedSkills"
            :key="item.skillCode"
            :label="`${item.skillName}（${item.skillCode}）`"
            :value="item.skillCode"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="已发布版本" prop="skillVersionId">
        <ElSelect
          v-model="form.skillVersionId"
          class="full-width"
          :loading="versionsLoading"
          :disabled="!form.skillCode"
          placeholder="选择要快照的已发布版本"
        >
          <ElOption
            v-for="item in publishedVersions"
            :key="item.id"
            :label="`V${item.versionNo}${item.publishedAt ? `（发布于 ${formatTime(item.publishedAt)}）` : ''}`"
            :value="item.id"
          />
        </ElSelect>
      </ElFormItem>
      <ElFormItem label="变更说明" prop="changeNote">
        <ElInput v-model="form.changeNote" type="textarea" :rows="3" placeholder="本次提交的变更说明（选填）" />
      </ElFormItem>
    </ElForm>
    <template #footer>
      <ElButton @click="emit('update:modelValue', false)">取消</ElButton>
      <ElButton type="primary" :loading="submitting" @click="submit">提交审核</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import skillService from '@/views/ai-agent/services/skill';
import type { SkillCatalogItem, SkillVersionSummary } from '@/views/ai-agent/services/skill';
import skillMarketService from '@/views/ai-agent/services/skillMarket';
import type { SkillMarketListing } from '@/views/ai-agent/services/skillMarket';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';

const props = defineProps<{
  modelValue: boolean;
  listing: SkillMarketListing | null;
}>();

const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void;
  (event: 'submitted'): void;
}>();

const formRef = ref<FormInstance>();
const skillsLoading = ref(false);
const versionsLoading = ref(false);
const submitting = ref(false);
const publishedSkills = ref<SkillCatalogItem[]>([]);
const publishedVersions = ref<SkillVersionSummary[]>([]);
const form = reactive<{ skillCode: string; skillVersionId: string; changeNote: string }>({
  skillCode: '',
  skillVersionId: '',
  changeNote: ''
});

const isResubmit = computed(() => props.listing?.reviewStatus === 'REJECTED');

const rules: FormRules = {
  skillCode: [{ required: true, message: '请选择已发布的技能', trigger: 'change' }],
  skillVersionId: [{ required: true, message: '请选择已发布的技能版本', trigger: 'change' }]
};

async function handleOpen() {
  Object.assign(form, { skillCode: '', skillVersionId: '', changeNote: '' });
  publishedVersions.value = [];
  formRef.value?.clearValidate();
  skillsLoading.value = true;
  try {
    publishedSkills.value = await skillService.listPublished();
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载已发布技能失败'));
    }
    publishedSkills.value = [];
  } finally {
    skillsLoading.value = false;
  }
}

async function handleSkillChange(skillCode: string) {
  form.skillVersionId = '';
  publishedVersions.value = [];
  if (!skillCode) {
    return;
  }
  versionsLoading.value = true;
  try {
    const detail = await skillService.detail(skillCode);
    publishedVersions.value = (detail.versions || []).filter(item => item.status === 'PUBLISHED');
    if (publishedVersions.value.length === 0) {
      ElMessage.warning('该技能暂无已发布版本，无法提交审核');
    }
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '加载技能版本失败'));
    }
  } finally {
    versionsLoading.value = false;
  }
}

async function submit() {
  if (!props.listing) {
    return;
  }
  const valid = await formRef.value?.validate().catch(() => false);
  if (!valid) {
    return;
  }
  submitting.value = true;
  try {
    await skillMarketService.submitReview(props.listing.id, {
      skillVersionId: form.skillVersionId,
      changeNote: form.changeNote || undefined
    });
    ElMessage.success('已提交平台审核');
    emit('update:modelValue', false);
    emit('submitted');
  } catch (error) {
    if (shouldShowLocalApiError(error)) {
      ElMessage.error(extractApiErrorMessage(error, '提交审核失败'));
    }
  } finally {
    submitting.value = false;
  }
}

function formatTime(value?: string): string {
  if (!value) {
    return '';
  }
  return new Date(value).toLocaleString();
}
</script>

<style scoped>
.full-width {
  width: 100%;
}

.mb-12px {
  margin-bottom: 12px;
}
</style>
