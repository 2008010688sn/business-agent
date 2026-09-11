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
import { haveAuth } from '@/mixins/userAuth.js';
import { unwrapDataOr } from './common';
import type { ApiResponse, XxCloudResult } from './common';

export interface ThinkingPermission {
  canViewThinking: boolean;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/permissions';
const DIAGNOSTIC_BUTTON_PERMISSION_ENABLED = import.meta.env.VITE_DATA_AGENT_DIAGNOSTIC_BUTTON_PERMISSION_ENABLED;

export const isDiagnosticButtonPermissionEnabled = (): boolean =>
  DIAGNOSTIC_BUTTON_PERMISSION_ENABLED?.trim().toUpperCase() !== 'N';

export const canShowDiagnosticButton = (permission: string): boolean =>
  !isDiagnosticButtonPermissionEnabled() || haveAuth(permission);

class PermissionService {
  async canViewThinking(): Promise<boolean> {
    const response = await Http.get(`${API_BASE_URL}/thinking`);
    return (
      unwrapDataOr(response as ServiceResponse<ThinkingPermission>, { canViewThinking: false }).canViewThinking === true
    );
  }
}

export default new PermissionService();
