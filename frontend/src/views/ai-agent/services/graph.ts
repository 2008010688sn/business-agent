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

import { Http } from '@/service/request';
import { unwrapData, type ApiResponse } from './common';
import { streamWithAuth } from './http';
import type { ModelConfigId } from './modelConfig';
import type { ChatMessage } from './chat';
import { suiteFileUploadApi } from './suiteFileUpload';
import runtimeRunService from './runtimeRun';
import type { RuntimeRunAttachOptions, RuntimeRunAttachment } from './runtimeRun';
import {
  createRuntimeStreamError,
  parseRuntimeErrorEvent,
  type RuntimeErrorMetadata
} from '@/views/ai-agent/utils/runtimeError';
import { resolveChatAttachmentType } from '@/views/ai-agent/utils/chatAttachment';

export interface ChatAttachment {
  type: string;
  storageKey?: string;
  url?: string;
  previewUrl?: string;
  contentType?: string;
  fileName?: string;
  size?: number;
}

export interface AgentPageContext {
  keys: Record<string, string>;
  objectType?: string;
}

export interface AgentRequest {
  agentId: string;
  threadId?: string;
  runtimeRequestId?: string;
  chatModelConfigId?: ModelConfigId;
  query: string;
  responseMode?: 'normal' | 'report';
  clarifyCheckEnabled?: boolean;
  humanFeedback?: boolean;
  humanFeedbackContent?: string;
  clarificationResponse?: ClarificationResponse;
  pageContext?: AgentPageContext;
  rejectedPlan: boolean;
  attachments?: ChatAttachment[];
  flowInstanceId?: string;
  flowAction?: FlowAction;
}

export interface ClarificationResponse {
  schemaVersion: 'business-clarify/v1' | 'confirm/v1';
  clarificationId: string;
  optionIds?: string[];
  freeText?: string;
}

export interface FlowAction {
  actionId: string;
  type: string;
  value?: unknown;
  payload?: Record<string, unknown>;
}

export interface ClarifyMetadata {
  clarifyRequired?: boolean;
  riskLevel?: string;
  originalQuery?: string;
  missingDimensions?: string[];
  followUpQuestions?: string[];
  suggestedAssumptions?: string[];
  summary?: string;
}

export interface AgentResponse {
  agentId: string;
  threadId: string;
  nodeName: string;
  textType: TextType;
  text: string;
  metadata?: ClarifyMetadata & Record<string, any>;
  error: boolean;
  complete: boolean;
}

export type StreamErrorMetadata = RuntimeErrorMetadata;

export interface RuntimeProgressEvent {
  eventType: 'runtime_progress';
  runtimeRequestId?: string;
  seq?: number;
  stageCode: string;
  status?: 'running' | 'success' | 'failed' | 'cancelled' | 'waiting' | string;
  elapsedMs?: number;
  durationMs?: number | null;
  displayName?: string | null;
  toolExecutionSeq?: number;
  errorCode?: string;
  orchestrationRunId?: string;
  orchestrationStepId?: string;
  collaboratorName?: string;
  collaboratorRole?: string;
  childRuntimeRequestId?: string;
  flowInstanceId?: string;
  nodeId?: string;
  resolverId?: string;
  clientReceivedAtMs?: number;
}

interface StreamHandlers {
  onMessage: (response: AgentResponse) => Promise<void>;
  onError?: (error: Error) => Promise<void>;
  onComplete?: () => Promise<void>;
  onUserMessage?: (message: ChatMessage) => Promise<void> | void;
  onRuntimeProgress?: (progress: RuntimeProgressEvent) => Promise<void> | void;
}

export enum TextType {
  JSON = 'JSON',
  PYTHON = 'PYTHON',
  SQL = 'SQL',
  HTML = 'HTML',
  MARK_DOWN = 'MARK_DOWN',
  RESULT_SET = 'RESULT_SET',
  TEXT = 'TEXT'
}

const API_BASE_URL = '/ai';
const STREAM_EVENT_RUNTIME_PROGRESS = 'runtime_progress';

