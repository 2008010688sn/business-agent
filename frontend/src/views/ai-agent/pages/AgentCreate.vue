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
    <main class="agent-create-page flex flex-col gap-8px">
      <ElCard
        class="card-wrapper"
        shadow="never"
        body-class="!p-14px flex items-start justify-between gap-12px lt-md:flex-col lt-md:items-stretch"
      >
        <div class="create-title-copy">
          <h1 class="text-20px text-primary font-bold leading-28px">创建智能体</h1>
          <p class="mt-6px text-13px text-[var(--el-text-color-secondary)]">
            配置智能体基础信息、类型和默认模型，创建后可继续完善数据源、知识库和运行策略。
          </p>
        </div>
        <ElButton class="detail-back-control" native-type="button" aria-label="返回智能体列表" @click="goBack">
          <ElIcon class="mr-3px"><component :is="ArrowLeft" /></ElIcon>
          <span>返回列表</span>
        </ElButton>
      </ElCard>

      <div class="create-form-wrapper flex flex-col gap-8px">
        <ElCard class="section-card card-wrapper" shadow="never" body-class="!p-16px">
          <div class="section-header">
            <h3>创建智能体</h3>
            <p>配置专属智能体，创建后默认为待发布，完成配置后可在详情页发布。</p>
          </div>

          <div class="form-group">
            <label>头像设置</label>
            <div class="avatar-upload">
              <div class="avatar-preview">
                <img :src="agentForm.avatar" alt="智能体头像" @load="handleImageLoad" @error="handleImageError" />
              </div>
              <div class="avatar-controls">
                <div class="avatar-buttons">
                  <ElButton @click="regenerateAvatar">
                    <ElIcon class="mr-3px"><Refresh /></ElIcon>
                    重新生成
                  </ElButton>
                  <ElButton :disabled="uploading" @click="triggerFileUpload">
                    <ElIcon v-if="!uploading" class="mr-3px"><Upload /></ElIcon>
                    <ElIcon v-if="uploading" class="mr-3px"><Loading /></ElIcon>
                    {{ uploading ? '上传中...' : '上传图片' }}
                  </ElButton>
                  <input
                    ref="fileInput"
                    type="file"
                    accept="image/*"
                    style="display: none"
                    @change="handleFileUpload"
                  />
                </div>
              </div>
            </div>
          </div>

          <ElRow :gutter="20">
            <ElCol :span="24">
              <div class="form-item">
                <label>
                  智能体名称
                  <span class="required-mark">*</span>
                </label>
                <ElInput v-model="agentForm.name" placeholder="请输入智能体名称" />
              </div>
            </ElCol>
          </ElRow>

          <ElRow :gutter="20">
            <ElCol :span="12">
              <div class="form-item">
                <label>
                  Agent 类型
                  <span class="required-mark">*</span>
                </label>
                <ElSelect v-model="agentForm.agentType" style="width: 100%">
                  <ElOption
                    v-for="option in agentTypeOptions"
                    :key="option.value"
                    :label="option.label"
                    :value="option.value"
                  />
                </ElSelect>
              </div>
            </ElCol>
            <ElCol :span="12">
              <div class="form-item">
                <label>默认模型</label>
                <ElSelect
                  v-model="agentForm.chatModelConfigId"
                  placeholder="可创建后再配置"
                  clearable
                  filterable
                  style="width: 100%"
                >
                  <ElOption
                    v-for="model in chatModels"
                    :key="model.id"
                    :label="formatModelLabel(model)"
                    :value="model.id"
                  />
                </ElSelect>
              </div>
            </ElCol>
          </ElRow>

          <ElRow :gutter="20">
            <ElCol :span="12">
              <div class="form-item">
                <label>总运行超时（秒）</label>
                <ElInputNumber
                  v-model="agentForm.runtimeTimeoutSeconds"
                  class="runtime-timeout-input"
                  :min="1"
                  :step="10"
                  style="width: 100%"
                />
              </div>
            </ElCol>
          </ElRow>

          <ElRow :gutter="20">
            <ElCol :span="24">
              <div class="form-item">
                <label>描述</label>
                <ElInput v-model="agentForm.description" :rows="4" type="textarea" placeholder="请输入智能体描述" />
              </div>
            </ElCol>
          </ElRow>

          <ElRow :gutter="20">
            <ElCol :span="24">
              <div class="form-item">
                <label>系统提示词</label>
                <ElInput v-model="agentForm.prompt" :rows="4" type="textarea" placeholder="请输入系统提示词" />
              </div>
            </ElCol>
          </ElRow>

          <ElRow :gutter="20">
            <ElCol :span="24">
              <div class="form-item">
                <label>
                  标签
                  <span class="required-mark">*</span>
                </label>
                <ElInput v-model="agentForm.tags" placeholder="多个标签用逗号分隔" />
              </div>
            </ElCol>
          </ElRow>
          <div class="form-actions">
            <ElButton @click="goBack">取消</ElButton>
            <ElButton type="primary" :loading="loading" @click="createAgent">
              <ElIcon class="mr-3px"><component :is="Check" /></ElIcon>
              {{ loading ? '创建中...' : '创建智能体' }}
            </ElButton>
          </div>
        </ElCard>
      </div>
    </main>
  </BaseLayout>
