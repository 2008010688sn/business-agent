<!--
 * Copyright 2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
-->

<template>
  <ElAside
    :width="localCollapsed ? '0px' : '320px'"
    class="chat-session-sidebar"
    :class="{ collapsed: localCollapsed }"
  >
    <template v-if="!localCollapsed">
      <div class="sidebar-header">
        <div class="header-controls">
          <div class="user-profile">
            <ElAvatar :src="authStore.userInfo.avatar" class="user-avatar">
              {{ userInitial }}
            </ElAvatar>
            <div class="user-meta">
              <div class="user-name">{{ displayUserName }}</div>
              <div class="user-account">{{ displayAccount }}</div>
            </div>
          </div>
          <ElButton
            class="mobile-sidebar-close"
            text
            circle
            title="收起会话列表"
            aria-label="收起会话列表"
            @click="localCollapsed = true"
          >
            <ElIcon><Fold /></ElIcon>
          </ElButton>
        </div>

        <div class="new-session-section">
          <ElButton class="w-100%" :disabled="!canCreateSession" @click="createNewSession()">
            <ElIcon class="mr-5px"><ChatLineRound /></ElIcon>
            新建会话
          </ElButton>
          <ElButton type="danger" text :disabled="!canCreateSession" @click="clearAllSessions">
            <ElIcon><Delete /></ElIcon>
          </ElButton>
        </div>
      </div>

      <div class="session-list-heading">历史对话</div>
      <div ref="sessionListRef" class="session-list">
        <template v-if="isAiMode">
          <div v-for="group in aiSessionGroups" :key="group.agentId" class="session-agent-group">
            <button
              class="session-agent-header"
              :class="{ active: isAgentGroupActive(group) }"
              type="button"
              @click="handleAgentGroupHeaderClick(group)"
            >
              <svg
                class="session-agent-icon"
                viewBox="0 0 1024 1024"
                version="1.1"
                xmlns="http://www.w3.org/2000/svg"
                aria-hidden="true"
              >
                <path
                  d="M568.021333 171.306667c0 16.64-7.253333 31.530667-18.688 41.813333v70.4h186.666667c29.696 0 58.197333 11.818667 79.189333 32.853333s32.810667 49.578667 32.810667 79.36v400.896c0 29.738667-11.818667 58.282667-32.810667 79.317334-20.992 21.034667-49.493333 32.853333-79.189333 32.853333H288a111.914667 111.914667 0 0 1-79.189333-32.853333 112.298667 112.298667 0 0 1-32.810667-79.36V395.733333c0-29.738667 11.818667-58.282667 32.810667-79.36a111.914667 111.914667 0 0 1 79.189333-32.853333h186.666667V213.162667a56.064 56.064 0 0 1-7.168-75.861334 55.978667 55.978667 0 0 1 100.48 34.048zM283.477333 353.792a37.290667 37.290667 0 0 0-37.333333 37.418667v409.898666a37.418667 37.418667 0 0 0 37.333333 37.376h456.96a37.290667 37.290667 0 0 0 37.333334-37.376V391.210667a37.418667 37.418667 0 0 0-37.333334-37.418667H283.52zM138.666667 479.488a8.96 8.96 0 0 0-8.96-8.96H72.96a8.96 8.96 0 0 0-8.96 8.96v233.386667c0 4.906667 4.010667 8.96 8.96 8.96h56.746667a8.96 8.96 0 0 0 8.96-8.96v-233.386667z m746.666666 0a8.96 8.96 0 0 1 8.96-8.96h56.746667a8.96 8.96 0 0 1 8.96 8.96v233.386667a8.96 8.96 0 0 1-8.96 8.96h-56.746667a8.96 8.96 0 0 1-8.96-8.96v-233.386667zM400 638.805333a55.978667 55.978667 0 0 0 56.021333-56.106666 56.149333 56.149333 0 0 0-56.021333-56.106667 55.936 55.936 0 0 0-56.021333 56.106667 56.149333 56.149333 0 0 0 56.021333 56.106666z m224 0a55.978667 55.978667 0 0 0 56.021333-56.106666 56.149333 56.149333 0 0 0-56.021333-56.106667 55.936 55.936 0 0 0-56.021333 56.106667 56.149333 56.149333 0 0 0 56.021333 56.106666z"
                  fill="currentColor"
                />
              </svg>
              <span class="session-agent-name">{{ group.agentName }}</span>
              <span v-if="group.expanded && group.loaded" class="session-agent-count">{{ group.total }}</span>
              <ElIcon class="session-agent-arrow" :class="{ expanded: group.expanded }"><ArrowDown /></ElIcon>
            </button>
            <template v-if="group.expanded">
              <div
                v-for="session in getVisibleGroupSessions(group)"
                :key="session.id"
                class="session-item"
                :class="[{ active: handleGetCurrentSession()?.id === session.id, pinned: session.isPinned }]"
                @click="selectGroupedSession(session, group.agent)"
              >
                <div class="session-header">
                  <span v-if="!session.editing" class="session-title" @dblclick="startEditSessionTitle(session)">
                    {{ session.title || '新会话' }}
                  </span>
                  <ElInput
                    v-else
                    ref="sessionTitleInputRef"
                    v-model="session.editingTitle"
                    size="small"
                    @blur="saveSessionTitle(session)"
                    @keyup.enter="saveSessionTitle(session)"
                    @keyup.esc="cancelEditSessionTitle(session)"
                  />
                  <div class="session-actions">
                    <ElButton text size="small" @click.stop="startEditSessionTitle(session)">
                      <ElIcon><Edit /></ElIcon>
                    </ElButton>
                    <ElButton text size="small" @click.stop="togglePinSession(session)">
                      <ElIcon>
                        <StarFilled v-if="session.isPinned" />
                        <Star v-else />
                      </ElIcon>
                    </ElButton>
                    <ElButton text size="small" @click.stop="deleteSession(session)">
                      <ElIcon><Delete /></ElIcon>
                    </ElButton>
                  </div>
                </div>
                <div class="session-time">
                  {{ formatTime(session.lastModifyTime || session.updateTime || session.createTime) }}
                </div>
              </div>
              <div v-if="hasMoreAgentSessions(group) || group.sessionsExpanded" class="session-pagination-actions">
                <ElButton
                  v-if="hasMoreAgentSessions(group)"
                  class="session-page-button"
                  :loading="group.loading"
                  :disabled="group.loading"
                  text
                  @click="expandAgentSessions(group.agentId)"
                >
                  显示更多
                </ElButton>
                <ElButton
                  v-if="group.sessionsExpanded"
                  class="session-page-button"
                  :disabled="group.loading"
                  text
                  @click="collapseAgentSessions(group.agentId)"
                >
                  折叠显示
                </ElButton>
              </div>
            </template>
          </div>
        </template>
        <template v-else>
          <div
            v-for="session in sessions"
            :key="session.id"
            class="session-item"
            :class="[{ active: handleGetCurrentSession()?.id === session.id, pinned: session.isPinned }]"
            @click="selectCurrentSession(session)"
          >
            <div class="session-header">
              <span v-if="!session.editing" class="session-title" @dblclick="startEditSessionTitle(session)">
                {{ session.title || '新会话' }}
              </span>
              <ElInput
                v-else
                ref="sessionTitleInputRef"
                v-model="session.editingTitle"
                size="small"
                @blur="saveSessionTitle(session)"
                @keyup.enter="saveSessionTitle(session)"
                @keyup.esc="cancelEditSessionTitle(session)"
              />
              <div class="session-actions">
                <ElButton text size="small" @click.stop="startEditSessionTitle(session)">
                  <ElIcon><Edit /></ElIcon>
                </ElButton>
                <ElButton text size="small" @click.stop="togglePinSession(session)">
                  <ElIcon>
                    <StarFilled v-if="session.isPinned" />
                    <Star v-else />
                  </ElIcon>
                </ElButton>
                <ElButton text size="small" @click.stop="deleteSession(session)">
                  <ElIcon><Delete /></ElIcon>
                </ElButton>
              </div>
            </div>
            <div class="session-time">
              {{ formatTime(session.lastModifyTime || session.updateTime || session.createTime) }}
            </div>
          </div>
          <div v-if="showSessionPager" class="session-pagination-actions">
            <ElButton
              v-if="hasMoreSessions"
              class="session-page-button"
              :loading="sessionPageLoading"
              :disabled="sessionPageLoading"
              @click="expandSessions"
            >
              显示更多
            </ElButton>
            <ElButton
              v-if="canCollapseSessions"
              class="session-page-button"
              :disabled="sessionPageLoading"
              @click="collapseSessions"
            >
              折叠显示
            </ElButton>
          </div>
        </template>
      </div>
    </template>
  </ElAside>
