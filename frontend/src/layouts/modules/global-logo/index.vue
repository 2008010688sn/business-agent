<script setup lang="ts">
import { $t } from '@/locales';
import logo from '@/assets/imgs/logo.svg';

defineOptions({ name: 'GlobalLogo' });

interface Props {
  showTitle?: boolean;
}

withDefaults(defineProps<Props>(), {
  showTitle: true
});
</script>

<template>
  <RouterLink to="/" class="w-full flex-center nowrap-hidden">
    <img class="h-32px w-32px" :src="logo" alt="Business Agent" />
    <h2 v-show="showTitle" class="pl-8px text-16px text-primary font-bold transition duration-300 ease-in-out">
      {{ $t('system.title') }}
    </h2>
  </RouterLink>
</template>
