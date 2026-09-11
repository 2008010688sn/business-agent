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
import type { ApiResponse, XxCloudResult } from '@/views/ai-agent/services/common';
import { unwrapData, unwrapList } from '@/views/ai-agent/services/common';

export type NotificationProvider = 'DINGTALK' | 'WECOM' | 'WECHAT_MP' | 'WECHAT_MINI' | 'SMS' | string;

export type NotificationChannelType =
  | 'DINGTALK_ROBOT'
  | 'DINGTALK_WORK_NOTICE'
  | 'WECOM_ROBOT'
  | 'WECOM_APP'
  | 'WECHAT_MP_TEMPLATE'
  | 'WECHAT_MINI_SUBSCRIBE'
  | 'SMS_ALIYUN'
  | string;

export interface NotificationConnector {
  id?: string;
  connectorCode?: string;
  connectorName?: string;
  provider?: NotificationProvider;
  channelType?: NotificationChannelType;
  authType?: string;
  config?: Record<string, unknown>;
  credentialRef?: string;
  tenantId?: string;
  status?: string;
  displayOrder?: number;
}

export interface NotificationTarget {
  id?: string;
  targetAlias?: string;
  targetName?: string;
  targetType?: string;
  connectorCode?: string;
  provider?: NotificationProvider;
  targetConfig?: Record<string, unknown>;
  resolverCode?: string;
  status?: string;
  displayOrder?: number;
}

export interface NotificationTemplate {
  id?: string;
  templateCode?: string;
  templateName?: string;
  connectorCode?: string;
  provider?: NotificationProvider;
  messageType?: string;
  titleTemplate?: string;
  contentTemplate?: string;
  variableSchema?: Record<string, unknown>;
  platformTemplateId?: string;
  riskLevel?: string;
  confirmRequired?: boolean;
  status?: string;
  displayOrder?: number;
}

export interface NotificationAuthorization {
  id?: string;
  agentId?: string | null;
  skillCode?: string;
  skillVersionId?: string | null;
  resourceKey?: string;
  targetAlias?: string;
  templateCode?: string;
  confirmPolicy?: string;
  expireTime?: string;
  status?: string;
  displayOrder?: number;
}

export interface NotificationDelivery {
  id?: string;
  deliveryId?: string;
  idempotencyKey?: string;
  sessionId?: string;
  runtimeRequestId?: string;
  agentId?: string;
  skillCode?: string;
  skillVersionId?: string;
  resourceKey?: string;
  connectorCode?: string;
  targetAlias?: string;
  templateCode?: string;
  provider?: string;
  status?: string;
  previewSummary?: string;
  platformRequestId?: string;
  platformCode?: string;
  errorMessage?: string;
  retryCount?: number;
  createTime?: string;
}

export interface NotificationSendRequest {
  agentId?: string;
  skillCode?: string;
  skillVersionId?: string;
  resourceKey?: string;
  sessionId?: string;
  runtimeRequestId?: string;
  targetAlias?: string;
  templateCode?: string;
  variables?: Record<string, unknown>;
  idempotencyKey?: string;
  confirmed?: boolean;
}

export interface NotificationPreview {
  provider?: string;
  channelType?: string;
  connectorCode?: string;
  targetAlias?: string;
  targetName?: string;
  templateCode?: string;
  title?: string;
  summary?: string;
  confirmRequired?: boolean;
}

export interface NotificationSendResponse {
  status?: string;
  deliveryId?: string;
  message?: string;
  preview?: NotificationPreview;
}

export interface NotificationAdapterResult {
  success?: boolean;
  platformRequestId?: string;
  platformCode?: string;
  message?: string;
  rawResponse?: Record<string, unknown>;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const CONNECTOR_BASE = '/ai/notification-connectors';
const TARGET_BASE = '/ai/notification-targets';
const TEMPLATE_BASE = '/ai/notification-templates';
const AUTHORIZATION_BASE = '/ai/notification-authorizations';
const RUNTIME_BASE = '/ai/notifications';

class NotificationService {
  async listConnectors(): Promise<NotificationConnector[]> {
    const response = await Http.get(CONNECTOR_BASE);
    return unwrapList<NotificationConnector>(response as ServiceResponse<unknown>);
  }

  async createConnector(request: NotificationConnector): Promise<NotificationConnector> {
    const response = await Http.post(`${CONNECTOR_BASE}/create`, request);
    return unwrapData(response as ServiceResponse<NotificationConnector>);
  }