</template>

<script lang="ts">
import { defineComponent, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { ArrowLeft, Check, Loading, Refresh, Upload } from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import { AGENT_TYPE, AGENT_TYPE_OPTIONS } from '@/views/ai-agent/constants/agentTypes';
import agentService from '@/views/ai-agent/services/agent';
import { fileUploadApi } from '@/views/ai-agent/services/fileUpload';
import { useCommonMixin } from '@/mixins/composition.js';
import modelConfigService, {
  isChatModelConfig,
  type ModelConfig,
  type ModelConfigId
} from '@/views/ai-agent/services/modelConfig';

export default defineComponent({
  name: 'AgentCreate',
  components: {
    BaseLayout
  },
  setup() {
    const router = useRouter();
    const { closeTab } = useCommonMixin();
    const loading = ref(false);
    const fileInput = ref<HTMLInputElement | null>(null);
    const uploading = ref(false);
    const avatarStoragePath = ref('');
    const chatModels = ref<ModelConfig[]>([]);

    const agentForm = reactive({
      name: '',
      description: '',
      avatar: '',
      agentType: AGENT_TYPE.DATA_ANALYSIS,
      chatModelConfigId: undefined as ModelConfigId | undefined,
      tags: '',
      prompt: '',
      runtimeTimeoutSeconds: 120,
      humanReviewEnabled: false
    });

    onMounted(() => {
      agentForm.avatar = generateFallbackAvatar();
      loadChatModels();
    });

    const loadChatModels = async () => {
      try {
        chatModels.value = (await modelConfigService.list()).filter(isChatModelConfig);
      } catch (error) {
        console.error('加载对话模型失败:', error);
        chatModels.value = [];
        ElMessage.warning('对话模型列表加载失败，可创建后再配置');
      }
    };

    const formatModelLabel = (model: ModelConfig) => {
      const activeText = model.isActive ? ' / 平台默认' : '';
      return `${model.modelName} / ${model.provider}${activeText}`;
    };

    const generateFallbackAvatar = (): string => {
      const colors = ['17492F', '167243', '7B9F42', '52685A', '315846', '8A6F32', '2F7D60', '6F9338'];
      const randomColor = colors[Math.floor(Math.random() * colors.length)];
      const letters = ['AI', '数据', '智能', 'DA', 'BI', 'ML', 'DL', 'NL'];
      const randomLetter = letters[Math.floor(Math.random() * letters.length)];

      const svg = `<svg width="200" height="200" xmlns="http://www.w3.org/2000/svg">
        <rect width="200" height="200" fill="#${randomColor}"/>
        <text x="100" y="120" font-family="Arial, sans-serif" font-size="48" font-weight="bold" text-anchor="middle" fill="white">${randomLetter}</text>
      </svg>`;

      return `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
    };

    const closeCurrentCreateTab = () => {
      closeTab();
    };

    const goBack = async () => {
      closeCurrentCreateTab();
      await router.push('/ai-agent/agents');
    };

    const finishCreate = async (agentId?: string) => {
      closeCurrentCreateTab();
      await router.push(
        agentId
          ? {
              name: 'ai-agent_agent_detail',
              query: { id: String(agentId) }
            }
          : '/ai-agent/agents'
      );
    };

    const regenerateAvatar = () => {
      avatarStoragePath.value = '';
      agentForm.avatar = generateFallbackAvatar();
    };

    const triggerFileUpload = () => {
      fileInput.value?.click();
    };

    const handleFileUpload = async (event: Event) => {
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

      try {
        uploading.value = true;

        const response = await fileUploadApi.uploadAvatar(file);

        if (response.success) {
          avatarStoragePath.value = response.path || response.url || '';
          agentForm.avatar = response.previewUrl || generateFallbackAvatar();
          ElMessage.success('头像上传成功');
        } else {
          throw new Error(response.message || '上传失败');
        }
      } catch (error) {
        console.error('头像上传失败:', error);
        ElMessage.error(`头像上传失败: ${error instanceof Error ? error.message : '未知错误'}`);
        agentForm.avatar = generateFallbackAvatar();
      } finally {
        uploading.value = false;
        if (fileInput.value) {
          fileInput.value.value = '';
        }
      }
    };

    const handleImageLoad = () => {
      console.log('头像图片加载成功');
    };

    const handleImageError = () => {
      console.error('头像图片加载失败');
      agentForm.avatar = generateFallbackAvatar();
    };

    const createAgent = async () => {
      if (!agentForm.name.trim() || !agentForm.agentType || !agentForm.tags.trim()) {
        ElMessage.error('请填写必要的字段');
        return;
      }

      try {
        loading.value = true;

        const agentData = {
          name: agentForm.name.trim(),
          description: agentForm.description.trim(),
          avatar: avatarStoragePath.value.trim(),
          agentType: agentForm.agentType,
          chatModelConfigId: agentForm.chatModelConfigId,
          tags: agentForm.tags.trim(),
          prompt: agentForm.prompt.trim(),
          runtimeTimeoutSeconds: agentForm.runtimeTimeoutSeconds,
          status: 'draft',
          humanReviewEnabled: agentForm.humanReviewEnabled === true
        };

        const result = await agentService.create(agentData);

        ElMessage.success('智能体创建成功，状态：待发布');
        await finishCreate(result.id);
      } catch (error) {
        console.error('创建智能体失败', error);
        ElMessage.error('创建失败，请重试');
      } finally {
        loading.value = false;
      }
    };

    return {
      ArrowLeft,
      Check,
      Refresh,
      Upload,
      Loading,
      agentForm,
      loading,
      fileInput,
      uploading,
      goBack,
      regenerateAvatar,
      triggerFileUpload,
      handleFileUpload,
      handleImageLoad,
      handleImageError,
      chatModels,
      formatModelLabel,
      agentTypeOptions: AGENT_TYPE_OPTIONS,
      createAgent
    };
  }
});
</script>

<style scoped>
.agent-create-page {
  min-height: auto;
}

.detail-back-control {
  width: fit-content;
  min-height: 32px;
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

.create-title-copy {
  min-width: 0;
}

.create-form-wrapper {
  max-width: none;
}

.section-card {
  border-color: var(--el-border-color-light);
  background: var(--el-bg-color);
  box-shadow: none;
}

.section-header {
  margin-bottom: 16px;
}

.section-header h3 {
  margin: 0 0 4px;
  color: var(--el-text-color-primary);
  font-size: 16px;
  font-weight: 600;
  line-height: 22px;
}

.section-header p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.form-group {
  margin-bottom: 20px;
}

.form-group label {
  display: block;
  margin-bottom: 10px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
}

.form-item {
  margin-bottom: 20px;
}

.form-item label {
  display: block;
  margin-bottom: 10px;
  color: var(--el-text-color-primary);
  font-size: 14px;
  font-weight: 600;
}

.required-mark {
  margin-left: 2px;
  color: var(--el-color-danger);
  font-weight: 600;
}

.runtime-timeout-input {
  --agent-input-number-control-width: 42px;
  box-sizing: border-box;
  overflow: hidden;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  transition: border-color var(--el-transition-duration-fast);
}

.runtime-timeout-input:hover {
  border-color: var(--el-border-color-hover);
}

.runtime-timeout-input:focus-within {
  border-color: var(--el-color-primary);
}

.runtime-timeout-input :deep(.el-input-number__decrease),
.runtime-timeout-input :deep(.el-input-number__increase) {
  top: 0;
  bottom: 0;
  width: var(--agent-input-number-control-width);
  height: auto;
  border-radius: 0;
  background: var(--el-fill-color-light);
}

.runtime-timeout-input :deep(.el-input-number__decrease) {
  left: 0;
  border-top-left-radius: var(--el-border-radius-base);
  border-bottom-left-radius: var(--el-border-radius-base);
}

.runtime-timeout-input :deep(.el-input-number__increase) {
  right: 0;
  border-top-right-radius: var(--el-border-radius-base);
  border-bottom-right-radius: var(--el-border-radius-base);
}

.runtime-timeout-input :deep(.el-input__wrapper) {
  padding-right: calc(var(--agent-input-number-control-width) + 8px);
  padding-left: calc(var(--agent-input-number-control-width) + 8px);
  border-radius: 0;
  box-shadow: none;
}

.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 15px;
  justify-content: flex-end;
}

.avatar-upload {
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.avatar-preview {
  width: 84px;
  height: 84px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  box-shadow: none;
}

.avatar-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.avatar-controls {
  flex: 1;
}

.avatar-buttons {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

@media (max-width: 768px) {
  .avatar-upload {
    flex-direction: column;
    align-items: flex-start;
  }

  .form-actions {
    flex-direction: column;
  }
}
</style>