</template>

<script lang="ts">
import type { PropType } from 'vue';
import { computed, defineComponent, nextTick, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import ChatService from '@/views/ai-agent/services/chat';
import { useAuthStore } from '@/store/modules/auth';
import { ArrowDown, ChatLineRound, Delete, Edit, Fold, Star, StarFilled } from '@element-plus/icons-vue';
import { type Agent } from '@/views/ai-agent/services/agent';
import { type ChatSession } from '@/views/ai-agent/services/chat';

interface ExtendedChatSession extends ChatSession {
  editing?: boolean;
  editingTitle?: string;
}

const SESSION_PAGE_SIZE = 10;
const AI_SESSION_PAGE_SIZE = 5;

interface AiSessionGroup {
  agentId: string;
  agentName: string;
  agent: Agent;
  sessions: ExtendedChatSession[];
  total: number;
  currentPage: number;
  pageSize: number;
  sessionsExpanded: boolean;
  loading: boolean;
  expanded: boolean;
  loaded: boolean;
}

interface CreateNewSessionOptions {
  silent?: boolean;
}

export default defineComponent({
  name: 'ChatSessionSidebar',
  components: {
    ArrowDown,
    ChatLineRound,
    Delete,
    Edit,
    Star,
    StarFilled
  },
  props: {
    collapsed: {
      type: Boolean,
      default: false
    },
    agent: {
      type: Object as PropType<Agent>,
      required: true
    },
    availableAgents: {
      type: Array as PropType<Agent[]>,
      default: () => []
    },
    mode: {
      type: String as PropType<'normal' | 'ai'>,
      default: 'normal'
    },
    handleSetCurrentSession: {
      type: Function as PropType<(session: ChatSession | null) => Promise<void>>,
      required: true
    },
    handleGetCurrentSession: {
      type: Function as PropType<() => ChatSession | null>,
      required: true
    },
    handleSelectSession: {
      type: Function as PropType<(session: ChatSession) => Promise<void>>,
      required: true
    },
    handleDeleteSessionState: {
      type: Function as PropType<(sessionId: string) => void>,
      required: true
    },
    handleSwitchAgent: {
      type: Function as PropType<(agent: Agent) => Promise<void>>,
      default: undefined
    }
  },
  emits: ['update:collapsed'],
  setup(props, { emit, expose }) {
    const sessions = ref<ExtendedChatSession[]>([]);
    const aiSessionGroups = ref<AiSessionGroup[]>([]);
    const sessionListRef = ref<HTMLElement | null>(null);
    const localCollapsed = computed({
      get: () => props.collapsed,
      set: value => emit('update:collapsed', value)
    });
    const isAiMode = computed(() => props.mode === 'ai');
    const authStore = useAuthStore();
    const sessionPageLoading = ref(false);
    const sessionCurrentPage = ref(1);
    const sessionTotal = ref(0);
    const sessionsExpanded = ref(false);
    const selectedSessionByAgent = new Map<string, string>();
    let aiGroupedSessionsLoading = false;
    let sessionRequestSeq = 0;

    const router = useRouter();
    const route = useRoute();

    const formatTime = (time: Date | string | undefined) => {
      if (!time) return '';
      const date = new Date(time);
      return date.toLocaleString('zh-CN');
    };

    const displayUserName = computed(() => authStore.userInfo.nickName || authStore.userInfo.userName || '用户');
    const displayAccount = computed(() => authStore.userInfo.userName || authStore.userInfo.username || '-');
    const userInitial = computed(() => {
      const name = displayUserName.value.trim();
      if (!name) return 'U';
      if (/^[A-Za-z0-9]/.test(name)) {
        return name.slice(0, 2).toUpperCase();
      }
      return Array.from(name).slice(0, 2).join('');
    });

    const parseAgentId = (value: unknown): string | null => {
      if (value === undefined || value === null) {
        return null;
      }
      const parsed = String(value).trim();
      return parsed || null;
    };

    const normalizeRouteValue = (value: unknown): string | null => {
      const rawValue = Array.isArray(value) ? value[0] : value;
      return parseAgentId(rawValue);
    };

    const routeAgentId = computed(() => normalizeRouteValue(route.query.agentId) ?? '');

    const agentId = computed(() => {
      if (isAiMode.value) {
        return parseAgentId(props.agent?.id) ?? routeAgentId.value;
      }
      return routeAgentId.value;
    });

    const getRouteAgentId = (): string | null => {
      if (isAiMode.value) {
        return parseAgentId(props.agent?.id) ?? (routeAgentId.value || null);
      }
      return routeAgentId.value || null;
    };

    const requireRouteAgentId = (): string => {
      const resolvedAgentId = getRouteAgentId();
      if (resolvedAgentId === null) {
        throw new Error('智能体ID无效，请刷新后重试');
      }
      return resolvedAgentId;
    };

    const hasMoreSessions = computed(() => !isAiMode.value && sessions.value.length < sessionTotal.value);
    const canCollapseSessions = computed(() => !isAiMode.value && sessionsExpanded.value);
    const showSessionPager = computed(() => !isAiMode.value && (hasMoreSessions.value || canCollapseSessions.value));
    const canCreateSession = computed(() => {
      return isAiMode.value ? Boolean(parseAgentId(props.agent?.id)) : Boolean(getRouteAgentId());
    });

    const getSessionAgentId = (session?: ChatSession | null): string => {
      return parseAgentId(session?.agentId) ?? requireRouteAgentId();
    };

    const isCurrentNormalAgent = (targetAgentId: string) => {
      return isAiMode.value || getRouteAgentId() === targetAgentId;
    };

    const isSessionForAgent = (session: ChatSession | null | undefined, targetAgentId: string) => {
      if (isAiMode.value || !session) {
        return true;
      }
      const sessionAgentId = parseAgentId(session.agentId);
      return !sessionAgentId || sessionAgentId === targetAgentId;
    };

    const findAiGroup = (agentIdValue: unknown) => {
      const resolvedAgentId = parseAgentId(agentIdValue);
      return resolvedAgentId ? aiSessionGroups.value.find(group => group.agentId === resolvedAgentId) : undefined;
    };

    const getVisibleGroupSessions = (group: AiSessionGroup) => {
      return group.sessions;
    };

    const hasMoreAgentSessions = (group: AiSessionGroup) => {
      return group.sessions.length < group.total;
    };

    const isAgentGroupActive = (group: AiSessionGroup) => {
      return group.agentId === agentId.value;
    };

    const getCurrentSessionId = () => {
      return parseAgentId(props.handleGetCurrentSession()?.id);
    };

    const ensureActiveSessionLoaded = async (group: AiSessionGroup, currentSessionId: string) => {
      const currentSessionIndex = group.sessions.findIndex(session => session.id === currentSessionId);
      if (currentSessionIndex >= 0 || !hasMoreAgentSessions(group)) {
        return;
      }
      const loadedSessions = await loadAgentSessionPage(group, group.currentPage + 1, true);
      if (loadedSessions.length > 0) {
        await ensureActiveSessionLoaded(group, currentSessionId);
      }
    };

    const prepareActiveSessionVisibility = async () => {
      if (!isAiMode.value) {
        return;
      }
      const currentAgentGroup = findAiGroup(agentId.value);
      if (!currentAgentGroup) {
        return;
      }
      currentAgentGroup.expanded = true;

      const currentSessionId = getCurrentSessionId();
      if (!currentSessionId) {
        return;
      }
      await ensureActiveSessionLoaded(currentAgentGroup, currentSessionId);
      const currentSessionIndex = currentAgentGroup.sessions.findIndex(session => session.id === currentSessionId);
      if (currentSessionIndex >= 0) {
        currentAgentGroup.sessionsExpanded = currentAgentGroup.currentPage > 1;
      }
    };

    const scrollActiveSessionIntoView = async () => {
      if (!isAiMode.value || localCollapsed.value) {
        return;
      }
      await prepareActiveSessionVisibility();
      await nextTick();
      const container = sessionListRef.value;
      const activeHeader = container?.querySelector('.session-agent-header.active') as HTMLElement | null;
      const activeGroup = activeHeader?.closest('.session-agent-group') as HTMLElement | null;
      const activeSession = activeGroup?.querySelector('.session-item.active') as HTMLElement | null;
      const firstVisibleSession = activeGroup?.querySelector('.session-item') as HTMLElement | null;
      const scrollTarget = activeSession ?? firstVisibleSession ?? activeHeader;
      if (!container || !scrollTarget) {
        return;
      }

      const containerRect = container.getBoundingClientRect();
      const activeRect = scrollTarget.getBoundingClientRect();
      const offset = 6;
      if (activeRect.top < containerRect.top) {
        container.scrollTo({
          top: container.scrollTop + activeRect.top - containerRect.top - offset,
          behavior: 'smooth'
        });
      } else if (activeRect.bottom > containerRect.bottom) {
        container.scrollTo({
          top: container.scrollTop + activeRect.bottom - containerRect.bottom + offset,
          behavior: 'smooth'
        });
      }
    };

    const toggleAgentGroup = (agentIdValue: string) => {
      const group = findAiGroup(agentIdValue);
      if (group) {
        group.expanded = !group.expanded;
      }
    };

    const focusAgentGroup = async (agentOrId: Agent | string | null | undefined) => {
      if (!isAiMode.value) {
        return;
      }
      const resolvedAgentId =
        typeof agentOrId === 'object' && agentOrId !== null ? parseAgentId(agentOrId.id) : parseAgentId(agentOrId);
      const group = findAiGroup(resolvedAgentId);
      if (!group) {
        return;
      }
      group.expanded = true;
      if (!group.loaded) {
        await loadAgentSessionPage(group, 1);
      }
      await selectPreferredGroupSession(group);
      await nextTick();
      await scrollActiveSessionIntoView();
    };

    const handleAgentGroupHeaderClick = async (group: AiSessionGroup) => {
      if (!isAiMode.value) {
        toggleAgentGroup(group.agentId);
        return;
      }
      if (isAgentGroupActive(group)) {
        toggleAgentGroup(group.agentId);
        if (group.expanded && !group.loaded) {
          const loadedSessions = await loadAgentSessionPage(group, 1);
          if (loadedSessions.length > 0) {
            await selectPreferredGroupSession(group);
          }
        }
        return;
      }
      group.expanded = true;
      if (props.handleSwitchAgent) {
        await props.handleSwitchAgent(group.agent);
      } else {
        await props.handleSetCurrentSession(null);
        await focusAgentGroup(group.agentId);
      }
    };

    const loadAgentSessionPage = async (group: AiSessionGroup, current: number, append = false) => {
      if (group.loading) {
        return [];
      }
      group.loading = true;
      try {
        const page = await ChatService.queryAgentSessions(group.agentId, {
          current,
          size: group.pageSize
        });
        group.currentPage = page.pageNum;
        group.pageSize = page.pageSize || group.pageSize;
        group.total = page.total;
        group.loaded = true;
        group.sessionsExpanded = append || page.pageNum > 1;
        group.sessions = append ? mergeSessions(group.sessions, page.data) : (page.data as ExtendedChatSession[]);
        syncFlatSessionsFromGroups();
        return page.data as ExtendedChatSession[];
      } finally {
        group.loading = false;
      }
    };

    const selectPreferredGroupSession = async (group: AiSessionGroup) => {
      if (!isAiMode.value || group.agentId !== agentId.value) {
        return;
      }
      const preferredSessionId = selectedSessionByAgent.get(group.agentId);
      const preferredSession = preferredSessionId
        ? group.sessions.find(session => session.id === preferredSessionId)
        : undefined;
      const nextSession = preferredSession ?? group.sessions[0] ?? null;
      if (nextSession) {
        await props.handleSelectSession(nextSession);
      } else {
        await props.handleSetCurrentSession(null);
      }
    };

    const expandAgentSessions = async (agentIdValue: string) => {
      const group = findAiGroup(agentIdValue);
      if (!group || !hasMoreAgentSessions(group)) {
        return;
      }
      try {
        await loadAgentSessionPage(group, group.currentPage + 1, true);
      } catch (error) {
        ElMessage.error('展开会话列表失败');
        console.error('展开会话列表失败:', error);
      }
    };

    const collapseAgentSessions = async (agentIdValue: string) => {
      const group = findAiGroup(agentIdValue);
      if (!group) {
        return;
      }
      try {
        await loadAgentSessionPage(group, 1);
        await scrollActiveSessionIntoView();
      } catch (error) {
        ElMessage.error('折叠会话列表失败');
        console.error('折叠会话列表失败:', error);
      }
    };

    const selectGroupedSession = async (session: ExtendedChatSession, targetAgent: Agent) => {
      const selectedAgentId = parseAgentId(targetAgent.id) ?? getSessionAgentId(session);
      selectedSessionByAgent.set(selectedAgentId, session.id);
      if (isAiMode.value) {
        const targetAgentId = parseAgentId(targetAgent.id);
        const currentAgentId = parseAgentId(props.agent?.id);
        if (targetAgentId && targetAgentId !== currentAgentId && props.handleSwitchAgent) {
          await props.handleSwitchAgent(targetAgent);
        }
      }
      await props.handleSelectSession(session);
    };

    const syncFlatSessionsFromGroups = () => {
      sessions.value = aiSessionGroups.value.flatMap(group => group.sessions);
    };

    const reorderPinnedSession = (sessionList: ExtendedChatSession[], sessionId: string, isPinned: boolean) => {
      const targetIndex = sessionList.findIndex(session => session.id === sessionId);
      if (targetIndex < 0) {
        return sessionList;
      }

      const nextSessions = [...sessionList];
      const [target] = nextSessions.splice(targetIndex, 1);
      target.isPinned = isPinned;

      if (isPinned) {
        return [target, ...nextSessions];
      }

      const insertIndex = nextSessions.findIndex(session => !session.isPinned);
      if (insertIndex < 0) {
        return [...nextSessions, target];
      }
      nextSessions.splice(insertIndex, 0, target);
      return nextSessions;
    };

    const applyPinnedSessionOrder = (sessionId: string, isPinned: boolean, targetAgentId: string) => {
      if (isAiMode.value) {
        aiSessionGroups.value.forEach(group => {
          if (group.agentId === targetAgentId) {
            group.sessions = reorderPinnedSession(group.sessions, sessionId, isPinned);
          }
        });
        syncFlatSessionsFromGroups();
        return;
      }

      sessions.value = reorderPinnedSession(sessions.value, sessionId, isPinned);
    };

    const startEditSessionTitle = (session: ExtendedChatSession) => {
      session.editing = true;
      session.editingTitle = session.title || '新会话';
      nextTick().then(() => {
        const input = document.querySelector('.el-input__inner') as HTMLInputElement;
        if (input) {
          input.focus();
          input.select();
        }
      });
    };

    const saveSessionTitle = async (session: ExtendedChatSession) => {
      if (!session.editingTitle || session.editingTitle.trim() === '') {
        ElMessage.warning('会话标题不能为空');
        return;
      }

      const newTitle = session.editingTitle.trim();
      if (newTitle === session.title) {
        session.editing = false;
        return;
      }

      try {
        const targetAgentId = getSessionAgentId(session);
        await ChatService.renameSession(session.id, targetAgentId, newTitle);
        if (!isCurrentNormalAgent(targetAgentId)) {
          return;
        }
        session.title = newTitle;
        session.editing = false;
        ElMessage.success('会话标题已更新');
      } catch (error) {
        ElMessage.error('更新会话标题失败');
        console.error('更新会话标题失败:', error);
      }
    };

    const cancelEditSessionTitle = (session: ExtendedChatSession) => {
      session.editing = false;
    };

    const goBack = () => {
      const currentAgentId = getRouteAgentId();
      if (!currentAgentId) {
        ElMessage.error('智能体ID无效，请刷新后重试');
        return;
      }
      router.push({
        name: 'ai-agent_agent_detail',
        query: { id: currentAgentId }
      });
    };

    const mergeSessions = (
      currentSessions: ExtendedChatSession[],
      nextSessions: ChatSession[]
    ): ExtendedChatSession[] => {
      const existingIds = new Set(currentSessions.map(session => session.id));
      return [
        ...currentSessions,
        ...(nextSessions as ExtendedChatSession[]).filter(session => !existingIds.has(session.id))
      ];
    };

    const loadSessionPage = async (
      current: number,
      append = false,
      targetAgentId = requireRouteAgentId(),
      requestSeq = sessionRequestSeq
    ) => {
      sessionPageLoading.value = true;
      try {
        const page = await ChatService.queryAgentSessions(targetAgentId, {
          current,
          size: SESSION_PAGE_SIZE
        });
        if (!isCurrentNormalAgent(targetAgentId) || requestSeq !== sessionRequestSeq) {
          return [];
        }
        const pageSessions = (page.data as ExtendedChatSession[]).filter(session =>
          isSessionForAgent(session, targetAgentId)
        );
        sessionCurrentPage.value = page.pageNum;
        sessionTotal.value = page.total;
        sessionsExpanded.value = append || page.pageNum > 1;
        sessions.value = append ? mergeSessions(sessions.value, pageSessions) : pageSessions;
        return pageSessions;
      } finally {
        if (requestSeq === sessionRequestSeq) {
          sessionPageLoading.value = false;
        }
      }
    };

    const reloadDisplayedSessionPages = async () => {
      const targetAgentId = requireRouteAgentId();
      const requestSeq = ++sessionRequestSeq;
      const targetPage = Math.max(sessionCurrentPage.value, 1);
      sessionPageLoading.value = true;
      try {
        const pages = await Promise.all(
          Array.from({ length: targetPage }, (_, index) =>
            ChatService.queryAgentSessions(targetAgentId, {
              current: index + 1,
              size: SESSION_PAGE_SIZE
            })
          )
        );
        if (!isCurrentNormalAgent(targetAgentId) || requestSeq !== sessionRequestSeq) {
          return;
        }
        const loadedSessions = pages.reduce<ExtendedChatSession[]>((mergedSessions, page) => {
          if (mergedSessions.length >= page.total || page.data.length === 0) {
            return mergedSessions;
          }
          return mergeSessions(
            mergedSessions,
            (page.data as ExtendedChatSession[]).filter(session => isSessionForAgent(session, targetAgentId))
          );
        }, []);
        const lastLoadedPage = pages.filter(page => page.data.length > 0).at(-1)?.pageNum ?? 1;
        const lastPage = pages.at(-1);
        if (lastPage) {
          sessionTotal.value = lastPage.total;
        } else {
          const page = await ChatService.queryAgentSessions(targetAgentId, {
            current: 1,
            size: SESSION_PAGE_SIZE
          });
          if (!isCurrentNormalAgent(targetAgentId) || requestSeq !== sessionRequestSeq) {
            return;
          }
          sessionTotal.value = page.total;
          sessions.value = (page.data as ExtendedChatSession[]).filter(session =>
            isSessionForAgent(session, targetAgentId)
          );
          sessionCurrentPage.value = page.pageNum;
          sessionsExpanded.value = page.pageNum > 1;
          return;
        }
        sessionCurrentPage.value = lastLoadedPage;
        sessionsExpanded.value = lastLoadedPage > 1;
        sessions.value = loadedSessions;
      } finally {
        if (requestSeq === sessionRequestSeq) {
          sessionPageLoading.value = false;
        }
      }
    };

    const createNewSession = async (options: CreateNewSessionOptions = {}) => {
      if (!canCreateSession.value) {
        if (!options.silent) {
          ElMessage.warning('暂无可用 Agent');
        }
        return;
      }
      const targetAgentId = requireRouteAgentId();
      try {
        const newSession = await ChatService.createSession(targetAgentId, '新会话');
        if (!isCurrentNormalAgent(targetAgentId)) {
          return;
        }
        if (isAiMode.value) {
          sessions.value.unshift(newSession);
          const group = findAiGroup(newSession.agentId);
          if (group) {
            group.sessions.unshift(newSession);
            group.total += 1;
            group.expanded = true;
            group.loaded = true;
          } else {
            const resolvedAgentId = getSessionAgentId(newSession);
            aiSessionGroups.value.unshift({
              agentId: resolvedAgentId,
              agentName: props.agent.name || '未命名智能体',
              agent: props.agent,
              sessions: [newSession],
              total: 1,
              currentPage: 1,
              pageSize: AI_SESSION_PAGE_SIZE,
              sessionsExpanded: false,
              loading: false,
              expanded: true,
              loaded: true
            });
          }
        } else {
          sessions.value = [
            newSession as ExtendedChatSession,
            ...sessions.value.filter(session => session.id !== newSession.id)
          ];
          sessionTotal.value += 1;
          if (!sessionsExpanded.value && sessions.value.length > SESSION_PAGE_SIZE) {
            sessions.value = sessions.value.slice(0, SESSION_PAGE_SIZE);
          }
        }
        selectedSessionByAgent.set(isAiMode.value ? getSessionAgentId(newSession) : targetAgentId, newSession.id);
        await props.handleSelectSession(newSession);
        if (!options.silent) {
          ElMessage.success('新会话创建成功');
        }
      } catch (error) {
        ElMessage.error('创建会话失败');
        console.error('创建会话失败:', error);
      }
    };

    const selectCurrentSession = async (session: ExtendedChatSession) => {
      const targetAgentId = getRouteAgentId();
      if (!targetAgentId || !isSessionForAgent(session, targetAgentId)) {
        return;
      }
      await props.handleSelectSession(session);
    };

    const loadSessions = async () => {
      const targetAgentId = getRouteAgentId();
      const requestSeq = ++sessionRequestSeq;
      if (!targetAgentId) {
        sessions.value = [];
        sessionCurrentPage.value = 1;
        sessionTotal.value = 0;
        sessionsExpanded.value = false;
        await props.handleSetCurrentSession(null);
        return;
      }
      try {
        const firstPageSessions = await loadSessionPage(1, false, targetAgentId, requestSeq);
        if (!isCurrentNormalAgent(targetAgentId) || requestSeq !== sessionRequestSeq) {
          return;
        }
        if (firstPageSessions.length > 0) {
          await props.handleSelectSession(firstPageSessions[0]);
        } else {
          await createNewSession({ silent: true });
        }
      } catch (error) {
        ElMessage.error('加载会话列表失败');
        console.error('加载会话列表失败:', error);
      }
    };

    const loadAiGroupedSessions = async () => {
      if (aiGroupedSessionsLoading) {
        return;
      }
      aiGroupedSessionsLoading = true;
      try {
        aiSessionGroups.value = props.availableAgents
          .filter(agent => parseAgentId(agent.id))
          .map(agent => {
            const resolvedAgentId = parseAgentId(agent.id) as string;
            const existingGroup = findAiGroup(resolvedAgentId);
            return {
              agentId: resolvedAgentId,
              agentName: agent.name || '未命名智能体',
              agent,
              sessions: existingGroup?.sessions || [],
              total: existingGroup?.total || 0,
              currentPage: existingGroup?.currentPage || 1,
              pageSize: existingGroup?.pageSize || AI_SESSION_PAGE_SIZE,
              sessionsExpanded: existingGroup?.sessionsExpanded || false,
              loading: false,
              expanded: resolvedAgentId === agentId.value || Boolean(existingGroup?.expanded),
              loaded: Boolean(existingGroup?.loaded)
            };
          });
        const currentGroup = findAiGroup(agentId.value);
        if (currentGroup && !currentGroup.loaded) {
          await loadAgentSessionPage(currentGroup, 1);
        }
        syncFlatSessionsFromGroups();
        if (currentGroup) {
          await selectPreferredGroupSession(currentGroup);
        } else {
          await props.handleSetCurrentSession(null);
        }
        await scrollActiveSessionIntoView();
      } catch (error) {
        ElMessage.error('加载会话列表失败');
        console.error('加载会话列表失败:', error);
      } finally {
        aiGroupedSessionsLoading = false;
      }
    };

    const reloadSessions = async () => {
      if (isAiMode.value) {
        await loadAiGroupedSessions();
      } else {
        await loadSessions();
      }
    };

    const expandSessions = async () => {
      if (sessionPageLoading.value || !hasMoreSessions.value) {
        return;
      }
      const targetAgentId = requireRouteAgentId();
      const requestSeq = ++sessionRequestSeq;
      try {
        await loadSessionPage(sessionCurrentPage.value + 1, true, targetAgentId, requestSeq);
      } catch (error) {
        ElMessage.error('展开会话列表失败');
        console.error('展开会话列表失败:', error);
      }
    };

    const collapseSessions = async () => {
      if (sessionPageLoading.value) {
        return;
      }
      const targetAgentId = requireRouteAgentId();
      const requestSeq = ++sessionRequestSeq;
      try {
        await loadSessionPage(1, false, targetAgentId, requestSeq);
      } catch (error) {
        ElMessage.error('折叠会话列表失败');
        console.error('折叠会话列表失败:', error);
      }
    };

    const togglePinSession = async (session: ChatSession) => {
      try {
        const nextPinned = !session.isPinned;
        const targetAgentId = getSessionAgentId(session);
        await ChatService.pinSession(session.id, targetAgentId, nextPinned);
        if (!isCurrentNormalAgent(targetAgentId)) {
          return;
        }
        applyPinnedSessionOrder(session.id, nextPinned, targetAgentId);
        ElMessage.success(nextPinned ? '会话已置顶' : '会话已取消置顶');
      } catch (error) {
        ElMessage.error('操作失败');
        console.error('置顶会话失败:', error);
      }
    };

    const deleteSession = async (session: ChatSession) => {
      try {
        await ElMessageBox.confirm('确定要删除这个会话吗？', '确认删除', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        });
        const targetAgentId = getSessionAgentId(session);
        await ChatService.deleteSession(session.id, targetAgentId);
        if (!isCurrentNormalAgent(targetAgentId)) {
          return;
        }
        props.handleDeleteSessionState(session.id);
        sessions.value = sessions.value.filter((s: ChatSession) => s.id !== session.id);
        if (isAiMode.value) {
          aiSessionGroups.value.forEach(group => {
            group.sessions = group.sessions.filter(item => item.id !== session.id);
            if (group.agentId === getSessionAgentId(session)) {
              group.total = Math.max(group.total - 1, group.sessions.length);
            }
          });
        } else {
          sessionTotal.value = Math.max(sessionTotal.value - 1, sessions.value.length);
          if (sessionsExpanded.value) {
            await reloadDisplayedSessionPages();
          } else if (sessionTotal.value > sessions.value.length) {
            await loadSessionPage(1);
          }
        }
        if (props.handleGetCurrentSession() === session) {
          await props.handleSetCurrentSession(null);
        }
        ElMessage.success('会话删除成功');
      } catch (error) {
        if (error !== 'cancel') {
          ElMessage.error('删除会话失败');
          console.error('删除会话失败:', error);
        }
      }
    };

    const clearAllSessions = async () => {
      if (!canCreateSession.value) {
        ElMessage.warning('暂无可用 Agent');
        return;
      }
      try {
        await ElMessageBox.confirm('确定要清空所有会话吗？此操作不可恢复。', '确认清空', {
          confirmButtonText: '确定',
          cancelButtonText: '取消',
          type: 'warning'
        });
        const targetAgentId = requireRouteAgentId();
        await ChatService.clearAgentSessions(targetAgentId);
        if (!isCurrentNormalAgent(targetAgentId)) {
          return;
        }
        if (isAiMode.value) {
          const group = findAiGroup(targetAgentId);
          if (group) {
            group.sessions.forEach((session: ChatSession) => {
              props.handleDeleteSessionState(session.id);
            });
            group.sessions = [];
            group.total = 0;
            group.currentPage = 1;
            group.sessionsExpanded = false;
            group.loaded = true;
          }
          syncFlatSessionsFromGroups();
        } else {
          sessions.value.forEach((session: ChatSession) => {
            props.handleDeleteSessionState(session.id);
          });
          sessions.value = [];
          sessionCurrentPage.value = 1;
          sessionTotal.value = 0;
          sessionsExpanded.value = false;
        }
        await props.handleSetCurrentSession(null);
        ElMessage.success('所有会话已清空');
      } catch (error) {
        if (error !== 'cancel') {
          ElMessage.error('清空会话失败');
          console.error('清空会话失败:', error);
        }
      }
    };

    onMounted(async () => {
      if (!isAiMode.value) {
        return;
      }
      // 等待 agent 加载完成后再初始化会话列表
      if (!props.agent?.id && (isAiMode.value || !getRouteAgentId())) {
        // 如果 agent 还没加载，监听 agent 变化
        const unwatch = watch(
          () => props.agent?.id,
          async newId => {
            if (newId) {
              unwatch();
              await loadAiGroupedSessions();
            }
          }
        );
      } else {
        // agent 已加载，直接初始化
        await loadAiGroupedSessions();
      }
    });

    watch(
      () => [agentId.value, aiSessionGroups.value.length, localCollapsed.value, getCurrentSessionId()],
      () => {
        scrollActiveSessionIntoView().catch(error => {
          console.error('滚动到当前会话条目失败:', error);
        });
      },
      { flush: 'post' }
    );

    watch(
      () => agentId.value,
      (currentAgentId, previousAgentId) => {
        if (!isAiMode.value || !currentAgentId || !previousAgentId || currentAgentId === previousAgentId) {
          return;
        }
        const previousGroup = findAiGroup(previousAgentId);
        if (previousGroup) {
          previousGroup.expanded = false;
        }
      }
    );

    watch(
      () => props.availableAgents.map(agent => parseAgentId(agent.id)).join(','),
      async () => {
        if (isAiMode.value && props.agent?.id) {
          await loadAiGroupedSessions();
        }
      }
    );

    expose({
      createNewSession,
      clearAllSessions,
      reloadSessions,
      focusAgentGroup
    });

    return {
      sessions,
      aiSessionGroups,
      sessionListRef,
      localCollapsed,
      isAiMode,
      authStore,
      sessionPageLoading,
      hasMoreSessions,
      canCollapseSessions,
      showSessionPager,
      canCreateSession,
      displayUserName,
      displayAccount,
      userInitial,
      formatTime,
      goBack,
      expandSessions,
      collapseSessions,
      createNewSession,
      selectCurrentSession,
      handleAgentGroupHeaderClick,
      togglePinSession,
      deleteSession,
      clearAllSessions,
      getVisibleGroupSessions,
      hasMoreAgentSessions,
      isAgentGroupActive,
      toggleAgentGroup,
      expandAgentSessions,
      collapseAgentSessions,
      selectGroupedSession,
      startEditSessionTitle,
      saveSessionTitle,
      cancelEditSessionTitle
    };
  }
});
</script>

