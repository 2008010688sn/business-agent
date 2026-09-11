<template>
  <div
    v-for="(message, messageIndex) in messages"
    v-show="!shouldHideFlowMessage(message, messageIndex)"
    :key="getMessageRenderKey(message, messageIndex)"
    class="message-container"
    :class="getMessageRole(message)"
  >
    <div v-if="message.messageType === CHAT_MESSAGE_TYPES.RESULT_SET" class="result-set-message">
      <ResultSetDisplay
        v-if="message.content"
        v-model:page-size="resultSetPageSizeModel"
        :resultData="JSON.parse(message.content)"
      />
    </div>
    <div v-else-if="message.messageType === CHAT_MESSAGE_TYPES.MARKDOWN_REPORT" class="markdown-report-message">
      <div class="markdown-report-header flex items-center justify-between">
        <div class="report-info">
          <ElIcon><Document /></ElIcon>
          <span>报告已生成</span>
          <span v-if="getReportLevelBadge(message)" class="report-level-badge">
            {{ getReportLevelBadge(message) }}
          </span>
        </div>
        <ElButtonGroup class="flex items-center">
          <ElButton
            v-for="followUp in getReportFollowUps(message)"
            :key="`${followUp.type}-${followUp.label}`"
            size="small"
            :disabled="isReportFollowUpDisabled(message, messageIndex)"
            @click="emitReportFollowUp(followUp)"
          >
            {{ followUp.label || '按更细粒度继续分析' }}
          </ElButton>
          <ElTooltip content="基于本次查询数据生成深度解读" placement="top">
            <ElButton
              size="small"
              type="warning"
              plain
              :loading="professionalLoadingRequestId === getMessageRuntimeRequestId(message)"
              :disabled="
                !getMessageRuntimeRequestId(message) ||
                isSubmittingMessage ||
                isStreaming ||
                Boolean(professionalLoadingRequestId)
              "
              @click="emitProfessionalReport(message)"
            >
              <ElIcon v-if="professionalLoadingRequestId !== getMessageRuntimeRequestId(message)">
                <MagicStick />
              </ElIcon>
              专业解读
            </ElButton>
          </ElTooltip>
          <ElButton size="small" type="primary" @click="emitDownloadReport(message, 'markdown')">
            <ElIcon><Download /></ElIcon>
            下载 Markdown
          </ElButton>
          <ElButton size="small" @click="emitDownloadReport(message, 'html')">
            <ElIcon><Download /></ElIcon>
            下载 HTML
          </ElButton>
          <ElTooltip content="全屏查看 Markdown 报告" placement="top">
            <ElButton text @click="openReportFullscreen(message.content, 'markdown', 'markdown')">
              <ElIcon class="ml-5px"><FullScreen /></ElIcon>
            </ElButton>
          </ElTooltip>
          <ElTooltip content="全屏查看 HTML 报告" placement="top">
            <ElButton text @click="openReportFullscreen(message.content, 'html', 'markdown')">
              <ElIcon class="ml-5px"><FullScreen /></ElIcon>
            </ElButton>
          </ElTooltip>
        </ElButtonGroup>
      </div>
      <div v-if="canShowRuntimeMeta(message)" class="message-runtime-meta report-message-runtime-meta">
        <span v-if="formatMessageDuration(message)" class="message-runtime-duration">
          耗时 {{ formatMessageDuration(message) }}
        </span>
        <ElButton
          v-if="getMessageRuntimeRequestId(message)"
          link
          type="primary"
          size="small"
          @click.stop="openMessageDiagnostics(message)"
        >
          会话排障
        </ElButton>
      </div>
      <div class="markdown-report-content">
        <SafeMarkdownContent class="md-body" :content="message.content" aria-label="分析报告" />
      </div>
    </div>
    <div v-else-if="message.messageType === CHAT_MESSAGE_TYPES.HTML_REPORT" class="markdown-report-message">
      <div class="markdown-report-header flex items-center justify-between">
        <div class="report-info">
          <ElIcon><Document /></ElIcon>
          <span>HTML 报告</span>
        </div>
        <ElButtonGroup class="flex items-center">
          <ElButton size="small" type="primary" @click="emitDownloadReport(message)">
            <ElIcon><Download /></ElIcon>
            下载分析报告
          </ElButton>
          <ElTooltip content="全屏查看报告" placement="top">
            <ElButton text @click="openReportFullscreen(message.content, 'html', 'html')">
              <ElIcon class="ml-5px"><FullScreen /></ElIcon>
            </ElButton>
          </ElTooltip>
        </ElButtonGroup>
      </div>
      <div v-if="canShowRuntimeMeta(message)" class="message-runtime-meta report-message-runtime-meta">
        <span v-if="formatMessageDuration(message)" class="message-runtime-duration">
          耗时 {{ formatMessageDuration(message) }}
        </span>
        <ElButton
          v-if="getMessageRuntimeRequestId(message)"
          link
          type="primary"
          size="small"
          @click.stop="openMessageDiagnostics(message)"
        >
          会话排障
        </ElButton>
      </div>
      <ReportHtmlView :content="message.content" source-format="html" />
    </div>
    <div
      v-else
      class="message"
      :class="[
        getMessageRole(message),
        { 'message-wide': isAgentUiMessage(message), 'message-table': hasMessageTable(message) }
      ]"
    >
      <div class="message-avatar">
        <ElAvatar v-if="getMessageRole(message) === 'user'" :size="32" class="user-text-avatar">我</ElAvatar>
        <img v-else class="assistant-logo-avatar" src="@/assets/imgs/logo.svg" alt="AI" />
      </div>
      <div
        class="message-content"
        :class="{ 'question-message-editable': canEditQuestionMessage(message) }"
        :title="canEditQuestionMessage(message) ? '双击编辑后再次发送' : ''"
        @dblclick="startEditQuestion(message)"
      >
        <div v-if="isEditingQuestion(message)" class="question-edit-panel" @dblclick.stop>
          <ElInput
            v-model="editingQuestionContent"
            type="textarea"
            :autosize="{ minRows: 2, maxRows: 6 }"
            :disabled="isSubmittingMessage || hasSelectedImages"
            class="question-edit-input"
            @keydown.enter.exact.prevent="submitEditedQuestion"
            @keydown.esc.prevent="cancelEditQuestion"
          />
          <div class="question-edit-actions">
            <ElButton
              size="small"
              type="primary"
              :disabled="isSubmittingMessage || hasSelectedImages"
              @click="submitEditedQuestion"
            >
              发送
            </ElButton>
            <ElButton size="small" @click="cancelEditQuestion">取消</ElButton>
          </div>
        </div>
        <AnalysisResultCard
          v-if="isAnalysisResultUi(getAgentUiMessage(message))"
          :ui="getAgentUiMessage(message)!"
          :disabled="isAnalysisActionDisabled(message, messageIndex)"
          @action="emitAnalysisAction"
        />
        <ToolConfirmCard
          v-else-if="isToolConfirmUi(getAgentUiMessage(message))"
          :ui="getAgentUiMessage(message)!"
          :disabled="isToolConfirmActionDisabled(message, messageIndex)"
        />
        <AgentMessageRenderer
          v-else-if="getAgentUiMessage(message)"
          :ui="getAgentUiMessage(message)!"
          :disabled="isFlowActionDisabled(message, messageIndex)"
          :invalid="!isValidSkillFlowUi(getAgentUiMessage(message))"
          @action="emitFlowAction"
        />
        <div v-else-if="message.messageType === CHAT_MESSAGE_TYPES.THINKING" class="thinking-message-inline">
          <SafeLegacyHtmlContent class="legacy-message-content" :content="message.content" />
        </div>
        <div v-else class="question-message-block">
          <SafeMarkdownContent
            v-if="resolveMessageDisplayContent(message).contentFormat === 'markdown'"
            class="message-text assistant-reading-surface"
            :content="resolveMessageDisplayContent(message).content"
          />
          <SafeLegacyHtmlContent
            v-else-if="resolveMessageDisplayContent(message).contentFormat === 'legacy-html'"
            class="message-text legacy-message-content"
            :content="resolveMessageDisplayContent(message).content"
          />
          <!-- eslint-disable vue/no-v-text -->
          <div
            v-else
            class="message-text plain-message-text"
            v-text="resolveMessageDisplayContent(message).content"
          ></div>
          <!-- eslint-enable vue/no-v-text -->
          <SuggestedReplies
            :suggested-replies="getMessageSuggestedReplies(message, messageIndex)"
            :disabled="suggestedRepliesDisabled"
            @submit="emitSuggestedReply"
          />
          <div
            v-if="canCopyQuestionMessage(message)"
            class="question-message-actions"
            :class="{ copied: isQuestionCopied(message, messageIndex) }"
          >
            <ElButton
              text
              bg
              circle
              size="small"
              class="question-copy-button"
              :class="{ copied: isQuestionCopied(message, messageIndex) }"
              :title="isQuestionCopied(message, messageIndex) ? '已复制' : '复制问题'"
              @click.stop="copyQuestionMessage(message, messageIndex)"
              @dblclick.stop
            >
              <ElIcon>
                <Check v-if="isQuestionCopied(message, messageIndex)" />
                <CopyDocument v-else />
              </ElIcon>
            </ElButton>
          </div>
        </div>
        <div
          v-if="getMessageImageAttachments(message).length > 0 || getMessageFileAttachments(message).length > 0"
          class="message-attachment-list"
        >
          <a
            v-for="attachment in getMessageImageAttachments(message)"
            :key="attachment.url || attachment.storageKey || attachment.fileName"
            :href="attachment.url"
            target="_blank"
            rel="noopener"
            class="message-attachment-image"
          >
            <img :src="attachment.url" :alt="attachment.fileName || '图片附件'" />
          </a>
          <component
            :is="attachment.url || attachment.previewUrl ? 'a' : 'div'"
            v-for="attachment in getMessageFileAttachments(message)"
            :key="attachment.storageKey || attachment.url || attachment.fileName"
            :href="attachment.url || attachment.previewUrl"
            target="_blank"
            rel="noopener"
            class="message-attachment-file"
          >
            <ElIcon><Document /></ElIcon>
            <span>{{ attachment.fileName || '文档附件' }}</span>
          </component>
          <ElButton
            v-if="canAddAttachmentsToKnowledge(message)"
            link
            type="primary"
            size="small"
            class="message-knowledge-button"
            :disabled="knowledgeSaving"
            @click.stop="openKnowledgeDialog(message)"
          >
            加入知识库
          </ElButton>
        </div>
        <div v-if="canShowRuntimeMeta(message)" class="message-runtime-meta">
          <span v-if="formatMessageDuration(message)" class="message-runtime-duration">
            耗时 {{ formatMessageDuration(message) }}
          </span>
          <ElButton
            v-if="getMessageRuntimeRequestId(message)"
            link
            type="primary"
            size="small"
            @click.stop="openMessageDiagnostics(message)"
          >
            会话排障
          </ElButton>
        </div>
      </div>
    </div>
  </div>
  <ReportFullscreenPreview
    :visible="showReportFullscreen"
    :report-format="fullscreenReportFormat"
    :source-format="fullscreenReportSourceFormat"
    :content="fullscreenReportContent"
    :markdown-options="markdownOptions"
    @close="closeReportFullscreen"
  />
  <ElDialog v-model="knowledgeDialogVisible" title="加入技能知识库" width="420px" destroy-on-close>
    <p class="knowledge-dialog-hint">仅把当前回合附件沉淀到所选技能知识库，不会自动向量化对话原文。</p>
    <ElSelect v-model="knowledgeSkillId" filterable placeholder="选择技能" class="knowledge-dialog-select">
      <ElOption
        v-for="skill in knowledgeSkills"
        :key="skill.skillId"
        :label="skill.skillName || skill.skillCode"
        :value="skill.skillId"
      />
    </ElSelect>
    <template #footer>
      <ElButton @click="knowledgeDialogVisible = false">取消</ElButton>
      <ElButton type="primary" :loading="knowledgeSaving" @click="submitKnowledgeIngest">确定</ElButton>
    </template>
  </ElDialog>
