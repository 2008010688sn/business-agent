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
    <main class="agent-detail-page">
      <section class="agent-detail-head">
        <div class="agent-head-left">
          <div
            class="avatar-wrapper"
            @mouseenter="showHeaderAvatarButton = true"
            @mouseleave="showHeaderAvatarButton = false"
          >
            <ElAvatar :src="agentAvatarUrl" class="header-avatar">
              {{ agent.name }}
            </ElAvatar>
            <div v-if="showHeaderAvatarButton" class="avatar-overlay-header">
              <ElButton type="primary" size="small" :loading="headerUploading" @click="triggerHeaderFileUpload">
                {{ headerUploading ? '上传中...' : '更换头像' }}
              </ElButton>
            </div>
          </div>
          <input
            ref="headerFileInput"
            type="file"
            accept="image/*"
            style="display: none"
            @change="handleHeaderFileUpload"
          />

          <div class="agent-title-copy">
            <span class="detail-eyebrow">智能体配置</span>
            <h1>{{ agent.name }}</h1>
            <div class="agent-meta-row">
              <span class="agent-type-chip">{{ formatAgentType(agent.agentType) }}</span>
              <span class="status-chip" :class="getStatusClass(agent.status)">
                {{ getStatusText(agent.status) }}
              </span>
            </div>
            <p class="publish-state-hint">{{ publishStateHint }}</p>
          </div>
        </div>
        <div class="agent-head-actions">
          <ElButton
            class="action-button publish-action"
            :class="[isPublished ? 'sync-action' : 'save-action']"
            :loading="publishActionLoading"
            @click="handlePublishStatusAction"
          >
            <ElIcon class="button-glyph">
              <component :is="isPublished ? SwitchButton : Promotion" />
            </ElIcon>
            <span>{{ isPublished ? '下线' : '发布' }}</span>
          </ElButton>
          <ElButton class="detail-back-control" native-type="button" aria-label="返回智能体列表" @click="goBack">
            <ElIcon><component :is="ArrowLeft" /></ElIcon>
            <span>返回列表</span>
          </ElButton>
        </div>
      </section>

      <section class="agent-detail-shell">
        <aside class="config-sidebar">
          <ElMenu :default-active="activeMenuIndex" class="agent-menu" @select="handleMenuSelect">
            <ElMenuItemGroup title="基本信息">
              <ElMenuItem index="basic">
                <ElIcon><InfoFilled /></ElIcon>
                基本信息
              </ElMenuItem>
              <ElMenuItem index="model-config">
                <ElIcon><Setting /></ElIcon>
                模型配置
              </ElMenuItem>
            </ElMenuItemGroup>
            <ElMenuItemGroup v-if="showSkillMenu" title="Skills">
              <ElMenuItem index="skills">
                <ElIcon><Setting /></ElIcon>
                Skill 绑定
              </ElMenuItem>
            </ElMenuItemGroup>
            <ElMenuItemGroup v-if="showPresetMenu" title="预设问题管理">
              <ElMenuItem index="preset-questions">
                <ElIcon><Setting /></ElIcon>
                预设问题管理
              </ElMenuItem>
            </ElMenuItemGroup>
            <ElMenuItemGroup title="记忆配置">
              <ElMenuItem index="memory-config">
                <ElIcon><Notebook /></ElIcon>
                长期记忆
              </ElMenuItem>
            </ElMenuItemGroup>
            <ElMenuItemGroup v-if="isOrchestrator" title="编排管理">
              <ElMenuItem index="orchestration">
                <ElIcon><Connection /></ElIcon>
                编排配置
              </ElMenuItem>
            </ElMenuItemGroup>
            <ElMenuItemGroup title="运行与发布">
              <ElMenuItem index="visibility-config">
                <ElIcon><Connection /></ElIcon>
                可见性治理
              </ElMenuItem>
              <ElMenuItem index="go-run">
                <ElIcon><VideoPlay /></ElIcon>
                前往运行页面
              </ElMenuItem>
              <ElMenuItem index="access-api">
                <ElIcon><Connection /></ElIcon>
                访问 API
              </ElMenuItem>
            </ElMenuItemGroup>
          </ElMenu>
        </aside>

        <template v-if="detailNotFound">
          <NotFound></NotFound>
        </template>
        <section v-else v-loading="detailLoading" class="config-panel">
          <div :key="loadedAgentPanelKey" class="config-panel-body">
            <AgentBaseSetting v-if="activeMenuIndex === 'basic'" :agent="agent"></AgentBaseSetting>
            <AgentModelConfigPanel
              v-else-if="activeMenuIndex === 'model-config'"
              :agent-id="resolvedAgentId"
            ></AgentModelConfigPanel>
            <AgentSkillsConfig v-else-if="activeMenuIndex === 'skills'" :agent-id="resolvedAgentId"></AgentSkillsConfig>
            <AgentPresetsConfig
              v-else-if="activeMenuIndex === 'preset-questions'"
              :agent-id="resolvedAgentId"
            ></AgentPresetsConfig>
            <AgentAccessApi v-else-if="activeMenuIndex === 'access-api'" :agent-id="resolvedAgentId"></AgentAccessApi>
            <AgentOrchestrationConfig
              v-else-if="activeMenuIndex === 'orchestration'"
              :agent-id="resolvedAgentId"
            ></AgentOrchestrationConfig>
            <AgentMemoryConfig
              v-else-if="activeMenuIndex === 'memory-config'"
              :agent-id="resolvedAgentId"
            ></AgentMemoryConfig>
            <AgentVisibilityConfig
              v-else-if="activeMenuIndex === 'visibility-config'"
              :agent-id="resolvedAgentId"
            ></AgentVisibilityConfig>
            <NotFound v-else></NotFound>
          </div>
        </section>
      </section>
    </main>
  </BaseLayout>
