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

import axios from 'axios';
import { Http } from '@/service/request';
import { dataAgentAuthHeaders, resolveDataAgentRequestUrl, streamWithAuth } from './http';
import {
  getResponseStatus,
  normalizeApiResponse,
  toPageResponse,
  unwrapData,
  unwrapDataOr,
  unwrapList
} from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';
import type { AgentId } from './agent';
import type { ModelConfigId } from './modelConfig';
import type { OrchestrationTrace } from './agentOrchestration';

export const CHAT_MESSAGE_TYPES = {
  TEXT: 'text',
  MARKDOWN: 'markdown',
  RESULT_SET: 'result-set',
  THINKING: 'thinking',
  MARKDOWN_REPORT: 'markdown-report',
  HTML_REPORT: 'html-report'
} as const;

export type KnownChatMessageType = (typeof CHAT_MESSAGE_TYPES)[keyof typeof CHAT_MESSAGE_TYPES];
export type ChatMessageType = KnownChatMessageType | (string & {});

export interface ChatSession {
  id: string;
  agentId: AgentId;
  title: string;
  status: string;
  isPinned: boolean;
  userId?: string;
  createTime?: Date;
  // 服务端 DataChatSession 继承 SuperEntity，更新时间字段名为 lastModifyTime；updateTime 服务端从未下发
  lastModifyTime?: Date;
  updateTime?: Date;
}

export interface ChatSessionPageQuery {
  current: number;
  size: number;
}

export interface ChatMessage {
  id?: string;
  sessionId: string;
  role: string;
  content: string;
  messageType: ChatMessageType;
  metadata?: string;
  runtimeRequestId?: string;
  clientRequestId?: string;
  durationMs?: number | null;
  turnStatus?: string;
  createTime?: Date;
  titleNeeded?: boolean;
}

export interface SessionContextUsage {
  usedTokens?: number;
  limitTokens?: number;
  usageRatio?: number;
  messageCount?: number;
  estimated?: boolean;
  modelName?: string;
  chatModelConfigId?: ModelConfigId;
}

export interface SessionContextCompressionResult {
  status: 'COMPRESSED' | 'NO_COMPRESSIBLE_CONTENT' | 'DISABLED' | 'BUSY' | 'FAILED';
  compressed: boolean;
  beforeRuntimeTokens?: number;
  afterRuntimeTokens?: number;
  beforeRuntimeMessageCount?: number;
  afterRuntimeMessageCount?: number;
  displayContextUsage?: SessionContextUsage;
  estimated: boolean;
  message?: string;
}

export interface AnswerTraceSemanticHit {
  tableName?: string;
  columnName?: string;
  businessName?: string;
  businessDescription?: string;
  matchedBy?: string;
  score?: number;
  relationHint?: string;
}

export interface AnswerTraceKnowledgeHit {
  vectorType?: string;
  knowledgeId?: string;
  title?: string;
  summary?: string;
  snippet?: string;
  source?: string;
  concreteType?: string;
}

export interface AnswerTraceToolStep {
  toolName?: string;
  stepType?: 'EXECUTION' | 'EXPLAIN' | string;
  title?: string;
  summary?: string;
  detail?: string;
  datasource?: string;
  timestampEpochMs?: number;
  status?: string;
  startEpochMs?: number;
  endEpochMs?: number;
  durationMs?: number;
  inputSummary?: string;
  outputSummary?: string;
  errorCode?: string;
  errorMessage?: string;
  sequenceNo?: number;
}

export interface AnswerTraceExplain {
  sessionId: string;
  runtimeRequestId: string;
  agentId?: string;
  question?: string;
  answer?: string;
  datasource?: string;
  sql?: string;
  decisionReason?: string;
  resultScope?: string;
  usedTables: string[];
  usedColumns: string[];
  relationEvidence: Record<string, any>[];
  toolDecisionReasons: string[];
  resultScopeDetails: string[];
  semanticHits: AnswerTraceSemanticHit[];
  knowledgeHits: AnswerTraceKnowledgeHit[];
  toolSteps: AnswerTraceToolStep[];
  clarify?: Record<string, any>;
  linkResolve?: Record<string, unknown> | null;
  warnings: string[];
  updatedAt: number;
}

export interface TraceSpan {
  name: string;
  spanId: string;
  parentSpanId: string;
  kind: string;
  status: string;
  startEpochMs: number;
  endEpochMs: number;
  durationMs: number;
  attributes: Record<string, string>;
  children: TraceSpan[];
}

