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
  <div class="access-api">
    <section class="section">
      <div class="section-head">
        <div>
          <h3>访问 API Key</h3>
          <p class="desc">为该智能体生成并管理 API Key，用于外部系统访问。</p>
        </div>
        <ElTag v-if="apiKey" :type="apiKeyEnabled ? 'success' : 'info'" effect="plain">
          {{ apiKeyEnabled ? '已启用' : '已禁用' }}
        </ElTag>
      </div>

      <div class="card">
        <div class="key-status">
          <div class="key-mark">
            <ElIcon><component :is="Key" /></ElIcon>
          </div>
          <div class="key-copy">
            <div class="label">当前 Key</div>
            <ElInput v-model="displayKey" class="key-input" readonly placeholder="尚未生成 API Key" />
          </div>
          <div class="switch-area">
            <span>启用状态</span>
            <ElSwitch v-model="apiKeyEnabled" :disabled="!apiKey" @change="handleToggle" />
          </div>
        </div>

        <div class="key-actions">
          <ElButton class="create-action action-button" :loading="loading.generate" @click="handleGenerate">
            <ElIcon class="button-glyph"><component :is="Key" /></ElIcon>
            <span>{{ apiKey ? '重新生成' : '生成 Key' }}</span>
          </ElButton>
          <ElButton
            class="refresh-action action-button"
            :disabled="!apiKey"
            :loading="loading.reset"
            @click="handleReset"
          >
            <ElIcon class="button-glyph"><component :is="RefreshRight" /></ElIcon>
            <span>重置</span>
          </ElButton>
          <ElButton
            class="delete-action action-button"
            :disabled="!apiKey"
            :loading="loading.delete"
            @click="handleDelete"
          >
            <ElIcon class="button-glyph"><component :is="Delete" /></ElIcon>
            <span>删除</span>
          </ElButton>
          <ElButton class="refresh-action action-button" :disabled="!apiKey || !canCopy" @click="handleCopy">
            <ElIcon class="button-glyph"><component :is="CopyDocument" /></ElIcon>
            <span>复制</span>
          </ElButton>
          <ElButton class="refresh-action action-button" :disabled="!apiKey" @click="toggleMask">
            <ElIcon class="button-glyph">
              <component :is="masked ? View : Hide" />
            </ElIcon>
            <span>{{ masked ? '显示' : '隐藏' }}</span>
          </ElButton>
        </div>

        <ElAlert
          v-if="!canCopy && apiKey"
          type="info"
          :closable="false"
          show-icon
          title="为安全起见，已生成/重置时才显示完整 Key，之后仅显示掩码。如需复制请重新生成/重置。"
        />
      </div>
    </section>
  </div>
</template>

<script lang="ts">
import { defineComponent, ref, computed, onMounted } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { CopyDocument, Delete, Hide, Key, RefreshRight, View } from '@element-plus/icons-vue';
import AgentService from '@/views/ai-agent/services/agent';