</template>

<script lang="ts">
import { computed, defineComponent, ref, watch, type Ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  ArrowLeft,
  Coin,
  Connection,
  InfoFilled,
  Notebook,
  Promotion,
  Setting,
  SwitchButton,
  VideoPlay
} from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import AgentBaseSetting from '@/views/ai-agent/components/agent/BaseSetting.vue';
import AgentPresetsConfig from '@/views/ai-agent/components/agent/PresetsConfig.vue';
import AgentAccessApi from '@/views/ai-agent/components/agent/AccessApi.vue';
import AgentSkillsConfig from '@/views/ai-agent/components/agent/SkillsConfig.vue';
import AgentModelConfigPanel from '@/views/ai-agent/components/agent/ModelConfigPanel.vue';
import AgentOrchestrationConfig from '@/views/ai-agent/components/agent/OrchestrationConfig.vue';
import AgentMemoryConfig from '@/views/ai-agent/components/agent/MemoryConfig.vue';
import AgentVisibilityConfig from '@/views/ai-agent/components/agent/VisibilityConfig.vue';
import NotFound from '@/views/ai-agent/pages/NotFound.vue';
import AgentService, { type Agent } from '@/views/ai-agent/services/agent';
import { fileUploadApi } from '@/views/ai-agent/services/fileUpload';
import { AGENT_TYPE, formatAgentType, normalizeAgentType } from '@/views/ai-agent/constants/agentTypes';
import { useCommonMixin } from '@/mixins/composition.js';

const ROUTE_TAB_VALUES = new Set([
  'basic',
  'model-config',
  'skills',
  'preset-questions',
  'memory-config',
  'orchestration',
  'visibility-config',
  'access-api'
]);

const normalizeRouteValue = (value: unknown) => {
  const rawValue = Array.isArray(value) ? value[0] : value;
  return rawValue === undefined || rawValue === null ? '' : String(rawValue).trim();
};