export interface SessionTrace {
  sessionId: string;
  traceId: string;
  runtimeRequestId: string;
  agentId: string;
  startEpochMs: number;
  endEpochMs: number;
  durationMs: number;
  spanCount: number;
  rootSpan: TraceSpan | null;
  rootSpans: TraceSpan[];
}

export interface SessionCallChain {
  sessionId: string;
  agentId: string;
  runtimeRequestId?: string;
  trace?: SessionTrace | null;
  orchestration?: OrchestrationTrace | null;
  answerExplain?: AnswerTraceExplain | null;
  toolSteps?: AnswerTraceToolStep[];
}

export interface GeneratedReport {
  content: string;
  runtimeRequestId: string;
  /** 实际生效的报告级别：standard/professional */
  reportLevel?: string;
  /** 专业叙事失败回落标准报告时为 true */
  degraded?: boolean;
}

export interface ReportGenerateOptions {
  reportLevel?: string;
  onMetadata?: (metadata: { reportLevel?: string; degraded?: boolean }) => void;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;
type RawTokenValue = number | string | null | undefined;

type RawSessionContextUsage = Omit<
  SessionContextUsage,
  'usedTokens' | 'limitTokens' | 'usageRatio' | 'messageCount'
> & {
  usedTokens?: RawTokenValue;
  used_tokens?: RawTokenValue;
  limitTokens?: RawTokenValue;
  limit_tokens?: RawTokenValue;
  usageRatio?: RawTokenValue;
  usage_ratio?: RawTokenValue;
  messageCount?: RawTokenValue;
  message_count?: RawTokenValue;
  model_name?: string;
  chat_model_config_id?: ModelConfigId;
};

type RawSessionContextCompressionResult = Omit<
  SessionContextCompressionResult,
  | 'beforeRuntimeTokens'
  | 'afterRuntimeTokens'
  | 'beforeRuntimeMessageCount'
  | 'afterRuntimeMessageCount'
  | 'displayContextUsage'
> & {
  beforeRuntimeTokens?: RawTokenValue;
  before_runtime_tokens?: RawTokenValue;
  afterRuntimeTokens?: RawTokenValue;
  after_runtime_tokens?: RawTokenValue;
  beforeRuntimeMessageCount?: RawTokenValue;
  before_runtime_message_count?: RawTokenValue;
  afterRuntimeMessageCount?: RawTokenValue;
  after_runtime_message_count?: RawTokenValue;
  displayContextUsage?: RawSessionContextUsage | null;
  display_context_usage?: RawSessionContextUsage | null;
};

const API_BASE_URL = '/ai/chat';

const resolveAgentId = (agentId: AgentId): string => {
  const resolvedAgentId = String(agentId ?? '').trim();
  if (!resolvedAgentId) {
    throw new Error('智能体ID无效，请刷新后重试');
  }
  return resolvedAgentId;
};

const normalizeNumberValue = (value: RawTokenValue): number | undefined => {
  if (value === undefined || value === null) {
    return undefined;
  }
  if (typeof value === 'string') {
    const normalizedValue = value.trim();
    if (!normalizedValue) {
      return undefined;
    }
    const numericValue = Number(normalizedValue);
    return Number.isFinite(numericValue) ? numericValue : undefined;
  }
  const numericValue = value;
  return Number.isFinite(numericValue) ? numericValue : undefined;
};

const normalizeSessionContextUsage = (usage?: RawSessionContextUsage | null): SessionContextUsage | undefined => {
  if (!usage) {
    return undefined;
  }

  const usedTokens = normalizeNumberValue(usage.usedTokens ?? usage.used_tokens);
  const limitTokens = normalizeNumberValue(usage.limitTokens ?? usage.limit_tokens);
  const resolvedUsageRatio =
    normalizeNumberValue(usage.usageRatio ?? usage.usage_ratio) ??
    (usedTokens !== undefined && limitTokens !== undefined && limitTokens > 0
      ? Math.min(1, usedTokens / limitTokens)
      : undefined);

  return {
    usedTokens,
    limitTokens,
    usageRatio: resolvedUsageRatio,
    messageCount: normalizeNumberValue(usage.messageCount ?? usage.message_count),
    estimated: usage.estimated,
    modelName: usage.modelName ?? usage.model_name,
    chatModelConfigId: usage.chatModelConfigId ?? usage.chat_model_config_id
  };
};

const normalizeSessionContextCompression = (
  result: RawSessionContextCompressionResult
): SessionContextCompressionResult => ({
  ...result,
  beforeRuntimeTokens: normalizeNumberValue(result.beforeRuntimeTokens ?? result.before_runtime_tokens),
  afterRuntimeTokens: normalizeNumberValue(result.afterRuntimeTokens ?? result.after_runtime_tokens),
  beforeRuntimeMessageCount: normalizeNumberValue(
    result.beforeRuntimeMessageCount ?? result.before_runtime_message_count
  ),
  afterRuntimeMessageCount: normalizeNumberValue(result.afterRuntimeMessageCount ?? result.after_runtime_message_count),
  displayContextUsage: normalizeSessionContextUsage(result.displayContextUsage ?? result.display_context_usage)
});

class ChatService {
  async getAgentSessions(agentId: AgentId): Promise<ChatSession[]> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/query`, { agentId: resolvedAgentId });
    return unwrapList<ChatSession>(response);
  }

  async queryAgentSessions(agentId: AgentId, query: ChatSessionPageQuery): Promise<PageResponse<ChatSession[]>> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/page`, { ...query, agentId: resolvedAgentId });
    return toPageResponse(response as ServiceResponse<MybatisPage<ChatSession>>);
  }

  async createSession(agentId: AgentId, title?: string, userId?: string): Promise<ChatSession> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/create`, {
      agentId: resolvedAgentId,
      title,
      userId
    });
    return unwrapData(response as ServiceResponse<ChatSession>);
  }

  async clearAgentSessions(agentId: AgentId): Promise<ApiResponse> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.delete(`${API_BASE_URL}/sessions/clear`, { agentId: resolvedAgentId });
    return normalizeApiResponse(response as ServiceResponse<unknown>);
  }

  async getSessionMessages(sessionId: string, agentId: AgentId): Promise<ChatMessage[]> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/messages/query`, {
      sessionId,
      agentId: resolvedAgentId
    });
    return unwrapList<ChatMessage>(response);
  }

  async getSessionContext(
    sessionId: string,
    agentId: AgentId,
    chatModelConfigId?: ModelConfigId
  ): Promise<SessionContextUsage> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/context/query`, {
      sessionId,
      agentId: resolvedAgentId,
      chatModelConfigId
    });
    return normalizeSessionContextUsage(unwrapData(response as ServiceResponse<RawSessionContextUsage>)) ?? {};
  }

  async compressSessionContext(
    sessionId: string,
    agentId: AgentId,
    chatModelConfigId?: ModelConfigId
  ): Promise<SessionContextCompressionResult> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/context-compress`, {
      sessionId,
      agentId: resolvedAgentId,
      chatModelConfigId
    });
    return normalizeSessionContextCompression(
      unwrapData(response as ServiceResponse<RawSessionContextCompressionResult>)
    );
  }

  async getSessionTrace(sessionId: string, agentId: AgentId): Promise<SessionTrace> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/trace/query`, {
      sessionId,
      agentId: resolvedAgentId
    });
    return unwrapData(response as ServiceResponse<SessionTrace>);
  }

  async getSessionCallChain(
    sessionId: string,
    agentId: AgentId,
    runtimeRequestId?: string
  ): Promise<SessionCallChain> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/call-chain/query`, {
      sessionId,
      agentId: resolvedAgentId,
      runtimeRequestId
    });
    return unwrapData(response as ServiceResponse<SessionCallChain>);
  }

  async getLatestAnswerExplain(sessionId: string, agentId: AgentId): Promise<AnswerTraceExplain> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/answers/latest/explain/query`, {
      sessionId,
      agentId: resolvedAgentId
    });
    return unwrapData(response as ServiceResponse<AnswerTraceExplain>);
  }

  async getAnswerExplain(sessionId: string, runtimeRequestId: string, agentId: AgentId): Promise<AnswerTraceExplain> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/answers/explain/query`, {
      sessionId,
      runtimeRequestId,
      agentId: resolvedAgentId
    });
    return unwrapData(response as ServiceResponse<AnswerTraceExplain>);
  }

  async generateReport(
    sessionId: string,
    runtimeRequestId: string,
    agentId: AgentId,
    options?: ReportGenerateOptions
  ): Promise<GeneratedReport> {
    const resolvedAgentId = resolveAgentId(agentId);
    const response = await Http.post(`${API_BASE_URL}/sessions/reports/generate`, {
      sessionId,
      runtimeRequestId,
      agentId: resolvedAgentId,
      reportLevel: options?.reportLevel
    });
    return unwrapData(response as ServiceResponse<GeneratedReport>);
  }

  async streamGenerateReport(
    sessionId: string,
    runtimeRequestId: string,
    agentId: AgentId,
    handlers: {
      onChunk: (chunk: string) => Promise<void> | void;
      onComplete?: () => Promise<void> | void;
      onError?: (error: Error) => Promise<void> | void;
    },
    options?: ReportGenerateOptions
  ): Promise<() => void> {
    const resolvedAgentId = resolveAgentId(agentId);
    const url = `${API_BASE_URL}/sessions/reports/generate/stream`;
    return streamWithAuth(
      url,
      async event => {
        if (event.event === 'complete') {
          await handlers.onComplete?.();
          return;
        }
        if (!event.data) {
          return;
        }
        const response = JSON.parse(event.data);
        if (response.metadata && options?.onMetadata) {
          options.onMetadata({
            reportLevel: typeof response.metadata.reportLevel === 'string' ? response.metadata.reportLevel : undefined,
            degraded:
              response.metadata.degraded === true ? true : response.metadata.degraded === false ? false : undefined
          });
        }
        await handlers.onChunk(response.text || '');
      },
      handlers.onError,
      undefined,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          sessionId,
          runtimeRequestId,
          agentId: resolvedAgentId,
          reportLevel: options?.reportLevel
        })
      }
    );
  }

  async saveMessage(sessionId: string, agentId: AgentId, message: ChatMessage): Promise<ChatMessage> {
    try {
      const resolvedAgentId = resolveAgentId(agentId);
      const response = await Http.post(`${API_BASE_URL}/sessions/messages`, {
        ...message,
        sessionId,
        agentId: resolvedAgentId
      });
      return unwrapData(response as ServiceResponse<ChatMessage>);
    } catch (error) {
      if (getResponseStatus(error) === 500) {
        throw new Error('保存消息失败');
      }
      throw error;
    }
  }

  async pinSession(sessionId: string, agentId: AgentId, isPinned: boolean): Promise<ApiResponse> {
    try {
      const resolvedAgentId = resolveAgentId(agentId);
      const response = await Http.put(`${API_BASE_URL}/sessions/pin`, {
        sessionId,
        agentId: resolvedAgentId,
        isPinned
      });
      return normalizeApiResponse(response as ServiceResponse<unknown>);
    } catch (error) {
      if (getResponseStatus(error) === 400) {
        throw new Error('isPinned参数不能为空');
      }
      if (getResponseStatus(error) === 500) {
        throw new Error('操作失败');
      }
      throw error;
    }
  }

  async renameSession(sessionId: string, agentId: AgentId, title: string): Promise<ApiResponse> {
    try {
      const resolvedAgentId = resolveAgentId(agentId);
      if (!title || title.trim().length === 0) {
        throw new Error('标题不能为空');
      }

      const response = await Http.put(`${API_BASE_URL}/sessions/rename`, {
        sessionId,
        agentId: resolvedAgentId,
        title: title.trim()
      });
      return normalizeApiResponse(response as ServiceResponse<unknown>);
    } catch (error) {
      if (getResponseStatus(error) === 400) {
        throw new Error('标题不能为空');
      }
      if (getResponseStatus(error) === 500) {
        throw new Error('重命名失败');
      }
      throw error;
    }
  }

  async deleteSession(sessionId: string, agentId: AgentId): Promise<ApiResponse> {
    try {
      const resolvedAgentId = resolveAgentId(agentId);
      const response = await Http.delete(`${API_BASE_URL}/sessions`, { sessionId, agentId: resolvedAgentId });
      return normalizeApiResponse(response as ServiceResponse<unknown>);
    } catch (error) {
      if (getResponseStatus(error) === 500) {
        throw new Error('删除失败');
      }
      throw error;
    }
  }

  async downloadHtmlReport(sessionId: string, agentId: AgentId, content: string): Promise<void> {
    try {
      const resolvedAgentId = resolveAgentId(agentId);
      const response = await axios.post(
        resolveDataAgentRequestUrl(`${API_BASE_URL}/sessions/reports/html`),
        {
          sessionId,
          agentId: resolvedAgentId,
          content
        },
        {
          responseType: 'blob',
          headers: {
            'Content-Type': 'application/json',
            ...dataAgentAuthHeaders()
          }
        }
      );

      const contentDisposition = response.headers['content-disposition'];
      let filename = 'report.html';
      if (contentDisposition) {
        const filenameMatch = contentDisposition.match(/filename="?([^;"]+)"?/);
        if (filenameMatch && filenameMatch[1]) {
          filename = filenameMatch[1];
        }
      }

      const blob = new Blob([response.data], { type: 'text/html' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = filename;
      document.body.appendChild(a);
      a.click();
      document.body.removeChild(a);
      URL.revokeObjectURL(url);
    } catch (error) {
      if (axios.isAxiosError(error)) {
        throw new Error(`下载失败: ${error.message}`);
      }
      throw error;
    }
  }
}

export default new ChatService();
