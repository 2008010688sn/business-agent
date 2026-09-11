<script setup lang="ts">
import { computed } from 'vue';
import { useFullscreen } from '@vueuse/core';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { GLOBAL_HEADER_MENU_ID } from '@/constants/app';
import GlobalLogo from '../global-logo/index.vue';
import GlobalBreadcrumb from '../global-breadcrumb/index.vue';
import GlobalSearch from '../global-search/index.vue';
import ThemeButton from './components/theme-button.vue';
import UserAvatar from './components/user-avatar.vue';
import WarnPasswodTip from './components/WarnPasswodTip.vue';

defineOptions({ name: 'GlobalHeader' });

interface Props {
  /** Whether to show the logo */
  showLogo?: App.Global.HeaderProps['showLogo'];
  /** Whether to show the menu toggler */
  showMenuToggler?: App.Global.HeaderProps['showMenuToggler'];
  /** Whether to show the menu */
  showMenu?: App.Global.HeaderProps['showMenu'];
}

defineProps<Props>();

const appStore = useAppStore();
const themeStore = useThemeStore();
const { isFullscreen, toggle } = useFullscreen();

type StyleMapType = Record<UnionKey.ThemeStyle, string>;

const globalHeaderClass = computed(() => {
  const styleMap: StyleMapType = {
    classic: 'h-full flex-y-center px-12px shadow-header',
    minimal: 'h-full flex-y-center px-16px shadow-header-minimal border-header-minimal',
    enterprise: 'h-full flex-y-center px-12px shadow-header-enterprise border-header-enterprise'
  };
  return styleMap[themeStore.themeStyle] || styleMap.classic;
});

const globalHeaderActionsClass = computed(() => {
  const styleMap: StyleMapType = {
    classic: 'h-full flex-y-center justify-end',
    minimal: 'h-full flex-y-center justify-end gap-8px',
    enterprise: 'h-full flex-y-center justify-end gap-8px'
  };
  return styleMap[themeStore.themeStyle] || styleMap.classic;
});

const themeTransitionDuration = 560;
const themeTransitionActiveClass = 'theme-view-transition-active';
const themeTransitionShrinkClass = 'theme-view-transition-shrink';

interface ViewTransitionLike {
  ready: Promise<void>;
  finished: Promise<void>;
  skipTransition: () => void;
}

type DocumentWithViewTransition = Document & {
  startViewTransition?: (updateCallback: () => void | Promise<void>) => ViewTransitionLike;
};

type ElementWithPseudoAnimate = HTMLElement & {
  animate: (
    keyframes: Keyframe[] | PropertyIndexedKeyframes | null,
    options?: KeyframeAnimationOptions & { pseudoElement?: string }
  ) => Animation;
};

function getThemeSwitchPoint(event?: MouseEvent) {
  if (event) {
    return {
      x: event.clientX,
      y: event.clientY
    };
  }

  return {
    x: window.innerWidth / 2,
    y: window.innerHeight / 2
  };
}

function isReducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

function animateThemeViewTransition(x: number, y: number, maxRadius: number, isDarkBeforeSwitch: boolean) {
  const root = document.documentElement as ElementWithPseudoAnimate;
  const clipPath = isDarkBeforeSwitch
    ? [`circle(0px at ${x}px ${y}px)`, `circle(${maxRadius}px at ${x}px ${y}px)`]
    : [`circle(${maxRadius}px at ${x}px ${y}px)`, `circle(0px at ${x}px ${y}px)`];
  const pseudoElement = isDarkBeforeSwitch ? '::view-transition-new(root)' : '::view-transition-old(root)';

  return root.animate(
    {
      clipPath
    },
    {
      duration: themeTransitionDuration,
      easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
      fill: 'both',
      pseudoElement
    }
  );
}