export default defineComponent({
  name: 'AgentDetail',
  components: {
    BaseLayout,
    AgentBaseSetting,
    AgentPresetsConfig,
    AgentAccessApi,
    AgentSkillsConfig,
    AgentModelConfigPanel,
    AgentOrchestrationConfig,
    AgentMemoryConfig,
    AgentVisibilityConfig,
    NotFound,
    InfoFilled,
    Setting,
    VideoPlay,
    Connection,
    Notebook
  },
  setup() {
    const route = useRoute();
    const router = useRouter();
    const { closeTab } = useCommonMixin();

    const activeMenuIndex: Ref<string> = ref('basic');
    const agent: Ref<Agent> = ref({
      id: undefined,
      name: 'loading...',
      description: '',
      status: 'draft',
      createTime: undefined,
      updateTime: undefined,
      avatar: '',
      agentType: AGENT_TYPE.DATA_ANALYSIS,
      chatModelConfigId: undefined,
      prompt: '',
      adminId: undefined,
      tags: '',
      humanReviewEnabled: false
    } as Agent);

    const headerFileInput = ref<HTMLInputElement | null>(null);
    const headerUploading = ref(false);
    const showHeaderAvatarButton = ref(false);
    const originalHeaderAvatar = ref<string>('');
    const originalHeaderAvatarPreviewUrl = ref<string>('');
    const publishActionLoading = ref(false);
    const detailLoading = ref(false);
    const detailNotFound = ref(false);
    const loadedAgentId = ref('');
    let detailRequestSeq = 0;
    const agentAvatarUrl = computed(() => agent.value.avatarPreviewUrl || '');
    const resolvedAgentId = computed(() => agent.value.id ?? '');
    const loadedAgentPanelKey = computed(() => `agent-panel-${loadedAgentId.value || 'empty'}`);
    const isDetailRoute = computed(() => route.name === 'ai-agent_agent_detail');
    const routeAgentId = computed(() => normalizeRouteValue(route.query.id));

    const applyRouteTab = () => {
      const tab = normalizeRouteValue(route.query.tab);
      activeMenuIndex.value = ROUTE_TAB_VALUES.has(tab) ? tab : 'basic';
    };

    const resetTransientStates = () => {
      headerUploading.value = false;
      publishActionLoading.value = false;
      showHeaderAvatarButton.value = false;
      if (headerFileInput.value) {
        headerFileInput.value.value = '';
      }
    };

    const isCurrentAgent = (agentId: unknown) => {
      return isDetailRoute.value && normalizeRouteValue(agentId) === routeAgentId.value;
    };

    const shouldUpdateActionState = (agentId: unknown) => {
      return !isDetailRoute.value || isCurrentAgent(agentId);
    };

    const triggerHeaderFileUpload = () => {
      headerFileInput.value?.click();
    };

    const handleHeaderFileUpload = async (event: Event) => {
      const target = event.target as HTMLInputElement;
      const file = target.files?.[0];
      if (!file) return;

      if (!file.type.startsWith('image/')) {
        ElMessage.error('请选择图片文件');
        return;
      }

      if (file.size > 5 * 1024 * 1024) {
        ElMessage.error('图片大小不能超过 5MB');
        return;
      }

      const currentAgent = { ...agent.value };
      const currentAgentId = currentAgent.id;
      const shouldUpdateUploadState = () =>
        currentAgentId == null || shouldUpdateActionState(currentAgentId);

      try {
        headerUploading.value = true;
        if (currentAgentId == null || !normalizeRouteValue(currentAgentId)) {
          throw new Error('Agent ID is required');
        }
        originalHeaderAvatar.value = currentAgent.avatar || '';
        originalHeaderAvatarPreviewUrl.value = currentAgent.avatarPreviewUrl || '';

        const response = await fileUploadApi.uploadAvatar(file);
        if (!isCurrentAgent(currentAgentId)) {
          return;
        }
        if (response.success) {
          const avatarPath = response.path || response.url || '';
          if (!avatarPath) {
            throw new Error(response.message || 'Upload path is unavailable');
          }
          const updatedAgent = await AgentService.update(currentAgentId, {
            ...currentAgent,
            avatar: avatarPath
          });
          if (!isCurrentAgent(currentAgentId)) {
            return;
          }
          if (updatedAgent) {
            agent.value = {
              ...updatedAgent,
              avatar: updatedAgent.avatar || avatarPath,
              avatarPreviewUrl: updatedAgent.avatarPreviewUrl || response.previewUrl
            };
          } else {
            agent.value.avatar = avatarPath;
            agent.value.avatarPreviewUrl = response.previewUrl;
          }
          ElMessage.success('头像上传成功');
        } else {
          throw new Error(response.message || '上传失败');
        }
      } catch (error) {
        if (currentAgentId == null || isCurrentAgent(currentAgentId)) {
          ElMessage.error(`头像上传失败：${error instanceof Error ? error.message : '未知错误'}`);
        }
        if (currentAgentId != null && isCurrentAgent(currentAgentId)) {
          agent.value.avatar = originalHeaderAvatar.value;
          agent.value.avatarPreviewUrl = originalHeaderAvatarPreviewUrl.value;
        }
      } finally {
        if (shouldUpdateUploadState()) {
          headerUploading.value = false;
          if (headerFileInput.value) {
            headerFileInput.value.value = '';
          }
        }
      }
    };

    const handleMenuSelect = (index: string) => {
      if (index === 'go-run') {
        const id = routeAgentId.value || normalizeRouteValue(agent.value.id);
        if (!id) {
          ElMessage.error('智能体ID无效，请刷新后重试');
          return;
        }
        router.push({
          name: 'ai-agent_agent_run',
          query: { agentId: String(id) }
        });
        return;
      }
      activeMenuIndex.value = index;
    };

    const normalizedAgentType = computed(() => normalizeAgentType(agent.value.agentType));
    const isDataAnalysis = computed(() => normalizedAgentType.value === AGENT_TYPE.DATA_ANALYSIS);
    const isKnowledgeBase = computed(() => normalizedAgentType.value === AGENT_TYPE.KNOWLEDGE_BASE);
    const isCustomerService = computed(() => normalizedAgentType.value === AGENT_TYPE.CUSTOMER_SERVICE);
    const isOrchestrator = computed(() => normalizedAgentType.value === AGENT_TYPE.ORCHESTRATOR);
    const showDatasourceMenu = computed(() => isDataAnalysis.value || isCustomerService.value);
    const showSkillMenu = computed(() => !isOrchestrator.value);
    const showPresetMenu = computed(() => !isOrchestrator.value);

    const goBack = async () => {
      closeTab();
      await router.push('/ai-agent/agents');
    };

    const isPublished = computed(() => agent.value.status === 'published');
    const publishStateHint = computed(() => {
      if (agent.value.status === 'published') {
        return '当前配置已发布，编辑保存后会立即生效，可下线后停止运行与协作调用。';
      }
      if (agent.value.status === 'offline') {
        return '当前已下线，编辑保存后仍保持下线，发布后恢复运行与协作调用。';
      }
      return '当前待发布，编辑保存后仍保持待发布，发布后才会用于运行与协作调用。';
    });

    const getStatusText = (status?: string) => {
      const statusMap: Record<string, string> = {
        published: '已发布',
        draft: '待发布',
        offline: '已下线'
      };
      return statusMap[status || ''] || status || '-';
    };

    const getStatusClass = (status?: string) => {
      const classMap: Record<string, string> = {
        published: 'status-published',
        draft: 'status-draft',
        offline: 'status-offline'
      };
      return classMap[status || ''] || 'status-unknown';
    };

    const loadAgent = async (id: string) => {
      const requestSeq = ++detailRequestSeq;
      resetTransientStates();
      detailNotFound.value = false;
      if (!id) {
        detailLoading.value = false;
        detailNotFound.value = true;
        ElMessage.error('智能体ID无效，请返回列表后重试');
        return;
      }
      detailLoading.value = true;
      try {
        const loadedAgent = await AgentService.get(id);
        if (requestSeq !== detailRequestSeq) {
          return;
        }
        if (loadedAgent) {
          agent.value = loadedAgent;
          loadedAgentId.value = normalizeRouteValue(loadedAgent.id ?? id);
        } else {
          detailNotFound.value = true;
          throw new Error('Agent 不存在');
        }
      } catch (error) {
        if (requestSeq !== detailRequestSeq) {
          return;
        }
        detailNotFound.value = true;
        ElMessage.error('加载失败');
        console.error('加载失败:', error);
      } finally {
        if (requestSeq === detailRequestSeq) {
          detailLoading.value = false;
        }
      }
    };

    const handlePublishAgent = async () => {
      if (!agent.value.id) {
        ElMessage.error('发布失败：智能体 ID 不存在');
        return;
      }
      publishActionLoading.value = true;
      const currentAgentId = agent.value.id;
      try {
        const publishedAgent = await AgentService.publish(currentAgentId);
        if (!isCurrentAgent(currentAgentId)) {
          return;
        }
        if (publishedAgent) {
          agent.value = publishedAgent;
          ElMessage.success('智能体已发布');
        } else {
          ElMessage.error('发布失败：智能体不存在');
        }
      } catch (error) {
        console.error('发布智能体失败:', error);
        if (isCurrentAgent(currentAgentId)) {
          ElMessage.error(`发布失败：${error instanceof Error ? error.message : '未知错误'}`);
        }
      } finally {
        if (shouldUpdateActionState(currentAgentId)) {
          publishActionLoading.value = false;
        }
      }
    };

    const handleOfflineAgent = async () => {
      if (!agent.value.id) {
        ElMessage.error('下线失败：智能体 ID 不存在');
        return;
      }
      try {
        await ElMessageBox.confirm(
          `确定要下线智能体"${agent.value.name}"吗？下线后将不能运行或作为协作智能体被调用。`,
          '下线智能体',
          {
            confirmButtonText: '确定下线',
            cancelButtonText: '取消',
            type: 'warning',
            dangerouslyUseHTMLString: false
          }
        );
      } catch {
        return;
      }

      publishActionLoading.value = true;
      const currentAgentId = agent.value.id;
      try {
        const offlineAgent = await AgentService.offline(currentAgentId);
        if (!isCurrentAgent(currentAgentId)) {
          return;
        }
        if (offlineAgent) {
          agent.value = offlineAgent;
          ElMessage.success('智能体已下线');
        } else {
          ElMessage.error('下线失败：智能体不存在');
        }
      } catch (error) {
        console.error('下线智能体失败:', error);
        if (isCurrentAgent(currentAgentId)) {
          ElMessage.error(`下线失败：${error instanceof Error ? error.message : '未知错误'}`);
        }
      } finally {
        if (shouldUpdateActionState(currentAgentId)) {
          publishActionLoading.value = false;
        }
      }
    };

    const handlePublishStatusAction = async () => {
      if (isPublished.value) {
        await handleOfflineAgent();
        return;
      }
      await handlePublishAgent();
    };

    watch(
      () => route.query.tab,
      () => {
        if (!isDetailRoute.value) {
          return;
        }
        applyRouteTab();
      }
    );

    watch(
      () => route.name,
      name => {
        if (name !== 'ai-agent_agent_detail') {
          resetTransientStates();
        }
      }
    );

    watch(
      routeAgentId,
      async id => {
        if (!isDetailRoute.value) {
          return;
        }
        applyRouteTab();
        await loadAgent(id);
      },
      { immediate: true }
    );

    return {
      ArrowLeft,
      Promotion,
      SwitchButton,
      agent,
      resolvedAgentId,
      loadedAgentPanelKey,
      detailLoading,
      detailNotFound,
      activeMenuIndex,
      handleMenuSelect,
      goBack,
      headerFileInput,
      headerUploading,
      showHeaderAvatarButton,
      agentAvatarUrl,
      triggerHeaderFileUpload,
      handleHeaderFileUpload,
      formatAgentType,
      getStatusClass,
      getStatusText,
      handlePublishStatusAction,
      isPublished,
      isOrchestrator,
      publishStateHint,
      publishActionLoading,
      showDatasourceMenu,
      showPresetMenu,
      showSkillMenu
    };
  }
});
</script>

