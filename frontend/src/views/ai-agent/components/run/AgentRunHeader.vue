<template>
  <div class="agent-run-title-card">
    <div v-if="mode === 'ai'" class="agent-run-sidebar-actions">
      <template v-if="sidebarCollapsed">
        <ElTooltip content="展开会话列表" placement="bottom">
          <ElButton text class="agent-run-sidebar-action" @click="emit('expandSidebar')">
            <ElIcon size="18">
              <svg
                viewBox="0 0 1024 1024"
                xmlns="http://www.w3.org/2000/svg"
                width="1em"
                height="1em"
                fill="currentColor"
                aria-hidden="true"
              >
                <path
                  d="M862.037333 171.093333A85.333333 85.333333 0 0 1 938.666667 256v512a85.333333 85.333333 0 0 1-76.629334 84.906667L853.333333 853.333333H170.666667a85.333333 85.333333 0 0 1-85.333334-85.333333V256a85.333333 85.333333 0 0 1 85.333334-85.333333h682.666666l8.704 0.426666zM170.666667 230.4a25.6 25.6 0 0 0-25.6 25.6v512a25.6 25.6 0 0 0 25.6 25.6h183.466666V230.4H170.666667z m243.2 563.2H853.333333a25.6 25.6 0 0 0 25.6-25.6V256a25.6 25.6 0 0 0-25.6-25.6H413.866667v563.2z"
                />
              </svg>
            </ElIcon>
          </ElButton>
        </ElTooltip>
        <ElTooltip content="新建会话" placement="bottom">
          <ElButton text class="agent-run-sidebar-action" :disabled="!hasAgent" @click="emit('createSession')">
            <ElIcon><ChatLineRound /></ElIcon>
          </ElButton>
        </ElTooltip>
        <ElTooltip content="清空会话" placement="bottom">
          <ElButton text class="agent-run-sidebar-action danger" :disabled="!hasAgent" @click="emit('clearSessions')">
            <ElIcon><Delete /></ElIcon>
          </ElButton>
        </ElTooltip>
      </template>
      <ElTooltip v-else content="收起会话列表" placement="bottom">
        <ElButton text class="agent-run-sidebar-action" @click="emit('collapseSidebar')">
          <ElIcon size="18">
            <svg
              viewBox="0 0 1024 1024"
              xmlns="http://www.w3.org/2000/svg"
              width="1em"
              height="1em"
              fill="currentColor"
              aria-hidden="true"
            >
              <path
                d="M862.037333 171.093333A85.333333 85.333333 0 0 1 938.666667 256v512a85.333333 85.333333 0 0 1-76.629334 84.906667L853.333333 853.333333H170.666667a85.333333 85.333333 0 0 1-85.333334-85.333333V256a85.333333 85.333333 0 0 1 85.333334-85.333333h682.666666l8.704 0.426666zM170.666667 230.4a25.6 25.6 0 0 0-25.6 25.6v512a25.6 25.6 0 0 0 25.6 25.6h183.466666V230.4H170.666667z m243.2 563.2H853.333333a25.6 25.6 0 0 0 25.6-25.6V256a25.6 25.6 0 0 0-25.6-25.6H413.866667v563.2z"
              />
            </svg>
          </ElIcon>
        </ElButton>
      </ElTooltip>
    </div>
    <div class="agent-run-title">{{ title || '智能体' }}</div>
    <div
      v-if="mode === 'ai'"
      class="agent-run-top-actions"
      :class="{ 'is-expanded': topActionsExpanded }"
      @mouseenter="handleTopActionsMouseEnter"
      @mouseleave="handleTopActionsMouseLeave"
      @focusin="expandTopActions"
      @focusout="handleTopActionsFocusOut"
    >
      <div class="agent-run-logo-action" tabindex="0" aria-label="AI模式操作">
        <img src="@/assets/imgs/logo.svg" alt="" />
      </div>
      <ElTooltip content="申请更多 Agent" placement="bottom">
        <ElButton text class="agent-run-top-action" aria-label="申请更多 Agent" @click="emit('openAgentCatalog')">
          <ElIcon size="18"><Promotion /></ElIcon>
        </ElButton>
      </ElTooltip>
      <ElTooltip content="普通模式" placement="bottom">
        <ElButton text class="agent-run-top-action" aria-label="普通模式" @click="backToWorkbench">
          <ElIcon size="18"><Monitor /></ElIcon>
        </ElButton>
      </ElTooltip>
      <ElTooltip :content="isFullscreen ? '退出全屏' : '全屏'" placement="bottom">
        <ElButton
          text
          class="agent-run-top-action"
          :aria-label="isFullscreen ? '退出全屏' : '全屏'"
          @click="toggleFullscreen"
        >
          <ElIcon size="18">
            <icon-gridicons-fullscreen-exit v-if="isFullscreen" />
            <icon-gridicons-fullscreen v-else />
          </ElIcon>
        </ElButton>
      </ElTooltip>
      <ElTooltip content="退出登录" placement="bottom">
        <ElButton text class="agent-run-top-action" aria-label="退出登录" @click="logout">
          <SvgIcon icon="ph:sign-out" class="text-18px" />
        </ElButton>
      </ElTooltip>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ChatLineRound, Delete, Monitor, Plus } from '@element-plus/icons-vue';
import { useFullscreen } from '@vueuse/core';
import { onBeforeUnmount, ref } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { $t } from '@/locales';

