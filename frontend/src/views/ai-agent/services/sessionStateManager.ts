/*
 * Copyright 2024-2025 the original author or authors.
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
 */

import { ref, type Ref } from 'vue';
import type { AgentResponse, AgentRequest, RuntimeProgressEvent } from '@/views/ai-agent/services/graph';
import type { AnswerTraceExplain } from '@/views/ai-agent/services/chat';

export type SessionResponseMode = 'normal' | 'report';

export interface PendingClarifyState {
  originalQuery: string;
  riskLevel: string;
  summary?: string;
  missingDimensions: string[];
  followUpQuestions: string[];
  suggestedAssumptions: string[];
}

export interface SessionRuntimeState {
  isStreaming: boolean;
  nodeBlocks: AgentResponse[][];
  runtimeProgressEvents: RuntimeProgressEvent[];
  persistedBlockCount: number;
  closeStream: (() => void) | null;
  lastRequest: AgentRequest | null;
  pendingClarify: PendingClarifyState | null;
  queuedRequests: QueuedRequestState[];
  htmlReportContent: string;
  htmlReportSize: number;
  markdownReportContent: string;
  answerExplain: AnswerTraceExplain | null;
  answerExplainVisible: boolean;
  responseMode: SessionResponseMode;
  /** 当前会话进行中的持久运行时 run id（durable 链路），刷新后据此 reconcile */
  activeRuntimeRunId: string | null;
  /** 本地已收到的最大持久事件 seq，刷新/断线重连时作为 afterSeq 回放游标 */
  lastRuntimeEventSeq: number;
}

export interface QueuedRequestState {
  id: string;
  sessionId: string;
  content: string;
  createdAt: number;
  request: AgentRequest;
}

// 可持久化的状态字段（不包括函数和临时状态）
interface PersistableState {
  nodeBlocks: AgentResponse[][];
  persistedBlockCount: number;
  lastRequest: AgentRequest | null;
  pendingClarify: PendingClarifyState | null;
  queuedRequests: QueuedRequestState[];
  htmlReportContent: string;
  htmlReportSize: number;
  markdownReportContent: string;
  answerExplain: AnswerTraceExplain | null;
  answerExplainVisible: boolean;
  responseMode: SessionResponseMode;
  activeRuntimeRunId: string | null;
  lastRuntimeEventSeq: number;
}

const STORAGE_KEY_PREFIX = 'session_state_';
const MAX_STORAGE_SIZE_MB = 4; // 最大存储 4MB
const MAX_NODE_BLOCKS = 10; // 最多保存 10 个 nodeBlocks

/**
 * 从 sessionStorage 加载状态
 */
function loadStateFromStorage(sessionId: string): Partial<PersistableState> | null {
  try {
    const key = STORAGE_KEY_PREFIX + sessionId;
    const stored = sessionStorage.getItem(key);
    if (stored) {
      return JSON.parse(stored);
    }
  } catch (error) {
    console.error('加载会话状态失败:', error);
  }
  return null;
}

/**
 * 保存状态到 sessionStorage（带大小限制）
 */
function saveStateToStorage(sessionId: string, state: SessionRuntimeState) {
  try {
    const key = STORAGE_KEY_PREFIX + sessionId;

    // 只保存最近的 nodeBlocks，避免数据过大
    const persistable: PersistableState = {
      nodeBlocks: state.nodeBlocks.slice(-MAX_NODE_BLOCKS),
      persistedBlockCount: state.persistedBlockCount,
      lastRequest: state.lastRequest,
      pendingClarify: state.pendingClarify,
      queuedRequests: state.queuedRequests,
      htmlReportContent: state.htmlReportContent,
      htmlReportSize: state.htmlReportSize,
      markdownReportContent: state.markdownReportContent,
      answerExplain: state.answerExplain,
      answerExplainVisible: state.answerExplainVisible,
      responseMode: state.responseMode,
      activeRuntimeRunId: state.activeRuntimeRunId,
      lastRuntimeEventSeq: state.lastRuntimeEventSeq
    };

    const json = JSON.stringify(persistable);
    const sizeInMB = new Blob([json]).size / (1024 * 1024);

    // 检查大小限制
    if (sizeInMB > MAX_STORAGE_SIZE_MB) {
      console.warn(`会话状态过大 (${sizeInMB.toFixed(2)}MB)，跳过保存`);
      return;
    }

    sessionStorage.setItem(key, json);
  } catch (error) {
    console.error('保存会话状态失败:', error);
  }
}

/**
 * 从 sessionStorage 删除状态
 */
function removeStateFromStorage(sessionId: string) {
  try {
    const key = STORAGE_KEY_PREFIX + sessionId;
    sessionStorage.removeItem(key);
  } catch (error) {
    console.error('删除会话状态失败:', error);
  }
}

/**
 * 会话状态管理器
 * 用于管理运行中的会话，实现会话隔离
 */
