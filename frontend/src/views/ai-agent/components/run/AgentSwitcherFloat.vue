<template>
  <div v-if="enabledAgents.length > 0" class="agent-switcher-float">
    <div class="agent-switcher-list" aria-label="已启用智能体">
      <ElPopover
        v-for="item in enabledAgents"
        :key="String(item.id)"
        placement="top"
        trigger="hover"
        :width="260"
        popper-class="agent-switcher-popover"
      >
        <template #reference>
          <ElButton
            native-type="button"
            text
            circle
            class="agent-switcher-trigger"
            :class="{ active: isActiveAgent(item), loading }"
            :aria-current="isActiveAgent(item) ? 'true' : undefined"
            @click="emit('switchAgent', item)"
          >
            <ElAvatar :size="32" :src="item.avatar" class="agent-switcher-avatar">
              {{ getAgentInitial(item) }}
            </ElAvatar>
          </ElButton>
        </template>
        <div class="agent-switcher-card">
          <div class="agent-switcher-card-head">
            <ElAvatar :size="40" :src="item.avatar" class="agent-switcher-card-avatar">
              {{ getAgentInitial(item) }}
            </ElAvatar>
            <div class="agent-switcher-card-meta">
              <div class="agent-switcher-card-name">{{ getAgentName(item) }}</div>
              <div class="agent-switcher-card-type">{{ formatAgentType(item.agentType) }}</div>
            </div>
          </div>
          <div class="agent-switcher-card-desc">{{ getAgentDescription(item) }}</div>
          <div v-if="getAgentTagList(item).length > 0" class="agent-switcher-card-tags">
            <span v-for="tag in getAgentTagList(item)" :key="tag" class="agent-switcher-card-tag">
              {{ tag }}
            </span>
          </div>
        </div>
      </ElPopover>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { formatAgentType } from '@/views/ai-agent/constants/agentTypes';
import type { Agent } from '@/views/ai-agent/services/agent';
import {
  getAgentDescription,
  getAgentInitial,
  getAgentName,
  getAgentTagList,
  parseAgentId
} from '@/views/ai-agent/utils/agentDisplay';

defineOptions({ name: 'AgentSwitcherFloat' });

const props = defineProps<{
  availableAgents: Agent[];
  currentAgentId?: string | null;
  loading?: boolean;
}>();

const emit = defineEmits<{
  switchAgent: [agent: Agent];
}>();

const enabledAgents = computed(() => props.availableAgents.filter(item => parseAgentId(item.id) !== null));

const normalizedCurrentAgentId = computed(() => parseAgentId(props.currentAgentId));

function isActiveAgent(agent: Agent): boolean {
  return parseAgentId(agent.id) === normalizedCurrentAgentId.value;
}
</script>

<style scoped>
.agent-switcher-float {
  display: flex;
  align-self: flex-start;
  max-width: min(360px, 100%);
  padding: 4px 6px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.86);
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.08);
  backdrop-filter: blur(8px);
}

.agent-switcher-list {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
}

.agent-switcher-trigger {
  width: 34px;
  height: 34px;
  padding: 0 !important;
  border: 1px solid transparent;
  background: transparent;
  color: var(--el-text-color-secondary);
  box-shadow: none;
}

.agent-switcher-trigger :deep(.el-button__content) {
  width: 100%;
  height: 100%;
}

.agent-switcher-trigger:hover,
.agent-switcher-trigger:focus,
.agent-switcher-trigger.active {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.agent-switcher-trigger.loading {
  cursor: progress;
  opacity: 0.72;
}

.agent-switcher-avatar {
  border: 1px solid var(--el-border-color-light);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-size: 12px;
  font-weight: 600;
}

.agent-switcher-trigger.active .agent-switcher-avatar {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-9);
}

.agent-switcher-card {
  color: var(--el-text-color-primary);
}

.agent-switcher-card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.agent-switcher-card-avatar {
  flex: 0 0 auto;
  border: 1px solid var(--el-border-color-light);
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  font-weight: 600;
}

.agent-switcher-card-meta {
  min-width: 0;
}

.agent-switcher-card-name {
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.4;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-switcher-card-type {
  margin-top: 2px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.agent-switcher-card-desc {
  margin-top: 10px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  line-height: 1.6;
}

.agent-switcher-card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.agent-switcher-card-tag {
  padding: 2px 7px;
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 999px;
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  font-size: 12px;
  line-height: 1.5;
}
</style>

<style>
.agent-switcher-popover {
  border: 1px solid var(--el-border-color-light) !important;
  background: var(--el-bg-color) !important;
  box-shadow: var(--el-box-shadow-light) !important;
}
</style>
