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
  <BaseLayout class="model-config-shell">
    <main class="model-config-page flex flex-col gap-8px">
      <ElTabs v-model="activeCenterTab" class="model-center-tabs">
        <ElTabPane label="模型配置" name="models" />
        <ElTabPane label="智能路由" name="routing" />
      </ElTabs>
      <!-- 主内容区域 -->
      <!-- 内容头部 -->
      <ElCard v-show="activeCenterTab === 'models'" class="card-wrapper" shadow="never" body-class="!p-14px">
        <div class="flex items-center justify-between gap-16px lt-md:flex-col lt-md:items-stretch">
          <div>
            <h1 class="text-20px text-primary font-bold leading-28px">模型中心</h1>
            <p class="mt-6px text-13px text-[var(--el-text-color-secondary)]">
              配置和管理AI模型参数，支持多种模型提供商
            </p>
          </div>
          <div class="header-stats grid grid-cols-7 gap-10px lt-lg:grid-cols-3 lt-md:grid-cols-2 lt-sm:grid-cols-1">
            <div class="stat-item">
              <div class="stat-number">{{ totalCount }}</div>
              <div class="stat-label">模型总数</div>
            </div>
            <div class="stat-item">
              <div class="stat-number">{{ activeCount }}</div>
              <div class="stat-label">已启用</div>
            </div>
            <div class="stat-item">
              <div class="stat-number">{{ chatCount }}</div>
              <div class="stat-label">对话模型</div>
            </div>
            <div class="stat-item">
              <div class="stat-number">{{ embeddingCount }}</div>
              <div class="stat-label">嵌入模型</div>
            </div>
            <div class="stat-item">
              <div class="stat-number">{{ audioCount }}</div>
              <div class="stat-label">语音模型</div>
            </div>
            <div class="stat-item">
              <div class="stat-number">{{ ttsCount }}</div>
              <div class="stat-label">语音合成</div>
            </div>
            <div class="stat-item">
              <div class="stat-number">{{ realtimeVoiceCount }}</div>
              <div class="stat-label">实时语音</div>
            </div>
          </div>
        </div>
      </ElCard>

      <!-- 操作区域 -->
      <ElCard v-show="activeCenterTab === 'models'" class="model-config-list-card card-wrapper" shadow="never">
        <div class="flex items-center justify-between gap-12px lt-md:flex-col lt-md:items-stretch">
          <div class="flex gap-8px">
            <ElButton type="primary" @click="showAddDialog">
              <ElIcon class="mr-3px"><component :is="Cpu" /></ElIcon>
              新增配置
            </ElButton>
            <ElButton @click="loadConfigs">
              <ElIcon class="mr-3px"><component :is="Refresh" /></ElIcon>
              刷新
            </ElButton>
          </div>
          <div class="filter-options flex justify-end lt-md:justify-start">
            <ElSelect v-model="activeFilter" class="w-300px lt-md:w-full" placeholder="筛选模型类型" clearable>
              <ElOption label="全部" value="" />
              <ElOption label="对话模型 (CHAT)" value="CHAT" />
              <ElOption label="嵌入模型 (EMBEDDING)" value="EMBEDDING" />
              <ElOption label="语音转写模型 (AUDIO_TRANSCRIPTION)" value="AUDIO_TRANSCRIPTION" />
              <ElOption label="语音合成模型 (TEXT_TO_SPEECH)" value="TEXT_TO_SPEECH" />
              <ElOption label="实时语音模型 (REALTIME_VOICE)" value="REALTIME_VOICE" />
            </ElSelect>
          </div>
        </div>
        <!-- 配置表格 -->
        <div class="model-config-table-wrap">
          <ElTable v-if="!loading && pageTotal > 0" border :data="configs" height="100%" stripe>
            <ElTableColumn prop="provider" label="提供商" width="120">
              <template #default="scope">
                <ElTag :type="getProviderTagType(scope.row.provider)" size="small">
                  {{ scope.row.provider }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="modelName" label="模型名称" width="180" />
            <ElTableColumn prop="modelType" label="模型类型" width="120">
              <template #default="scope">
                <ElTag :type="getModelTypeTagType(scope.row.modelType)" size="small">
                  {{ getModelTypeLabel(scope.row.modelType) }}
                </ElTag>
                <ElTag
                  v-if="scope.row.modelType === 'CHAT' && scope.row.supportVision"
                  type="warning"
                  size="small"
                  effect="plain"
                  style="margin-left: 6px"
                >
                  视觉
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="baseUrl" label="API地址" min-width="200" show-overflow-tooltip />
            <ElTableColumn label="密钥状态" width="110">
              <template #default="scope">
                <ElTag :type="scope.row.apiKeyConfigured ? 'success' : 'info'" size="small" effect="light">
                  {{ scope.row.apiKeyConfigured ? '已配置' : '未配置' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="路径配置" min-width="180" show-overflow-tooltip>
              <template #default="scope">
                <div v-if="scope.row.modelType === 'CHAT' && scope.row.completionsPath">
                  <ElTag type="primary" size="small">对话: {{ scope.row.completionsPath }}</ElTag>
                </div>
                <div v-else-if="scope.row.modelType === 'EMBEDDING' && scope.row.embeddingsPath">
                  <ElTag type="success" size="small">嵌入: {{ scope.row.embeddingsPath }}</ElTag>
                </div>
                <div v-else-if="scope.row.modelType === 'AUDIO_TRANSCRIPTION' && scope.row.transcriptionsPath">
                  <ElTag type="warning" size="small">转写: {{ scope.row.transcriptionsPath }}</ElTag>
                </div>
                <div v-else-if="scope.row.modelType === 'TEXT_TO_SPEECH'">
                  <ElTag type="info" size="small">语音: 独立 TTS 配置</ElTag>
                </div>
                <div v-else-if="scope.row.modelType === 'REALTIME_VOICE'">
                  <ElTag type="danger" size="small">Realtime: 独立实时语音配置</ElTag>
                </div>
                <div v-else>
                  <span class="text-muted">使用默认路径</span>
                </div>
              </template>
            </ElTableColumn>
            <ElTableColumn label="协议能力" min-width="220">
              <template #default="scope">
                <template v-if="scope.row.modelType === 'CHAT'">
                  <div class="protocol-tags">
                    <ElTag size="small" effect="plain">{{ scope.row.endpointDialect || 'OPENAI_COMPATIBLE' }}</ElTag>
                    <ElTag size="small" type="warning" effect="plain">
                      {{ scope.row.reasoningMode || 'AUTO' }} / {{ scope.row.reasoningLevel || 'DEFAULT' }}
                    </ElTag>
                    <ElTag size="small" type="success" effect="plain">
                      {{ scope.row.structuredOutputMode || 'AUTO' }}
                    </ElTag>
                  </div>
                </template>
                <span v-else class="text-muted">不适用</span>
              </template>
            </ElTableColumn>
            <ElTableColumn prop="temperature" label="温度" width="100">
              <template #default="scope">
                {{ scope.row.temperature || 0.0 }}
              </template>
            </ElTableColumn>
            <ElTableColumn prop="maxTokens" label="最大输出" width="120">
              <template #default="scope">
                {{ formatTokenCount(scope.row.maxTokens ?? DEFAULT_MAX_TOKENS) }}
              </template>
            </ElTableColumn>
            <ElTableColumn prop="contextWindowTokens" label="上下文窗口" width="120">
              <template #default="scope">
                {{ formatTokenCount(scope.row.contextWindowTokens ?? DEFAULT_CONTEXT_WINDOW_TOKENS) }}
              </template>
            </ElTableColumn>
            <ElTableColumn prop="isActive" label="状态" width="100">
              <template #default="scope">
                <ElTag :type="scope.row.isActive ? 'success' : 'info'" size="small" effect="light">
                  {{ scope.row.isActive ? '已启用' : '未启用' }}
                </ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="操作" width="236" fixed="right">
              <template #default="scope">
                <div class="table-actions">
                  <ElTooltip content="连接测试" placement="top">
                    <ElButton
                      type="success"
                      text
                      aria-label="连接测试"
                      :disabled="testingId === scope.row.id"
                      @click="handleTestConnection(scope.row)"
                    >
                      <ElIcon :class="{ 'is-loading': testingId === scope.row.id }">
                        <component :is="testingId === scope.row.id ? Loading : Connection" />
                      </ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip v-if="!scope.row.isActive" content="启用" placement="top">
                    <ElButton
                      type="primary"
                      aria-label="启用"
                      text
                      :disabled="activatingId === scope.row.id"
                      @click="handleActivate(scope.row.id, scope.row.modelType)"
                    >
                      <ElIcon :class="{ 'is-loading': activatingId === scope.row.id }">
                        <component :is="activatingId === scope.row.id ? Loading : Check" />
                      </ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip content="编辑" placement="top">
                    <ElButton type="warning" aria-label="编辑" text @click="handleEdit(scope.row)">
                      <ElIcon><component :is="Edit" /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip v-if="scope.row.modelType === 'TEXT_TO_SPEECH'" content="TTS配置" placement="top">
                    <ElButton type="primary" aria-label="TTS配置" text @click="handleTtsConfig(scope.row)">
                      <ElIcon><component :is="Microphone" /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip v-if="scope.row.modelType === 'AUDIO_TRANSCRIPTION'" content="ASR配置" placement="top">
                    <ElButton type="primary" aria-label="ASR配置" text @click="handleAsrConfig(scope.row)">
                      <ElIcon><component :is="Microphone" /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip v-if="scope.row.modelType === 'REALTIME_VOICE'" content="Realtime配置" placement="top">
                    <ElButton
                      type="primary"
                      aria-label="Realtime配置"
                      text
                      @click="handleRealtimeVoiceConfig(scope.row)"
                    >
                      <ElIcon><component :is="Connection" /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                  <ElTooltip content="删除" placement="top">
                    <ElButton text type="danger" aria-label="删除" @click="handleDelete(scope.row)">
                      <ElIcon><component :is="Delete" /></ElIcon>
                    </ElButton>
                  </ElTooltip>
                </div>
              </template>
            </ElTableColumn>
          </ElTable>
          <div v-if="loading" class="model-config-table-state loading-state">
            <ElSkeleton :rows="6" animated />
          </div>
          <div v-if="!loading && pageTotal === 0" class="model-config-table-state empty-state">
            <ElEmpty description="暂无模型配置">
              <template #image>
                <ElIcon size="60"><Cpu /></ElIcon>
              </template>
              <ElButton @click="showAddDialog">
                <ElIcon class="mr-3px"><component :is="Cpu" /></ElIcon>
                新增配置
              </ElButton>
            </ElEmpty>
          </div>
        </div>
        <ElPagination
          v-if="!loading && pageTotal > 0"
          v-model:current-page="pageQuery.current"
          v-model:page-size="pageQuery.size"
          class="pagination-bar"
          background
          layout="total, sizes, prev, pager, next, jumper"
          :page-sizes="[10, 20, 50, 100]"
          :total="pageTotal"
          @size-change="handlePageSizeChange"
          @current-change="loadConfigs"
        />
      </ElCard>
      <RoutePlatformSettings v-if="activeCenterTab === 'routing'" />
    </main>

    <!-- 新增/编辑对话框 -->
    <ElDialog v-model="dialogVisible" :title="dialogTitle" width="760px" :close-on-click-modal="false">
      <ElForm ref="formRef" :model="formData" :rules="formRules" label-width="120px">
        <ElFormItem label="提供商" prop="provider">
          <ElSelect
            v-model="formData.provider"
            placeholder="请选择提供商"
            style="width: 100%"
            @change="updateBaseUrlByProvider"
          >
            <ElOption label="DeepSeek" value="deepseek" />
            <ElOption label="Qwen" value="qwen" />
            <ElOption label="StepFun 阶跃" value="stepfun" />
            <ElOption label="智谱 GLM" value="zhipu" />
            <ElOption label="Moonshot / Kimi" value="moonshot" />
            <ElOption label="OpenAI" value="openai" />
            <ElOption label="Siliconflow" value="siliconflow" />
            <ElOption label="Custom" value="custom" />
          </ElSelect>
        </ElFormItem>

        <ElFormItem label="模型类型" prop="modelType">
          <ElRadioGroup v-model="formData.modelType">
            <ElRadio label="CHAT">对话模型</ElRadio>
            <ElRadio label="EMBEDDING">嵌入模型</ElRadio>
            <ElRadio label="AUDIO_TRANSCRIPTION">语音转写模型</ElRadio>
            <ElRadio label="TEXT_TO_SPEECH">语音合成模型</ElRadio>
            <ElRadio label="REALTIME_VOICE">实时语音模型</ElRadio>
          </ElRadioGroup>
        </ElFormItem>

        <ElFormItem label="模型名称" prop="modelName">
          <ElInput v-model="formData.modelName" :placeholder="modelNamePlaceholder" />
          <div v-if="modelNameTip" class="form-tip">{{ modelNameTip }}</div>
        </ElFormItem>

        <ElFormItem prop="apiKey">
          <template #label>
            <span class="api-key-label">
              <span v-if="isApiKeyRequired" class="api-key-required-mark">*</span>
              API密钥
            </span>
          </template>
          <ElInput v-model="formData.apiKey" type="password" show-password :placeholder="apiKeyPlaceholder" />
          <div v-if="isEditMode" class="form-tip">
            {{ formData.apiKeyConfigured ? '已配置，留空则保留原密钥' : '未配置，请输入新密钥' }}
          </div>
        </ElFormItem>

        <ElFormItem label="Base URL" prop="baseUrl">
          <ElInput v-model="formData.baseUrl" placeholder="请填写兼容 OpenAI 协议的 Base URL，通常不包含 /v1 后缀" />
        </ElFormItem>

        <ElFormItem v-if="formData.modelType === 'CHAT'" label="Completions路径" prop="completionsPath">
          <ElInput
            v-model="formData.completionsPath"
            placeholder="附加到base-url的路径。留空则使用默认值/v1/chat/completions"
          />
        </ElFormItem>

        <ElFormItem v-if="formData.modelType === 'CHAT'" label="支持视觉">
          <ElSwitch v-model="formData.supportVision" />
          <span class="form-tip" style="margin-left: 10px">开启后，该聊天模型可用于图片和文字混合输入。</span>
        </ElFormItem>

        <template v-if="formData.modelType === 'CHAT'">
          <ElDivider content-position="left">端点与推理协议</ElDivider>
          <div class="protocol-config-grid">
            <ElFormItem label="端点方言" prop="endpointDialect">
              <ElSelect
                v-model="formData.endpointDialect"
                :disabled="capabilityLoading || !capabilityReady"
                :loading="capabilityLoading"
                style="width: 100%"
                @change="handleEndpointDialectChange"
              >
                <ElOption
                  v-for="option in endpointDialectOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </ElSelect>
              <div class="form-tip">按实际 API 端点选择，不能仅按模型厂商推断。</div>
            </ElFormItem>
            <ElFormItem label="能力 Profile" prop="capabilityProfile">
              <ElSelect
                v-model="formData.capabilityProfile"
                :disabled="capabilityLoading || !capabilityReady || capabilityProfileOptions.length === 0"
                :loading="capabilityLoading"
                style="width: 100%"
                @change="handleCapabilityProfileChange"
              >
                <ElOption
                  v-for="option in capabilityProfileOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="推理参数协议" prop="reasoningProtocol">
              <ElSelect
                v-model="formData.reasoningProtocol"
                :disabled="!currentCapabilityDescriptor"
                style="width: 100%"
                @change="handleReasoningProtocolChange"
              >
                <ElOption v-for="option in reasoningProtocolOptions" :key="option.value" v-bind="option" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="思考模式" prop="reasoningMode">
              <ElSelect v-model="formData.reasoningMode" :disabled="reasoningSettingsDisabled" style="width: 100%">
                <ElOption v-for="option in reasoningModeOptions" :key="option.value" v-bind="option" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="思考级别" prop="reasoningLevel">
              <ElSelect
                v-model="formData.reasoningLevel"
                :disabled="reasoningSettingsDisabled || formData.reasoningMode === 'DISABLED'"
                clearable
                placeholder="按端点默认"
                style="width: 100%"
              >
                <ElOption v-for="option in reasoningLevelOptions" :key="option.value" v-bind="option" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="思考预算 Token" prop="reasoningBudgetTokens">
              <ElInputNumber
                v-model="formData.reasoningBudgetTokens"
                :disabled="
                  reasoningSettingsDisabled || formData.reasoningMode === 'DISABLED' || !reasoningBudgetSupported
                "
                :min="1"
                :max="2147483647"
                :step="128"
                controls-position="right"
                placeholder="按端点默认"
                style="width: 100%"
              />
            </ElFormItem>
            <ElFormItem label="输出 Token 字段" prop="tokenLimitMode">
              <ElSelect
                v-model="formData.tokenLimitMode"
                :disabled="!currentCapabilityDescriptor"
                clearable
                placeholder="按端点默认"
                style="width: 100%"
              >
                <ElOption v-for="option in tokenLimitModeOptions" :key="option.value" v-bind="option" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="温度参数" prop="temperaturePolicy">
              <ElSelect
                v-model="formData.temperaturePolicy"
                :disabled="!currentCapabilityDescriptor"
                clearable
                placeholder="按端点默认"
                style="width: 100%"
              >
                <ElOption v-for="option in temperaturePolicyOptions" :key="option.value" v-bind="option" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="结构化输出" prop="structuredOutputMode">
              <ElSelect
                v-model="formData.structuredOutputMode"
                :disabled="!currentCapabilityDescriptor"
                style="width: 100%"
              >
                <ElOption v-for="option in structuredOutputModeOptions" :key="option.value" v-bind="option" />
              </ElSelect>
            </ElFormItem>
            <ElFormItem label="保留思考内容" prop="preservedReasoningPolicy">
              <ElSegmented
                v-model="formData.preservedReasoningPolicy"
                :options="[{ label: '丢弃', value: 'DROP' }]"
                disabled
              />
            </ElFormItem>
          </div>
        </template>

        <ElFormItem v-if="formData.modelType === 'EMBEDDING'" label="Embeddings路径" prop="embeddingsPath">
          <ElInput
            v-model="formData.embeddingsPath"
            placeholder="附加到附加到base-url的路径。留空则使用默认值/v1/embeddings"
          />
        </ElFormItem>

        <ElFormItem
          v-if="formData.modelType === 'AUDIO_TRANSCRIPTION'"
          label="Transcriptions路径"
          prop="transcriptionsPath"
        >
          <ElInput v-model="formData.transcriptionsPath" placeholder="留空使用默认 /v1/audio/transcriptions" />
          <div class="form-tip">
            兼容 OpenAI audio transcriptions 的供应商可填写自定义转写路径；阿里 Qwen-ASR 会自动使用 chat/completions。
          </div>
        </ElFormItem>

        <ElFormItem label="温度" prop="temperature">
          <ElSlider
            v-model="formData.temperature"
            class="model-temperature-slider"
            :min="0"
            :max="2"
            :step="0.1"
            show-input
            show-input-controls
            :disabled="formData.temperaturePolicy === 'OMIT'"
          />
          <div class="form-tip">建议默认0。控制生成文本的随机性，值越高越随机</div>
        </ElFormItem>

        <ElFormItem label="最大输出（k Token）" prop="maxTokensK">
          <ElInputNumber
            v-model="formData.maxTokensK"
            class="model-number-input"
            :min="0.1"
            :max="2147483"
            :step="1"
            :precision="3"
            style="width: 100%"
          />
          <div class="form-tip">控制单次回复的最大生成长度，2 表示约 2k Token。</div>
        </ElFormItem>

        <ElFormItem label="上下文窗口（k Token）" prop="contextWindowTokensK">
          <ElInputNumber
            v-model="formData.contextWindowTokensK"
            class="model-number-input"
            :min="1"
            :max="1000000"
            :step="1"
            :precision="3"
            style="width: 100%"
          />
          <div class="form-tip">用于聊天上下文容量和用量提示，200 表示约 200k Token。</div>
        </ElFormItem>
      </ElForm>

      <ElDivider content-position="left">网络代理配置</ElDivider>

      <ElFormItem label="启用代理">
        <ElSwitch v-model="formData.proxyEnabled" />
        <span class="form-tip" style="margin-left: 10px">如果您的服务器处于受限内网，请开启代理以连接 AI 服务</span>
      </ElFormItem>

      <Transition name="el-fade-in">
        <div v-if="formData.proxyEnabled">
          <ElForm label-width="120px">
            <ElFormItem label="代理主机" prop="proxyHost" :required="formData.proxyEnabled">
              <ElInput v-model="formData.proxyHost" placeholder="例如: 127.0.0.1 或 proxy.example.com" />
            </ElFormItem>

            <ElFormItem label="代理端口" prop="proxyPort" :required="formData.proxyEnabled">
              <ElInputNumber
                v-model="formData.proxyPort"
                class="model-number-input"
                :min="1"
                :max="65535"
                controls-position="right"
                style="width: 100%"
              />
            </ElFormItem>

            <ElFormItem label="代理用户名" prop="proxyUsername">
              <ElInput v-model="formData.proxyUsername" placeholder="可选，代理服务器需要认证时填写" />
            </ElFormItem>

            <ElFormItem label="代理密码" prop="proxyPassword">
              <ElInput
                v-model="formData.proxyPassword"
                type="password"
                show-password
                :placeholder="proxyPasswordPlaceholder"
              />
              <div v-if="isEditMode" class="form-tip">
                {{ formData.proxyPasswordConfigured ? '已配置，留空则保留原代理密码' : '未配置，可输入代理密码' }}
              </div>
            </ElFormItem>
          </ElForm>
        </div>
      </Transition>

      <template #footer>
        <span class="dialog-footer">
          <ElButton @click="dialogVisible = false">取消</ElButton>
          <ElButton
            type="primary"
            :disabled="formData.modelType === 'CHAT' && (capabilityLoading || !capabilityReady)"
            :loading="submitting"
            @click="handleSubmit"
          >
            {{ isEditMode ? '更新' : '创建' }}
          </ElButton>
        </span>
      </template>
    </ElDialog>

    <TtsConfigDialog v-model="ttsDialogVisible" :model-config="selectedTtsModelConfig" @saved="loadConfigs" />
    <AsrConfigDialog v-model="asrDialogVisible" :model-config="selectedAsrModelConfig" @saved="loadConfigs" />
    <RealtimeVoiceModelConfigDialog
      v-model="realtimeVoiceDialogVisible"
      :model-config="selectedRealtimeVoiceModelConfig"
      @saved="loadConfigs"
    />
  </BaseLayout>
</template>

<script lang="ts">
import { defineComponent, ref, reactive, computed, onMounted, nextTick, watch } from 'vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { Check, Connection, Cpu, Delete, Edit, Loading, Microphone, Refresh } from '@element-plus/icons-vue';
import BaseLayout from '@/views/ai-agent/components/AiAgentPageShell.vue';
import AsrConfigDialog from '@/views/ai-agent/components/model/AsrConfigDialog.vue';
import RealtimeVoiceModelConfigDialog from '@/views/ai-agent/components/model/RealtimeVoiceModelConfigDialog.vue';
import TtsConfigDialog from '@/views/ai-agent/components/model/TtsConfigDialog.vue';
import RoutePlatformSettings from '@/views/ai-agent/components/routing/RoutePlatformSettings.vue';
import modelConfigService, {
  getModelCapabilityProfileOptions,
  getModelEndpointDialectOptions,
  normalizeModelCapabilitySelection,
  normalizeModelCapabilityProfileForDialect,
  findModelCapabilityDescriptor,
  type ModelConfig,
  type ModelConfigSummary,
  type ModelConfigId,
  type ModelCapabilityDescriptor,
  type ModelEndpointDialect,
  normalizeTokenCount
} from '@/views/ai-agent/services/modelConfig';

const TOKEN_UNIT = 1000;
const DEFAULT_MAX_TOKENS = 2000;
const DEFAULT_CONTEXT_WINDOW_TOKENS = 32768;

interface ModelConfigForm extends ModelConfig {
  maxTokensK?: number;
  contextWindowTokensK?: number;
}

export default defineComponent({
  name: 'ModelConfig',
  components: {
    BaseLayout,
    AsrConfigDialog,
    Cpu,
    RealtimeVoiceModelConfigDialog,
    RoutePlatformSettings,
    TtsConfigDialog
  },
  setup() {
    const loading = ref(true);
    const activeCenterTab = ref('models');
    const dialogVisible = ref(false);
    const isEditMode = ref(false);
    const submitting = ref(false);
    const activatingId = ref<ModelConfigId | null>(null);
    const testingId = ref<ModelConfigId | null>(null);
    const activeFilter = ref('');
    const configs = ref<ModelConfig[]>([]);
    const capabilityDescriptors = ref<ModelCapabilityDescriptor[]>([]);
    const capabilityLoading = ref(false);
    const capabilityLoadFailed = ref(false);
    const pageQuery = reactive({
      current: 1,
      size: 20
    });
    const pageTotal = ref(0);
    const modelSummary = ref<ModelConfigSummary>({});
    const formRef = ref<FormInstance>();
    const ttsDialogVisible = ref(false);
    const selectedTtsModelConfig = ref<ModelConfig | null>(null);
    const asrDialogVisible = ref(false);
    const selectedAsrModelConfig = ref<ModelConfig | null>(null);
    const realtimeVoiceDialogVisible = ref(false);
    const selectedRealtimeVoiceModelConfig = ref<ModelConfig | null>(null);

    // 表单数据
    const formData = ref<ModelConfigForm>({
      provider: '',
      apiKey: '',
      baseUrl: '',
      modelName: '',
      modelType: 'CHAT',
      temperature: 0.0,
      maxTokens: DEFAULT_MAX_TOKENS,
      contextWindowTokens: DEFAULT_CONTEXT_WINDOW_TOKENS,
      maxTokensK: toTokenK(DEFAULT_MAX_TOKENS),
      contextWindowTokensK: toTokenK(DEFAULT_CONTEXT_WINDOW_TOKENS),
      supportVision: false,
      completionsPath: '',
      endpointDialect: 'OPENAI_COMPATIBLE',
      capabilityProfile: 'AUTO',
      reasoningProtocol: 'AUTO',
      reasoningMode: 'AUTO',
      reasoningLevel: undefined,
      reasoningBudgetTokens: undefined,
      tokenLimitMode: undefined,
      temperaturePolicy: undefined,
      structuredOutputMode: 'AUTO',
      preservedReasoningPolicy: 'DROP',
      embeddingsPath: '',
      transcriptionsPath: '',
      isActive: false,
      proxyEnabled: false,
      proxyHost: '',
      proxyPort: 7890, // 给个常用的默认端口
      proxyUsername: '',
      proxyPassword: ''
    });

    // 提供商与API地址的映射
    const providerBaseUrlMap: Record<string, string> = {
      deepseek: 'https://api.deepseek.com',
      qwen: 'https://dashscope.aliyuncs.com/compatible-mode',
      stepfun: 'https://api.stepfun.com',
      zhipu: 'https://open.bigmodel.cn/api/paas',
      moonshot: 'https://api.moonshot.cn',
      openai: 'https://api.openai.com',
      siliconflow: 'https://api.siliconflow.cn',
      custom: '' // 自定义提供商不设置默认API地址
    };

    // 监听提供商变化，自动更新API地址
    const updateBaseUrlByProvider = (provider: string) => {
      if (provider && provider !== 'custom') {
        formData.value.baseUrl = providerBaseUrlMap[provider] || '';
      }
      clearApiKeyValidation();
    };

    // 表单验证规则
    const formRules: FormRules = {
      provider: [{ required: true, message: '请选择提供商', trigger: 'change' }],
      modelType: [{ required: true, message: '请选择模型类型', trigger: 'change' }],
      modelName: [{ required: true, message: '请输入模型名称', trigger: 'blur' }],
      apiKey: [
        {
          validator: (_rule, value, callback) => {
            if (!isApiKeyRequired.value) {
              callback();
            } else if (!value || value.trim() === '') {
              callback(new Error('请输入API密钥'));
            } else {
              callback();
            }
          },
          trigger: 'blur'
        }
      ],
      baseUrl: [{ required: true, message: '请输入API地址', trigger: 'blur' }],
      endpointDialect: [{ required: true, message: '请选择端点方言', trigger: 'change' }],
      capabilityProfile: [{ required: true, message: '请选择能力 Profile', trigger: 'change' }],
      reasoningProtocol: [{ required: true, message: '请选择推理参数协议', trigger: 'change' }],
      reasoningMode: [{ required: true, message: '请选择思考模式', trigger: 'change' }],
      structuredOutputMode: [{ required: true, message: '请选择结构化输出模式', trigger: 'change' }],
      temperature: [
        {
          type: 'number',
          min: 0,
          max: 2,
          message: '温度值必须在0-2之间',
          trigger: 'blur'
        }
      ],
      maxTokensK: [
        {
          type: 'number',
          min: 0.1,
          max: 2147483,
          message: '最大输出必须大于 0，且不能超过 Spring AI 当前支持范围',
          trigger: 'blur'
        }
      ],
      contextWindowTokensK: [
        {
          type: 'number',
          min: 1,
          max: 1000000,
          message: '上下文窗口必须在 1-1000000 k Token 之间',
          trigger: 'blur'
        }
      ],
      proxyHost: [
        {
          validator: (_rule, value, callback) => {
            if (formData.value.proxyEnabled && (!value || value.trim() === '')) {
              callback(new Error('启用代理时，必须填写代理主机地址'));
            } else {
              callback();
            }
          },
          trigger: 'blur'
        }
      ],
      proxyPort: [
        {
          validator: (_rule, value, callback) => {
            if (formData.value.proxyEnabled && !value) {
              callback(new Error('启用代理时，必须填写代理端口'));
            } else {
              callback();
            }
          },
          trigger: 'blur'
        }
      ]
    };

    // 计算属性
    const dialogTitle = computed(() => {
      return isEditMode.value ? '编辑模型配置' : '新增模型配置';
    });

    const totalCount = computed(() => Number(modelSummary.value.totalCount ?? 0));
    const activeCount = computed(() => Number(modelSummary.value.activeCount ?? 0));
    const chatCount = computed(() => Number(modelSummary.value.chatCount ?? 0));
    const embeddingCount = computed(() => Number(modelSummary.value.embeddingCount ?? 0));
    const audioCount = computed(() => Number(modelSummary.value.audioCount ?? 0));
    const ttsCount = computed(() => Number(modelSummary.value.ttsCount ?? 0));
    const realtimeVoiceCount = computed(() => Number(modelSummary.value.realtimeVoiceCount ?? 0));
    const isApiKeyRequired = computed(() => {
      return formData.value.provider !== 'custom' && !(isEditMode.value && formData.value.apiKeyConfigured);
    });
    const modelNamePlaceholder = computed(() => {
      if (formData.value.modelType === 'TEXT_TO_SPEECH') {
        return '例如: tts-1、cosyvoice、f5-tts，或本地 sidecar 模型名';
      }
      if (formData.value.modelType === 'AUDIO_TRANSCRIPTION') {
        return '例如: whisper-1、gpt-4o-mini-transcribe，或供应商要求的 provider/model';
      }
      if (formData.value.modelType === 'REALTIME_VOICE') {
        return '例如: qwen-omni-turbo-realtime、gpt-realtime，或供应商实时语音模型名';
      }
      if (formData.value.modelType === 'EMBEDDING') {
        return '例如: text-embedding-v4';
      }
      return '例如: gpt-4, deepseek-chat, qwen-plus';
    });
    const modelNameTip = computed(() => {
      if (formData.value.modelType === 'TEXT_TO_SPEECH') {
        return '语音合成只保存模型接入信息，音色、语速、参考录音请在 TTS 配置中维护。';
      }
      if (formData.value.modelType === 'REALTIME_VOICE') {
        return '一体化实时语音模型只作为后续 REALTIME 模式的供应商接入配置，WebSocket 地址请在 Realtime 配置中维护。';
      }
      if (formData.value.modelType !== 'AUDIO_TRANSCRIPTION') {
        return '';
      }
      return '语音转写不能使用聊天模型名；若供应商网关要求路由前缀，请按其要求填写 provider/model。';
    });
    const apiKeyPlaceholder = computed(() => {
      if (isEditMode.value && formData.value.apiKeyConfigured) return '已配置，留空则保留原密钥';
      return formData.value.provider === 'custom' ? '可选填' : '请输入API密钥';
    });
    const proxyPasswordPlaceholder = computed(() => {
      if (isEditMode.value && formData.value.proxyPasswordConfigured) return '已配置，留空则保留原代理密码';
      return '可选';
    });
    const currentCapabilityDescriptor = computed(() => {
      const endpointDialect = formData.value.endpointDialect || 'OPENAI_COMPATIBLE';
      const capabilityProfile = formData.value.capabilityProfile || 'AUTO';
      return findModelCapabilityDescriptor(endpointDialect, capabilityProfile, capabilityDescriptors.value);
    });

    const capabilityReady = computed(() => capabilityDescriptors.value.length > 0 && !capabilityLoadFailed.value);
    const endpointDialectOptions = computed(() => getModelEndpointDialectOptions(capabilityDescriptors.value));

    const capabilityProfileOptions = computed(() =>
      getModelCapabilityProfileOptions(
        formData.value.endpointDialect || 'OPENAI_COMPATIBLE',
        capabilityDescriptors.value
      )
    );

    const optionLabels: Record<string, string> = {
      AUTO: '按端点默认',
      REASONING_EFFORT: 'reasoning_effort',
      THINKING_OBJECT: 'thinking 对象',
      THINKING_OBJECT_WITH_EFFORT: 'thinking + reasoning_effort',
      ENABLE_THINKING: 'enable_thinking',
      ENABLE_THINKING_ONLY: 'enable_thinking (enable only)',
      NONE: '不发送推理参数',
      ENABLED: '启用',
      DISABLED: '关闭',
      MINIMAL: '极低',
      LOW: '低',
      MEDIUM: '中',
      HIGH: '高',
      XHIGH: '极高',
      MAX: '最大',
      MAX_TOKENS: 'max_tokens',
      MAX_COMPLETION_TOKENS: 'max_completion_tokens',
      SEND: '发送 temperature',
      OMIT: '省略 temperature',
      STRICT_JSON_SCHEMA: '严格 JSON Schema',
      JSON_OBJECT: 'JSON Object',
      PROMPT_JSON: 'Prompt JSON'
    };

    const labeledOptions = (values: readonly string[]) =>
      values.map(value => ({ label: optionLabels[value] || value, value }));

    const reasoningProtocolOptions = computed(() =>
      labeledOptions(currentCapabilityDescriptor.value?.reasoningProtocols || [])
    );
    const reasoningModeOptions = computed(() =>
      labeledOptions(currentCapabilityDescriptor.value?.reasoningModes || [])
    );
    const reasoningLevelOptions = computed(() =>
      labeledOptions(currentCapabilityDescriptor.value?.reasoningLevels || [])
    );
    const tokenLimitModeOptions = computed(() =>
      labeledOptions(currentCapabilityDescriptor.value?.tokenLimitModes || [])
    );
    const temperaturePolicyOptions = computed(() =>
      labeledOptions(currentCapabilityDescriptor.value?.temperaturePolicies || [])
    );
    const structuredOutputModeOptions = computed(() =>
      labeledOptions(currentCapabilityDescriptor.value?.structuredOutputModes || [])
    );
    const reasoningBudgetSupported = computed(
      () => currentCapabilityDescriptor.value?.reasoningBudgetSupported === true
    );
    const reasoningSettingsDisabled = computed(
      () => !currentCapabilityDescriptor.value || formData.value.reasoningProtocol === 'NONE'
    );

    const normalizeFormOptions = () => {
      if (!capabilityReady.value) {
        return;
      }
      const normalized = normalizeModelCapabilitySelection(
        {
          endpointDialect: formData.value.endpointDialect || 'OPENAI_COMPATIBLE',
          capabilityProfile: formData.value.capabilityProfile || 'AUTO',
          reasoningProtocol: formData.value.reasoningProtocol,
          reasoningMode: formData.value.reasoningMode,
          reasoningLevel: formData.value.reasoningLevel,
          reasoningBudgetTokens: formData.value.reasoningBudgetTokens,
          tokenLimitMode: formData.value.tokenLimitMode,
          temperaturePolicy: formData.value.temperaturePolicy,
          structuredOutputMode: formData.value.structuredOutputMode,
          preservedReasoningPolicy: formData.value.preservedReasoningPolicy
        },
        capabilityDescriptors.value
      );
      Object.assign(formData.value, normalized);
    };

    const handleEndpointDialectChange = (endpointDialect: ModelEndpointDialect) => {
      formData.value.endpointDialect = endpointDialect;
      formData.value.capabilityProfile = normalizeModelCapabilityProfileForDialect(
        endpointDialect,
        formData.value.capabilityProfile,
        capabilityDescriptors.value
      );
      normalizeFormOptions();
    };

    const handleCapabilityProfileChange = () => {
      normalizeFormOptions();
    };

    const handleReasoningProtocolChange = () => normalizeFormOptions();

    function trimTrailingZero(value: string) {
      return value.replace(/\.0$/, '').replace(/(\.\d*?)0+$/, '$1');
    }

    function formatTokenCount(value?: number | string | null): string {
      const tokens = normalizeTokenCount(value);
      if (tokens === undefined) {
        return '--';
      }
      if (tokens >= 1000000) {
        return `${trimTrailingZero((tokens / 1000000).toFixed(1))}M`;
      }
      if (tokens >= 1000) {
        return `${trimTrailingZero((tokens / 1000).toFixed(1))}k`;
      }
      return String(Math.round(tokens));
    }

    function toTokenK(tokens?: number | string | null): number {
      const tokenCount = normalizeTokenCount(tokens);
      if (tokenCount === undefined) {
        return 0;
      }
      return Math.round((tokenCount / TOKEN_UNIT) * 1000) / 1000;
    }

    function toTokenCount(value: number | undefined, fallback: number): number {
      if (typeof value !== 'number' || !Number.isFinite(value)) {
        return fallback;
      }
      return Math.round(value * TOKEN_UNIT);
    }

    const clearApiKeyValidation = async () => {
      await nextTick();
      formRef.value?.clearValidate('apiKey');
    };

    // 方法
    const loadCapabilityDescriptors = async () => {
      capabilityLoading.value = true;
      capabilityLoadFailed.value = false;
      try {
        capabilityDescriptors.value = await modelConfigService.capabilities();
        normalizeFormOptions();
      } catch (error) {
        capabilityDescriptors.value = [];
        capabilityLoadFailed.value = true;
        ElMessage.error('获取模型能力描述失败，暂不能保存对话模型配置');
      } finally {
        capabilityLoading.value = false;
      }
    };

    const loadConfigs = async () => {
      loading.value = true;
      try {
        const [pageResult, summaryResult] = await Promise.allSettled([
          modelConfigService.page({
            current: pageQuery.current,
            size: pageQuery.size,
            modelType: activeFilter.value || undefined
          }),
          modelConfigService.summary()
        ]);
        if (pageResult.status === 'fulfilled') {
          const page = pageResult.value;
          configs.value = page.data || [];
          pageTotal.value = page.total;
          pageQuery.current = page.pageNum || pageQuery.current;
          pageQuery.size = page.pageSize || pageQuery.size;
        } else {
          throw pageResult.reason;
        }
        modelSummary.value = summaryResult.status === 'fulfilled' ? summaryResult.value || {} : {};
      } catch (error) {
        ElMessage.error('获取模型配置列表失败，请检查网络！');
        configs.value = [];
        pageTotal.value = 0;
        modelSummary.value = {};
      } finally {
        loading.value = false;
      }
    };

    const handlePageSizeChange = () => {
      pageQuery.current = 1;
      loadConfigs();
    };

    const showAddDialog = () => {
      isEditMode.value = false;
      formData.value = {
        provider: '',
        apiKey: '',
        baseUrl: '',
        modelName: '',
        modelType: 'CHAT',
        temperature: 0.0,
        maxTokens: DEFAULT_MAX_TOKENS,
        contextWindowTokens: DEFAULT_CONTEXT_WINDOW_TOKENS,
        maxTokensK: toTokenK(DEFAULT_MAX_TOKENS),
        contextWindowTokensK: toTokenK(DEFAULT_CONTEXT_WINDOW_TOKENS),
        supportVision: false,
        completionsPath: '',
        endpointDialect: 'OPENAI_COMPATIBLE',
        capabilityProfile: 'AUTO',
        reasoningProtocol: 'AUTO',
        reasoningMode: 'AUTO',
        reasoningLevel: undefined,
        reasoningBudgetTokens: undefined,
        tokenLimitMode: undefined,
        temperaturePolicy: undefined,
        structuredOutputMode: 'AUTO',
        preservedReasoningPolicy: 'DROP',
        embeddingsPath: '',
        transcriptionsPath: '',
        isActive: false,
        apiKeyConfigured: false,
        proxyEnabled: false,
        proxyHost: '',
        proxyPort: 7890,
        proxyUsername: '',
        proxyPassword: '',
        proxyPasswordConfigured: false
      };
      normalizeFormOptions();
      dialogVisible.value = true;
      clearApiKeyValidation();
    };

    const handleEdit = (config: ModelConfig) => {
      isEditMode.value = true;
      const maxTokens = normalizeTokenCount(config.maxTokens) ?? DEFAULT_MAX_TOKENS;
      const contextWindowTokens = normalizeTokenCount(config.contextWindowTokens) ?? DEFAULT_CONTEXT_WINDOW_TOKENS;
      formData.value = {
        ...config,
        apiKey: '',
        proxyPassword: '',
        maxTokens,
        contextWindowTokens,
        maxTokensK: toTokenK(maxTokens),
        contextWindowTokensK: toTokenK(contextWindowTokens),
        supportVision: Boolean(config.supportVision),
        transcriptionsPath: config.transcriptionsPath || '',
        endpointDialect: config.endpointDialect || 'OPENAI_COMPATIBLE',
        capabilityProfile: config.capabilityProfile || 'AUTO',
        reasoningProtocol: config.reasoningProtocol || 'AUTO',
        reasoningMode: config.reasoningMode || 'AUTO',
        reasoningLevel: config.reasoningLevel,
        reasoningBudgetTokens: config.reasoningBudgetTokens,
        tokenLimitMode: config.tokenLimitMode,
        temperaturePolicy: config.temperaturePolicy,
        structuredOutputMode: config.structuredOutputMode || 'AUTO',
        preservedReasoningPolicy: 'DROP'
      };
      normalizeFormOptions();
      dialogVisible.value = true;
      clearApiKeyValidation();
    };

    const handleSubmit = async () => {
      if (!formRef.value) return;

      try {
        if (formData.value.modelType === 'CHAT') {
          if (capabilityLoading.value || !capabilityReady.value || !currentCapabilityDescriptor.value) {
            ElMessage.error('模型能力描述尚未加载完成，暂不能保存对话模型配置');
            return;
          }
          normalizeFormOptions();
        }
        await formRef.value.validate();
        submitting.value = true;
        const payload = buildSubmitPayload(formData.value);

        if (isEditMode.value) {
          // 更新配置
          const result = await modelConfigService.update(payload);
          if (result.success) {
            ElMessage.success('配置更新成功');
            dialogVisible.value = false;
            loadConfigs();
          } else {
            ElMessage.error(result.message || '配置更新失败');
          }
        } else {
          // 新增配置
          const result = await modelConfigService.add(payload);
          if (result.success) {
            ElMessage.success('配置添加成功');
            dialogVisible.value = false;
            loadConfigs();
          } else {
            ElMessage.error(result.message || '配置添加失败');
          }
        }
      } catch (error) {
        console.error('表单验证失败:', error);
      } finally {
        submitting.value = false;
      }
    };

    const buildSubmitPayload = (config: ModelConfigForm): ModelConfig => {
      const payload = {
        ...config,
        maxTokens: toTokenCount(config.maxTokensK, DEFAULT_MAX_TOKENS),
        contextWindowTokens: toTokenCount(config.contextWindowTokensK, DEFAULT_CONTEXT_WINDOW_TOKENS),
        preservedReasoningPolicy: 'DROP' as const
      };
      delete payload.maxTokensK;
      delete payload.contextWindowTokensK;
      delete payload.apiKeyConfigured;
      delete payload.proxyPasswordConfigured;
      payload.apiKey = payload.apiKey?.trim() || '';
      payload.proxyPassword = payload.proxyPassword?.trim() || '';
      return payload;
    };

    const handleDelete = async (config: ModelConfig) => {
      try {
        await ElMessageBox.confirm(
          `确定要删除配置 "${config.provider} - ${config.modelName}" 吗？此操作不可恢复。`,
          '删除确认',
          {
            confirmButtonText: '确定删除',
            cancelButtonText: '取消',
            type: 'warning'
          }
        );

        if (config.id) {
          const result = await modelConfigService.delete(config.id);
          if (result.success) {
            ElMessage.success('配置删除成功');
            loadConfigs();
          } else {
            ElMessage.error(result.message || '配置删除失败');
          }
        }
      } catch (error) {
        // 用户取消了删除操作
        console.log('删除操作已取消');
      }
    };

    const handleActivate = async (id?: ModelConfigId, modelType?: string) => {
      if (!id) return;

      try {
        // 如果是嵌入模型，显示确认提示
        if (modelType === 'EMBEDDING') {
          try {
            await ElMessageBox.confirm(
              '您正在更换嵌入模型，此操作风险较高！由于不同模型的向量空间不一致，切换后可能导致所有历史向量数据（含数据源、智能体知识、业务知识）将全部失效且无法检索。确定要执行吗？',
              '切换嵌入模型确认',
              {
                confirmButtonText: '确定继续',
                cancelButtonText: '取消',
                type: 'warning'
              }
            );
          } catch (error) {
            // 用户取消了操作
            console.log('用户取消了嵌入模型切换');
            return;
          }
        }

        activatingId.value = id;
        const result = await modelConfigService.activate(id);
        if (result.success) {
          ElMessage.success('模型启用成功');
          loadConfigs();
        } else {
          ElMessage.error(result.message || '模型启用失败');
        }
      } catch (error) {
        ElMessage.error('启用过程中发生错误');
      } finally {
        activatingId.value = null;
      }
    };

    const handleTestConnection = async (config: ModelConfig) => {
      if (!config.id) return;

      try {
        testingId.value = config.id;
        const result = await modelConfigService.testConnection(config);
        if (result.success) {
          ElMessage.success(result.message || '连接测试成功！');
        } else {
          ElMessage.error(result.message || '连接测试失败');
        }
      } catch (error) {
        ElMessage.error('连接测试过程中发生错误');
      } finally {
        testingId.value = null;
      }
    };

    const handleTtsConfig = (config: ModelConfig) => {
      selectedTtsModelConfig.value = config;
      ttsDialogVisible.value = true;
    };

    const handleAsrConfig = (config: ModelConfig) => {
      selectedAsrModelConfig.value = config;
      asrDialogVisible.value = true;
    };

    const handleRealtimeVoiceConfig = (config: ModelConfig) => {
      selectedRealtimeVoiceModelConfig.value = config;
      realtimeVoiceDialogVisible.value = true;
    };

    const getProviderTagType = (provider: string) => {
      const typeMap: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
        deepseek: 'success',
        qwen: 'warning',
        openai: 'primary',
        siliconflow: 'danger',
        custom: 'info'
      };
      return typeMap[provider] || 'info';
    };

    const getModelTypeLabel = (modelType: string) => {
      const labels: Record<string, string> = {
        CHAT: '对话模型',
        EMBEDDING: '嵌入模型',
        AUDIO_TRANSCRIPTION: '语音转写模型',
        TEXT_TO_SPEECH: '语音合成模型',
        REALTIME_VOICE: '实时语音模型'
      };
      return labels[modelType] || modelType;
    };

    const getModelTypeTagType = (modelType: string) => {
      const typeMap: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
        CHAT: 'primary',
        EMBEDDING: 'success',
        AUDIO_TRANSCRIPTION: 'warning',
        TEXT_TO_SPEECH: 'info',
        REALTIME_VOICE: 'danger'
      };
      return typeMap[modelType] || 'info';
    };

    watch(activeFilter, () => {
      pageQuery.current = 1;
      loadConfigs();
    });

    // 生命周期
    onMounted(() => {
      loadConfigs();
      loadCapabilityDescriptors();
    });

    return {
      loading,
      activeCenterTab,
      dialogVisible,
      isEditMode,
      submitting,
      activatingId,
      testingId,
      activeFilter,
      configs,
      capabilityDescriptors,
      capabilityLoading,
      capabilityReady,
      currentCapabilityDescriptor,
      endpointDialectOptions,
      pageQuery,
      pageTotal,
      ttsDialogVisible,
      selectedTtsModelConfig,
      asrDialogVisible,
      selectedAsrModelConfig,
      realtimeVoiceDialogVisible,
      selectedRealtimeVoiceModelConfig,
      DEFAULT_MAX_TOKENS,
      DEFAULT_CONTEXT_WINDOW_TOKENS,
      formData,
      formRef,
      formRules,
      totalCount,
      activeCount,
      chatCount,
      embeddingCount,
      audioCount,
      ttsCount,
      realtimeVoiceCount,
      isApiKeyRequired,
      modelNamePlaceholder,
      modelNameTip,
      apiKeyPlaceholder,
      proxyPasswordPlaceholder,
      reasoningSettingsDisabled,
      reasoningBudgetSupported,
      capabilityProfileOptions,
      reasoningProtocolOptions,
      reasoningModeOptions,
      reasoningLevelOptions,
      tokenLimitModeOptions,
      temperaturePolicyOptions,
      structuredOutputModeOptions,
      dialogTitle,
      loadConfigs,
      showAddDialog,
      handleEdit,
      handleSubmit,
      handleDelete,
      handleActivate,
      handleTestConnection,
      handleTtsConfig,
      handleAsrConfig,
      handleRealtimeVoiceConfig,
      handlePageSizeChange,
      formatTokenCount,
      getProviderTagType,
      getModelTypeLabel,
      getModelTypeTagType,
      updateBaseUrlByProvider,
      handleEndpointDialectChange,
      handleCapabilityProfileChange,
      handleReasoningProtocolChange,
      Check,
      Connection,
      Cpu,
      Delete,
      Edit,
      Loading,
      Microphone,
      Refresh
    };
  }
});
</script>

<style scoped>
.model-config-shell {
  display: flex;
  overflow: hidden;
  flex-direction: column;
  min-height: 0;
}

.model-config-page {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.model-config-list-card {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
}

.model-config-list-card :deep(.el-card__body) {
  display: flex;
  overflow: hidden;
  flex: 1 1 0;
  flex-direction: column;
  min-height: 0;
}

.model-config-table-wrap {
  overflow: hidden;
  flex: 1 1 0;
  min-height: 0;
  margin-top: 10px;
}

.model-config-table-wrap :deep(.el-table) {
  height: 100%;
}

.model-config-table-state {
  display: flex;
  box-sizing: border-box;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 0;
}

.model-config-table-state.loading-state {
  align-items: stretch;
  padding: 16px 0;
}

.stat-item {
  min-width: 86px;
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-extra-light);
}

.model-config-page > .card-wrapper:first-child h1 {
  margin: 0 !important;
  color: var(--el-text-color-primary) !important;
  font-size: 22px !important;
  font-weight: 700 !important;
  line-height: 28px !important;
  letter-spacing: 0 !important;
}

.model-config-page > .card-wrapper:first-child p {
  margin: 4px 0 0 !important;
  color: var(--el-text-color-secondary) !important;
  font-size: 13px !important;
  line-height: 1.5 !important;
}

.stat-number {
  color: var(--el-color-primary);
  font-size: 22px;
  font-weight: 600;
  line-height: 1;
}

.stat-label {
  margin-top: 0.25rem;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.empty-state {
  padding: 24px 0;
  border: 1px dashed var(--el-border-color-light);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}

.form-tip {
  font-size: 0.75rem;
  color: var(--el-text-color-secondary);
  margin-top: 0.25rem;
}

.protocol-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.protocol-config-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}

@media (max-width: 720px) {
  .protocol-config-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}

.api-key-label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.api-key-required-mark {
  color: var(--el-color-danger);
}

.model-number-input {
  --model-input-number-control-width: 35px;
  box-sizing: border-box;
  overflow: hidden;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  transition: border-color var(--el-transition-duration-fast);
}

.model-number-input:hover {
  border-color: var(--el-border-color-hover);
}

.model-number-input:focus-within {
  border-color: var(--el-color-primary);
}

.model-number-input :deep(.el-input__wrapper) {
  border-radius: 0;
  box-shadow: none;
}

.model-number-input:not(.is-controls-right) :deep(.el-input__wrapper) {
  padding-right: calc(var(--model-input-number-control-width) + 8px);
  padding-left: calc(var(--model-input-number-control-width) + 8px);
}

.model-number-input :deep(.el-input-number__decrease),
.model-number-input :deep(.el-input-number__increase) {
  background: var(--el-fill-color-light);
}

.model-number-input:not(.is-controls-right) :deep(.el-input-number__decrease),
.model-number-input:not(.is-controls-right) :deep(.el-input-number__increase) {
  top: 0;
  bottom: 0;
  width: var(--model-input-number-control-width);
  height: auto;
  border-radius: 0;
}

.model-number-input:not(.is-controls-right) :deep(.el-input-number__decrease) {
  left: 0;
  border-top-left-radius: var(--el-border-radius-base);
  border-bottom-left-radius: var(--el-border-radius-base);
}

.model-number-input:not(.is-controls-right) :deep(.el-input-number__increase) {
  right: 0;
  border-top-right-radius: var(--el-border-radius-base);
  border-bottom-right-radius: var(--el-border-radius-base);
}

.model-number-input.is-controls-right :deep(.el-input__wrapper) {
  padding-right: calc(var(--model-input-number-control-width) + 8px);
  padding-left: 15px;
}

.model-number-input.is-controls-right :deep(.el-input-number__decrease),
.model-number-input.is-controls-right :deep(.el-input-number__increase) {
  right: 0;
  width: var(--model-input-number-control-width);
  height: 50%;
  line-height: 1;
  border-radius: 0;
}

.model-number-input.is-controls-right :deep(.el-input-number__increase) {
  top: 0;
  bottom: auto;
  border-top-right-radius: var(--el-border-radius-base);
}

.model-number-input.is-controls-right :deep(.el-input-number__decrease) {
  top: auto;
  bottom: 0;
  border-bottom-right-radius: var(--el-border-radius-base);
}

.model-temperature-slider :deep(.el-slider__input) {
  --model-input-number-control-width: 35px;
  box-sizing: border-box;
  overflow: hidden;
  border: 1px solid var(--el-border-color);
  border-radius: var(--el-border-radius-base);
  background: var(--el-fill-color-blank);
  transition: border-color var(--el-transition-duration-fast);
}

.model-temperature-slider :deep(.el-slider__input:hover) {
  border-color: var(--el-border-color-hover);
}

.model-temperature-slider :deep(.el-slider__input:focus-within) {
  border-color: var(--el-color-primary);
}

.model-temperature-slider :deep(.el-slider__input .el-input__wrapper) {
  padding-right: calc(var(--model-input-number-control-width) + 8px);
  padding-left: calc(var(--model-input-number-control-width) + 8px);
  border-radius: 0;
  box-shadow: none;
}

.model-temperature-slider :deep(.el-slider__input .el-input-number__decrease),
.model-temperature-slider :deep(.el-slider__input .el-input-number__increase) {
  top: 0;
  bottom: 0;
  width: var(--model-input-number-control-width);
  height: auto;
  border-radius: 0;
  background: var(--el-fill-color-light);
}

.model-temperature-slider :deep(.el-slider__input .el-input-number__decrease) {
  left: 0;
  border-top-left-radius: var(--el-border-radius-base);
  border-bottom-left-radius: var(--el-border-radius-base);
}

.model-temperature-slider :deep(.el-slider__input .el-input-number__increase) {
  right: 0;
  border-top-right-radius: var(--el-border-radius-base);
  border-bottom-right-radius: var(--el-border-radius-base);
}

.text-muted {
  color: var(--el-text-color-secondary);
  font-style: italic;
}
</style>
