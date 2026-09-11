<script setup lang="ts">
import { computed } from 'vue';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { GLOBAL_SIDER_MENU_ID } from '@/constants/app';
import GlobalLogo from '../global-logo/index.vue';

defineOptions({ name: 'GlobalSider' });

const appStore = useAppStore();
const themeStore = useThemeStore();

const isVerticalMix = computed(() => themeStore.layout.mode === 'vertical-mix');
const isHorizontalMix = computed(() => themeStore.layout.mode === 'horizontal-mix');
const darkMenu = computed(() => !themeStore.darkMode && !isHorizontalMix.value && themeStore.sider.inverted);
const showLogo = computed(() => !isVerticalMix.value && !isHorizontalMix.value);
const menuWrapperClass = computed(() => (showLogo.value ? 'flex-1-hidden' : 'h-full'));

function closeSider() {
  appStore.setSiderCollapse(true);
}
</script>

<template>
  <DarkModeContainer class="shadow-sider-minimal border-sider-minimal size-full flex-col-stretch" :inverted="darkMenu">
    <!-- 窄屏抽屉关闭按钮 -->
    <div
      v-if="appStore.isMobile && !appStore.siderCollapse"
      class="absolute right-8px top-4px z-10 cursor-pointer rounded-full p-4px text-18px hover:bg-[rgba(0,0,0,0.06)]"
      @click="closeSider"
    >
      <icon-mdi-close />
    </div>
    <GlobalLogo
      v-if="showLogo"
      :show-title="!appStore.siderCollapse"
      :style="{ height: themeStore.header.height + 'px' }"
    />
    <div :id="GLOBAL_SIDER_MENU_ID" :class="menuWrapperClass"></div>
  </DarkModeContainer>
</template>

<style scoped>
.shadow-sider-minimal {
  box-shadow: var(--minimal-shadow-sm);
}

.border-sider-minimal {
  border-right: var(--minimal-border-light);
}

:deep(.dark-mode-container) {
  background: var(--minimal-bg-container);
}
</style>
