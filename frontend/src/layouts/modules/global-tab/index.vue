<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { useElementBounding } from '@vueuse/core';
import { PageTab } from '@sa/materials';
import BetterScroll from '@/components/custom/better-scroll.vue';
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import { useRouteStore } from '@/store/modules/route';
import { useTabStore } from '@/store/modules/tab';
import { isPC } from '@/utils/agent';
import emitter from '@/utils/event_bus';
import ContextMenu from './context-menu.vue';

defineOptions({ name: 'GlobalTab' });

const route = useRoute();
const router = useRouter();
const appStore = useAppStore();
const themeStore = useThemeStore();
const routeStore = useRouteStore();
const tabStore = useTabStore();

const bsWrapper = ref<HTMLElement>();
const { width: bsWrapperWidth, left: bsWrapperLeft } = useElementBounding(bsWrapper);
const bsScroll = ref<InstanceType<typeof BetterScroll>>();
const tabRef = ref<HTMLElement>();
const isPCFlag = isPC();

const TAB_DATA_ID = 'data-tab-id';

const tabContainerClass = computed(() => {
  if (themeStore.themeStyle === 'minimal') {
    return 'size-full flex-y-center px-16px shadow-tab-minimal border-tab-minimal minimal-tab';
  }
  if (themeStore.themeStyle === 'enterprise') {
    return 'size-full flex-y-center px-12px shadow-tab-enterprise border-tab-enterprise enterprise-tab';
  }
  return 'size-full flex-y-center px-12px shadow-tab shadow-tab-enterprise';
});

let scrollTimer: ReturnType<typeof setTimeout> | undefined;

function scrollToActiveTab() {
  const tabElement = tabRef.value;
  if (!tabElement?.isConnected) return;

  const { children } = tabElement;

  for (let i = 0; i < children.length; i += 1) {
    const child = children[i];

    const tabId = child.getAttribute(TAB_DATA_ID);

    if (tabId === tabStore.activeTabId) {
      const { left, width } = child.getBoundingClientRect();
      const clientX = left + width / 2;

      if (scrollTimer) {
        clearTimeout(scrollTimer);
      }

      scrollTimer = setTimeout(() => {
        if (!child.isConnected || tabStore.activeTabId !== tabId) return;

        scrollByClientX(clientX);
      }, 50);

      break;
    }
  }
}

function scrollByClientX(clientX: number) {
  const currentX = clientX - bsWrapperLeft.value;
  const deltaX = currentX - bsWrapperWidth.value / 2;

  if (bsScroll.value?.instance) {
    const { maxScrollX, x: leftX, scrollBy } = bsScroll.value.instance;

    const rightX = maxScrollX - leftX;
    const update = deltaX > 0 ? Math.max(-deltaX, rightX) : Math.min(-deltaX, -leftX);

    scrollBy(update, 0, 300);
  }
}

function getContextMenuDisabledKeys(tabId: string) {
  const disabledKeys: App.Global.DropdownKey[] = [];

  if (tabStore.isTabRetain(tabId)) {
    const homeDisable: App.Global.DropdownKey[] = ['closeCurrent', 'closeLeft'];
    disabledKeys.push(...homeDisable);
  }

  return disabledKeys;
}

async function handleCloseTab(tab: App.Global.Tab) {
  await tabStore.removeTab(tab.id);

  if (themeStore.resetCacheStrategy === 'close') {
    routeStore.resetRouteCache(tab.routeKey);
  }
}

async function refresh() {
  appStore.reloadPage(500);
}

interface DropdownConfig {
  visible: boolean;
  x: number;
  y: number;
  tabId: string;
}

const dropdown = ref<DropdownConfig>({
  visible: false,
  x: 0,
  y: 0,
  tabId: ''
});

function setDropdown(config: Partial<DropdownConfig>) {
  Object.assign(dropdown.value, config);
}

let isClickContextMenu = false;

function handleDropdownVisible(visible: boolean) {
  if (!isClickContextMenu) {
    setDropdown({ visible });
  }
}

async function handleContextMenu(e: MouseEvent, tabId: string) {
  e.preventDefault();

  const { clientX, clientY } = e;

  isClickContextMenu = true;

  const DURATION = dropdown.value.visible ? 150 : 0;

  setDropdown({ visible: false });

  setTimeout(() => {
    setDropdown({
      visible: true,
      x: clientX,
      y: clientY,
      tabId
    });
    isClickContextMenu = false;
  }, DURATION);
}

function init() {
  tabStore.initTabStore(route);
}

function removeFocus() {
  (document.activeElement as HTMLElement)?.blur();
}
function handleClick(tab: App.Global.Tab) {
  if (tab.routeKey === 'dual-effect_receivable_expenditure_list') {
    nextTick(() => {
      router.replace({
        path: tab.fullPath,
        query: {}
      });
    });
  }
}