class GraphService {
  /**
   * 流式搜索处理
   * @param request 图请求参数
   * @param onMessage 消息回调函数
   * @param onError 错误回调函数
   * @param onComplete 完成回调函数
   * @returns 关闭连接的函数
   */
  async stopRuntime(request: Pick<AgentRequest, 'agentId' | 'threadId' | 'runtimeRequestId'>): Promise<boolean> {
    const response = await Http.post(`${API_BASE_URL}/chat/runtime/stop`, request);
    return unwrapData(response as ApiResponse<boolean>) === true;
  }

  async streamSearch(request: AgentRequest, handlers: StreamHandlers): Promise<() => void> {
    return this.openStream(`${API_BASE_URL}/stream/search`, handlers, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(request)
    });
  }

  async streamSearchMultipart(request: AgentRequest, files: File[], handlers: StreamHandlers): Promise<() => void> {
    const attachments = await Promise.all(
      files.map(async file => {
        const type = resolveChatAttachmentType(file);
        if (!type) {
          throw createRuntimeStreamError(`不支持的附件类型：${file.name || '未知文件'}`);
        }
        const uploaded = await suiteFileUploadApi.upload(file);
        return {
          type,
          storageKey: uploaded.path,
          url: uploaded.previewUrl,
          previewUrl: uploaded.previewUrl,
          contentType: uploaded.contentType || file.type,
          fileName: uploaded.fileName || file.name,
          size: uploaded.size || file.size
        } satisfies ChatAttachment;
      })
    );

    return this.streamSearch(
      {
        ...request,
        attachments
      },
      handlers
    );
  }

  /**
   * 新 durable 链路：附着持久运行时事件流（断线自动重连、afterSeq 回放、按 seq 去重）。
   * 老链路 /ai/stream/search 契约保持不变，durable 运行的事件订阅统一走本入口。
   */
  attachRuntimeRun(options: RuntimeRunAttachOptions): RuntimeRunAttachment {
    return runtimeRunService.attachRunEvents(options);
  }

  private async openStream(
    url: string,
    handlers: StreamHandlers,
    init?: Omit<RequestInit, 'signal'>
  ): Promise<() => void> {
    const { onMessage, onError, onComplete, onUserMessage, onRuntimeProgress } = handlers;
    let isCompleted = false;
    let isClosed = false;
    let hasTerminalEvent = false;
    let isClientClosed = false;
    let closeStream: (() => void) | null = null;

    const closeConnection = (clientInitiated = true) => {
      if (isClosed) {
        return;
      }
      isClosed = true;
      isClientClosed = clientInitiated;
      closeStream?.();
    };

    closeStream = await streamWithAuth(
      url,
      async event => {
        if (isCompleted) {
          return;
        }
        if (event.event === 'complete') {
          isCompleted = true;
          hasTerminalEvent = true;
          closeConnection(false);
          if (onComplete) {
            await onComplete();
          }
          return;
        }
        if (event.event === 'error') {
          isCompleted = true;
          hasTerminalEvent = true;
          closeConnection(false);
          if (onError) {
            await onError(parseRuntimeErrorEvent(event.data));
          }
          return;
        }
        if (event.event === STREAM_EVENT_RUNTIME_PROGRESS) {
          try {
            const nodeResponse: AgentResponse = JSON.parse(event.data);
            const progress = normalizeRuntimeProgress(nodeResponse);
            if (progress && onRuntimeProgress) {
              await onRuntimeProgress(progress);
            }
          } catch (parseError) {
            console.error('Failed to parse runtime progress SSE data:', parseError);
          }
          return;
        }
        // JSON 解析与业务回调分开捕获：解析失败按协议错误上报；回调异常终止流并透传原始错误，
        // 避免渲染/持久化异常被误报为解析失败、且不置 isCompleted 导致后续帧继续产生幽灵内容
        let nodeResponse: AgentResponse;
        try {
          nodeResponse = JSON.parse(event.data);
        } catch (parseError) {
          isCompleted = true;
          hasTerminalEvent = true;
          closeConnection(false);
          console.error('Failed to parse SSE data:', parseError);
          if (onError) {
            await onError(createRuntimeStreamError('Failed to parse server response'));
          }
          return;
        }
        try {
          if (event.event === 'user_message') {
            const savedMessage = nodeResponse.metadata?.message as ChatMessage | undefined;
            if (savedMessage && onUserMessage) {
              await onUserMessage(savedMessage);
            }
            return;
          }
          if (nodeResponse.error) {
            isCompleted = true;
            hasTerminalEvent = true;
            closeConnection(false);
            if (onError) {
              await onError(
                createRuntimeStreamError(nodeResponse.text, nodeResponse.metadata as StreamErrorMetadata | undefined)
              );
            }
            return;
          }
          if (nodeResponse.complete) {
            isCompleted = true;
            hasTerminalEvent = true;
            closeConnection(false);
            if (onComplete) {
              await onComplete();
            }
            return;
          }
          await onMessage(nodeResponse);
        } catch (callbackError) {
          if (isCompleted) {
            return;
          }
          isCompleted = true;
          hasTerminalEvent = true;
          closeConnection(false);
          if (onError) {
            await onError(
              callbackError instanceof Error ? callbackError : createRuntimeStreamError(String(callbackError))
            );
          }
        }
      },
      async error => {
        if (isClientClosed) {
          return;
        }
        if (isCompleted) {
          closeConnection(false);
          return;
        }
        console.error('EventSource error:', error);
        closeConnection(false);
        if (onError) {
          await onError(createRuntimeStreamError('Stream connection failed'));
        }
      },
      async () => {
        if (isClientClosed) {
          return;
        }
        if (isCompleted || hasTerminalEvent) {
          return;
        }
        isCompleted = true;
        closeConnection(false);
        if (onError) {
          await onError(createRuntimeStreamError('Stream ended without completion'));
        }
      },
      init
    );

    return () => {
      closeConnection(true);
    };
  }
}

