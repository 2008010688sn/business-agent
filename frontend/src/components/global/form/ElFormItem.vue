<script setup lang="ts">
import { computed, useAttrs } from 'vue';
import { storeToRefs } from 'pinia';
import { ElFormItem as ElFormItemNative } from 'element-plus';
import { useAppStore } from '@/store/modules/app';

defineOptions({
  name: 'ElFormItem',
  inheritAttrs: false
});

const attrs = useAttrs();
const appStore = useAppStore();
const { locale } = storeToRefs(appStore);

const mergedAttrs = computed(() => {
  const raw = { ...attrs } as Record<string, unknown>;
  const keepFixed = Boolean(raw.keepFixedLabelWidth ?? raw.disableLocaleAutoLabelWidth);

  delete raw.keepFixedLabelWidth;
  delete raw.disableLocaleAutoLabelWidth;

  if (locale.value !== 'zh-CN' && !keepFixed) {
    delete raw.labelWidth;
    delete raw['label-width'];
  }

  return raw;
});
</script>

<template>
  <ElFormItemNative v-bind="mergedAttrs">
    <template v-for="(_, slotName) in $slots" :key="slotName" #[slotName]="slotProps">
      <slot :name="slotName" v-bind="slotProps || {}" />
    </template>
  </ElFormItemNative>
</template>