<style scoped>
.chat-session-sidebar {
  display: flex !important;
  flex-direction: column !important;
  height: 100% !important;
  min-height: 0 !important;
  color: var(--el-text-color-primary) !important;
  background: var(--el-bg-color) !important;
  overflow: hidden !important;
  border: 0 !important;
  border-right: 1px solid var(--el-border-color-lighter) !important;
  border-radius: 0 !important;
  box-shadow: none !important;
  transition:
    width 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    background 0.18s cubic-bezier(0.16, 1, 0.3, 1),
    border-color 0.18s cubic-bezier(0.16, 1, 0.3, 1) !important;
}

.chat-session-sidebar.collapsed {
  width: 0 !important;
  min-width: 0 !important;
  overflow: hidden !important;
  background: transparent !important;
  border-color: transparent !important;
}

.sidebar-header {
  flex: 0 0 auto !important;
  padding: 12px !important;
  background: var(--el-bg-color) !important;
  border-bottom: 1px solid var(--el-border-color-lighter) !important;
}

.mobile-sidebar-close {
  display: none;
  flex: 0 0 auto;
}

.header-controls {
  display: flex !important;
  align-items: center !important;
  justify-content: space-between !important;
  gap: 8px !important;
  margin-bottom: 10px !important;
}

.user-profile {
  display: flex !important;
  align-items: center !important;
  min-width: 0 !important;
  gap: 10px !important;
}

