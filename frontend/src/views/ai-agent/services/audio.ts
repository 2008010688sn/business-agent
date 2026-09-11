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
import { getToken } from '@/store/modules/auth/shared';
import { baseURL } from '@/service/request';
import { unwrapDataOr } from './common';
import type { ApiResponse, XxCloudResult } from './common';
import type { ModelConfigId } from './modelConfig';

export interface AudioTranscriptionResponse {
  text: string;
}

export interface AudioSpeechRequest {
  text: string;
  modelConfigId?: ModelConfigId;
  voiceProfileId?: ModelConfigId;
  format?: string;
  sampleRate?: number;
  speed?: number;
  options?: Record<string, any>;
}

export interface TtsVoiceSample {
  id?: ModelConfigId;
  fileId: string;
  fileName?: string;
  filePath?: string;
  contentType?: string;
  durationMs?: number;
  sampleRate?: number;
  transcript?: string;
  consentConfirmed?: boolean;
  status?: string;
  errorMessage?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/audio';

class AudioService {
  async transcribe(file: File | Blob): Promise<AudioTranscriptionResponse> {
    const formData = new FormData();
    const filename = file instanceof File ? file.name : 'recording.wav';
    formData.append('file', file, filename);
    const response = await Http.post(`${API_BASE_URL}/transcriptions`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120 * 1000
    });
    return unwrapDataOr(response as ServiceResponse<AudioTranscriptionResponse>, { text: '' });
  }

  async speech(request: AudioSpeechRequest): Promise<Blob> {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 120 * 1000);
    try {
      const response = await fetch(`${baseURL}${API_BASE_URL}/speech`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'V4-Authorization': getToken() || ''
        },
        body: JSON.stringify(request),
        signal: controller.signal
      });
      if (!response.ok) {
        throw new Error(`TTS request failed: ${response.status}`);
      }
      return response.blob();
    } finally {
      window.clearTimeout(timeout);
    }
  }
  async uploadVoiceSample(file: File, transcript = '', consentConfirmed = false): Promise<TtsVoiceSample> {
    const formData = new FormData();
    formData.append('file', file, file.name);
    formData.append('transcript', transcript);
    formData.append('consentConfirmed', String(consentConfirmed));
    const response = await Http.post(`${API_BASE_URL}/voice-samples`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120 * 1000
    });
    return unwrapDataOr(response as ServiceResponse<TtsVoiceSample>, {} as TtsVoiceSample);
  }

  async listVoiceSamples(): Promise<TtsVoiceSample[]> {
    const response = await Http.get(`${API_BASE_URL}/voice-samples`);
    return unwrapDataOr(response as ServiceResponse<TtsVoiceSample[]>, []);
  }

  async deleteVoiceSample(id: ModelConfigId): Promise<void> {
    await Http.delete(`${API_BASE_URL}/voice-samples/${id}`);
  }
}

export default new AudioService();
