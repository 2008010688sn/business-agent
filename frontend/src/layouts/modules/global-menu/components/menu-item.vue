<script setup lang="ts">
interface Props {
  item: App.Global.Menu;
}

const { item } = defineProps<Props>();

const hasChildren = item.children && item.children.length > 0;
</script>

<template>
  <ElSubMenu v-if="hasChildren" :index="item.key">
    <template #title>
      <ElIcon class="menu-item-icon">
        <component :is="item.icon" />
      </ElIcon>
      <span class="ib-ellipsis">{{ item.label }}</span>
    </template>
    <MenuItem v-for="child in item.children" :key="child.key" :item="child" :index="child.key"></MenuItem>
  </ElSubMenu>
  <ElMenuItem v-else>
    <ElIcon class="menu-item-icon">
      <component :is="item.icon" />
    </ElIcon>
    <span class="ib-ellipsis">{{ item.label }}</span>
  </ElMenuItem>
</template>

<style scoped>
.ib-ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  position: relative;
}

.menu-item-icon {
  transform-origin: center;
  transition: transform 150ms ease;
  will-change: transform;
}

:deep(.el-menu-item:hover) .menu-item-icon,
:deep(.el-sub-menu__title:hover) .menu-item-icon {
  transform: scale(1.12);
}
</style>
