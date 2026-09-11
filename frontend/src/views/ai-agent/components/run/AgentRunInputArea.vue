<template>
  <div class="input-area">
    <div v-if="runState.businessClarificationLocked" class="business-clarify-lock-hint">
      {{ businessClarificationLockHint }}
    </div>
    <div v-if="runState.pendingClarify" class="clarify-banner">
      <div class="clarify-banner-header">
        <span class="clarify-banner-title">需要先澄清后再查询</span>
        <span class="clarify-banner-risk">riskLevel={{ runState.pendingClarify?.riskLevel }}</span>
      </div>
      <div class="clarify-banner-body">
        {{ runState.pendingClarify?.summary || '当前问题存在高歧义，下一条输入将作为补充信息或显式假设回填。' }}
      </div>
      <div
        v-if="runState.pendingClarify && runState.pendingClarify.missingDimensions.length > 0"
        class="clarify-banner-tags"
      >
        <ElTag
          v-for="dimension in runState.pendingClarify.missingDimensions"
          :key="dimension"
          size="small"
          effect="plain"
        >
          {{ dimension }}
        </ElTag>
      </div>
      <div
        v-if="runState.pendingClarify && runState.pendingClarify.suggestedAssumptions.length > 0"
        class="clarify-banner-assumptions"
      >
        <ElButton
          v-for="assumption in runState.pendingClarify.suggestedAssumptions"
          :key="assumption"
          size="small"
          text
          bg
          :disabled="runState.isStreaming || runState.isSubmittingMessage"
          @click="actions.clarifyAssumption(assumption)"
        >
          {{ assumption }}
        </ElButton>
      </div>
      <div class="clarify-banner-footer">
        <span>下一条输入会作为人工反馈补充给上一个高歧义问题，填写后请点击发送按钮提交。</span>
        <ElButton link type="primary" @click="actions.cancelPendingClarify()">改为新问题</ElButton>
      </div>
    </div>
    <div class="input-container">
      <AgentSwitcherFloat
        v-if="agentSwitchState.show"
        class="mt-8px"
        :available-agents="agentSwitchState.availableAgents"
        :current-agent-id="agentSwitchState.currentRunAgentId"
        :loading="agentSwitchState.isSwitchingAgent"
        @switch-agent="agent => actions.switchAgent?.(agent)"
      />
      <QueuedRequestList
        :requests="runState.queuedRequests"
        @clear="actions.clearQueuedRequests()"
        @guide="id => actions.guideQueuedRequest(id)"
        @remove="id => actions.removeQueuedRequest(id)"
      />
      <div class="input-shell mt-8px" :class="{ 'mr-8px mb-8px': agentSwitchState.show }">
        <input
          ref="imageFileInput"
          type="file"
          :accept="IMAGE_FILE_ACCEPT"
          multiple
          class="hidden-file-input"
          @change="handleImageFileSelect"
        />
        <input
          ref="documentFileInput"
          type="file"
          :accept="DOCUMENT_FILE_ACCEPT"
          multiple
          class="hidden-file-input"
          @change="handleDocumentFileSelect"
        />
        <ElInput
          ref="chatInputRef"
          v-model="inputValue"
          type="textarea"
          :rows="3"
          :placeholder="inputPlaceholder"
          :disabled="runState.isSubmittingMessage || runState.businessClarificationLocked"
          class="chat-input"
          @paste="handleInputPaste"
          @keydown.enter.exact.prevent="handleSendShortcut"
        />
        <div v-if="attachmentPreviews.length > 0" class="image-preview-list">
          <div
            v-for="preview in attachmentPreviews"
            :key="preview.url"
            class="image-preview-item"
            :class="{ 'file-preview-item': preview.kind !== 'image' }"
          >
            <img v-if="preview.kind === 'image'" :src="preview.url" :alt="preview.file.name" />
            <div v-else class="file-preview-chip" :title="preview.file.name">
              <ElIcon><DocumentIcon /></ElIcon>
              <span>{{ preview.file.name }}</span>
            </div>
            <ElButton
              circle
              class="image-preview-remove"
              title="移除附件"
              @click="removeSelectedAttachment(preview.url)"
            >
              <ElIcon><Close /></ElIcon>
            </ElButton>
          </div>
        </div>
        <div v-if="audioStatusText" class="audio-status-row" :class="`audio-status-${audioStatusTone}`">
          <span v-if="isRecordingAudio" class="audio-status-pulse"></span>
          <span>{{ audioStatusText }}</span>
        </div>
        <div v-if="showRealtimeVoiceStatus" class="realtime-voice-status-row">
          <span v-if="realtimeVoiceActive" class="audio-status-pulse"></span>
          <span class="realtime-voice-status-main">{{ realtimeVoiceStatusText }}</span>
          <ElButton
            v-if="realtimeVoiceCanGenerateDefault"
            link
            type="primary"
            :loading="realtimeVoiceReadinessLoading"
            @click="handleGenerateRealtimeDefault"
          >
            生成默认配置
          </ElButton>
          <span v-if="realtimeVoiceTranscript" class="realtime-voice-caption">我：{{ realtimeVoiceTranscript }}</span>
          <span v-if="realtimeVoiceAnswer" class="realtime-voice-caption">AI：{{ realtimeVoiceAnswer }}</span>
        </div>
        <div class="input-toolbar">
          <div class="input-toolbar-left">
            <ElPopover
              v-model:visible="chatModelPopoverVisible"
              placement="top-start"
              trigger="click"
              :width="280"
              popper-class="chat-model-popover"
              :disabled="runState.isSubmittingMessage"
            >
              <template #reference>
                <ElButton
                  text
                  bg
                  size="small"
                  class="chat-model-trigger"
                  :loading="modelState.chatModelsLoading"
                  :disabled="runState.isSubmittingMessage"
                >
                  <span class="chat-model-trigger-label">{{ currentChatModelLabel }}</span>
                  <ElIcon class="chat-model-trigger-icon"><ArrowDown /></ElIcon>
                </ElButton>
              </template>
              <div class="chat-model-menu">
                <ElButton
                  class="chat-model-option"
                  :class="{ active: modelState.chatModelConfigId === undefined }"
                  @click="handleSelectChatModel(undefined)"
                >
                  <span class="chat-model-option-name">默认</span>
                  <span class="chat-model-option-meta">{{ defaultChatModelMeta }}</span>
                </ElButton>
                <div class="chat-model-menu-divider"></div>
                <ElButton
                  v-for="model in modelState.chatModelOptions"
                  :key="model.id"
                  class="chat-model-option"
                  :class="{ active: modelState.chatModelConfigId === model.id }"
                  @click="handleSelectChatModel(model.id)"
                >
                  <span class="chat-model-option-name">{{ model.modelName }}</span>
                  <span class="chat-model-option-meta">
                    {{ model.provider }}{{ model.isActive ? ' - default' : '' }}
                  </span>
                </ElButton>
                <div
                  v-if="!modelState.chatModelsLoading && modelState.chatModelOptions.length === 0"
                  class="chat-model-empty"
                >
                  当前没有可切换模型
                </div>
              </div>
            </ElPopover>
          </div>
          <div class="input-toolbar-right">
            <ElTooltip content="本次回复先输出结果，再生成报告" placement="top">
              <ElButton
                round
                class="report-mode-trigger"
                :class="{ active: runState.responseMode === 'report' }"
                :disabled="runState.isSubmittingMessage"
                @click="actions.toggleReportMode()"
              >
                <ElIcon class="report-mode-trigger-icon"><Cloudy /></ElIcon>
                <span class="report-mode-trigger-text">分析报告</span>
              </ElButton>
            </ElTooltip>
            <ElPopover
              placement="top-end"
              trigger="click"
              :width="320"
              popper-class="chat-actions-popover"
              :disabled="runState.isSubmittingMessage"
            >
              <template #reference>
                <ElButton
                  text
                  bg
                  circle
                  class="chat-actions-trigger"
                  :disabled="runState.isSubmittingMessage"
                  title="更多功能"
                >
                  <ElIcon><Plus /></ElIcon>
                </ElButton>
              </template>
              <div class="chat-actions-panel">
                <ElButton class="chat-actions-option" :disabled="runState.isSubmittingMessage" @click="openImagePicker">
                  <ElIcon class="chat-actions-option-icon"><PictureIcon /></ElIcon>
                  <span class="chat-actions-option-main">
                    <span class="chat-actions-option-title">添加图片</span>
                    <span class="chat-actions-option-desc">支持 PNG、JPG、WebP，最多 3 张</span>
                  </span>
                </ElButton>
                <ElButton
                  class="chat-actions-option"
                  :disabled="runState.isSubmittingMessage"
                  @click="openDocumentPicker"
                >
                  <ElIcon class="chat-actions-option-icon"><DocumentIcon /></ElIcon>
                  <span class="chat-actions-option-main">
                    <span class="chat-actions-option-title">添加文件</span>
                    <span class="chat-actions-option-desc">PDF、Word、Excel、CSV、文本、日志或音频</span>
                  </span>
                </ElButton>
                <div class="chat-actions-setting">
                  <span class="chat-actions-option-main">
                    <span class="chat-actions-option-title">识别后自动发送</span>
                    <span class="chat-actions-option-desc">语音识别成功后直接发送到当前会话</span>
                  </span>
                  <ElSwitch
                    v-model="voiceAutoSend"
                    :disabled="isTranscribingAudio"
                    @change="handleVoiceAutoSendChange"
                  />
                </div>
                <div class="chat-actions-setting">
                  <span class="chat-actions-option-main">
                    <span class="chat-actions-option-title">回答时语音播报</span>
                    <span class="chat-actions-option-desc">{{ answerTtsStatusLabel }}</span>
                  </span>
                  <ElSwitch
                    :model-value="voiceState.answerTtsEnabled"
                    :loading="voiceState.answerTtsLoading"
                    @change="handleAnswerTtsChange"
                  />
                </div>
                <ElButton
                  v-if="voiceState.answerTtsPlaying || voiceState.answerTtsLoading"
                  class="chat-actions-option"
                  @click="actions.stopAnswerTts?.()"
                >
                  <ElIcon class="chat-actions-option-icon"><CircleClose /></ElIcon>
                  <span class="chat-actions-option-main">
                    <span class="chat-actions-option-title">停止播报</span>
                    <span class="chat-actions-option-desc">仅停止当前语音，不影响文字回答</span>
                  </span>
                </ElButton>
              </div>
            </ElPopover>
            <ContextUsagePopover
              :loading="contextState.loading"
              :error="contextState.error"
              :usage="contextState.usage"
              :current-model-label="currentChatModelLabel"
              :compression-summary="contextState.compressionSummary"
              :can-compress="contextState.canCompress"
              :compressing="contextState.compressing"
              @show="actions.loadSessionContextUsage()"
              @compress="actions.compressSessionContext()"
            />
            <ElButton
              text
              bg
              circle
              class="chat-actions-trigger"
              :class="{ active: isRecordingAudio }"
              :loading="isTranscribingAudio"
              :disabled="runState.isSubmittingMessage || isTranscribingAudio"
              :title="isRecordingAudio ? '停止录音' : '语音输入'"
              @click="toggleAudioRecording"
            >
              <ElIcon><Microphone /></ElIcon>
            </ElButton>
            <ElButton
              text
              bg
              circle
              class="chat-actions-trigger realtime-voice-trigger"
              :class="{ active: realtimeVoiceActive }"
              :loading="realtimeVoiceConnecting"
              :disabled="runState.isSubmittingMessage || isTranscribingAudio || isRecordingAudio"
              :title="realtimeVoiceButtonTitle"
              @click="toggleRealtimeVoice"
            >
              <ElIcon><Connection /></ElIcon>
            </ElButton>
            <ElButton
              v-if="realtimeVoiceActive"
              text
              bg
              circle
              class="chat-actions-trigger realtime-voice-interrupt"
              title="打断实时回答"
              @click="interruptRealtimeVoice"
            >
              <ElIcon><CircleClose /></ElIcon>
            </ElButton>
            <ElButton
              v-if="!runState.isStreaming"
              type="primary"
              :disabled="runState.isSubmittingMessage || isTranscribingAudio || runState.businessClarificationLocked"
              circle
              class="send-button"
              :title="sendButtonTitle"
              @click="actions.send()"
            >
              <ElIcon><Promotion /></ElIcon>
            </ElButton>
            <ElButton
              v-else
              type="danger"
              circle
              class="send-button stop-button-inline"
              title="取消"
              @click="actions.stopStreaming()"
            >
              <ElIcon><CircleClose /></ElIcon>
            </ElButton>
          </div>
        </div>
      </div>
      <PresetQuestions
        v-if="shouldShowPresetQuestions"
        :agentId="runState.agentId ?? ''"
        :onQuestionClick="question => actions.presetQuestionClick(question)"
        variant="inline"
        collapsible
        class="input-preset-questions"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue';