const authStore = useAuthStore();
const { isFullscreen, toggle: toggleFullscreen } = useFullscreen();
const topActionsExpanded = ref(false);

let topActionsOpenTimer: number | undefined;
let topActionsCloseTimer: number | undefined;

defineOptions({ name: 'AgentRunHeader' });

withDefaults(
  defineProps<{
    title?: string;
    mode?: 'normal' | 'ai';
    sidebarCollapsed: boolean;
    hasAgent?: boolean;
  }>(),
  {
    title: '',
    mode: 'normal',
    hasAgent: true
  }
);

const emit = defineEmits<{
  expandSidebar: [];
  collapseSidebar: [];
  createSession: [];
  clearSessions: [];
  backToWorkbench: [];
  openAgentCatalog: [];
}>();

function clearTopActionsTimers() {
  if (topActionsOpenTimer !== undefined) {
    window.clearTimeout(topActionsOpenTimer);
    topActionsOpenTimer = undefined;
  }
  if (topActionsCloseTimer !== undefined) {
    window.clearTimeout(topActionsCloseTimer);
    topActionsCloseTimer = undefined;
  }
}

function expandTopActions() {
  clearTopActionsTimers();
  topActionsExpanded.value = true;
}

function collapseTopActions(delay = 120) {
  if (topActionsOpenTimer !== undefined) {
    window.clearTimeout(topActionsOpenTimer);
    topActionsOpenTimer = undefined;
  }
  if (topActionsCloseTimer !== undefined) {
    window.clearTimeout(topActionsCloseTimer);
  }
  topActionsCloseTimer = window.setTimeout(() => {
    topActionsExpanded.value = false;
    topActionsCloseTimer = undefined;
  }, delay);
}

function handleTopActionsMouseEnter() {
  if (topActionsCloseTimer !== undefined) {
    window.clearTimeout(topActionsCloseTimer);
    topActionsCloseTimer = undefined;
  }
  if (topActionsOpenTimer !== undefined) {
    window.clearTimeout(topActionsOpenTimer);
  }
  topActionsOpenTimer = window.setTimeout(() => {
    topActionsExpanded.value = true;
    topActionsOpenTimer = undefined;
  }, 120);
}

function handleTopActionsMouseLeave() {
  collapseTopActions();
}

function handleTopActionsFocusOut(event: FocusEvent) {
  const currentTarget = event.currentTarget as HTMLElement | null;
  const nextTarget = event.relatedTarget as Node | null;
  if (currentTarget?.contains(nextTarget)) {
    return;
  }
  collapseTopActions();
}

function backToWorkbench() {
  emit('backToWorkbench');
}

function logout() {
  window.$messageBox
    ?.confirm($t('common.logoutConfirm'), $t('common.tip'), {
      confirmButtonText: $t('common.confirm'),
      cancelButtonText: $t('common.cancel'),
      type: 'warning'
    })
    .then(() => {
      authStore.requestLogout();
    });
}

onBeforeUnmount(clearTopActionsTimers);
</script>

<style scoped>
.agent-run-title-card {
  position: relative;
  flex: 0 0 auto;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
  height: 40px;
}

.agent-run-sidebar-actions {
  position: absolute;
  z-index: 2;
  left: 12px;
  top: 50%;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  transform: translateY(-50%);
}

.agent-run-top-actions {
  --agent-run-actions-peek: 20px;
  position: absolute;
  z-index: 20;
  right: 0;
  top: 50%;
  display: inline-flex;
  align-items: center;
  gap: 15px;
  padding: 4px 18px 4px 4px;
  border: 1px solid var(--el-border-color-lighter);
  border-right: 0;
  border-radius: 999px 0 0 999px;
  background: var(--el-bg-color);
  transform: translate(calc(100% - var(--agent-run-actions-peek)), -50%);
  transition:
    transform 1.5s cubic-bezier(0.16, 1, 0.3, 1),
    box-shadow 1.5s cubic-bezier(0.16, 1, 0.3, 1);
}

.agent-run-top-actions.is-expanded {
  transform: translate(0, -50%);
}

.agent-run-logo-action {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  overflow: hidden;
  border-radius: 50%;
  cursor: pointer;
  outline: none;
  transform: rotate(0deg);
  transition: transform 1.5s cubic-bezier(0.16, 1, 0.3, 1);
}

.agent-run-top-actions.is-expanded .agent-run-logo-action {
  transform: rotate(-180deg);
}

.agent-run-logo-action img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.agent-run-top-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin-left: 0 !important;
  color: var(--el-text-color-secondary);
  opacity: 0;
  pointer-events: none;
  transform: translateX(8px);
  transition:
    opacity 0.3s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.42s cubic-bezier(0.16, 1, 0.3, 1),
    color 0.2s cubic-bezier(0.16, 1, 0.3, 1),
    background-color 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

.agent-run-top-actions.is-expanded .agent-run-top-action {
  opacity: 1;
  pointer-events: auto;
  transform: translateX(0);
}

.agent-run-top-action:hover,
.agent-run-top-action:focus {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.agent-run-sidebar-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  margin-left: 0 !important;
  color: var(--el-text-color-secondary);
}

.agent-run-sidebar-action:hover,
.agent-run-sidebar-action:focus {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.agent-run-sidebar-action.danger {
  color: var(--el-color-danger);
}

.agent-run-sidebar-action.danger:hover,
.agent-run-sidebar-action.danger:focus {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}

.agent-run-title {
  width: 100%;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 600;
  line-height: 40px;
  text-align: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