</template>

<script lang="ts">
import { computed, defineComponent, nextTick, onBeforeUnmount, ref, type PropType } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Check, CopyDocument, Document, Download, FullScreen, MagicStick } from '@element-plus/icons-vue';
import { CHAT_MESSAGE_TYPES, type ChatMessage } from '@/views/ai-agent/services/chat';
import AgentMessageRenderer from '@/views/ai-agent/components/run/AgentMessageRenderer.vue';
import AnalysisResultCard from '@/views/ai-agent/components/run/AnalysisResultCard.vue';
import ToolConfirmCard from '@/views/ai-agent/components/run/ToolConfirmCard.vue';
import ReportFullscreenPreview from '@/views/ai-agent/components/run/ReportFullscreenPreview.vue';
import ReportHtmlView from '@/views/ai-agent/components/run/ReportHtmlView.vue';
import ResultSetDisplay from '@/views/ai-agent/components/run/ResultSetDisplay.vue';
import SafeLegacyHtmlContent from '@/views/ai-agent/components/run/SafeLegacyHtmlContent.vue';
import SafeMarkdownContent from '@/views/ai-agent/components/run/SafeMarkdownContent.vue';
import SuggestedReplies from '@/views/ai-agent/components/run/SuggestedReplies.vue';
import { hasMarkdownTable } from '@/views/ai-agent/utils/safeMarkdown';
import skillService, { type AgentSkillBindingOption } from '@/views/ai-agent/services/skill';
import skillKnowledgeService from '@/views/ai-agent/services/skillKnowledge';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import { isKnowledgeEligibleAttachmentType } from '@/views/ai-agent/utils/chatAttachment';
import {
  getMessageAttachments,
  getMessageFileAttachments,
  getMessageImageAttachments,
  getMessageRenderKey,
  getMessageRole,
  isTextMessage,
  parseMessageMetadata,
  resolveMessageDisplayContent,
  writeTextToClipboard,
  type MessageAttachment
} from '@/views/ai-agent/utils/runMessage';
import {
  normalizeSuggestedReplies,
  type SuggestedReplySubmission,
  type SuggestedReplies as SuggestedRepliesSchema
} from '@/views/ai-agent/utils/suggestedReplies';
import { useReportFullscreen } from '@/views/ai-agent/utils/useReportFullscreen';
import {
  getAgentUiFallbackIdentity,
  getAgentUiIdentity,
  ANALYSIS_ACTION_TYPES,
  followUpDisplayText,
  isAnalysisResultUi,
  isToolConfirmUi,
  isValidSkillFlowUi,
  isWaitingAgentUi,
  parseAgentUiFromMessage,
  parseAnalysisFollowUps,
  type AgentUiActionRequest,
  type AnalysisFollowUp
} from '@/views/ai-agent/utils/agentUi';

