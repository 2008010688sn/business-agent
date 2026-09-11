<script setup lang="ts">
import { computed } from 'vue';
import { themeStyleOptions } from '@/constants/app';
import { useThemeStore } from '@/store/modules/theme';
import { $t } from '@/locales';

defineOptions({ name: 'ThemeStyle' });

const themeStore = useThemeStore();

const options = computed(() =>
  themeStyleOptions.map(option => ({
    ...option,
    label: $t(option.label as App.I18n.I18nKey)
  }))
);

function handleSegmentChange(value: string | number | boolean | undefined) {
  if (value === 'classic' || value === 'minimal' || value === 'enterprise') {
    themeStore.setThemeStyle(value);
  }
}
</script>

<template>
  <ElDivider>{{ $t('theme.themeStyle.title') }}</ElDivider>
  <div class="i-flex-center">
    <ElSegmented
      :model-value="themeStore.themeStyle"
      :options="options"
      class="theme-style-segmented"
      @update:model-value="handleSegmentChange"
    />
  </div>
</template>

<style scoped>
.theme-style-segmented {
  min-width: 220px;
}
</style>
