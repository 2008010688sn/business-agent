/*
 * Copyright 2024-2026 the original author or authors.
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
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from '@/views/ai-agent/services/common';
import { toPageResponse, unwrapData, unwrapList } from '@/views/ai-agent/services/common';
import type { NotificationAdapterResult } from '@/views/ai-agent/services/notification';

export type ImProvider = 'DINGTALK' | 'WECOM' | string;
export type ImConversationType = 'SINGLE' | 'GROUP' | string;
export type ImTriggerPolicy = 'ALWAYS' | 'MENTION' | 'WAKE_WORD' | 'MENTION_OR_WAKE_WORD' | string;

export interface ImConnector {
  id?: string;
  connectorCode?: string;
  connectorName?: string;
  provider?: ImProvider;
  authType?: string;
  config: Record<string, any>;
  credentialRef?: string;
  defaultAgentId?: string;
  directEnabled?: boolean;
  groupEnabled?: boolean;
  status?: string;
  displayOrder?: number;
  streamStatus?: string;
  streamLeaseOwner?: string;
  streamLastError?: string;
}

export interface ImProviderConfig {
  id?: string;
  provider?: ImProvider;
  config?: Record<string, any>;
  status?: string;
  displayOrder?: number;
}

export interface ImConversationBinding {
  id?: string;
  provider?: ImProvider;
  connectorCode?: string;
  conversationType?: ImConversationType;
  externalConversationId?: string;
  conversationName?: string;
  agentId?: string;
  /** 绑定数字员工 ID，可空；IM 运行时仍可用 DataAgent，员工身份仅作会话归属 */
  digitalEmployeeId?: string;
  triggerPolicy?: ImTriggerPolicy;
  wakeWords?: string[];
  sessionScope?: string;
  status?: string;
  displayOrder?: number;
}

export interface ImUserIdentity {
  id?: string;
  provider?: ImProvider;
  connectorCode?: string;
  externalUserId?: string;
  unionId?: string;
  contact?: string;
  userId?: string;
  username?: string;
  nickName?: string;
  bindStatus?: string;
  bindSource?: string;
}

export interface ImUserBindSession {
  id?: string | number;
  code?: string;
  qrContent?: string;
  connectorCode?: string;
  userId?: string;
  nickName?: string;
  status?: string;
  expiresAt?: string;
  externalUserId?: string;
}

export interface IamBasicUser {
  id?: string;
  userId?: string;
  username?: string;
  nickName?: string;
  mobile?: string;
  phone?: string;
  email?: string;
}

export interface ImMessage {
  id?: string;
  provider?: ImProvider;
  connectorCode?: string;
  conversationType?: ImConversationType;
  externalConversationId?: string;
  externalUserId?: string;
  direction?: string;
  messageType?: string;
  content?: string;
  responseContent?: string;
  agentId?: string;
  sessionId?: string;
  runtimeRequestId?: string;
  userId?: string;
  status?: string;
  errorCode?: string;
  errorMessage?: string;
  createTime?: string;
}

export interface ImMessagePageQuery {
  current?: number;
  size?: number;
  provider?: string;
  connectorCode?: string;
  conversationType?: string;
  externalUserId?: string;
  agentId?: string;
  sessionId?: string;
  runtimeRequestId?: string;
  direction?: string;
  status?: string;
  keyword?: string;
}

export interface ImTestRequest {
  externalUserId?: string;
  contact?: string;
  text?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const PROVIDER_CONFIG_BASE = '/ai/im-provider-configs';
const CONNECTOR_BASE = '/ai/im-connectors';
const CONVERSATION_BINDING_BASE = '/ai/im-conversation-bindings';
const USER_IDENTITY_BASE = '/ai/im-user-identities';
const MESSAGE_BASE = '/ai/im-messages';

class ImConnectorService {
  async listProviderConfigs(): Promise<ImProviderConfig[]> {
    const response = await Http.get(PROVIDER_CONFIG_BASE);
    return unwrapList<ImProviderConfig>(response as ServiceResponse<unknown>);
  }

  async getProviderConfig(provider: string): Promise<ImProviderConfig> {
    const response = await Http.post(`${PROVIDER_CONFIG_BASE}/detail/query`, { provider });
    return unwrapData(response as ServiceResponse<ImProviderConfig>);
  }

  async saveProviderConfig(provider: string, request: ImProviderConfig): Promise<ImProviderConfig> {
    const response = await Http.put(`${PROVIDER_CONFIG_BASE}/modify`, { ...request, provider });
    return unwrapData(response as ServiceResponse<ImProviderConfig>);
  }