import { ElMessage } from 'element-plus';
import type { InputInstance } from 'element-plus';
import {
  ArrowDown,
  CircleClose,
  Close,
  Cloudy,
  Connection,
  Document as DocumentIcon,
  Microphone,
  Picture as PictureIcon,
  Plus,
  Promotion
} from '@element-plus/icons-vue';
import {
  DOCUMENT_FILE_ACCEPT,
  IMAGE_FILE_ACCEPT,
  MAX_CHAT_DOCUMENT_COUNT,
  MAX_CHAT_IMAGE_COUNT,
  resolveChatAttachmentType
} from '@/views/ai-agent/utils/chatAttachment';
import AgentSwitcherFloat from '@/views/ai-agent/components/run/AgentSwitcherFloat.vue';
import ContextUsagePopover from '@/views/ai-agent/components/run/ContextUsagePopover.vue';
import PresetQuestions from '@/views/ai-agent/components/run/PresetQuestions.vue';
import QueuedRequestList from '@/views/ai-agent/components/run/QueuedRequestList.vue';
import type { Agent } from '@/views/ai-agent/services/agent';
import AudioService from '@/views/ai-agent/services/audio';
import type { ChatMessage, ChatSession, SessionContextUsage } from '@/views/ai-agent/services/chat';
import { extractApiErrorMessage, getResponseStatus } from '@/views/ai-agent/services/common';
import type { ModelConfig, ModelConfigId } from '@/views/ai-agent/services/modelConfig';
import type { PendingClarifyState, QueuedRequestState } from '@/views/ai-agent/services/sessionStateManager';
import { useRealtimeVoiceConversation } from '@/views/ai-agent/utils/useRealtimeVoiceConversation';
import { BUSINESS_CLARIFICATION_INPUT_LOCK_HINT } from '@/views/ai-agent/utils/suggestedReplies';
import { localStg } from '@/utils/storage';