export default defineComponent({
  name: 'AgentMessageList',
  components: {
    AgentMessageRenderer,
    AnalysisResultCard,
    ToolConfirmCard,
    Check,
    CopyDocument,
    Document,
    Download,
    FullScreen,
    MagicStick,
    ReportFullscreenPreview,
    ReportHtmlView,
    ResultSetDisplay,
    SafeLegacyHtmlContent,
    SafeMarkdownContent,
    SuggestedReplies
  },
  props: {
    messages: {
      type: Array as PropType<ChatMessage[]>,
      required: true
    },
    resultSetPageSize: {
      type: Number,
      required: true
    },
    markdownOptions: {
      type: Object,
      required: true
    },
    isSubmittingMessage: {
      type: Boolean,
      required: true
    },
    hasSelectedImages: {
      type: Boolean,
      required: true
    },
    isStreaming: {
      type: Boolean,
      required: true
    },
    agentId: {
      type: String,
      default: ''
    },
    professionalLoadingRequestId: {
      type: String,
      default: ''
    }
  },
  emits: [
    'update:resultSetPageSize',
    'resendQuestion',
    'downloadReport',
    'professionalReport',
    'submitSuggestedReply',
    'flowAction'
  ],
  setup(props, { emit }) {
    const router = useRouter();
    const resultSetPageSizeModel = computed({
      get: () => props.resultSetPageSize,
      set: value => emit('update:resultSetPageSize', value)
    });
    const editingQuestionMessage = ref<ChatMessage | null>(null);
    const editingQuestionContent = ref('');
    const copiedQuestionKey = ref('');
    const knowledgeDialogVisible = ref(false);
    const knowledgeSaving = ref(false);
    const knowledgeSkillId = ref('');
    const knowledgeSkills = ref<AgentSkillBindingOption[]>([]);
    const knowledgeTarget = ref<ChatMessage | null>(null);
    const {
      showReportFullscreen,
      fullscreenReportContent,
      fullscreenReportFormat,
      fullscreenReportSourceFormat,
      openReportFullscreen,
      closeReportFullscreen
    } = useReportFullscreen();
    let copiedQuestionTimer: number | null = null;

    const getAgentUiMessage = (message: ChatMessage) => parseAgentUiFromMessage(message);
    const isAgentUiMessage = (message: ChatMessage) => Boolean(getAgentUiMessage(message));
    const hasMessageTable = (message: ChatMessage) => {
      const displayContent = resolveMessageDisplayContent(message);
      return displayContent.contentFormat === 'markdown' && hasMarkdownTable(displayContent.content);
    };
    const suggestedRepliesDisabled = computed(
      () => props.isSubmittingMessage || props.isStreaming || props.hasSelectedImages
    );
    const latestAssistantMessageKey = computed(() => {
      const index = props.messages.length - 1;
      if (index < 0) {
        return '';
      }
      const message = props.messages[index];
      return getMessageRole(message) === 'assistant' ? getMessageRenderKey(message, index) : '';
    });
    const latestWaitingFlowMessageKey = computed(() => {
      const index = props.messages.length - 1;
      if (index < 0) {
        return '';
      }
      const message = props.messages[index];
      if (getMessageRole(message) !== 'assistant') return '';
      const ui = getAgentUiMessage(message);
      return ui && isValidSkillFlowUi(ui) && isWaitingAgentUi(ui) ? getMessageRenderKey(message, index) : '';
    });

    const hasValidFlowCopy = (message: ChatMessage, index: number) => {
      const ui = getAgentUiMessage(message);
      if (!ui) return false;
      const identity = getAgentUiIdentity(ui);
      const fallbackIdentity = getAgentUiFallbackIdentity(ui);
      return props.messages.some((candidate, candidateIndex) => {
        if (candidateIndex === index) return false;
        const candidateUi = getAgentUiMessage(candidate);
        return (
          candidateUi &&
          isValidSkillFlowUi(candidateUi) &&
          (getAgentUiIdentity(candidateUi) === identity || getAgentUiFallbackIdentity(candidateUi) === fallbackIdentity)
        );
      });
    };

    const shouldHideFlowMessage = (message: ChatMessage, index: number) => {
      const ui = getAgentUiMessage(message);
      if (isAnalysisResultUi(ui)) return true;
      if (!ui || isToolConfirmUi(ui)) return false;
      return Boolean(!isValidSkillFlowUi(ui) && hasValidFlowCopy(message, index));
    };

    const isFlowActionDisabled = (message: ChatMessage, index: number) => {
      const ui = getAgentUiMessage(message);
      if (!ui || isAnalysisResultUi(ui) || isToolConfirmUi(ui) || !isValidSkillFlowUi(ui)) return true;
      return (
        props.isSubmittingMessage ||
        props.isStreaming ||
        props.hasSelectedImages ||
        getMessageRenderKey(message, index) !== latestWaitingFlowMessageKey.value
      );
    };

    const latestAnalysisMessageKey = computed(() => {
      for (let index = props.messages.length - 1; index >= 0; index -= 1) {
        const message = props.messages[index];
        if (getMessageRole(message) !== 'assistant') continue;
        if (isAnalysisResultUi(getAgentUiMessage(message))) {
          return getMessageRenderKey(message, index);
        }
      }
      return '';
    });

    const isAnalysisActionDisabled = (message: ChatMessage, index: number) => {
      const ui = getAgentUiMessage(message);
      if (!isAnalysisResultUi(ui)) return true;
      return (
        props.isSubmittingMessage ||
        props.isStreaming ||
        props.hasSelectedImages ||
        getMessageRenderKey(message, index) !== latestAnalysisMessageKey.value
      );
    };

    const latestToolConfirmMessageKey = computed(() => {
      for (let index = props.messages.length - 1; index >= 0; index -= 1) {
        const message = props.messages[index];
        if (getMessageRole(message) !== 'assistant') continue;
        if (isToolConfirmUi(getAgentUiMessage(message))) {
          return getMessageRenderKey(message, index);
        }
      }
      return '';
    });

    const isToolConfirmActionDisabled = (message: ChatMessage, index: number) => {
      const ui = getAgentUiMessage(message);
      if (!isToolConfirmUi(ui)) return true;
      return (
        props.isSubmittingMessage ||
        props.isStreaming ||
        props.hasSelectedImages ||
        getMessageRenderKey(message, index) !== latestToolConfirmMessageKey.value
      );
    };

    const canCopyQuestionMessage = (message: ChatMessage) => {
      return getMessageRole(message) === 'user' && isTextMessage(message) && Boolean(message.content?.trim());
    };

    const isQuestionCopied = (message: ChatMessage, index: number) => {
      return copiedQuestionKey.value === getMessageRenderKey(message, index);
    };

    const copyQuestionMessage = async (message: ChatMessage, index: number) => {
      const content = message.content?.trim();
      if (!content) {
        return;
      }
      try {
        await writeTextToClipboard(content);
        copiedQuestionKey.value = getMessageRenderKey(message, index);
        if (copiedQuestionTimer !== null) {
          window.clearTimeout(copiedQuestionTimer);
        }
        copiedQuestionTimer = window.setTimeout(() => {
          copiedQuestionKey.value = '';
          copiedQuestionTimer = null;
        }, 1600);
      } catch (error) {
        console.error('运行智能体处理失败:', error);
        ElMessage.error('操作失败，请稍后重试');
      }
    };

    const canEditQuestionMessage = (message: ChatMessage) => {
      return (
        getMessageRole(message) === 'user' &&
        isTextMessage(message) &&
        Boolean(message.content?.trim()) &&
        getMessageAttachments(message).length === 0
      );
    };

    const isEditingQuestion = (message: ChatMessage) => {
      return editingQuestionMessage.value === message;
    };

    const cancelEditQuestion = () => {
      editingQuestionMessage.value = null;
      editingQuestionContent.value = '';
    };

    const startEditQuestion = (message: ChatMessage) => {
      if (!canEditQuestionMessage(message)) {
        if (getMessageRole(message) === 'user' && getMessageAttachments(message).length > 0) {
          ElMessage.warning('当前操作暂不可用');
        }
        return;
      }
      editingQuestionMessage.value = message;
      editingQuestionContent.value = message.content;
      nextTick().then(() => {
        const input = document.querySelector('.question-edit-input textarea') as HTMLTextAreaElement | null;
        input?.focus();
        input?.select();
      });
    };

    const submitEditedQuestion = () => {
      const nextQuestion = editingQuestionContent.value.trim();
      if (!nextQuestion || props.hasSelectedImages) {
        ElMessage.warning('当前操作暂不可用');
        return;
      }
      cancelEditQuestion();
      emit('resendQuestion', nextQuestion);
    };

    const emitDownloadReport = (message: ChatMessage, format?: 'markdown' | 'html') => {
      emit('downloadReport', message, format);
    };

    const emitProfessionalReport = (message: ChatMessage) => {
      emit('professionalReport', message);
    };

    const getReportLevelBadge = (message: ChatMessage) => {
      const metadata = parseMessageMetadata(message) as Record<string, unknown> | null;
      if (metadata?.reportLevel !== 'professional') {
        return '';
      }
      return metadata.degraded === true ? '专业解读·标准兜底' : '专业解读';
    };

    const getReportFollowUps = (message: ChatMessage): AnalysisFollowUp[] => {
      const metadata = parseMessageMetadata(message) as Record<string, unknown> | null;
      const fromReport = parseAnalysisFollowUps(metadata);
      if (fromReport.length) {
        return fromReport;
      }
      const runtimeRequestId = getMessageRuntimeRequestId(message);
      if (!runtimeRequestId) {
        return [];
      }
      for (const candidate of props.messages) {
        const ui = getAgentUiMessage(candidate);
        if (!isAnalysisResultUi(ui) || getMessageRuntimeRequestId(candidate) !== runtimeRequestId) {
          continue;
        }
        return (ui.actions || [])
          .map(action => ({
            type: String(action.type || '').toUpperCase(),
            label: action.label,
            query: typeof action.payload?.query === 'string' ? action.payload.query : undefined,
            value: action.value
          }))
          .filter(item => ANALYSIS_ACTION_TYPES.has(item.type) && item.type !== 'NONE');
      }
      return [];
    };

    const isReportFollowUpDisabled = (_message: ChatMessage, _index: number) => {
      return props.isSubmittingMessage || props.isStreaming || props.hasSelectedImages;
    };

    const emitReportFollowUp = (followUp: AnalysisFollowUp) => {
      const text = followUpDisplayText(followUp);
      if (text) {
        emit('resendQuestion', text);
      }
    };

    const getMessageSuggestedReplies = (message: ChatMessage, index: number): SuggestedRepliesSchema | null => {
      if (getMessageRenderKey(message, index) !== latestAssistantMessageKey.value) {
        return null;
      }
      return normalizeSuggestedReplies(parseMessageMetadata(message) as Record<string, unknown> | null);
    };

    const emitSuggestedReply = (submission: SuggestedReplySubmission) => {
      emit('submitSuggestedReply', submission);
    };

    const emitFlowAction = (request: AgentUiActionRequest) => {
      emit('flowAction', request);
    };

    const emitAnalysisAction = (request: AgentUiActionRequest) => {
      const type = String(request.flowAction?.type || '').toUpperCase();
      const text = request.displayText?.trim();
      if ((type === 'DRILL' || type === 'START_FLOW' || type === 'ASK_WRITE') && text) {
        emit('resendQuestion', text);
      }
    };

    const getMessageRuntimeRequestId = (message: ChatMessage) => {
      const metadata = parseMessageMetadata(message) as Record<string, unknown> | null;
      const runtimeRequestId = message.runtimeRequestId || metadata?.runtimeRequestId;
      return typeof runtimeRequestId === 'string' && runtimeRequestId.trim() ? runtimeRequestId : '';
    };

    const getMessageDurationMs = (message: ChatMessage) => {
      if (typeof message.durationMs === 'number') {
        return message.durationMs;
      }
      const metadata = parseMessageMetadata(message) as Record<string, unknown> | null;
      const value = metadata?.durationMs;
      return typeof value === 'number' ? value : null;
    };

    const formatDurationMs = (durationMs: number | null) => {
      if (durationMs === null || Number.isNaN(durationMs)) {
        return '';
      }
      if (durationMs < 1000) {
        return `${Math.max(0, Math.round(durationMs))}ms`;
      }
      if (durationMs < 10000) {
        return `${(durationMs / 1000).toFixed(1)}s`;
      }
      return `${Math.round(durationMs / 1000)}s`;
    };

    const formatMessageDuration = (message: ChatMessage) => formatDurationMs(getMessageDurationMs(message));

    const knowledgeEligibleAttachments = (message: ChatMessage): MessageAttachment[] => {
      return getMessageFileAttachments(message).filter(
        attachment => isKnowledgeEligibleAttachmentType(attachment.type) && Boolean(attachment.storageKey)
      );
    };

    const canAddAttachmentsToKnowledge = (message: ChatMessage) => {
      return (
        getMessageRole(message) === 'user' && Boolean(props.agentId) && knowledgeEligibleAttachments(message).length > 0
      );
    };

    const openKnowledgeDialog = async (message: ChatMessage) => {
      if (!canAddAttachmentsToKnowledge(message)) {
        ElMessage.warning('当前附件不能加入知识库');
        return;
      }
      knowledgeTarget.value = message;
      knowledgeSkillId.value = '';
      knowledgeDialogVisible.value = true;
      try {
        const context = await skillService.getAgentBindingEditorContext(props.agentId);
        const enabledIds = new Set((context.bindings || []).filter(item => item.enabled).map(item => item.skillId));
        knowledgeSkills.value = (context.skills || []).filter(
          skill => skill.selectable && enabledIds.has(skill.skillId)
        );
        if (knowledgeSkills.value.length === 1) {
          knowledgeSkillId.value = knowledgeSkills.value[0].skillId;
        }
        if (knowledgeSkills.value.length === 0) {
          ElMessage.warning('当前智能体没有可写入的技能知识库');
        }
      } catch (error) {
        knowledgeDialogVisible.value = false;
        ElMessage.error(extractApiErrorMessage(error, '加载技能失败'));
      }
    };

    const submitKnowledgeIngest = async () => {
      const message = knowledgeTarget.value;
      const skillId = knowledgeSkillId.value;
      const attachments = message ? knowledgeEligibleAttachments(message) : [];
      if (!message || !skillId || attachments.length === 0) {
        ElMessage.warning('请选择要沉淀的技能');
        return;
      }
      knowledgeSaving.value = true;
      try {
        await Promise.all(
          attachments.map(attachment =>
            skillKnowledgeService.create(skillId, {
              title: String(attachment.fileName || '对话附件').replace(/\.[^.]+$/, '') || '对话附件',
              type: 'DOCUMENT',
              filePath: attachment.storageKey,
              sourceFilename: attachment.fileName,
              fileSize: attachment.size,
              fileType: attachment.contentType,
              splitterType: 'recursive'
            })
          )
        );
        ElMessage.success('已加入知识库，向量化任务已提交');
        knowledgeDialogVisible.value = false;
      } catch (error) {
        ElMessage.error(extractApiErrorMessage(error, '加入知识库失败'));
      } finally {
        knowledgeSaving.value = false;
      }
    };

    const canShowRuntimeMeta = (_message: ChatMessage) => {
      return false;
    };

    const openMessageDiagnostics = (message: ChatMessage) => {
      const runtimeRequestId = getMessageRuntimeRequestId(message);
      if (!message.sessionId || !runtimeRequestId) {
        ElMessage.warning('该消息缺少排障定位信息');
        return;
      }
      router.push({
        path: '/ai-agent/diagnostics',
        query: {
          sessionId: String(message.sessionId),
          runtimeRequestId
        }
      });
    };

    onBeforeUnmount(() => {
      if (copiedQuestionTimer !== null) {
        window.clearTimeout(copiedQuestionTimer);
      }
    });

    return {
      resultSetPageSizeModel,
      editingQuestionContent,
      CHAT_MESSAGE_TYPES,
      showReportFullscreen,
      fullscreenReportContent,
      fullscreenReportFormat,
      fullscreenReportSourceFormat,
      openReportFullscreen,
      closeReportFullscreen,
      getMessageRenderKey,
      isTextMessage,
      getMessageRole,
      resolveMessageDisplayContent,
      getMessageImageAttachments,
      getMessageFileAttachments,
      parseMessageMetadata,
      getAgentUiMessage,
      isAgentUiMessage,
      hasMessageTable,
      isValidSkillFlowUi,
      isAnalysisResultUi,
      isToolConfirmUi,
      shouldHideFlowMessage,
      getReportFollowUps,
      isReportFollowUpDisabled,
      emitReportFollowUp,
      isFlowActionDisabled,
      isAnalysisActionDisabled,
      isToolConfirmActionDisabled,
      suggestedRepliesDisabled,
      getMessageSuggestedReplies,
      canCopyQuestionMessage,
      isQuestionCopied,
      copyQuestionMessage,
      canEditQuestionMessage,
      isEditingQuestion,
      startEditQuestion,
      cancelEditQuestion,
      submitEditedQuestion,
      emitDownloadReport,
      emitProfessionalReport,
      getReportLevelBadge,
      emitSuggestedReply,
      emitFlowAction,
      emitAnalysisAction,
      getMessageRuntimeRequestId,
      formatMessageDuration,
      canShowRuntimeMeta,
      openMessageDiagnostics,
      knowledgeDialogVisible,
      knowledgeSaving,
      knowledgeSkillId,
      knowledgeSkills,
      canAddAttachmentsToKnowledge,
      openKnowledgeDialog,
      submitKnowledgeIngest
    };
  }
});
</script>

