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
  <BaseLayout>
    <main class="agent-list-page flex flex-col gap-8px">
      <!-- 主内容区域 -->
      <!-- 内容头部 -->
      <ElCard class="card-wrapper" shadow="never" body-class="!p-14px">
        <div class="flex items-center justify-between gap-16px lt-md:flex-col lt-md:items-stretch">
          <div>
            <h1 class="text-20px text-primary font-bold leading-28px">智能体管理中心</h1>
            <p class="mt-6px text-13px text-[var(--el-text-color-secondary)]">
              创建和管理您的AI智能体，让数据分析更智能
            </p>
          </div>
          <div class="header-stats grid grid-cols-4 gap-10px lt-md:grid-cols-2 lt-sm:grid-cols-1">
            <div class="stat-item">
              <div class="stat-number">{{ agents.length }}</div>
              <div class="stat-label">总数量</div>
            </div>
            <div class="stat-item">
              <div class="stat-number">{{ publishedCount }}</div>
              <div class="stat-label">已发布</div>
            </div>
            <div class="stat-item">
              <div class="stat-number">{{ draftCount }}</div>
              <div class="stat-label">草稿</div>
            </div>
            <div class="stat-item">
              <div class="stat-number">{{ offlineCount }}</div>
              <div class="stat-label">已下线</div>
            </div>
          </div>
        </div>
      </ElCard>

      <!-- 过滤和搜索区域 -->
      <ElCard class="card-wrapper" shadow="never" body-class="!p-12px">
        <div class="flex flex-col gap-10px">
          <div class="list-toolbar flex items-center justify-between gap-12px lt-md:flex-col lt-md:items-stretch">
            <div class="flex gap-8px">
              <ElButton type="primary" native-type="button" @click="goToCreateAgent">
                <ElIcon class="mr-3px"><component :is="UserFilled" /></ElIcon>
                创建智能体
              </ElButton>
              <ElButton native-type="button" :disabled="loading || refreshing" @click="refreshAgents">
                <ElIcon class="mr-3px" :class="{ 'is-refreshing': refreshing }"><component :is="Refresh" /></ElIcon>
                刷新
              </ElButton>
            </div>
            <ElInput
              v-model="searchKeyword"
              class="agent-search max-w-360px lt-md:max-w-none"
              placeholder="搜索智能体名称、ID或描述..."
              :prefix-icon="Search"
              clearable
            />
          </div>

          <div class="filter-tabs" role="tablist" aria-label="智能体类型筛选">
            <ElButton
              v-for="option in filterOptions"
              :key="option.value"
              class="filter-tab"
              :class="{ active: activeFilter === option.value }"
              native-type="button"
              :aria-pressed="activeFilter === option.value"
              @click="setFilter(option.value)"
            >
              <ElIcon><component :is="option.icon" /></ElIcon>
              <span>{{ option.label }}</span>
              <span class="tab-count">{{ option.count }}</span>
            </ElButton>
          </div>
        </div>
      </ElCard>

      <!-- 智能体网格 -->
      <!-- todo: 支持分页（需后端支持）-->
      <div v-if="!loading" class="agents-grid">
        <ElRow :gutter="8">
          <ElCol v-for="agent in filteredAgents" :key="agent.id" :xs="24" :sm="12" :md="8" :lg="6">
            <ElCard class="agent-card" :body-style="{ padding: '0' }" @click="enterAgent(agent.id)">
              <div class="agent-content">
                <div class="agent-card-top">
                  <div class="agent-identity">
                    <div class="agent-avatar">
                      <ElAvatar :size="46" :src="getAgentAvatar(agent)">
                        {{ getAgentInitial(agent.name) }}
                      </ElAvatar>
                    </div>
                    <div class="agent-heading">
                      <h3 class="agent-name">{{ agent.name }}</h3>
                      <span class="agent-type-badge" :class="getAgentTypeClass(agent.agentType)">
                        {{ formatAgentType(agent.agentType) }}
                      </span>
                    </div>
                  </div>
                  <div class="agent-card-actions">
                    <span class="status-pill" :class="getStatusClass(agent.status)">
                      {{ getStatusText(agent.status) }}
                    </span>
                    <ElButton text type="danger" title="删除智能体" @click.stop="handleDeleteAgent(agent)">
                      <ElIcon><Delete /></ElIcon>
                    </ElButton>
                  </div>
                </div>

                <div class="agent-card-body">
                  <p class="agent-description">{{ agent.description }}</p>
                </div>

                <div class="agent-meta">
                  <span class="agent-id">ID: {{ agent.id }}</span>
                  <span class="agent-time">{{ formatTime(agent.lastModifyTime ?? agent.updateTime) || '未更新' }}</span>
                </div>
              </div>
            </ElCard>
          </ElCol>
        </ElRow>
      </div>

      <!-- 加载状态 -->
      <div v-if="loading" class="loading-state">
        <ElSkeleton :rows="6" animated />
      </div>

      <!-- 空状态 -->
      <div v-if="!loading && filteredAgents.length === 0" class="empty-state">
        <ElEmpty description="暂无智能体">
          <template #image>
            <ElIcon size="60"><Grid /></ElIcon>
          </template>
          <ElButton class="empty-action" native-type="button" @click="goToCreateAgent">
            <ElIcon class="mr-3px"><component :is="UserFilled" /></ElIcon>
            创建智能体
          </ElButton>
        </ElEmpty>
      </div>
    </main>
  </BaseLayout>
