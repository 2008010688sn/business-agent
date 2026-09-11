<!--
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
-->

<template>
  <div class="conversation-tab">
    <ElAlert
      class="mb-12"
      :type="sandboxHint.type"
      :closable="false"
      :title="sandboxHint.title"
      :description="sandboxHint.description"
    />

    <div ref="messageListRef" class="message-list">
      <ElEmpty v-if="messages.length === 0" description="暂无对话，发送第一条消息试试" />
      <div v-for="(message, index) in messages" :key="index" :class="['message-row', message.role === 'user' ? 'mine' : '']">
        <div class="message-bubble">
          <div class="message-meta">
            <span>{{ message.role === 'user' ? '我' : employee.employeeName || '数字员工' }}</span>
            <span v-if="message.executionMode" class="mode-chip" :data-mode="message.executionMode">
              {{ executionModeLabel(message.executionMode) }}
            </span>
          </div>
          <div v-if="message.text" class="message-text">{{ message.text }}</div>
          <div v-if="message.agentUi && isToolConfirmUi(message.agentUi)" class="tool-confirm-wrap">
            <ToolConfirmCard
              :ui="message.agentUi!"
              :disabled="sending || index !== latestToolConfirmIndex"
            />
          </div>
          <ElButton
            v-if="message.runtimeRunId"
            class="run-link"
            link
            type="primary"
            size="small"
            @click="openRun(message.runtimeRunId)"
          >
            查看本次运行
          </ElButton>
        </div>
      </div>
    </div>

    <div class="conversation-input">
      <div class="session-info">
        <span class="cell-muted">
          会话：{{ sessionId || '新会话（首轮回传 sessionId）' }}
        </span>
        <ElSelect
          v-model="selectedChatModelConfigId"
          clearable
          filterable
          size="small"
          class="model-select"
          placeholder="默认模型"
          :loading="chatModelsLoading"
          :disabled="sending || !canUse"
        >
          <ElOption
            v-for="item in chatModelOptions"
            :key="String(item.modelConfigId)"
            :label="runtimeModelLabel(item)"
            :value="String(item.modelConfigId)"
          />
        </ElSelect>
        <ElButton v-if="sessionId" link type="primary" size="small" @click="startNewSession">开新会话</ElButton>
      </div>
      <div class="input-row">
        <ElInput
          v-model="draft"
          type="textarea"
          :rows="2"
          maxlength="4000"
          :placeholder="canUse ? '输入消息，Enter 发送 / Shift+Enter 换行' : '当前账号没有对话权限'"
          :disabled="sending || !canUse"
          @keydown.enter.exact.prevent="send"
        />
        <ElButton type="primary" :loading="sending" :disabled="!canUse || !draft.trim()" @click="send">发送</ElButton>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import ToolConfirmCard from '@/views/ai-agent/components/run/ToolConfirmCard.vue';
import digitalEmployeeService from '@/views/ai-agent/services/digitalEmployee';
import type { DigitalEmployee, EmployeeRuntimeChatModel } from '@/views/ai-agent/services/digitalEmployee';
import { extractApiErrorMessage } from '@/views/ai-agent/services/common';
import GraphService from '@/views/ai-agent/services/graph';
import { isToolConfirmUi, type AgentUiMessage } from '@/views/ai-agent/utils/agentUi';
import { EXECUTION_MODE_LABELS } from '../../employee-support';

defineOptions({ name: 'EmployeeConversationTab' });

const props = defineProps<{
  employee: DigitalEmployee;
  canUse: boolean;
}>();

interface ConversationMessage {
  role: 'user' | 'assistant';
  text: string;
  /** 仅助手消息携带：本次响应的执行模式（MODEL_ONLY/PRINCIPAL） */
  executionMode?: string;
  runtimeRunId?: string;
  agentUi?: AgentUiMessage;
  runtimeRequestId?: string;
}

interface ActiveStopContext {
  agentId: string;
  threadId: string;
  runtimeRequestId: string;
}

const router = useRouter();

const messages = ref<ConversationMessage[]>([]);
const draft = ref('');
const sending = ref(false);
const sessionId = ref('');
const messageListRef = ref<HTMLElement | null>(null);
let closeStream: (() => void) | null = null;
let settleCurrentStream: (() => void) | null = null;
let activeStopContext: ActiveStopContext | null = null;
let sendSeq = 0;
const chatModelOptions = ref<EmployeeRuntimeChatModel[]>([]);
const chatModelsLoading = ref(false);
const selectedChatModelConfigId = ref('');

const latestToolConfirmIndex = computed(() => {
  for (let index = messages.value.length - 1; index >= 0; index -= 1) {
    if (isToolConfirmUi(messages.value[index]?.agentUi)) {
      return index;
    }
  }
  return -1;
});

const executionModeLabel = (mode?: string) => EXECUTION_MODE_LABELS[mode || ''] || mode || '';
const canUse = computed(() => props.canUse);
const hasProductionRelease = ref(false);