<style scoped>
.agent-detail-page {
  min-height: auto;
}

.agent-detail-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  padding: 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  box-shadow: none;
}

.agent-head-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
  flex: 1 1 auto;
}

.agent-head-actions {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex-wrap: wrap;
}

.publish-action {
  min-width: 112px;
}

.detail-back-control {
  width: fit-content;
  min-height: 32px;
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-extra-light);
  font: inherit;
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  transition:
    background 0.18s ease,
    border-color 0.18s ease,
    color 0.18s ease;
}

.detail-back-control:hover,
.detail-back-control:focus-visible {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
  outline: none;
}

.avatar-wrapper {
  position: relative;
  width: 64px;
  height: 64px;
  flex: 0 0 64px;
  cursor: pointer;
  border-radius: 64px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  box-shadow: none;
  transition: all 0.3s ease;
}

.header-avatar {
  width: 100% !important;
  height: 100% !important;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.3s ease;
}

.avatar-wrapper:hover .header-avatar {
  opacity: 0.3;
}

.avatar-overlay-header {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: var(--el-overlay-color-light);
  animation: fadeIn 0.3s ease;
}

.agent-title-copy {
  min-width: 0;
}

.detail-eyebrow {
  display: block;
  margin-bottom: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
}

.agent-title-copy h1 {
  margin: 0 0 8px;
  color: var(--el-text-color-primary);
  font-size: 1.75rem;
  font-weight: 760;
  line-height: 1.2;
  letter-spacing: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.agent-meta-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.publish-state-hint {
  margin: 8px 0 0;
  max-width: 640px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}

.agent-type-chip,
.status-chip {
  display: inline-flex;
  align-items: center;
  min-height: 26px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid transparent;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.agent-type-chip {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.status-chip::before {
  content: '';
  width: 6px;
  height: 6px;
  margin-right: 6px;
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

.agent-detail-shell {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

.config-sidebar,
.config-panel {
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
  box-shadow: none;
  overflow: hidden;
}

.config-sidebar {
  position: sticky;
  top: 84px;
  padding: 8px;
}

.config-panel {
  min-width: 0;
  padding: 0;
}

.agent-menu {
  border-right: 0;
  background: transparent;
}

.agent-menu :deep(.el-menu-item-group__title) {
  padding: 16px 12px 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  font-weight: 600;
  line-height: 1;
}

.agent-menu :deep(.el-menu-item) {
  box-sizing: border-box;
  height: 38px;
  margin: 2px 0;
  padding: 0 12px !important;
  border-radius: 8px;
  color: var(--el-text-color-regular);
  font-weight: 600;
}

.agent-menu :deep(.el-menu-item::before) {
  display: none !important;
}

.agent-menu :deep(.el-menu-item:hover) {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}

.agent-menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  border: 1px solid var(--el-color-primary-light-7);
}

/* 右侧面板内边距统一由容器承担，面板组件自身不再各自设置，避免双层内边距 */
.config-panel-body {
  padding: 16px;
}

@media (max-width: 900px) {
  .agent-detail-page {
    padding: 0;
  }

  .agent-detail-shell {
    grid-template-columns: 1fr;
  }

  .config-sidebar {
    position: static;
  }

  .agent-head-left {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .agent-detail-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .agent-head-actions {
    width: 100%;
    justify-content: flex-start;
  }

  .agent-title-copy {
    flex: 1 1 220px;
  }
}

@media (max-width: 768px) {
  .agent-detail-head {
    padding: 14px;
  }

  .config-sidebar {
    padding: 8px;
  }

  .config-panel-body {
    padding: 14px;
  }
}

@keyframes fadeIn {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}
</style>
