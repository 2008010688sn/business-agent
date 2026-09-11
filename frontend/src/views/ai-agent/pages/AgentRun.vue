<template>
  <AgentRunPageShell mode="normal" :controller="controller" />
</template>

<script setup lang="ts">
import 'highlight.js/styles/github.css';
import AgentRunPageShell from '@/views/ai-agent/components/run/AgentRunPageShell.vue';
import { useAgentRunController } from '@/views/ai-agent/utils/useAgentRunController';

defineOptions({ name: 'AgentRun' });

const controller = useAgentRunController({ mode: 'normal' });
</script>
