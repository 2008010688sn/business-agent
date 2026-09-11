<script setup lang="ts">
import { computed, ref } from 'vue';
import type { Placement } from 'element-plus';
import { $t } from '@/locales';

defineOptions({ name: 'ThemeSchemaSwitch' });

interface Props {
  /** Theme schema */
  themeSchema: UnionKey.ThemeScheme;
  /** Show tooltip */
  showTooltip?: boolean;
  /** Tooltip placement */
  tooltipPlacement?: Placement;
}

const props = withDefaults(defineProps<Props>(), {
  showTooltip: true,
  tooltipPlacement: 'bottom'
});

interface Emits {
  (e: 'switch', event?: MouseEvent): void;
}

const emit = defineEmits<Emits>();

const buttonRef = ref<HTMLElement | null>(null);

function handleSwitch(event: MouseEvent) {
  emit('switch', event);
}

const icons: Record<UnionKey.ThemeScheme, string> = {
  light: 'material-symbols:sunny',
  dark: 'material-symbols:nightlight-rounded',
  auto: 'material-symbols:hdr-auto'
};

const icon = computed(() => icons[props.themeSchema]);

const tooltipContent = computed(() => {
  if (!props.showTooltip) return '';

  return $t('icon.themeSchema');
});
</script>

<template>
  <ButtonIcon ref="buttonRef" :icon="icon" :tooltip-placement="tooltipPlacement" @click="handleSwitch" />
</template>

<style scoped></style>
