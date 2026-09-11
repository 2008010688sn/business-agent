<template>
  <!-- eslint-disable vue/no-v-html -->
  <div class="safe-legacy-html-content" v-html="sanitizedContent"></div>
  <!-- eslint-enable vue/no-v-html -->
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { sanitizeLegacyHtml } from '@/views/ai-agent/utils/safeMarkdown';

defineOptions({ name: 'SafeLegacyHtmlContent' });

const props = withDefaults(
  defineProps<{
    content?: string | null;
  }>(),
  {
    content: ''
  }
);

const sanitizedContent = computed(() => sanitizeLegacyHtml(props.content));
</script>

<style scoped>
.safe-legacy-html-content {
  min-width: 0;
  overflow-wrap: anywhere;
}
</style>