<style scoped>
.message-container {
  display: flex;
  max-width: 100%;
}

.message-container.user {
  justify-content: flex-end;
}

.message-container.assistant {
  justify-content: flex-start;
}

.message {
  display: flex;
  gap: var(--agent-run-message-gap);
  width: min(88%, 760px);
  max-width: 100%;
}

.message.user {
  align-self: flex-end;
  flex-direction: row-reverse;
  width: fit-content;
  max-width: min(72%, 680px);
}

.message.assistant {
  align-self: flex-start;
}

.message.message-wide {
  width: min(100%, 980px);
}

.message.message-table {
  width: 100%;
}

.message.message-table .question-message-block {
  width: 100%;
}

.message-avatar {
  flex: 0 0 var(--agent-run-message-avatar-size);
  width: var(--agent-run-message-avatar-size);
  height: var(--agent-run-message-avatar-size);
}

.assistant-logo-avatar {
  display: block;
  width: var(--agent-run-message-avatar-size);
  height: var(--agent-run-message-avatar-size);
  object-fit: cover;
  border: 1px solid var(--el-border-color-light);
  border-radius: 50%;
  background: var(--el-bg-color);
}

.user-text-avatar {
  border: 1px solid var(--el-border-color-light);
  background: var(--el-bg-color);
  color: var(--el-color-primary);
}

