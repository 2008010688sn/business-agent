<template>
  <div class="empty-state">
    <ElEmpty description="请选择一个会话或创建新会话开始对话" />
  </div>
</template>

<script setup lang="ts">
defineOptions({ name: 'AgentRunEmptyState' });
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  padding: 28px 20px;
}
</style>