onMounted(() => {
  emitter.on('closeTab', async () => {
    await tabStore.removeTab(tabStore.activeTabId);
    // routeStore.resetRouteCache(tabStore.routeKey);
  });
});

onUnmounted(() => {
  emitter.off('closeTab');
  if (scrollTimer) {
    clearTimeout(scrollTimer);
  }
});

// watch
watch(
  () => route.fullPath,
  () => {
    tabStore.addTab(route);
  }
);
watch(
  () => tabStore.activeTabId,
  () => {
    scrollToActiveTab();
  },
  { flush: 'post' }
);

// init
init();
</script>

<template>
  <DarkModeContainer :class="tabContainerClass">
    <div ref="bsWrapper" class="global-tab-wrapper h-full flex-1-hidden">
      <BetterScroll ref="bsScroll" :options="{ scrollX: true, scrollY: false, click: !isPCFlag }" @click="removeFocus">
        <div
          ref="tabRef"
          class="global-tab-list h-full flex pr-18px"
          :class="[themeStore.tab.mode === 'chrome' ? 'items-end' : 'items-center gap-12px']"
        >
          <PageTab
            v-for="tab in tabStore.tabs"
            :key="tab.id"
            class="global-tab-item"
            :[TAB_DATA_ID]="tab.id"
            :mode="themeStore.tab.mode"
            :dark-mode="themeStore.darkMode"
            :active="tab.id === tabStore.activeTabId"
            :active-color="themeStore.themeColor"
            :closable="!tabStore.isTabRetain(tab.id)"
            @click="
              tabStore.switchRouteByTab(tab);
              handleClick(tab);
            "
            @close="handleCloseTab(tab)"
            @contextmenu="handleContextMenu($event, tab.id)"
          >
            <template #prefix>
              <SvgIcon :icon="tab.icon" :local-icon="tab.localIcon" class="inline-block align-text-bottom text-16px" />
            </template>
            <div class="max-w-240px ellipsis-text">{{ tab.label }}</div>
          </PageTab>
        </div>
      </BetterScroll>
    </div>
    <div>
      <ReloadButton :loading="!appStore.reloadFlag" @click="refresh" />
    </div>
    <FullScreen :full="appStore.fullContent" @click="appStore.toggleFullContent" />
  </DarkModeContainer>
  <ContextMenu
    :visible="dropdown.visible"
    :tab-id="dropdown.tabId"
    :disabled-keys="getContextMenuDisabledKeys(dropdown.tabId)"
    :x="dropdown.x"
    :y="dropdown.y"
    @update:visible="handleDropdownVisible"
  />
</template>

<style scoped>
.shadow-tab-minimal {
  box-shadow: var(--minimal-shadow-xs);
}

.border-tab-minimal {
  border-bottom: var(--minimal-border-subtle);
}

.minimal-tab {
  background: var(--minimal-bg-container);
}

.shadow-tab-enterprise {
  box-shadow: var(--enterprise-tab-shadow);
}

.border-tab-enterprise {
  border-bottom: var(--enterprise-tab-border-bottom);
}

.enterprise-tab {
  background: var(--enterprise-tab-bg);
}

html.theme-style-minimal :deep(.page-tab) {
  background: var(--minimal-bg-container);
  border: var(--minimal-border-subtle);
  transition: var(--minimal-transition-normal);
}

html.theme-style-minimal :deep(.page-tab:hover) {
  background: var(--minimal-bg-hover);
  box-shadow: var(--minimal-shadow-hover-xs);
}

html.theme-style-minimal :deep(.page-tab.active) {
  background: var(--minimal-bg-container);
  box-shadow: var(--minimal-shadow-sm);
}

html.theme-style-enterprise :deep(.page-tab) {
  background: var(--enterprise-tab-bg);
  border: var(--enterprise-tab-border);
  border-radius: var(--enterprise-radius-button);
  transition: var(--enterprise-transition-normal);
}

html.theme-style-enterprise :deep(.page-tab:hover) {
  background: var(--enterprise-tab-hover-bg);
  box-shadow: var(--enterprise-shadow-hover-xs);
}

html.theme-style-enterprise :deep(.page-tab.active) {
  background: var(--enterprise-tab-active-bg);
  box-shadow: var(--enterprise-shadow-xs);
}

@media (max-width: 767px) {
  .global-tab-wrapper {
    -webkit-overflow-scrolling: touch;
  }

  .global-tab-list {
    width: max-content;
    min-width: max-content;
  }

  :deep(.global-tab-item) {
    flex: 0 0 auto;
  }
}
</style>
