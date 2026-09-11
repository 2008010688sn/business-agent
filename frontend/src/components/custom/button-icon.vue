<script setup lang="ts">
import type { Placement } from 'element-plus';
import { twMerge } from 'tailwind-merge';

defineOptions({
  name: 'ButtonIcon',
  inheritAttrs: false
});

interface Props {
  /** Button class */
  class?: string;
  /** Iconify icon name */
  icon?: string;
  /** Tooltip content */
  tooltipContent?: string;
  /** Tooltip placement */
  tooltipPlacement?: Placement;
  zIndex?: number;
}

const props = withDefaults(defineProps<Props>(), {
  class: '',
  icon: '',
  tooltipContent: '',
  tooltipPlacement: 'bottom',
  zIndex: 98
});

const DEFAULT_CLASS = 'h-[36px] text-icon';
</script>

<template>
  <ElTooltip :placement="tooltipPlacement" :content="tooltipContent" :z-index="zIndex" :disabled="!tooltipContent">
    <ElButton text quaternary :class="twMerge(DEFAULT_CLASS, props.class)" v-bind="$attrs" class="icon-btn">
      <div class="flex-center gap-8px text-lg icon-content">
        <slot>
          <SvgIcon :icon="icon" />
        </slot>
      </div>
    </ElButton>
  </ElTooltip>
</template>

<style scoped>
.icon-btn {
  transition: transform 0.2s ease-out;
}

.icon-btn:hover .icon-content {
  animation: elastic-bounce 0.5s ease-out;
}

@keyframes elastic-bounce {
  0% {
    transform: scale(1);
  }
  30% {
    transform: scale(1.2);
  }
  50% {
    transform: scale(0.95);
  }
  70% {
    transform: scale(1.05);
  }
  100% {
    transform: scale(1);
  }
}
</style>