.user-avatar {
  flex: 0 0 auto !important;
  width: 36px !important;
  height: 36px !important;
  border: 1px solid var(--el-border-color-light) !important;
  box-shadow: none !important;
}

.user-meta {
  min-width: 0 !important;
}

.user-name {
  color: var(--el-text-color-primary) !important;
  font-size: 14px !important;
  font-weight: 600 !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  white-space: nowrap !important;
}

.user-account {
  margin-top: 2px !important;
  color: var(--el-text-color-secondary) !important;
  font-size: 12px !important;
  line-height: 1.4 !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  white-space: nowrap !important;
}

.new-session-section {
  display: flex;
  grid-template-columns: minmax(0, 1fr) 36px !important;
  gap: 8px !important;
}

.session-list {
  flex: 1 1 auto !important;
  min-height: 0 !important;
  max-height: none !important;
  overflow-y: auto !important;
  padding: 0px 12px 12px !important;
  background: var(--el-bg-color) !important;
}

.session-list::-webkit-scrollbar {
  width: 4px;
  height: 4px;
}

.session-list::-webkit-scrollbar-button {
  -webkit-appearance: none;
  appearance: none;
  display: none;
  width: 0;
  height: 0;
  background: transparent;
}

.session-list::-webkit-scrollbar-corner,
.session-list::-webkit-scrollbar-track-piece,
.session-list::-webkit-scrollbar-track {
  background: transparent;
}