const runtimeModelLabel = (item: EmployeeRuntimeChatModel) => {
  const name = item.modelConfig?.modelName || String(item.modelConfigId || '');
  const provider = item.modelConfig?.provider ? `（${item.modelConfig.provider}）` : '';
  return item.isDefault ? `${name}${provider} · 默认` : `${name}${provider}`;
};

const sandboxHint = computed(() => {
  if (!canUse.value) {
    return {
      type: 'warning' as const,
      title: '无对话权限',
      description: '当前账号缺少 ai-agent:digital-employee:use，可查看本页但不能发送。Tab 不会被卸载。'
    };
  }
  if (props.employee.rolloutEnabled === false) {
    return {
      type: 'warning' as const,
      title: '尚未上岗，仅试对话',
      description: '灰度开关关闭，Facade 仅走纯模型对话，不会以员工 Principal 执行，也查不了业务库。'
    };
  }
  if (!hasProductionRelease.value) {
    return {
      type: 'warning' as const,
      title: '尚未上岗，仅试对话',
      description: '生产环境尚无活跃发布。仍走 Facade 试对话；发布当前配置且 Principal 就绪后才进入 PRINCIPAL。'
    };
  }
  if (props.employee.principalStatus !== 'READY') {
    return {
      type: 'info' as const,
      title: '尚未上岗，仅试对话',
      description: '已有生产部署，但 Principal 未就绪，本轮将降级为 MODEL_ONLY，无业务工具。'
    };
  }
  return {
    type: 'info' as const,
    title: '流式对话（Facade）',
    description: '生产已部署且 Principal 就绪时以员工身份执行（PRINCIPAL）；否则为纯模型。不走 /stream/search。'
  };
});

async function loadRuntimeChatModels() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  chatModelsLoading.value = true;
  try {
    chatModelOptions.value = await digitalEmployeeService.listRuntimeChatModels(employeeId);
    const defaultItem = chatModelOptions.value.find(item => item.isDefault && item.modelConfigId);
    if (!selectedChatModelConfigId.value && defaultItem?.modelConfigId) {
      selectedChatModelConfigId.value = String(defaultItem.modelConfigId);
    }
  } catch {
    chatModelOptions.value = [];
  } finally {
    chatModelsLoading.value = false;
  }
}

async function loadProductionDeployment() {
  const employeeId = props.employee.id;
  if (!employeeId) return;
  try {
    const deployment = await digitalEmployeeService.findCurrentDeployment(employeeId, 'PRODUCTION');
    hasProductionRelease.value = Boolean(deployment?.activeReleaseId);
  } catch {
    hasProductionRelease.value = false;
  }
}

function rememberRuntimeRequestId(id: string) {
  if (!id || !activeStopContext) {
    return;
  }
  activeStopContext.runtimeRequestId = id;
}

function rememberThreadId(id: string) {
  if (!id || !activeStopContext) {
    return;
  }
  activeStopContext.threadId = id;
}

/** 有 runtimeRequestId + sessionId 时先 POST /ai/chat/runtime/stop，失败只记日志仍关 SSE。 */
async function stopActiveStream() {
  const settle = settleCurrentStream;
  const closer = closeStream;
  const ctx = activeStopContext;
  settleCurrentStream = null;
  closeStream = null;
  activeStopContext = null;
  settle?.();
  if (ctx?.agentId && ctx.threadId && ctx.runtimeRequestId) {
    try {
      await GraphService.stopRuntime({
        agentId: ctx.agentId,
        threadId: ctx.threadId,
        runtimeRequestId: ctx.runtimeRequestId
      });
    } catch (cancelError) {
      console.error('取消员工对话失败, runtimeRequestId=%s:', ctx.runtimeRequestId, cancelError);
      ElMessage.warning('服务端取消请求失败，任务可能仍在后台运行');
    }
  }
  closer?.();
}

/** 切换员工时重置会话状态（Tab 懒加载 + 员工切换兜底） */
watch(
  () => props.employee.id,
  () => {
    sendSeq += 1;
    void stopActiveStream();
    messages.value = [];
    sessionId.value = '';
    draft.value = '';
    sending.value = false;
    selectedChatModelConfigId.value = '';
    loadProductionDeployment();
    loadRuntimeChatModels();
  }
);

onMounted(() => {
  loadProductionDeployment();
  loadRuntimeChatModels();
});

onUnmounted(() => {
  sendSeq += 1;
  void stopActiveStream();
});

async function startNewSession() {
  sendSeq += 1;
  await stopActiveStream();
  sessionId.value = '';
  messages.value = [];
  sending.value = false;
}

function openRun(runtimeRunId: string) {
  const employeeId = String(props.employee.id || '').trim();
  if (!employeeId || !runtimeRunId) return;
  router.push({
    path: '/ai-agent/digital-employees/detail',
    query: { id: employeeId, tab: 'runtime', runtimeRunId }
  });
}