type ChatModelOption = ModelConfig & { id: ModelConfigId };
type ResponseMode = 'normal' | 'report';
type AgentRunInputState = {
  pendingClarify: PendingClarifyState | null;
  businessClarificationLocked?: boolean;
  isStreaming: boolean;
  isSubmittingMessage: boolean;
  queuedRequests: QueuedRequestState[];
  responseMode: ResponseMode;
  agentId?: string | null;
  currentSession: ChatSession | null;
  currentMessages: ChatMessage[];
};
type AgentRunInputModelState = {
  chatModelConfigId?: ModelConfigId;
  chatModelOptions: ChatModelOption[];
  chatModelsLoading: boolean;
  defaultChatModel?: ModelConfig;
};
type AgentRunInputDiagnosticsState = {
  canViewThinking: boolean;
  latestExplainRuntimeRequestId?: string | null;
  traceLoading: boolean;
  orchestrationTraceLoading: boolean;
};
type AgentRunInputContextState = {
  usage: SessionContextUsage | null;
  loading: boolean;
  compressing: boolean;
  error: string;
  compressionSummary: string;
  canCompress: boolean;
};
type AgentRunInputVoiceState = {
  answerTtsEnabled: boolean;
  answerTtsPlaying: boolean;
  answerTtsLoading: boolean;
  answerTtsStatusLabel: string;
};
type AgentRunInputAgentSwitchState = {
  show: boolean;
  availableAgents: Agent[];
  currentRunAgentId?: string | null;
  isSwitchingAgent: boolean;
};
type AgentRunInputActions = {
  send: () => void | Promise<void>;
  stopStreaming: () => void | Promise<void>;
  toggleReportMode: () => void;
  selectChatModel: (modelId: ModelConfigId | undefined) => void | Promise<void>;
  openLatestAnswerExplain: () => void | Promise<void>;
  openTraceDialog: () => void | Promise<void>;
  openOrchestrationTrace: () => void | Promise<void>;
  loadSessionContextUsage: () => void | Promise<void>;
  compressSessionContext: () => void | Promise<void>;
  clearQueuedRequests: () => void;
  guideQueuedRequest: (id: string) => void | Promise<void>;
  removeQueuedRequest: (id: string) => void;
  cancelPendingClarify: () => void;
  clarifyAssumption: (assumption: string) => void;
  presetQuestionClick: (question: string) => void | Promise<void>;
  switchAgent?: (agent: Agent) => void | Promise<void>;
  setAnswerTtsEnabled?: (enabled: boolean) => void;
  stopAnswerTts?: () => void;
  reloadCurrentMessages?: () => void | Promise<void>;
};
type AttachmentPreview = {
  file: File;
  url: string;
  kind: 'image' | 'file';
};
type AudioStatusTone = 'info' | 'warning';
type WebkitAudioWindow = Window &
  typeof globalThis & {
    webkitAudioContext?: typeof AudioContext;
  };

defineOptions({ name: 'AgentRunInputArea' });

const props = withDefaults(
  defineProps<{
    userInput: string;
    state: AgentRunInputState;
    modelState: AgentRunInputModelState;
    diagnosticsState: AgentRunInputDiagnosticsState;
    contextState: AgentRunInputContextState;
    voiceState?: AgentRunInputVoiceState;
    agentSwitchState?: AgentRunInputAgentSwitchState;
    showPresetQuestions?: boolean;
    actions: AgentRunInputActions;
  }>(),
  {
    voiceState: () => ({
      answerTtsEnabled: false,
      answerTtsPlaying: false,
      answerTtsLoading: false,
      answerTtsStatusLabel: ''
    }),
    agentSwitchState: () => ({
      show: false,
      availableAgents: [],
      currentRunAgentId: null,
      isSwitchingAgent: false
    }),
    showPresetQuestions: true
  }
);

const MAX_TRANSCRIPTION_AUDIO_SIZE = 20 * 1024 * 1024;

const emit = defineEmits<{
  'update:userInput': [value: string];
}>();

const inputValue = computed({
  get: () => props.userInput,
  set: value => emit('update:userInput', value)
});