export default defineComponent({
  name: 'AgentAccessApi',
  props: {
    agentId: {
      type: String,
      required: false,
      default: null
    }
  },
  setup(props) {
    const route = useRoute();
    const resolvedAgentId = computed(() => {
      const rawAgentId = props.agentId ?? route.query.id;
      const normalizedAgentId = Array.isArray(rawAgentId) ? rawAgentId[0] : rawAgentId;
      return normalizedAgentId ? String(normalizedAgentId) : '';
    });

    const apiKey = ref<string | null>(null);
    const apiKeyEnabled = ref<boolean>(false);
    const masked = ref(true);
    const canCopy = ref(false);
    const loading = ref({
      generate: false,
      reset: false,
      delete: false,
      toggle: false,
      fetch: false
    });
    const maskKey = (key: string) => {
      if (!key) return '';
      if (key.startsWith('****')) return key;
      if (key.length <= 8) return '****';
      return `****${key.slice(-4)}`;
    };

    const displayKey = computed(() => {
      if (!apiKey.value) return '';
      return masked.value ? maskKey(apiKey.value) : apiKey.value;
    });

    const loadApiKey = async () => {
      loading.value.fetch = true;
      try {
        const res = await AgentService.getApiKey(resolvedAgentId.value);
        apiKey.value = res?.apiKey ?? null;
        apiKeyEnabled.value = Boolean(res?.apiKeyEnabled);
        masked.value = true;
        canCopy.value = false;
      } catch (e) {
        ElMessage.error('获取 API Key 失败');
      } finally {
        loading.value.fetch = false;
      }
    };

    const handleGenerate = async () => {
      loading.value.generate = true;
      try {
        const res = await AgentService.generateApiKey(resolvedAgentId.value);
        apiKey.value = res.apiKey;
        apiKeyEnabled.value = Boolean(res.apiKeyEnabled);
        masked.value = false;
        canCopy.value = true;
        ElMessage.success('已生成 API Key');
      } catch (e) {
        ElMessage.error('生成失败');
      } finally {
        loading.value.generate = false;
      }
    };

    const handleReset = async () => {
      if (!apiKey.value) {
        await handleGenerate();
        return;
      }
      loading.value.reset = true;
      try {
        const res = await AgentService.resetApiKey(resolvedAgentId.value);
        apiKey.value = res.apiKey;
        apiKeyEnabled.value = Boolean(res.apiKeyEnabled);
        masked.value = false;
        canCopy.value = true;
        ElMessage.success('已重置 API Key');
      } catch (e) {
        ElMessage.error('重置失败');
      } finally {
        loading.value.reset = false;
      }
    };

    const handleDelete = async () => {
      if (!apiKey.value) return;
      try {
        await ElMessageBox.confirm('确认删除当前 API Key？删除后需重新生成。', '提示', {
          confirmButtonText: '删除',
          cancelButtonText: '取消',
          type: 'warning'
        });
      } catch (e) {
        return;
      }

      loading.value.delete = true;
      try {
        const res = await AgentService.deleteApiKey(resolvedAgentId.value);
        apiKey.value = res.apiKey;
        apiKeyEnabled.value = Boolean(res.apiKeyEnabled);
        masked.value = true;
        canCopy.value = false;
        ElMessage.success('已删除 API Key');
      } catch (e) {
        ElMessage.error('删除失败');
      } finally {
        loading.value.delete = false;
      }
    };

    const handleCopy = async () => {
      if (!canCopy.value || !apiKey.value) {
        ElMessage.info('请重新生成或重置后复制完整 Key');
        return;
      }
      try {
        await navigator.clipboard.writeText(apiKey.value);
        ElMessage.success('已复制到剪贴板');
      } catch (e) {
        ElMessage.error('复制失败');
      }
    };

    const toggleMask = () => {
      if (!apiKey.value) return;
      masked.value = !masked.value;
    };

    const handleToggle = async (val: boolean) => {
      loading.value.toggle = true;
      try {
        const res = await AgentService.toggleApiKey(resolvedAgentId.value, val);
        apiKeyEnabled.value = Boolean(res.apiKeyEnabled);
        // 返回值可能是掩码
        apiKey.value = res.apiKey;
        masked.value = true;
        canCopy.value = false;
        ElMessage.success(val ? '已启用 API Key' : '已禁用 API Key');
      } catch (e) {
        apiKeyEnabled.value = !val;
        ElMessage.error('切换失败');
      } finally {
        loading.value.toggle = false;
      }
    };

    onMounted(() => {
      loadApiKey();
    });

    return {
      apiKey,
      apiKeyEnabled,
      masked,
      canCopy,
      CopyDocument,
      Delete,
      Hide,
      Key,
      loading,
      RefreshRight,
      displayKey,
      View,
      handleGenerate,
      handleReset,
      handleDelete,
      handleCopy,
      handleToggle,
      toggleMask
    };
  }
});
</script>

<style scoped>
.access-api {
  max-width: 920px;
  margin: 0 auto;
}

.section {
  margin-bottom: 26px;
}

.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.section h3 {
  margin: 0;
  color: #183627;
  font-size: 18px;
  font-weight: 600;
}

.desc {
  color: #6b7f70;
  margin: 4px 0 0;
  font-size: 13px;
}

.card {
  background: linear-gradient(135deg, rgba(237, 248, 239, 0.76), rgba(255, 255, 255, 0.96)), #ffffff;
  border: 1px solid #dfeadb;
  border-radius: 8px;
  padding: 18px;
  box-shadow: 0 10px 24px rgba(31, 63, 43, 0.06);
}

.key-status {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  margin-bottom: 14px;
}

.key-mark {
  width: 44px;
  height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #cfe0ca;
  border-radius: 8px;
  color: #167243;
  background: #f8fbf6;
  font-size: 20px;
}

.label {
  margin-bottom: 6px;
  color: #315846;
  font-size: 13px;
  font-weight: 600;
}

.switch-area {
  min-width: 110px;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
  color: #6b7f70;
  font-size: 12px;
  font-weight: 600;
}

.key-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding-top: 14px;
  border-top: 1px solid #e4eee0;
}

.key-input {
  width: 100%;
}

@media (max-width: 768px) {
  .key-status {
    grid-template-columns: 44px minmax(0, 1fr);
  }

  .switch-area {
    grid-column: 1 / -1;
    align-items: flex-start;
  }
}
</style>