.session-list::-webkit-scrollbar-thumb {
  background-color: transparent !important;
  border-radius: 999px;
}

.chat-session-sidebar:hover .session-list::-webkit-scrollbar-thumb,
.chat-session-sidebar:hover .session-list::-webkit-scrollbar-thumb:hover,
.chat-session-sidebar:hover .session-list::-webkit-scrollbar-thumb:active {
  background-color: rgba(144, 147, 153, 0.22) !important;
}

@supports (-moz-appearance: none) {
  .session-list {
    scrollbar-width: none;
  }

  .chat-session-sidebar:hover .session-list {
    scrollbar-width: thin;
    scrollbar-color: rgba(144, 147, 153, 0.22) transparent;
  }
}

@media (max-width: 768px) {
  .mobile-sidebar-close {
    display: inline-flex;
  }
}

.session-list-heading {
  padding: 10px 12px;
  color: var(--el-text-color-secondary) !important;
  font-size: 12px !important;
  font-weight: 600 !important;
}

.session-agent-group {
  margin-bottom: 10px !important;
}

.session-agent-header {
  display: flex !important;
  width: 100% !important;
  height: 32px !important;
  align-items: center !important;
  gap: 6px !important;
  padding: 0 4px !important;
  color: var(--el-text-color-primary) !important;
  cursor: pointer !important;
  background: transparent !important;
  border: 0 !important;
  box-shadow: none !important;
}