.message-content {
  flex: 1;
  min-width: 0;
}

.message.user .message-content {
  flex: 0 1 auto;
  width: fit-content;
  max-width: 100%;
}

.message-content.question-message-editable {
  cursor: text;
}

.message-text {
  padding: 10px 14px;
  color: var(--el-text-color-primary);
  border-radius: 8px;
  line-height: 1.6;
  word-wrap: break-word;
}

.message.user .message-text {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border: 1px solid var(--el-color-primary-light-7);
  box-shadow: none;
}

.message.assistant .message-text {
  color: var(--el-text-color-primary);
  background: transparent;
  border: 0;
}

.assistant-reading-surface {
  padding: 3px 2px;
  border-left: 0 !important;
}

.plain-message-text {
  white-space: pre-wrap;
}

.message.assistant .plain-message-text,
.legacy-message-content {
  padding: 10px 14px;
  border: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.message-content.question-message-editable:hover .message-text {
  border-color: var(--el-color-primary-light-5);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-9);
}

.question-message-block {
  display: inline-flex;
  max-width: 100%;
  flex-direction: column;
  align-items: stretch;
}

.question-message-actions {
  align-self: flex-end;
  display: inline-flex;
  margin-top: 6px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-extra-light);
  border-color: var(--el-border-color-light);
  box-shadow: none;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.16s ease;
}