const toNumber = (value: unknown): number | undefined => {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : undefined;
  }
  return undefined;
};

const normalizeRuntimeProgress = (response: AgentResponse): RuntimeProgressEvent | null => {
  const metadata = response.metadata || {};
  if (metadata.eventType !== STREAM_EVENT_RUNTIME_PROGRESS || typeof metadata.stageCode !== 'string') {
    return null;
  }
  return {
    eventType: STREAM_EVENT_RUNTIME_PROGRESS,
    runtimeRequestId: typeof metadata.runtimeRequestId === 'string' ? metadata.runtimeRequestId : undefined,
    seq: toNumber(metadata.seq),
    stageCode: metadata.stageCode,
    status: typeof metadata.status === 'string' ? metadata.status : undefined,
    elapsedMs: toNumber(metadata.elapsedMs),
    durationMs: toNumber(metadata.durationMs) ?? null,
    displayName: typeof metadata.displayName === 'string' ? metadata.displayName : null,
    toolExecutionSeq: toNumber(metadata.toolExecutionSeq),
    errorCode: typeof metadata.errorCode === 'string' ? metadata.errorCode : undefined,
    orchestrationRunId:
      metadata.orchestrationRunId === undefined || metadata.orchestrationRunId === null
        ? undefined
        : String(metadata.orchestrationRunId),
    orchestrationStepId:
      metadata.orchestrationStepId === undefined || metadata.orchestrationStepId === null
        ? undefined
        : String(metadata.orchestrationStepId),
    collaboratorName: typeof metadata.collaboratorName === 'string' ? metadata.collaboratorName : undefined,
    collaboratorRole: typeof metadata.collaboratorRole === 'string' ? metadata.collaboratorRole : undefined,
    childRuntimeRequestId:
      typeof metadata.childRuntimeRequestId === 'string' ? metadata.childRuntimeRequestId : undefined,
    flowInstanceId: typeof metadata.flowInstanceId === 'string' ? metadata.flowInstanceId : undefined,
    nodeId: typeof metadata.nodeId === 'string' ? metadata.nodeId : undefined,
    resolverId: typeof metadata.resolverId === 'string' ? metadata.resolverId : undefined
  };
};

export default new GraphService();