const imageFileInput = ref<HTMLInputElement | null>(null);
const documentFileInput = ref<HTMLInputElement | null>(null);
const chatInputRef = ref<InputInstance>();
const chatModelPopoverVisible = ref(false);
const attachmentPreviews = ref<AttachmentPreview[]>([]);
const voiceAutoSend = ref(localStg.get('aiAgentVoiceAutoSend') === true);
const isRecordingAudio = ref(false);
const isTranscribingAudio = ref(false);
const mediaRecorder = ref<MediaRecorder | null>(null);
const audioChunks = ref<Blob[]>([]);
const audioStream = ref<MediaStream | null>(null);
const audioStatusText = ref('');
const audioStatusTone = ref<AudioStatusTone>('info');
const audioStatusTimers = ref<ReturnType<typeof setTimeout>[]>([]);
const audioTranscriptionRequestId = ref(0);
const isDisposingAudio = ref(false);
const runState = computed(() => props.state);
const businessClarificationLockHint = BUSINESS_CLARIFICATION_INPUT_LOCK_HINT;
const inputPlaceholder = computed(() => {
  if (runState.value.businessClarificationLocked) {
    return BUSINESS_CLARIFICATION_INPUT_LOCK_HINT;
  }
  return runState.value.pendingClarify ? '请输入补充信息或假设...' : '请输入问题...';
});
const sendButtonTitle = computed(() => {
  if (runState.value.businessClarificationLocked) {
    return BUSINESS_CLARIFICATION_INPUT_LOCK_HINT;
  }
  return runState.value.isStreaming ? '加入队列' : '发送';
});
const modelState = computed(() => props.modelState);
const contextState = computed(() => props.contextState);
const voiceState = computed(() => props.voiceState);
const agentSwitchState = computed(() => props.agentSwitchState);
const actions = computed(() => props.actions);

const answerTtsStatusLabel = computed(() => {
  return voiceState.value?.answerTtsStatusLabel || '开启后将按句合成并顺序播放回答';
});

const currentChatModelLabel = computed(() => {
  const selected = modelState.value.chatModelOptions.find(model => model.id === modelState.value.chatModelConfigId);
  if (selected) {
    return selected.modelName;
  }
  return modelState.value.defaultChatModel ? `默认：${modelState.value.defaultChatModel.modelName}` : '默认模型';
});

const defaultChatModelMeta = computed(() => {
  if (!modelState.value.defaultChatModel) {
    return 'Use current agent config';
  }
  const provider = modelState.value.defaultChatModel.provider ? `${modelState.value.defaultChatModel.provider} / ` : '';
  return `${provider}${modelState.value.defaultChatModel.modelName}`;
});

const handleSelectChatModel = async (modelId: ModelConfigId | undefined) => {
  await actions.value.selectChatModel(modelId);
  chatModelPopoverVisible.value = false;
};

const shouldShowPresetQuestions = computed(() => {
  if (!props.showPresetQuestions) {
    return false;
  }
  if (!runState.value.currentSession || !runState.value.agentId) {
    return false;
  }
  if (runState.value.isStreaming || runState.value.isSubmittingMessage || runState.value.businessClarificationLocked) {
    return false;
  }
  if (inputValue.value.trim()) {
    return false;
  }
  const messages = Array.isArray(runState.value.currentMessages) ? runState.value.currentMessages : [];
  return !messages.some(message => message.role === 'user');
});

const syncImageFilesFromPreviews = () => {
  return attachmentPreviews.value.map(preview => preview.file);
};

const countPreviews = (kind: AttachmentPreview['kind']) => {
  return attachmentPreviews.value.filter(preview => preview.kind === kind).length;
};

const addAttachmentFiles = (files: File[], preferredKind?: AttachmentPreview['kind']) => {
  const selected = files.filter(Boolean);
  if (selected.length === 0) {
    return;
  }
  let remainingImages = Math.max(0, MAX_CHAT_IMAGE_COUNT - countPreviews('image'));
  let remainingDocuments = Math.max(0, MAX_CHAT_DOCUMENT_COUNT - countPreviews('file'));
  let skippedUnsupported = 0;
  let skippedImageLimit = 0;
  let skippedDocumentLimit = 0;
  selected.forEach(file => {
    const type = resolveChatAttachmentType(file);
    const kind: AttachmentPreview['kind'] | null =
      type === 'image' || preferredKind === 'image' ? 'image' : type ? 'file' : null;
    if (!type || (preferredKind === 'image' && type !== 'image') || (preferredKind === 'file' && type === 'image')) {
      if (!type) {
        skippedUnsupported += 1;
      }
      return;
    }
    if (kind === 'image') {
      if (remainingImages <= 0) {
        skippedImageLimit += 1;
        return;
      }
      remainingImages -= 1;
    } else {
      if (remainingDocuments <= 0) {
        skippedDocumentLimit += 1;
        return;
      }
      remainingDocuments -= 1;
    }
    attachmentPreviews.value.push({
      file,
      url: URL.createObjectURL(file),
      kind
    });
  });
  if (skippedUnsupported > 0) {
    ElMessage.warning('仅支持图片、PDF、Word、Excel、CSV、文本、日志或音频');
  }
  if (skippedImageLimit > 0) {
    ElMessage.warning(`单次最多支持 ${MAX_CHAT_IMAGE_COUNT} 张图片`);
  }
  if (skippedDocumentLimit > 0) {
    ElMessage.warning(`单次最多支持 ${MAX_CHAT_DOCUMENT_COUNT} 个文件`);
  }
};

const addImageFiles = (files: File[]) => {
  addAttachmentFiles(
    files.filter(file => file.type.startsWith('image/') || resolveChatAttachmentType(file) === 'image'),
    'image'
  );
};

const clearSelectedImages = () => {
  attachmentPreviews.value.forEach(preview => URL.revokeObjectURL(preview.url));
  attachmentPreviews.value = [];
  if (imageFileInput.value) {
    imageFileInput.value.value = '';
  }
  if (documentFileInput.value) {
    documentFileInput.value.value = '';
  }
};

const removeSelectedAttachment = (url: string) => {
  attachmentPreviews.value = attachmentPreviews.value.filter(preview => {
    if (preview.url === url) {
      URL.revokeObjectURL(preview.url);
      return false;
    }
    return true;
  });
};

const openImagePicker = () => {
  imageFileInput.value?.click();
};

const openDocumentPicker = () => {
  documentFileInput.value?.click();
};

const handleImageFileSelect = (event: Event) => {
  const input = event.target as HTMLInputElement;
  addImageFiles(Array.from(input.files || []));
  input.value = '';
};

const handleDocumentFileSelect = (event: Event) => {
  const input = event.target as HTMLInputElement;
  addAttachmentFiles(Array.from(input.files || []), 'file');
  input.value = '';
};