.message-content.question-message-editable:hover .question-message-actions,
.message-content.question-message-editable:focus-within .question-message-actions,
.question-message-actions.copied {
  opacity: 1;
  pointer-events: auto;
}

.question-copy-button {
  width: 28px;
  height: 28px;
  padding: 0;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-light);
  box-shadow: none;
}

.question-copy-button:hover,
.question-copy-button:focus,
.question-copy-button.copied,
.question-copy-button.copied:hover,
.question-copy-button.copied:focus {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  border-color: var(--el-color-primary-light-7);
}

.question-copy-button :deep(.el-icon) {
  margin-right: 0;
}

.question-edit-panel {
  width: min(560px, 100%);
  padding: 10px;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
  box-shadow: none;
}

.question-edit-input :deep(.el-textarea__inner) {
  min-height: 76px !important;
  color: var(--el-text-color-primary);
  background: var(--el-bg-color);
  border-color: var(--el-border-color);
  border-radius: 7px;
  box-shadow: none;
  line-height: 1.6;
}

.question-edit-input :deep(.el-textarea__inner:focus) {
  border-color: var(--el-color-primary);
  box-shadow: 0 0 0 1px var(--el-color-primary-light-5);
}

.question-edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 8px;
}

.thinking-message-inline {
  max-width: 100%;
  align-self: flex-start;
  margin-left: 0;
}