</template>

<script lang="ts">
import { defineComponent, ref, computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  Connection,
  DataAnalysis,
  Document,
  Grid,
  Delete,
  Service,
  Search,
  Refresh,
  UserFilled
} from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import agentService from '@/views/ai-agent/services/agent';
import type { Agent } from '@/views/ai-agent/services/agent';
import { extractApiErrorMessage, shouldShowLocalApiError } from '@/views/ai-agent/services/common';
import {
  AGENT_TYPE,
  AGENT_TYPE_OPTIONS,
  formatAgentType,
  normalizeAgentType
} from '@/views/ai-agent/constants/agentTypes';

export default defineComponent({
  name: 'AgentList',
  components: {
    BaseLayout,
    Grid,
    Delete
  },
  setup() {
    const router = useRouter();
    const loading = ref(true);
    const refreshing = ref(false);
    const activeFilter = ref('all');
    const searchKeyword = ref('');
    const agents = ref<Agent[]>([]);

    // 计算属性
    const publishedCount = computed(() => agents.value.filter((a: Agent) => a.status === 'published').length);
    const draftCount = computed(() => agents.value.filter((a: Agent) => a.status === 'draft').length);
    const offlineCount = computed(() => agents.value.filter((a: Agent) => a.status === 'offline').length);
    const agentTypeIconMap = {
      [AGENT_TYPE.DATA_ANALYSIS]: DataAnalysis,
      [AGENT_TYPE.KNOWLEDGE_BASE]: Document,
      [AGENT_TYPE.CUSTOMER_SERVICE]: Service,
      [AGENT_TYPE.ORCHESTRATOR]: Connection
    };
    const filterOptions = computed(() => [
      { value: 'all', label: '全部', count: agents.value.length, icon: Grid },
      ...AGENT_TYPE_OPTIONS.map(option => ({
        ...option,
        count: agents.value.filter((agent: Agent) => normalizeAgentType(agent.agentType) === option.value).length,
        icon: agentTypeIconMap[option.value] || Grid
      }))
    ]);

    const filteredAgents = computed(() => {
      let filtered = agents.value;

      // 按 Agent 类型过滤
      if (activeFilter.value !== 'all') {
        filtered = filtered.filter((agent: Agent) => normalizeAgentType(agent.agentType) === activeFilter.value);
      }

      // 按关键词搜索
      if (searchKeyword.value.trim()) {
        const keyword = searchKeyword.value.toLowerCase();
        filtered = filtered.filter((agent: Agent) => {
          const name = agent.name?.toLowerCase() || '';
          const description = agent.description?.toLowerCase() || '';
          const typeText = formatAgentType(agent.agentType).toLowerCase();
          const id = agent.id?.toString() || '';
          return (
            name.includes(keyword) ||
            description.includes(keyword) ||
            typeText.includes(keyword) ||
            id.includes(keyword)
          );
        });
      }

      return filtered;
    });

    const setFilter = (filter: string) => {
      activeFilter.value = filter;
    };

    const loadAgents = async (forceRefresh = false) => {
      if (forceRefresh) {
        refreshing.value = true;
      } else {
        loading.value = true;
      }
      try {
        const response = forceRefresh ? await agentService.refreshList() : await agentService.list();
        agents.value = response || [];
      } catch (error) {
        ElMessage.error('获取智能体列表失败，请检查网络！');
        agents.value = [];
      } finally {
        loading.value = false;
        refreshing.value = false;
      }
    };

    const refreshAgents = () => {
      loadAgents(true);
    };

    const enterAgent = (agentId?: string) => {
      if (!agentId) {
        return;
      }
      router.push({
        name: 'ai-agent_agent_detail',
        query: { id: String(agentId) }
      });
    };

    const getStatusText = (status?: string) => {
      const statusMap: Record<string, string> = {
        published: '已发布',
        draft: '草稿',
        offline: '已下线'
      };
      return statusMap[status || ''] || status || '';
    };

    const getStatusClass = (status?: string) => {
      const classMap: Record<string, string> = {
        published: 'status-published',
        draft: 'status-draft',
        offline: 'status-offline'
      };
      return classMap[status || ''] || 'status-unknown';
    };

    const getAgentTypeClass = (agentType?: string) => {
      const normalizedType = normalizeAgentType(agentType);
      const classMap: Record<string, string> = {
        [AGENT_TYPE.DATA_ANALYSIS]: 'type-data-analysis',
        [AGENT_TYPE.KNOWLEDGE_BASE]: 'type-knowledge-base',
        [AGENT_TYPE.CUSTOMER_SERVICE]: 'type-customer-service',
        [AGENT_TYPE.ORCHESTRATOR]: 'type-orchestrator'
      };
      return classMap[normalizedType] || 'type-default';
    };

    const getAgentInitial = (name?: string) => {
      const trimmedName = (name || '').trim();
      if (!trimmedName) return 'AI';
      if (/^[A-Za-z0-9]/.test(trimmedName)) {
        return trimmedName.slice(0, 2).toUpperCase();
      }
      return Array.from(trimmedName).slice(0, 2).join('');
    };

    const getAgentAvatar = (agent: Agent) => agent.avatarPreviewUrl || '';

    // 服务端时间字段按 JSON 字符串下发（类型声明为 Date 仅为历史遗留），原样展示
    const formatTime = (time?: string | Date) => (time ? String(time) : '');

    const goToCreateAgent = () => {
      router.push('/ai-agent/agent/create');
    };

    // 删除智能体
    const handleDeleteAgent = async (agent: Agent) => {
      try {
        await ElMessageBox.confirm(`确定要删除智能体 "${agent.name}" 吗？此操作不可恢复。`, '删除确认', {
          confirmButtonText: '确定删除',
          cancelButtonText: '取消',
          type: 'warning'
        });
      } catch {
        // 用户取消删除，静默返回
        return;
      }

      try {
        const success = await agentService.delete(agent.id!);
        if (success) {
          ElMessage.success('智能体删除成功');
          // 从列表中移除已删除的智能体
          agents.value = agents.value.filter((a: Agent) => a.id !== agent.id);
        } else {
          ElMessage.error('智能体删除失败');
        }
      } catch (error) {
        if (shouldShowLocalApiError(error)) {
          ElMessage.error(extractApiErrorMessage(error, '智能体删除失败'));
        }
      }
    };

    // 生命周期
    onMounted(() => {
      loadAgents();
    });

    return {
      loading,
      refreshing,
      activeFilter,
      searchKeyword,
      agents,
      filteredAgents,
      filterOptions,
      publishedCount,
      draftCount,
      offlineCount,
      setFilter,
      loadAgents,
      refreshAgents,
      enterAgent,
      getStatusText,
      getStatusClass,
      getAgentTypeClass,
      getAgentInitial,
      getAgentAvatar,
      formatAgentType,
      formatTime,
      goToCreateAgent,
      handleDeleteAgent,
      Search,
      Refresh,
      UserFilled
    };
  }
});
</script>