const handleInputPaste = (event: ClipboardEvent) => {
  const files = Array.from(event.clipboardData?.files || []).filter(file => file.type.startsWith('image/'));
  if (files.length === 0) {
    return;
  }
  event.preventDefault();
  addImageFiles(files);
};

const handleSendShortcut = async () => {
  if (isTranscribingAudio.value) {
    return;
  }
  if (runState.value.businessClarificationLocked) {
    ElMessage.warning(BUSINESS_CLARIFICATION_INPUT_LOCK_HINT);
    return;
  }
  await actions.value.send();
};

const getSelectedImageFiles = () => {
  return syncImageFilesFromPreviews();
};

const hasSelectedImages = () => {
  return attachmentPreviews.value.length > 0;
};

const handleVoiceAutoSendChange = (value: string | number | boolean) => {
  const enabled = value === true;
  voiceAutoSend.value = enabled;
  localStg.set('aiAgentVoiceAutoSend', enabled);
};

const handleAnswerTtsChange = (value: string | number | boolean) => {
  actions.value.setAnswerTtsEnabled?.(value === true);
};

const stopAudioTracks = () => {
  audioStream.value?.getTracks().forEach(track => track.stop());
  audioStream.value = null;
};

const clearAudioStatusTimers = () => {
  audioStatusTimers.value.forEach(timer => clearTimeout(timer));
  audioStatusTimers.value = [];
};

const setAudioStatus = (text: string, tone: AudioStatusTone = 'info') => {
  audioStatusText.value = text;
  audioStatusTone.value = tone;
};

const clearAudioStatus = () => {
  clearAudioStatusTimers();
  audioStatusText.value = '';
  audioStatusTone.value = 'info';
};

const startAudioTranscriptionStatus = () => {
  clearAudioStatusTimers();
  setAudioStatus('正在识别语音...');
  audioStatusTimers.value = [
    setTimeout(() => setAudioStatus('语音较长，仍在识别中...', 'warning'), 8000),
    setTimeout(() => setAudioStatus('识别耗时较长，请稍候，完成前不会自动发送', 'warning'), 20000)
  ];
};

const appendTranscribedText = (text: string) => {
  const nextText = text.trim();
  if (!nextText) {
    return false;
  }
  inputValue.value = inputValue.value.trim() ? `${inputValue.value.trim()}\n${nextText}` : nextText;
  return true;
};

const getCurrentSessionId = () => {
  const sessionId = runState.value.currentSession?.id;
  return sessionId === undefined || sessionId === null ? '' : String(sessionId);
};

const realtimeVoice = useRealtimeVoiceConversation({
  getAgentId: () => runState.value.agentId ?? runState.value.currentSession?.agentId,
  getThreadId: () => getCurrentSessionId(),
  getChatModelConfigId: () => modelState.value.chatModelConfigId,
  onCompleted: () => actions.value.reloadCurrentMessages?.()
});

const realtimeVoiceActive = realtimeVoice.active;
const realtimeVoiceConnecting = realtimeVoice.connecting;
const realtimeVoiceReadinessLoading = realtimeVoice.readinessLoading;
const realtimeVoiceTranscript = realtimeVoice.transcriptText;
const realtimeVoiceAnswer = realtimeVoice.answerText;
const realtimeVoiceStatusText = computed(() => {
  if (realtimeVoice.errorText.value) {
    return realtimeVoice.errorText.value;
  }
  if (realtimeVoice.missingItems.value.length > 0 && !realtimeVoice.ready.value) {
    return `实时语音缺配置：${realtimeVoice.missingItems.value.join('、')}`;
  }
  return realtimeVoice.statusText.value;
});
const realtimeVoiceButtonTitle = computed(() => {
  if (realtimeVoice.active.value) {
    return '结束实时对话';
  }
  return '实时对话';
});
const showRealtimeVoiceStatus = computed(() => {
  return (
    realtimeVoice.active.value ||
    realtimeVoice.connecting.value ||
    realtimeVoice.processing.value ||
    Boolean(realtimeVoice.errorText.value) ||
    realtimeVoice.missingItems.value.length > 0 ||
    Boolean(realtimeVoice.transcriptText.value) ||
    Boolean(realtimeVoice.answerText.value)
  );
});
const realtimeVoiceCanGenerateDefault = computed(() => {
  return !realtimeVoice.ready.value && realtimeVoice.missingItems.value.length > 0 && !realtimeVoice.active.value;
});

const toggleRealtimeVoice = async () => {
  if (realtimeVoice.active.value) {
    await realtimeVoice.stop();
    return;
  }
  const started = await realtimeVoice.start();
  if (!started && realtimeVoice.missingItems.value.length > 0) {
    ElMessage.warning(`实时语音缺配置：${realtimeVoice.missingItems.value.join('、')}`);
  } else if (!started && realtimeVoice.errorText.value) {
    ElMessage.error(realtimeVoice.errorText.value);
  }
};

const interruptRealtimeVoice = () => {
  realtimeVoice.interrupt();
};

const handleGenerateRealtimeDefault = async () => {
  const nextReadiness = await realtimeVoice.generateDefaultConfig();
  if (nextReadiness.ready) {
    ElMessage.success('默认实时语音配置已生成');
  } else {
    ElMessage.warning(`仍缺配置：${(nextReadiness.missingItems || []).join('、') || '请检查模型配置'}`);
  }
};

const resolveAudioMimeType = () => {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') {
    return undefined;
  }
  return ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'].find(type =>
    MediaRecorder.isTypeSupported(type)
  );
};

const writeAsciiString = (view: DataView, offset: number, value: string) => {
  for (let index = 0; index < value.length; index += 1) {
    view.setUint8(offset + index, value.charCodeAt(index));
  }
};

const encodeAudioBufferToWav = (audioBuffer: AudioBuffer) => {
  const channelCount = audioBuffer.numberOfChannels;
  const sampleRate = audioBuffer.sampleRate;
  const sampleCount = audioBuffer.length;
  const bytesPerSample = 2;
  const dataSize = sampleCount * channelCount * bytesPerSample;
  const wavBuffer = new ArrayBuffer(44 + dataSize);
  const view = new DataView(wavBuffer);

  writeAsciiString(view, 0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  writeAsciiString(view, 8, 'WAVE');
  writeAsciiString(view, 12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, channelCount, true);
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * channelCount * bytesPerSample, true);
  view.setUint16(32, channelCount * bytesPerSample, true);
  view.setUint16(34, 16, true);
  writeAsciiString(view, 36, 'data');
  view.setUint32(40, dataSize, true);

  let offset = 44;
  const channels = Array.from({ length: channelCount }, (_, channelIndex) => audioBuffer.getChannelData(channelIndex));
  for (let sampleIndex = 0; sampleIndex < sampleCount; sampleIndex += 1) {
    for (let channelIndex = 0; channelIndex < channelCount; channelIndex += 1) {
      const sample = Math.max(-1, Math.min(1, channels[channelIndex][sampleIndex] || 0));
      view.setInt16(offset, sample < 0 ? sample * 0x8000 : sample * 0x7fff, true);
      offset += bytesPerSample;
    }
  }

  return new Blob([wavBuffer], { type: 'audio/wav' });
};