.legacy-message-content {
  line-height: 1.65;
  overflow-wrap: anywhere;
}

.result-set-message {
  width: 100%;
}

.markdown-report-message {
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  margin-bottom: 16px;
  padding: 16px;
  background: #fffef8;
  border: 1px solid #d8e7d4;
  border-radius: 10px;
}

.markdown-report-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid #e4eee1;
}

.markdown-report-content {
  margin-top: 16px;
}

.report-info {
  display: flex;
  align-items: center;
  gap: 12px;
  color: var(--el-color-primary);
  font-size: 16px;
  font-weight: 500;
}

.report-level-badge {
  padding: 1px 8px;
  border: 1px solid color-mix(in srgb, var(--el-color-warning) 55%, transparent);
  border-radius: 999px;
  background: color-mix(in srgb, var(--el-color-warning) 14%, transparent);
  color: var(--el-color-warning);
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
}

.message-attachment-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.message-attachment-image {
  display: block;
  width: 96px;
  height: 96px;
  overflow: hidden;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.message-attachment-image img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.message-attachment-file {
  display: inline-flex;
  max-width: 220px;
  align-items: center;
  gap: 6px;
  padding: 8px 10px;
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-size: 12px;
  text-decoration: none;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.message-attachment-file span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.message-knowledge-button {
  align-self: center;
}

.knowledge-dialog-hint {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.5;
}

.knowledge-dialog-select {
  width: 100%;
}

.message-runtime-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.message-runtime-duration {
  font-variant-numeric: tabular-nums;
}

.report-message-runtime-meta {
  margin: 6px 0 10px;
}

@media (max-width: 768px) {
  .message,
  .message.user,
  .message.message-wide {
    width: 100%;
    max-width: 100%;
  }

  .message-avatar {
    flex-basis: 28px;
    width: 28px;
    height: 28px;
  }

  .assistant-logo-avatar,
  .user-text-avatar {
    width: 28px;
    height: 28px;
  }

  .markdown-report-message {
    padding: 12px;
  }

  .markdown-report-header {
    align-items: flex-start;
    gap: 10px;
    flex-direction: column;
  }
}
</style>