<style scoped>
.agent-list-page {
  min-width: 0;
  overflow-x: hidden;
}

.agent-list-page > .card-wrapper:first-child h1 {
  margin: 0 !important;
  color: var(--el-text-color-primary) !important;
  font-size: 22px !important;
  font-weight: 600 !important;
  line-height: 28px !important;
  letter-spacing: 0 !important;
}

.agent-list-page > .card-wrapper:first-child p {
  margin: 4px 0 0 !important;
  color: var(--el-text-color-secondary) !important;
  font-size: 13px !important;
  line-height: 1.5 !important;
}

.stat-item {
  min-width: 86px;
  padding: 10px 12px;
  text-align: left;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.stat-number {
  font-size: 22px;
  font-weight: 600;
  color: var(--el-color-primary);
  line-height: 1;
}

.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-top: 0.25rem;
}

.list-toolbar {
  margin-bottom: 0;
}

.agent-search {
  width: 360px;
}

.is-refreshing {
  animation: refreshRotate 0.8s linear infinite;
}

.filter-tabs {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  padding: 0;
}

.filter-tab {
  min-height: 32px;
  display: inline-flex;
  align-items: center;
  gap: 0.45rem;
  padding: 0 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  background: var(--el-bg-color);
  color: var(--el-text-color-secondary);
  font-size: 13px;
  font-weight: 600;
}