.session-agent-header:hover {
  color: var(--el-color-primary) !important;
}

.session-agent-header.active {
  color: var(--el-color-primary) !important;
}

.session-agent-icon {
  flex: 0 0 16px !important;
  width: 16px !important;
  height: 16px !important;
  color: currentColor !important;
}

.session-agent-name {
  flex: 1 1 auto !important;
  min-width: 0 !important;
  overflow: hidden !important;
  font-size: 13px !important;
  font-weight: 600 !important;
  text-align: left !important;
  text-overflow: ellipsis !important;
  white-space: nowrap !important;
}

.session-agent-count {
  flex: 0 0 auto !important;
  min-width: 18px !important;
  height: 18px !important;
  padding: 0 6px !important;
  color: var(--el-text-color-secondary) !important;
  font-size: 12px !important;
  line-height: 18px !important;
  text-align: center !important;
  background: var(--el-fill-color-light) !important;
  border-radius: 999px !important;
}

.session-agent-arrow {
  flex: 0 0 auto !important;
  color: var(--el-text-color-secondary) !important;
  font-size: 13px !important;
  transition: transform 0.16s ease !important;
}

.session-agent-arrow.expanded {
  transform: rotate(180deg) !important;
}

.session-group-more {
  width: 100% !important;
  height: 28px !important;
  margin: 0 0 8px !important;
  justify-content: center !important;
  font-size: 12px !important;
}

