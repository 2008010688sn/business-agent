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
import { unwrapDataOr } from './common';
import type { ApiResponse, XxCloudResult } from './common';
import type { ModelConfigId } from './modelConfig';
import type { TtsVoiceSample } from './audio';

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

export type VoiceSourceType = 'SYSTEM' | 'CUSTOM' | 'LOCAL_REFERENCE';

export interface ModelTtsConfig {
  id?: ModelConfigId;
  modelConfigId?: ModelConfigId;
  speechPath?: string;
  speechStreamPath?: string;
  ttsProtocol?: string;
  streamingEnabled?: boolean;
  defaultFormat?: string;
  defaultSampleRate?: number;
  options?: Record<string, any>;
}

export interface TtsVoiceProfile {
  id?: ModelConfigId;
  ttsConfigId?: ModelConfigId;
  profileName: string;
  voiceName?: string;
  voiceLabel?: string;
  voiceSource?: VoiceSourceType | string;
  sampleId?: ModelConfigId;
  languageCode?: string;
  gender?: string;
  speechSpeed?: number;
  pitch?: number;
  volumeGain?: number;
  speechFormat?: string;
  sampleRate?: number;
  options?: Record<string, any>;
  isDefault?: boolean;
  enabled?: boolean;
  sample?: TtsVoiceSample;
}

export interface TtsConfigAggregate {
  config?: ModelTtsConfig;
  voiceProfiles: TtsVoiceProfile[];
}

const API_BASE_URL = '/ai/model-config';

class TtsConfigService {
  async get(modelConfigId: ModelConfigId): Promise<TtsConfigAggregate> {
    const response = await Http.post(`${API_BASE_URL}/tts/query`, { modelConfigId: String(modelConfigId) });
    return unwrapDataOr(response as ServiceResponse<TtsConfigAggregate>, {
      voiceProfiles: []
    });
  }

  async save(modelConfigId: ModelConfigId, request: TtsConfigAggregate): Promise<TtsConfigAggregate> {
    const response = await Http.put(`${API_BASE_URL}/tts/modify`, {
      ...request,
      config: {
        ...(request.config || {}),
        modelConfigId: String(modelConfigId)
      }
    });
    return unwrapDataOr(response as ServiceResponse<TtsConfigAggregate>, {
      voiceProfiles: []
    });
  }
}

export default new TtsConfigService();