export function useSessionStateManager() {
  const sessionStates = ref<Map<string, SessionRuntimeState>>(new Map());

  /**
   * 获取会话运行状态
   */
  const getSessionState = (sessionId: string): SessionRuntimeState => {
    if (!sessionStates.value.has(sessionId)) {
      // 尝试从 sessionStorage 加载
      const stored = loadStateFromStorage(sessionId);

      sessionStates.value.set(sessionId, {
        isStreaming: false,
        nodeBlocks: stored?.nodeBlocks ?? [],
        runtimeProgressEvents: [],
        persistedBlockCount: stored?.persistedBlockCount ?? 0,
        closeStream: null,
        lastRequest: stored?.lastRequest ?? null,
        pendingClarify: stored?.pendingClarify ?? null,
        queuedRequests: stored?.queuedRequests ?? [],
        htmlReportContent: stored?.htmlReportContent ?? '',
        htmlReportSize: stored?.htmlReportSize ?? 0,
        markdownReportContent: stored?.markdownReportContent ?? '',
        answerExplain: stored?.answerExplain ?? null,
        answerExplainVisible: stored?.answerExplainVisible ?? false,
        responseMode: stored?.responseMode === 'report' ? 'report' : 'normal',
        activeRuntimeRunId: stored?.activeRuntimeRunId ?? null,
        lastRuntimeEventSeq: stored?.lastRuntimeEventSeq ?? 0
      });
    }
    return sessionStates.value.get(sessionId)!;
  };

  /**
   * 将会话状态同步到页面状态
   */
  const syncStateToView = (
    sessionId: string,
    viewState: {
      isStreaming: Ref<boolean>;
      nodeBlocks: Ref<AgentResponse[][]>;
      runtimeProgressEvents?: Ref<RuntimeProgressEvent[]>;
      answerExplain?: Ref<AnswerTraceExplain | null>;
      answerExplainVisible?: Ref<boolean>;
      pendingClarify?: Ref<PendingClarifyState | null>;
      queuedRequests?: Ref<QueuedRequestState[]>;
      responseMode?: Ref<SessionResponseMode>;
    }
  ) => {
    const state = getSessionState(sessionId);
    viewState.isStreaming.value = state.isStreaming;
    viewState.nodeBlocks.value = state.nodeBlocks;
    if (viewState.runtimeProgressEvents) {
      viewState.runtimeProgressEvents.value = state.runtimeProgressEvents;
    }
    if (viewState.answerExplain) {
      viewState.answerExplain.value = state.answerExplain;
    }
    if (viewState.answerExplainVisible) {
      viewState.answerExplainVisible.value = state.answerExplainVisible;
    }
    if (viewState.pendingClarify) {
      viewState.pendingClarify.value = state.pendingClarify;
    }
    if (viewState.queuedRequests) {
      viewState.queuedRequests.value = state.queuedRequests;
    }
    if (viewState.responseMode) {
      viewState.responseMode.value = state.responseMode;
    }
  };

  /**
   * 保存页面状态到会话
   */
  const saveViewToState = (
    sessionId: string,
    viewState: {
      isStreaming: Ref<boolean>;
      nodeBlocks: Ref<AgentResponse[][]>;
      runtimeProgressEvents?: Ref<RuntimeProgressEvent[]>;
      answerExplain?: Ref<AnswerTraceExplain | null>;
      answerExplainVisible?: Ref<boolean>;
      pendingClarify?: Ref<PendingClarifyState | null>;
      queuedRequests?: Ref<QueuedRequestState[]>;
      responseMode?: SessionResponseMode;
    }
  ) => {
    const state = getSessionState(sessionId);
    state.isStreaming = viewState.isStreaming.value;
    state.nodeBlocks = viewState.nodeBlocks.value;
    if (viewState.runtimeProgressEvents) {
      state.runtimeProgressEvents = viewState.runtimeProgressEvents.value;
    }
    if (viewState.answerExplain) {
      state.answerExplain = viewState.answerExplain.value;
    }
    if (viewState.answerExplainVisible) {
      state.answerExplainVisible = viewState.answerExplainVisible.value;
    }
    if (viewState.pendingClarify) {
      state.pendingClarify = viewState.pendingClarify.value;
    }
    if (viewState.queuedRequests) {
      state.queuedRequests = viewState.queuedRequests.value;
    }
    if (viewState.responseMode) {
      state.responseMode = viewState.responseMode;
    }

    // 保存到 sessionStorage（带大小限制）
    saveStateToStorage(sessionId, state);
  };

  /**
   * 将当前内存态直接持久化到 sessionStorage。
   * 供 durable 事件流在无用户交互时同步 activeRuntimeRunId / lastRuntimeEventSeq 游标，刷新后可续传。
   */
  const persistSessionState = (sessionId: string) => {
    const state = sessionStates.value.get(sessionId);
    if (!state) {
      return;
    }
    saveStateToStorage(sessionId, state);
  };

  /**
   * 删除会话状态
   */
  const deleteSessionState = (sessionId: string) => {
    const state = sessionStates.value.get(sessionId);
    if (state?.closeStream) {
      state.closeStream();
    }
    sessionStates.value.delete(sessionId);
    // 同时删除 sessionStorage 中的数据
    removeStateFromStorage(sessionId);
  };

  /**
   * 获取所有正在运行的会话ID
   */
  const getRunningSessionIds = (): string[] => {
    const runningIds: string[] = [];
    sessionStates.value.forEach((state, sessionId) => {
      if (state.isStreaming) {
        runningIds.push(sessionId);
      }
    });
    return runningIds;
  };

  return {
    sessionStates,
    getSessionState,
    syncStateToView,
    saveViewToState,
    persistSessionState,
    deleteSessionState,
    getRunningSessionIds
  };
}