.session-item {
  margin-bottom: 8px !important;
  padding: 10px 12px !important;
  color: var(--el-text-color-primary) !important;
  background: var(--el-bg-color) !important;
  border: 1px solid var(--el-border-color-light) !important;
  border-radius: 6px !important;
  cursor: pointer !important;
  box-shadow: none !important;
  transform: none !important;
  transition:
    background 0.16s ease,
    border-color 0.16s ease !important;
}

.session-item:hover {
  background: var(--el-fill-color-extra-light) !important;
  border-color: var(--el-color-primary-light-7) !important;
}

.session-item.active {
  background: var(--el-fill-color-extra-light) !important;
  border-color: var(--el-color-primary-light-5) !important;
}

.session-item.pinned {
  border-left: 3px solid var(--el-color-primary) !important;
  padding-left: 10px !important;
}

.session-header {
  display: flex !important;
  align-items: flex-start !important;
  justify-content: space-between !important;
  gap: 8px !important;
  margin-bottom: 6px !important;
}

.session-title {
  flex: 1 1 auto !important;
  min-width: 0 !important;
  margin-right: 0 !important;
  color: var(--el-text-color-primary) !important;
  font-size: 13px !important;
  font-weight: 500 !important;
  line-height: 1.4 !important;
  overflow: hidden !important;
  text-overflow: ellipsis !important;
  white-space: nowrap !important;
}

.session-actions {
  display: flex !important;
  flex: 0 0 auto !important;
  gap: 2px !important;
  opacity: 0 !important;
  transition: opacity 0.16s ease !important;
}

.session-item:hover .session-actions,
.session-item:focus-within .session-actions,
.session-item.active .session-actions {
  opacity: 1 !important;
}

.session-actions :deep(.el-button) {
  width: 22px !important;
  height: 22px !important;
  color: var(--el-text-color-secondary) !important;
  border-radius: 4px !important;
}

.session-actions :deep(.el-button:hover),
.session-actions :deep(.el-button:focus) {
  color: var(--el-color-primary) !important;
  background: var(--el-color-primary-light-9) !important;
}

.session-time {
  color: var(--el-text-color-secondary) !important;
  font-size: 12px !important;
  line-height: 1.4 !important;
}

.session-pagination-actions {
  display: flex !important;
  justify-content: center !important;
  gap: 8px !important;
  padding: 4px 0 2px !important;
}

.session-page-button {
  min-width: 92px !important;
  height: 30px !important;
  margin-left: 0 !important;
  border-radius: 4px !important;
  font-size: 12px !important;
}
</style>