  async updateConnector(id: string, request: NotificationConnector): Promise<NotificationConnector> {
    const response = await Http.put(`${CONNECTOR_BASE}/${id}/modify`, request);
    return unwrapData(response as ServiceResponse<NotificationConnector>);
  }

  async deleteConnector(id: string): Promise<void> {
    await Http.delete(`${CONNECTOR_BASE}/${id}`);
  }

  async testConnector(connectorCode: string): Promise<NotificationAdapterResult> {
    const response = await Http.post(`${CONNECTOR_BASE}/test`, { connectorCode });
    return unwrapData(response as ServiceResponse<NotificationAdapterResult>);
  }

  async listTargets(connectorCode?: string): Promise<NotificationTarget[]> {
    const response = await Http.post(`${TARGET_BASE}/query`, connectorCode ? { connectorCode } : {});
    return unwrapList<NotificationTarget>(response as ServiceResponse<unknown>);
  }

  async createTarget(request: NotificationTarget): Promise<NotificationTarget> {
    const response = await Http.post(`${TARGET_BASE}/create`, request);
    return unwrapData(response as ServiceResponse<NotificationTarget>);
  }

  async updateTarget(id: string, request: NotificationTarget): Promise<NotificationTarget> {
    const response = await Http.put(`${TARGET_BASE}/${id}/modify`, request);
    return unwrapData(response as ServiceResponse<NotificationTarget>);
  }

  async deleteTarget(id: string): Promise<void> {
    await Http.delete(`${TARGET_BASE}/${id}`);
  }

  async listTemplates(connectorCode?: string): Promise<NotificationTemplate[]> {
    const response = await Http.post(`${TEMPLATE_BASE}/query`, connectorCode ? { connectorCode } : {});
    return unwrapList<NotificationTemplate>(response as ServiceResponse<unknown>);
  }

  async createTemplate(request: NotificationTemplate): Promise<NotificationTemplate> {
    const response = await Http.post(`${TEMPLATE_BASE}/create`, request);
    return unwrapData(response as ServiceResponse<NotificationTemplate>);
  }

  async updateTemplate(id: string, request: NotificationTemplate): Promise<NotificationTemplate> {
    const response = await Http.put(`${TEMPLATE_BASE}/${id}/modify`, request);
    return unwrapData(response as ServiceResponse<NotificationTemplate>);
  }

  async deleteTemplate(id: string): Promise<void> {
    await Http.delete(`${TEMPLATE_BASE}/${id}`);
  }

  async listAuthorizations(): Promise<NotificationAuthorization[]> {
    const response = await Http.get(AUTHORIZATION_BASE);
    return unwrapList<NotificationAuthorization>(response as ServiceResponse<unknown>);
  }

  async createAuthorization(request: NotificationAuthorization): Promise<NotificationAuthorization> {
    const response = await Http.post(`${AUTHORIZATION_BASE}/create`, request);
    return unwrapData(response as ServiceResponse<NotificationAuthorization>);
  }

  async updateAuthorization(
    id: string,
    request: NotificationAuthorization
  ): Promise<NotificationAuthorization> {
    const response = await Http.put(`${AUTHORIZATION_BASE}/${id}/modify`, request);
    return unwrapData(response as ServiceResponse<NotificationAuthorization>);
  }

  async deleteAuthorization(id: string): Promise<void> {
    await Http.delete(`${AUTHORIZATION_BASE}/${id}`);
  }

  async listDeliveries(params?: Record<string, unknown>): Promise<NotificationDelivery[]> {
    const response = await Http.post(`${RUNTIME_BASE}/deliveries/query`, params || {});
    return unwrapList<NotificationDelivery>(response as ServiceResponse<unknown>);
  }

  async preview(request: NotificationSendRequest): Promise<NotificationSendResponse> {
    const response = await Http.post(`${RUNTIME_BASE}/preview`, request);
    return unwrapData(response as ServiceResponse<NotificationSendResponse>);
  }

  async send(request: NotificationSendRequest): Promise<NotificationSendResponse> {
    const response = await Http.post(`${RUNTIME_BASE}/send`, request);
    return unwrapData(response as ServiceResponse<NotificationSendResponse>);
  }
}

export const notificationService = new NotificationService();

export default notificationService;
