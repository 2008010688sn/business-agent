<script setup lang="ts">
import { computed } from 'vue';
import type { WatermarkProps } from 'element-plus';
import { useAppStore } from './store/modules/app';
import { useThemeStore } from './store/modules/theme';
import { UILocales } from './locales/ui';

defineOptions({ name: 'App' });

const appStore = useAppStore();
const themeStore = useThemeStore();

const locale = computed(() => UILocales[appStore.locale]);

const watermarkProps = computed<WatermarkProps>(() => {
  const isDark = themeStore.darkMode;
  return {
    content: themeStore.watermark.visible ? themeStore.watermark.text || '' : '',
    cross: true,
    fontSize: 14,
    lineHeight: 14,
    gap: [160, 160],
    rotate: -15,
    zIndex: 9999,
    font: {
      color: isDark ? 'rgba(255, 255, 255, .08)' : 'rgba(0, 0, 0, .1)'
    }
  };
});
</script>

<template>
  <ElConfigProvider :locale="locale">
    <AppProvider>
      <ElWatermark class="h-full" v-bind="watermarkProps">
        <RouterView class="bg-layout" />
      </ElWatermark>
    </AppProvider>
  </ElConfigProvider>
</template>
