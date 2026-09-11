<script setup lang="ts">
import { computed, ref, useAttrs } from 'vue';
import { storeToRefs } from 'pinia';
import { ElForm as ElFormNative } from 'element-plus';
import type { FormInstance } from 'element-plus';
import { useAppStore } from '@/store/modules/app';

defineOptions({
  name: 'ElForm',
  inheritAttrs: false
});

const attrs = useAttrs();
const appStore = useAppStore();
const { locale } = storeToRefs(appStore);
const formRef = ref<FormInstance>();

const mergedAttrs = computed(() => {
  const raw = { ...attrs } as Record<string, unknown>;
  const keepFixed = Boolean(raw.keepFixedLabelWidth ?? raw.disableLocaleAutoLabelWidth);

  delete raw.keepFixedLabelWidth;
  delete raw.disableLocaleAutoLabelWidth;

  if (locale.value === 'zh-CN' || keepFixed) {
    return raw;
  }
  delete raw['label-width'];

  return raw;
});

defineExpose({
  formRef,
  validate: (...args: Parameters<FormInstance['validate']>) => formRef.value?.validate(...args),
  validateField: (...args: Parameters<FormInstance['validateField']>) => formRef.value?.validateField(...args),
  resetFields: (...args: Parameters<FormInstance['resetFields']>) => formRef.value?.resetFields(...args),
  clearValidate: (...args: Parameters<FormInstance['clearValidate']>) => formRef.value?.clearValidate(...args),
  scrollToField: (...args: Parameters<FormInstance['scrollToField']>) => formRef.value?.scrollToField(...args),
  fields: computed(() => formRef.value?.fields ?? [])
});
</script>

<template>
  <ElFormNative ref="formRef" v-bind="mergedAttrs">
    <template v-for="(_, slotName) in $slots" :key="slotName" #[slotName]="slotProps">
      <slot :name="slotName" v-bind="slotProps || {}" />
    </template>
  </ElFormNative>
</template>