const convertRecordedAudioToWavFile = async (blob: Blob) => {
  const AudioContextClass = window.AudioContext || (window as WebkitAudioWindow).webkitAudioContext;
  if (!AudioContextClass) {
    throw new Error('当前浏览器不支持录音格式转换，请使用新版 Chrome 或 Edge 浏览器');
  }
  const audioContext = new AudioContextClass();
  try {
    const decodedAudio = await audioContext.decodeAudioData(await blob.arrayBuffer());
    const wavBlob = encodeAudioBufferToWav(decodedAudio);
    if (wavBlob.size > MAX_TRANSCRIPTION_AUDIO_SIZE) {
      throw new Error('录音转换后文件过大，请缩短录音后重试');
    }
    return new File([wavBlob], 'recording.wav', { type: 'audio/wav' });
  } catch (error) {
    if (error instanceof Error) {
      throw error;
    }
    throw new Error('录音格式转换失败，请缩短录音或更换浏览器后重试');
  } finally {
    await audioContext.close().catch(() => undefined);
  }
};

const getAudioRecordingErrorMessage = (error: unknown) => {
  const name = error instanceof DOMException ? error.name : '';
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return '未获得麦克风权限，请在浏览器地址栏允许麦克风后重试';
  }
  if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
    return '没有检测到可用麦克风，请连接设备后重试';
  }
  if (name === 'NotReadableError' || name === 'TrackStartError') {
    return '麦克风可能正被其他应用占用，请关闭占用后重试';
  }
  if (name === 'OverconstrainedError') {
    return '当前麦克风设备不可用，请切换设备后重试';
  }
  if (name === 'AbortError') {
    return '麦克风启动失败，请稍后重试';
  }
  return '无法访问麦克风，请检查浏览器权限和设备状态';
};

const getAudioTranscriptionErrorMessage = (error: unknown) => {
  const message = extractApiErrorMessage(error, '语音识别失败，请稍后重试');
  const status = getResponseStatus(error);
  if (/未配置语音转写模型|AUDIO_TRANSCRIPTION/i.test(message)) {
    return '未配置语音转写模型，请先在模型配置中启用语音转写模型';
  }
  if (/音频文件不能为空|empty/i.test(message)) {
    return '没有录到有效语音内容，请重新录制';
  }
  if (/录音格式转换失败|不支持录音格式转换|decodeAudioData/i.test(message)) {
    return '当前浏览器无法处理这段录音，请缩短录音或使用新版 Chrome/Edge 后重试';
  }
  if (/仅支持|unsupported|content-type|media type|format/i.test(message)) {
    return '当前录音格式暂不支持，请尝试使用 Chrome 浏览器或缩短后重试';
  }
  if (/webm duration|EBML|ffprobe|count_token_failed/i.test(message)) {
    return '语音服务无法解析 WebM 录音时长，请刷新页面后重试，系统会改用 WAV 格式上传';
  }
  if (/LLM Provider NOT provided|provider you are trying to call|upstream_error/i.test(message)) {
    return '语音转写模型配置不匹配，请在模型配置中使用供应商支持的语音转写模型名，并按要求填写 provider/model 前缀';
  }
  if (/代理不可用|proxy|connection refused|connect refused|127\.0\.0\.1/i.test(message)) {
    return '语音转写模型代理不可用，请启动代理服务或关闭该模型的代理配置';
  }
  if (status === 413 || /20MB|too large|payload too large|文件过大/i.test(message)) {
    return '录音文件过大，请缩短录音后重试';
  }
  if (status === 401 || /401|鉴权|unauthorized|api key/i.test(message)) {
    return '语音模型鉴权失败，请检查 API Key 或模型权限';
  }
  if (status === 404 || /404|not found|model_not_found|模型不可用/i.test(message)) {
    return '语音模型或接口地址不可用，请检查模型名称、Base URL 和供应商兼容性';
  }
  if (status === 429 || /429|额度|限流|rate|quota/i.test(message)) {
    return '语音模型额度不足或请求过于频繁，请稍后再试';
  }
  if (/timeout|ECONNABORTED|超时/i.test(message)) {
    return '语音识别超时，请缩短录音后重试';
  }
  return message || '语音识别失败，请稍后重试';
};

const shouldAutoSendTranscription = (requestId: number, sessionId: string, inputBeforeTranscription: string) => {
  if (!voiceAutoSend.value || audioTranscriptionRequestId.value !== requestId) {
    return false;
  }
  if (!sessionId || getCurrentSessionId() !== sessionId) {
    return false;
  }
  if (inputValue.value !== inputBeforeTranscription) {
    return false;
  }
  return (
    !runState.value.isSubmittingMessage && !runState.value.isStreaming && !runState.value.businessClarificationLocked
  );
};