  async listConnectors(): Promise<ImConnector[]> {
    const response = await Http.get(CONNECTOR_BASE);
    return unwrapList<ImConnector>(response as ServiceResponse<unknown>);
  }

  async getConnector(connectorCode: string): Promise<ImConnector> {
    const response = await Http.post(`${CONNECTOR_BASE}/detail/query`, { connectorCode });
    return unwrapData(response as ServiceResponse<ImConnector>);
  }

  async createConnector(request: ImConnector): Promise<ImConnector> {
    const response = await Http.post(`${CONNECTOR_BASE}/create`, request);
    return unwrapData(response as ServiceResponse<ImConnector>);
  }

  async updateConnector(id: string, request: ImConnector): Promise<ImConnector> {
    const response = await Http.put(`${CONNECTOR_BASE}/${id}/modify`, request);
    return unwrapData(response as ServiceResponse<ImConnector>);
  }

  async deleteConnector(id: string): Promise<void> {
    const response = await Http.delete(`${CONNECTOR_BASE}/${id}`);
    unwrapData(response as ServiceResponse<void>);
  }

  async testConnector(connectorCode: string, request?: ImTestRequest): Promise<NotificationAdapterResult> {
    const response = await Http.post(`${CONNECTOR_BASE}/test`, { ...(request || {}), connectorCode });
    return unwrapData(response as ServiceResponse<NotificationAdapterResult>);
  }

  async listConversationBindings(params?: {
    provider?: string;
    connectorCode?: string;
  }): Promise<ImConversationBinding[]> {
    const response = await Http.post(`${CONVERSATION_BINDING_BASE}/query`, params || {});
    return unwrapList<ImConversationBinding>(response as ServiceResponse<unknown>);
  }

  async createConversationBinding(request: ImConversationBinding): Promise<ImConversationBinding> {
    const response = await Http.post(`${CONVERSATION_BINDING_BASE}/create`, request);
    return unwrapData(response as ServiceResponse<ImConversationBinding>);
  }

  async updateConversationBinding(id: string, request: ImConversationBinding): Promise<ImConversationBinding> {
    const response = await Http.put(`${CONVERSATION_BINDING_BASE}/${id}/modify`, request);
    return unwrapData(response as ServiceResponse<ImConversationBinding>);
  }

  async deleteConversationBinding(id: string): Promise<void> {
    const response = await Http.delete(`${CONVERSATION_BINDING_BASE}/${id}`);
    unwrapData(response as ServiceResponse<void>);
  }

  async listUserIdentities(): Promise<ImUserIdentity[]> {
    const response = await Http.get(USER_IDENTITY_BASE);
    return unwrapList<ImUserIdentity>(response as ServiceResponse<unknown>);
  }

  async createUserIdentity(request: ImUserIdentity): Promise<ImUserIdentity> {
    const response = await Http.post(`${USER_IDENTITY_BASE}/create`, request);
    return unwrapData(response as ServiceResponse<ImUserIdentity>);
  }

  async updateUserIdentity(id: string, request: ImUserIdentity): Promise<ImUserIdentity> {
    const response = await Http.put(`${USER_IDENTITY_BASE}/${id}/modify`, request);
    return unwrapData(response as ServiceResponse<ImUserIdentity>);
  }

  async deleteUserIdentity(id: string): Promise<void> {
    const response = await Http.delete(`${USER_IDENTITY_BASE}/${id}`);
    unwrapData(response as ServiceResponse<void>);
  }

  async createBindSession(connectorCode?: string): Promise<ImUserBindSession> {
    const response = await Http.post(`${USER_IDENTITY_BASE}/bind-sessions/create`, { connectorCode });
    return unwrapData(response as ServiceResponse<ImUserBindSession>);
  }

  async getBindSessionStatus(id: string | number): Promise<ImUserBindSession> {
    const response = await Http.get(`${USER_IDENTITY_BASE}/bind-sessions/${id}/status`);
    return unwrapData(response as ServiceResponse<ImUserBindSession>);
  }

  async listIamUsers(): Promise<IamBasicUser[]> {
    return [];
  }

  async pageMessages(query: ImMessagePageQuery): Promise<PageResponse<ImMessage[]>> {
    const response = await Http.post(`${MESSAGE_BASE}/page`, query);
    return toPageResponse<ImMessage>(response as ServiceResponse<MybatisPage<ImMessage>>);
  }
}

export const imConnectorService = new ImConnectorService();

export default imConnectorService;