async function send() {
  const query = draft.value.trim();
  const employeeId = props.employee.id;
  if (!canUse.value || !query || !employeeId) return;

  draft.value = '';
  const seq = ++sendSeq;
  await stopActiveStream();
  if (seq !== sendSeq) return;

  messages.value.push({ role: 'user', text: query });
  const assistant: ConversationMessage = { role: 'assistant', text: '' };
  messages.value.push(assistant);
  sending.value = true;
  activeStopContext = {
    agentId: String(employeeId),
    threadId: sessionId.value || '',
    runtimeRequestId: ''
  };
  scrollToBottom();

  let settled = false;
  const done = new Promise<void>((resolve, reject) => {
    const finish = (error?: Error) => {
      if (settled) {
        return;
      }
      settled = true;
      if (error) {
        reject(error);
      } else {
        resolve();
      }
    };
    settleCurrentStream = () => finish();
    digitalEmployeeService
      .converseStream(
        employeeId,
        {
          query,
          sessionId: sessionId.value || undefined,
          chatModelConfigId: selectedChatModelConfigId.value || undefined
        },
        {
          onStarted(info) {
            if (seq !== sendSeq) return;
            if (info.sessionId) {
              sessionId.value = String(info.sessionId);
              rememberThreadId(String(info.sessionId));
            }
            if (info.executionMode) {
              assistant.executionMode = info.executionMode;
            }
            if (info.runtimeRunId) {
              assistant.runtimeRunId = String(info.runtimeRunId);
            }
            if (info.runtimeRequestId) {
              assistant.runtimeRequestId = info.runtimeRequestId;
              rememberRuntimeRequestId(info.runtimeRequestId);
            }
          },
          onMessage(text) {
            if (seq !== sendSeq) return;
            assistant.text += text;
            scrollToBottom();
          },
          onAgentUi(ui) {
            if (seq !== sendSeq) return;
            assistant.agentUi = ui;
            if (ui.runtimeRequestId) {
              assistant.runtimeRequestId = ui.runtimeRequestId;
              rememberRuntimeRequestId(ui.runtimeRequestId);
            }
            scrollToBottom();
          },
          onRuntimeRequestId(id) {
            if (seq !== sendSeq) return;
            assistant.runtimeRequestId = id;
            rememberRuntimeRequestId(id);
          },
          onComplete() {
            finish();
          },
          onError(error) {
            finish(error);
          }
        }
      )
      .then(close => {
        if (seq !== sendSeq) {
          close();
          return;
        }
        closeStream = close;
      })
      .catch(error => finish(error instanceof Error ? error : new Error(String(error))));
  });

  try {
    await done;
    if (seq !== sendSeq) return;
    if (!assistant.text && !assistant.agentUi) {
      assistant.text = '（空回复）';
    }
  } catch (error) {
    if (seq !== sendSeq) return;
    const fallback = extractApiErrorMessage(error, '对话服务异常');
    assistant.text = assistant.text ? `${assistant.text}\n请求失败：${fallback}` : `请求失败：${fallback}`;
  } finally {
    if (seq === sendSeq) {
      closeStream = null;
      settleCurrentStream = null;
      activeStopContext = null;
      sending.value = false;
    }
    scrollToBottom();
  }
}

function scrollToBottom() {
  void nextTick(() => {
    messageListRef.value?.scrollTo({ top: messageListRef.value.scrollHeight, behavior: 'smooth' });
  });
}
</script>

<style scoped>
.conversation-tab {
  display: flex;
  overflow: hidden;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 320px;
}

.message-list {
  display: flex;
  overflow: auto;
  flex: 1 1 auto;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  padding: 4px;
}

.message-row {
  display: flex;
}

.message-row.mine {
  justify-content: flex-end;
}

.message-bubble {
  max-width: 72%;
  padding: 8px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
}

.mine .message-bubble {
  background: var(--el-color-primary-light-9);
}

.message-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mode-chip {
  padding: 0 6px;
  border-radius: 8px;
  font-size: 11px;
  line-height: 18px;
}

.mode-chip[data-mode='PRINCIPAL'] {
  background: var(--el-color-success-light-8);
  color: var(--el-color-success-dark-2);
}

.mode-chip[data-mode='MODEL_ONLY'] {
  background: var(--el-color-info-light-8);
  color: var(--el-color-info-dark-2);
}

.message-text {
  color: var(--el-text-color-primary);
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.tool-confirm-wrap {
  margin-top: 8px;
}

.message-bubble :deep(.tool-confirm-card) {
  width: 100%;
  max-width: 100%;
}

.run-link {
  margin-top: 6px;
}

.conversation-input {
  padding-top: 10px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.session-info {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.model-select {
  width: 220px;
}

.input-row {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.input-row :deep(.el-textarea) {
  flex: 1 1 auto;
}

.cell-muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mb-12 {
  margin-bottom: 12px;
}
</style>