const startAudioRecording = async () => {
  if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
    ElMessage.warning('当前浏览器不支持语音输入，请使用新版 Chrome 或 Edge 浏览器');
    return;
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    audioStream.value = stream;
    audioChunks.value = [];
    const mimeType = resolveAudioMimeType();
    const recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined);
    mediaRecorder.value = recorder;
    recorder.ondataavailable = event => {
      if (event.data.size > 0) {
        audioChunks.value.push(event.data);
      }
    };
    recorder.onstop = async () => {
      const chunks = audioChunks.value;
      audioChunks.value = [];
      stopAudioTracks();
      mediaRecorder.value = null;
      isRecordingAudio.value = false;
      if (isDisposingAudio.value) {
        return;
      }
      if (chunks.length === 0) {
        clearAudioStatus();
        ElMessage.warning('没有录到有效语音内容，请重新录制');
        return;
      }
      isTranscribingAudio.value = true;
      const requestId = audioTranscriptionRequestId.value + 1;
      audioTranscriptionRequestId.value = requestId;
      const sessionId = getCurrentSessionId();
      const inputBeforeTranscription = inputValue.value;
      startAudioTranscriptionStatus();
      try {
        const blob = new Blob(chunks, { type: recorder.mimeType || 'audio/webm' });
        const audioFile = await convertRecordedAudioToWavFile(blob);
        const result = await AudioService.transcribe(audioFile);
        if (audioTranscriptionRequestId.value !== requestId) {
          return;
        }
        const nextText = (result.text || '').trim();
        if (!nextText) {
          ElMessage.warning('没有识别到有效语音内容');
          return;
        }
        const canAutoSend = shouldAutoSendTranscription(requestId, sessionId, inputBeforeTranscription);
        appendTranscribedText(nextText);
        if (canAutoSend) {
          await nextTick();
          await actions.value.send();
        }
      } catch (error) {
        console.error('语音识别失败', error);
        ElMessage.error(getAudioTranscriptionErrorMessage(error));
      } finally {
        if (audioTranscriptionRequestId.value === requestId) {
          isTranscribingAudio.value = false;
          clearAudioStatus();
        }
      }
    };
    recorder.start();
    isRecordingAudio.value = true;
    setAudioStatus('正在录音，点击麦克风结束');
  } catch (error) {
    stopAudioTracks();
    mediaRecorder.value = null;
    isRecordingAudio.value = false;
    clearAudioStatus();
    console.error('麦克风启动失败', error);
    ElMessage.error(getAudioRecordingErrorMessage(error));
  }
};

const stopAudioRecording = () => {
  const recorder = mediaRecorder.value;
  if (recorder && recorder.state !== 'inactive') {
    recorder.stop();
  }
};

const toggleAudioRecording = async () => {
  if (isRecordingAudio.value) {
    stopAudioRecording();
    return;
  }
  await startAudioRecording();
};

onBeforeUnmount(() => {
  isDisposingAudio.value = true;
  audioTranscriptionRequestId.value += 1;
  clearAudioStatus();
  stopAudioRecording();
  stopAudioTracks();
  clearSelectedImages();
});

const focusInput = async () => {
  await nextTick();
  chatInputRef.value?.focus();
};

defineExpose({
  clearSelectedImages,
  focusInput,
  getSelectedImageFiles,
  hasSelectedImages
});
</script>

<style scoped>
.input-area {
  position: relative;
  z-index: 8;
  flex: 0 0 auto;
  padding: 0 0 0 8px;
  background: transparent;
  border: 0;
  border-radius: 0;
  box-shadow: none;
  pointer-events: auto;
}

.input-container {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  width: 100%;
  margin: 0;
  pointer-events: auto;
}

.input-shell {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: none;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.input-shell:focus-within {
  border-color: var(--el-color-primary-light-7);
}

.chat-input {
  width: 100%;
  border: 0;
}

.chat-input :deep(.el-textarea__inner) {
  min-height: 96px !important;
  padding: 16px 18px 10px;
  color: var(--el-text-color-primary);
  line-height: 1.6;
  resize: vertical;
  background: var(--el-bg-color);
  border: 0;
  border-radius: 0;
  box-shadow: none;
}

.chat-input :deep(.el-textarea__inner:focus) {
  box-shadow: none;
}

.hidden-file-input {
  display: none;
}

.image-preview-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px;
  background: var(--el-fill-color-extra-light);
  border-top: 1px solid var(--el-border-color-extra-light);
}

.image-preview-item {
  position: relative;
  width: 72px;
  height: 72px;
  overflow: visible;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.image-preview-item img {
  display: block;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: inherit;
  object-fit: cover;
}

.file-preview-item {
  width: auto;
  min-width: 96px;
  max-width: 180px;
  height: 72px;
}

.file-preview-chip {
  display: flex;
  height: 100%;
  align-items: center;
  gap: 6px;
  padding: 0 10px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 12px;
}

.file-preview-chip span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.image-preview-remove {
  position: absolute;
  top: -6px;
  right: -6px;
  display: inline-flex;
  width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  cursor: pointer;
  background: rgba(17, 24, 39, 0.68);
  box-shadow: 0 2px 6px rgba(15, 23, 42, 0.18);
  opacity: 0;
  border-radius: 999px !important;
  pointer-events: none;
  transition:
    opacity 0.16s ease,
    background 0.16s ease;
}

.image-preview-item:hover .image-preview-remove,
.image-preview-item:focus-within .image-preview-remove {
  opacity: 1;
  pointer-events: auto;
}

.image-preview-remove:hover,
.image-preview-remove:focus {
  background: rgba(17, 24, 39, 0.78);
}

.image-preview-remove :deep(.el-icon) {
  margin-right: 0;
  font-size: 12px;
}

.audio-status-row {
  display: flex;
  min-height: 32px;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
  background: var(--el-fill-color-extra-light);
  border-top: 1px solid var(--el-border-color-extra-light);
}

.audio-status-warning {
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-9);
}

.realtime-voice-status-row {
  display: flex;
  min-height: 34px;
  align-items: center;
  gap: 8px;
  padding: 6px 16px;
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.4;
  background: var(--el-color-primary-light-9);
  border-top: 1px solid var(--el-border-color-extra-light);
}

.realtime-voice-status-main {
  flex: 0 1 auto;
  min-width: 0;
  font-weight: 600;
}

