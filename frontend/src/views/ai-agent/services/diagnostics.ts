/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Http } from '@/service/request';
import { toPageResponse, unwrapData, unwrapDataOr } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';
import type { AnswerTraceExplain, ChatMessage, ChatSession, SessionCallChain } from './chat';
import type { AgentId } from './agent';

export interface DataChatTurn {
  id?: string;
  sessionId?: string;
  threadId?: string;
  runtimeRequestId?: string;
  agentId?: AgentId;
  userId?: string;
  createBy?: string;
  createName?: string;
  question?: string;
  answer?: string;
  status?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  durationMs?: number;
  answerExplainMessageId?: string;
  orchestrationRunId?: string;
  toolCount?: number;
  toolFailCount?: number;
  hasDatasource?: boolean;
  hasSql?: boolean;
  createTime?: string;
  updateTime?: string;
  lastModifyTime?: string;
}

export interface DataChatTurnPageQuery {
  current: number;
  size: number;
  agentId?: AgentId | '';
  userId?: string;
  sessionId?: string;
  runtimeRequestId?: string;
  keyword?: string;
  status?: string;
  hasDatasource?: boolean;
  hasSql?: boolean;
  hasToolCall?: boolean;
  startTime?: string;
  endTime?: string;
}

export interface DataChatUserSummaryQuery {
  agentId?: AgentId | '';
  startTime?: string;
  endTime?: string;
}

export interface DataChatUserSummary {
  userId?: string;
  createBy?: string;
  createName?: string;
  sessionCount?: number;
  turnCount?: number;
  failedTurnCount?: number;
  averageDurationMs?: number;
}

export interface BasicUser {
  id?: string;
  userId?: string;
  name?: string;
  nickName?: string;
  username?: string;
  userName?: string;
  realName?: string;
  account?: string;
  code?: string;
}

export interface DataChatTurnDetail {
  turn?: DataChatTurn | null;
  session?: ChatSession | null;
  messages?: ChatMessage[];
  thinkingMessages?: ChatMessage[];
  answerExplain?: AnswerTraceExplain | null;
  callChain?: SessionCallChain | null;
  canViewThinking?: boolean;
  canViewAnswerSource?: boolean;
  canViewCallChain?: boolean;
  diagnosticsAvailable?: boolean;
  diagnosticsMessage?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/data-agent/diagnostics';

class DataAgentDiagnosticsService {
  async queryTurns(query: DataChatTurnPageQuery): Promise<PageResponse<DataChatTurn[]>> {
    const response = await Http.post(`${API_BASE_URL}/turns/page`, normalizePageQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<DataChatTurn>>);
  }

  async getTurnDetail(sessionId: string, runtimeRequestId: string): Promise<DataChatTurnDetail> {
    const response = await Http.post(`${API_BASE_URL}/turns/detail/query`, {
      sessionId: String(sessionId),
      runtimeRequestId
    });
    return unwrapData(response as ServiceResponse<DataChatTurnDetail>);
  }

  async listSessionTurns(sessionId: string): Promise<DataChatTurn[]> {
    const response = await Http.post(`${API_BASE_URL}/sessions/turns/query`, { sessionId: String(sessionId) });
    return unwrapDataOr(response as ServiceResponse<DataChatTurn[]>, []);
  }

  async summarizeUsers(query?: DataChatUserSummaryQuery): Promise<DataChatUserSummary[]> {
    const response = await Http.post(`${API_BASE_URL}/users/summary`, normalizeObject(query ?? {}));
    return unwrapDataOr(response as ServiceResponse<DataChatUserSummary[]>, []);
  }

  async listUsers(): Promise<BasicUser[]> {
    return [];
  }
}

const normalizePageQuery = (query: DataChatTurnPageQuery): DataChatTurnPageQuery => {
  return normalizeObject({
    ...query,
    current: query.current || 1,
    size: query.size || 20
  }) as DataChatTurnPageQuery;
};

const normalizeObject = <T extends object>(value: T): Partial<T> => {
  return Object.entries(value as Record<string, unknown>).reduce<Partial<T>>((result, [key, item]) => {
    if (item !== '' && item !== undefined && item !== null) {
      (result as Record<string, unknown>)[key] = item;
    }
    return result;
  }, {});
};

export default new DataAgentDiagnosticsService();