function handleThemeSwitch(event?: MouseEvent) {
  const documentWithViewTransition = document as DocumentWithViewTransition;

  if (!event || !documentWithViewTransition.startViewTransition || isReducedMotion()) {
    themeStore.toggleThemeScheme();
    return;
  }

  const { x, y } = getThemeSwitchPoint(event);
  const maxRadius = Math.hypot(Math.max(window.innerWidth - x, x), Math.max(window.innerHeight - y, y));
  const isDarkBeforeSwitch = themeStore.darkMode;
  const htmlElement = document.documentElement;

  htmlElement.classList.add(themeTransitionActiveClass);

  if (!isDarkBeforeSwitch) {
    htmlElement.classList.add(themeTransitionShrinkClass);
  }

  const transition = documentWithViewTransition.startViewTransition(() => {
    themeStore.toggleThemeScheme();
  });

  transition.ready
    .then(() => {
      const animation = animateThemeViewTransition(x, y, maxRadius, isDarkBeforeSwitch);

      return animation.finished;
    })
    .catch(() => {
      transition.skipTransition();
    })
    .finally(() => {
      htmlElement.classList.remove(themeTransitionActiveClass, themeTransitionShrinkClass);
    });
}
</script>

<template>
  <DarkModeContainer :class="globalHeaderClass">
    <GlobalLogo v-if="showLogo" class="h-full" :style="{ width: themeStore.sider.width + 'px' }" />
    <MenuToggler v-if="showMenuToggler" :collapsed="appStore.siderCollapse" @click="appStore.toggleSiderCollapse" />
    <div v-if="showMenu" :id="GLOBAL_HEADER_MENU_ID" class="h-full flex-y-center flex-1-hidden"></div>
    <div v-else class="h-full flex-y-center flex-1-hidden">
      <GlobalBreadcrumb v-if="!appStore.isMobile" class="ml-12px" />
    </div>
    <div :class="globalHeaderActionsClass">
      <WarnPasswodTip />
      <GlobalSearch />
      <div>
        <FullScreen v-if="!appStore.isMobile" :full="isFullscreen" @click="toggle" />
      </div>
      <LangSwitch
        v-if="themeStore.header.multilingual.visible"
        :lang="appStore.locale"
        :lang-options="appStore.localeOptions"
        @change-lang="appStore.changeLocale"
      />
      <ThemeSchemaSwitch
        :theme-schema="themeStore.themeScheme"
        :is-dark="themeStore.darkMode"
        @switch="handleThemeSwitch"
      />
      <div>
        <ThemeButton />
      </div>
      <UserAvatar />
    </div>
  </DarkModeContainer>
</template>

<style scoped>
.shadow-header-minimal {
  box-shadow: none;
}

.border-header-minimal {
  border-bottom: var(--minimal-nav-border);
}

.shadow-header-enterprise {
  box-shadow: none;
}

.border-header-enterprise {
  border-bottom: 1px solid rgba(0, 0, 0, 0.06);
}
</style>

<style>
html.theme-style-minimal .dark-mode-container {
  background: var(--minimal-nav-bg);
}

html.theme-style-enterprise .dark-mode-container {
  background: var(--enterprise-nav-bg);
}

html,
body,
#app,
::view-transition {
  background: rgb(var(--layout-bg-color));
}

html.theme-view-transition-active *,
html.theme-view-transition-active *::before,
html.theme-view-transition-active *::after {
  transition: none !important;
}

::view-transition-group(root),
::view-transition-image-pair(root),
::view-transition-old(root),
::view-transition-new(root) {
  animation: none;
}

::view-transition-image-pair(root),
::view-transition-old(root),
::view-transition-new(root) {
  mix-blend-mode: normal;
}

::view-transition-old(root) {
  z-index: 1;
}

::view-transition-new(root) {
  z-index: 2;
}

html.theme-view-transition-shrink::view-transition-old(root) {
  z-index: 2;
}

html.theme-view-transition-shrink::view-transition-new(root) {
  z-index: 1;
}
</style>