.realtime-voice-caption {
  overflow: hidden;
  max-width: 260px;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.audio-status-pulse {
  width: 7px;
  height: 7px;
  flex: 0 0 auto;
  background: var(--el-color-danger);
  border-radius: 50%;
  box-shadow: 0 0 0 4px rgba(245, 108, 108, 0.14);
}

.input-toolbar {
  display: flex;
  min-height: 44px;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 16px 10px;
  background: var(--el-bg-color);
  border-top: 0;
}

.input-toolbar-left,
.input-toolbar-right {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.input-toolbar-left {
  flex: 1 1 auto;
}

.input-toolbar-right {
  flex: 0 0 auto;
}

.chat-actions-trigger {
  width: 36px;
  height: 36px;
  padding: 0;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 50%;
  box-shadow: none;
}

.chat-settings-trigger {
  width: 36px;
  height: 36px;
  padding: 0;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 7px;
  box-shadow: none;
}

.chat-model-trigger {
  flex: 0 1 320px;
  max-width: min(260px, 100%);
  height: 32px;
  justify-content: flex-start;
  padding: 0 11px;
  color: var(--el-text-color-regular);
  font-weight: 600;
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-light);
  border-radius: 7px;
  box-shadow: none;
}

.chat-actions-trigger:hover,
.chat-actions-trigger:focus,
.chat-actions-trigger.active,
.chat-settings-trigger:hover,
.chat-settings-trigger:focus,
.chat-settings-trigger.active,
.chat-model-trigger:hover,
.chat-model-trigger:focus {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.realtime-voice-interrupt:hover,
.realtime-voice-interrupt:focus {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
  border-color: var(--el-color-danger-light-5);
}

.chat-actions-trigger :deep(.el-icon),
.chat-settings-trigger :deep(.el-icon) {
  margin-right: 0;
}

.chat-model-trigger :deep(span),
.chat-model-trigger :deep(.el-button__content) {
  min-width: 0;
}

.chat-model-trigger :deep(.el-button__content) {
  width: 100%;
  justify-content: flex-start;
}

.chat-model-trigger-label {
  display: inline-block;
  flex: 0 1 auto;
  min-width: 0;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-model-trigger-icon {
  flex: 0 0 auto;
  margin-left: 4px;
}

.report-mode-trigger {
  display: inline-flex;
  height: 32px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 13px;
  border-radius: 999px !important;
  box-shadow: none;
}

.report-mode-trigger.active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-5);
}

.report-mode-trigger:hover:not(.active),
.report-mode-trigger:focus:not(.active) {
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-extra-light);
  border-color: var(--el-border-color);
}

.report-mode-trigger :deep(.el-button__content) {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.report-mode-trigger-icon {
  margin-right: 0;
  color: inherit;
  font-size: 16px;
}

.report-mode-trigger-text {
  color: inherit;
  font-size: 13px;
  line-height: 1;
  white-space: nowrap;
}

.send-button,
.stop-button-inline {
  width: 32px;
  height: 32px;
  flex-basis: 32px;
  box-shadow: none;
}

.send-button,
.send-button:hover,
.send-button:focus {
  background: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

.send-button.is-disabled,
.send-button.is-disabled:hover,
.send-button.is-disabled:focus {
  background: var(--el-fill-color-dark);
  border-color: var(--el-border-color);
  box-shadow: none;
}

.stop-button-inline,
.stop-button-inline:hover,
.stop-button-inline:focus {
  background: var(--el-color-danger);
  border-color: var(--el-color-danger);
}

.chat-actions-panel,
.chat-settings-panel,
.chat-model-menu {
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border-color: var(--el-border-color-light);
  box-shadow: none;
}

.chat-actions-panel,
.chat-settings-panel {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.chat-actions-panel :deep(.el-button + .el-button) {
  margin-left: 0;
}

.chat-actions-option,
.chat-actions-setting,
.chat-settings-action {
  color: var(--el-text-color-primary);
  text-align: left;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.chat-actions-option {
  display: flex;
  width: 100%;
  height: auto;
  min-height: 58px;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  padding: 14px 16px;
  cursor: pointer;
  border-radius: 0;
  transition:
    color 0.2s ease,
    background 0.2s ease,
    border-color 0.2s ease;
}

.chat-actions-setting {
  display: flex;
  width: 100%;
  min-height: 58px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
}

.chat-actions-option:hover,
.chat-actions-option.active,
.chat-settings-action:hover:not(:disabled) {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.chat-actions-option :deep(> span),
.chat-actions-option :deep(.el-button__content) {
  display: inline-flex;
  width: 100%;
  height: auto;
  min-height: 0;
  align-items: center;
  justify-content: flex-start;
  gap: 12px;
  white-space: normal;
}

.chat-actions-option-icon {
  flex: 0 0 auto;
  color: inherit;
  font-size: 18px;
}

.chat-actions-option-main {
  display: flex;
  flex: 1 1 auto;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.chat-actions-option-title {
  font-size: 13px;
  font-weight: 600;
}

.chat-actions-option-desc {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.35;
}

.chat-settings-action {
  padding: 9px 10px;
  cursor: pointer;
  border-radius: 7px;
  transition:
    color 0.2s ease,
    background 0.2s ease,
    border-color 0.2s ease;
}

.chat-settings-action:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.chat-model-menu {
  display: flex;
  max-height: 300px;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
}

.chat-model-option {
  display: grid;
  width: 100%;
  min-height: 40px;
  align-items: center;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  padding: 7px 9px;
  color: var(--el-text-color-primary);
  text-align: left;
  cursor: pointer;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
}

.chat-model-option + .chat-model-option {
  margin-left: 0 !important;
}

.chat-model-option :deep(.el-button__content) {
  display: grid;
  width: 100%;
  min-width: 0;
  align-items: center;
  justify-content: stretch;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
}

.chat-model-option:hover,
.chat-model-option.active {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.chat-model-option-name {
  min-width: 0;
  font-size: 13px;
  text-align: left;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chat-model-option-meta {
  justify-self: end;
  flex: 0 0 auto;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  text-align: right;
  white-space: nowrap;
  margin-left: 5px;
}

.chat-model-menu-divider {
  height: 1px;
  margin: 4px 0;
  background: var(--el-border-color-lighter);
}

.chat-model-empty {
  padding: 10px 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.input-preset-questions {
  width: 100%;
  margin-top: 12px;
}

.business-clarify-lock-hint {
  padding: 10px 14px;
  margin-bottom: 12px;
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
  line-height: 1.5;
  background: var(--el-color-warning-light-9);
  border: 1px solid var(--el-color-warning-light-7);
  border-radius: 8px;
}

.clarify-banner {
  padding: 14px 16px;
  margin-bottom: 12px;
  background: var(--el-color-warning-light-9);
  border: 1px solid var(--el-color-warning-light-7);
  border-radius: 12px;
  box-shadow: none;
}

.clarify-banner-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.clarify-banner-title {
  color: var(--el-color-warning-dark-2);
  font-size: 14px;
  font-weight: 600;
}

.clarify-banner-risk {
  color: var(--el-color-warning-dark-2);
  font-size: 12px;
  font-weight: 600;
}

.clarify-banner-body {
  color: var(--el-color-warning-dark-2);
  font-size: 13px;
  line-height: 1.6;
}

.clarify-banner-tags,
.clarify-banner-assumptions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.clarify-banner-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  color: var(--el-color-warning-dark-2);
  font-size: 12px;
}

:global(.chat-actions-popover),
:global(.chat-settings-popover),
:global(.chat-model-popover) {
  padding: 8px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: var(--el-box-shadow-light);
}
</style>