.filter-tab:hover {
  background: var(--el-fill-color-light);
  border-color: var(--el-color-primary-light-7);
  color: var(--el-color-primary);
}

.filter-tab.active {
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
  color: var(--el-color-primary);
}

.filter-tab.active .tab-count {
  background: var(--el-bg-color);
  color: var(--el-color-primary);
}

.filter-tab:focus-visible {
  outline: 2px solid var(--el-color-primary-light-5);
  outline-offset: 2px;
}

.tab-count {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  min-width: 1.55rem;
  height: 1.35rem;
  padding: 0 0.45rem;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 600;
}

/* 智能体网格 */
.agents-grid {
  min-width: 0;
  overflow-x: hidden;
}

.agents-grid :deep(.el-row) {
  margin-right: -4px !important;
  margin-left: -4px !important;
  row-gap: 8px;
}

@keyframes refreshRotate {
  to {
    transform: rotate(360deg);
  }
}

.agent-card {
  cursor: pointer;
  min-height: 220px;
  overflow: hidden;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.2s ease;
  border-radius: 8px;
  border: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  box-shadow: none;
}

.agent-card:hover {
  border-color: var(--el-color-primary-light-7);
  box-shadow: 0 4px 12px color-mix(in srgb, var(--el-color-primary) 12%, transparent);
  transform: none;
}

.agent-content {
  min-height: 220px;
  position: relative;
  display: flex;
  flex-direction: column;
}

.agent-card-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 0.8rem;
  padding: 1.05rem 1rem 0.9rem;
  background: var(--el-fill-color-extra-light);
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.agent-identity {
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 0.8rem;
}

.agent-avatar {
  flex: 0 0 auto;
}

.agent-avatar :deep(.el-avatar) {
  border: 1px solid var(--el-color-primary-light-7);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
  box-shadow: none;
}

.agent-heading {
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.4rem;
}

.agent-name {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--el-text-color-primary);
  margin: 0;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-type-badge {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 0.22rem 0.55rem;
  border-radius: 999px;
  border: 1px solid transparent;
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1.2;
}

.type-data-analysis {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.type-knowledge-base {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
  border-color: var(--el-color-success-light-7);
}

.type-customer-service {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-color: var(--el-color-warning-light-7);
}

.type-orchestrator {
  color: var(--el-color-info);
  background: var(--el-color-info-light-9);
  border-color: var(--el-color-info-light-7);
}

.type-default {
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-color: var(--el-border-color-light);
}

.agent-card-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 0.45rem;
}

.agent-card-body {
  flex: 1;
  padding: 1rem 1rem 0.75rem;
  background: var(--el-bg-color);
}

.agent-description {
  min-height: 2.65rem;
  color: var(--el-text-color-regular);
  font-size: 0.875rem;
  line-height: 1.5;
  margin: 0;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.agent-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 0.75rem;
  min-height: 2.7rem;
  padding: 0 1rem;
  border-top: 1px solid var(--el-border-color-lighter);
  background: var(--el-fill-color-extra-light);
  font-size: 0.75rem;
  color: var(--el-text-color-secondary);
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  padding: 0.2rem 0.5rem;
  border-radius: 999px;
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1.25;
  border: 1px solid transparent;
}

.status-pill::before {
  content: '';
  width: 0.4rem;
  height: 0.4rem;
  border-radius: 999px;
  background: currentColor;
}

.status-published {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
  border-color: var(--el-color-success-light-7);
}

.status-draft {
  color: var(--el-color-warning);
  background: var(--el-color-warning-light-9);
  border-color: var(--el-color-warning-light-7);
}

.status-offline,
.status-unknown {
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  border-color: var(--el-border-color-light);
}

/* 删除按钮 */
.delete-button {
  width: 28px;
  height: 28px;
  padding: 0;
  border: 1px solid var(--el-color-danger-light-7);
  border-radius: 7px;
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  opacity: 0.72;
  transition:
    background 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease,
    opacity 0.2s ease,
    transform 0.2s ease;
}

.delete-button:hover {
  background: var(--el-color-danger);
  border-color: var(--el-color-danger);
  color: #fff;
  opacity: 1;
  transform: translateY(-1px);
}

.agent-card:hover .delete-button {
  opacity: 1;
}

/* 加载状态 */
.empty-state {
  padding: 24px 0;
  border: 1px dashed var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.empty-action {
  margin: 0 auto;
}
</style>
