<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { SimpleScrollbar } from '@sa/materials';
import type { RouteKey } from '@elegant-router/types';
import { useAppStore } from '@/store/modules/app';
import { useRouteStore } from '@/store/modules/route';
import { useRouterPush } from '@/hooks/common/router';
import { GLOBAL_SIDER_MENU_ID } from '@/constants/app';
import { useMenu } from '../../../context';
import MenuItem from '../components/menu-item.vue';

defineOptions({ name: 'VerticalMenu' });

const route = useRoute();
const appStore = useAppStore();
const routeStore = useRouteStore();
const { routerPushByKeyWithMetaQuery } = useRouterPush();
const { selectedKey } = useMenu();

// const inverted = computed(() => !themeStore.darkMode && themeStore.sider.inverted);

const expandedKeys = ref<string[]>([]);
const menuCollapsed = computed(() => !appStore.isMobile && appStore.siderCollapse);

function updateExpandedKeys() {
  if (menuCollapsed.value || !selectedKey.value) {
    expandedKeys.value = [];
    return;
  }
  expandedKeys.value = routeStore.getSelectedMenuKeyPath(selectedKey.value);
}

watch(
  () => route.name,
  () => {
    updateExpandedKeys();
    // 窄屏下路由跳转后自动关闭侧边栏
    if (appStore.isMobile && !appStore.siderCollapse) {
      appStore.setSiderCollapse(true);
    }
  },
  { immediate: true }
);
</script>

<template>
  <Teleport :to="`#${GLOBAL_SIDER_MENU_ID}`">
    <SimpleScrollbar>
      <ElMenu
        :key="expandedKeys.join('|')"
        mode="vertical"
        unique-opened
        :default-active="selectedKey"
        :default-openeds="expandedKeys"
        :collapse="menuCollapsed"
        @select="val => routerPushByKeyWithMetaQuery(val as RouteKey)"
      >
        <MenuItem v-for="item in routeStore.menus" :key="item.key" :item="item" :index="item.key" />
      </ElMenu>
    </SimpleScrollbar>
  </Teleport>
</template>

<style scoped></style>
