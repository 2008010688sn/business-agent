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

import { suiteFileUploadApi } from './suiteFileUpload';

export interface UploadAvatarResult {
  success: boolean;
  message: string;
  url?: string;
  path?: string;
  previewUrl?: string;
  fileName?: string;
  contentType?: string;
  size?: number;
}

export const fileUploadApi = {
  async uploadAvatar(file: File): Promise<UploadAvatarResult> {
    const result = await suiteFileUploadApi.upload(file);

    return {
      success: true,
      message: 'ok',
      url: result.url || result.previewUrl || result.path,
      path: result.path,
      previewUrl: result.previewUrl || result.url,
      fileName: result.fileName,
      contentType: result.contentType,
      size: result.size
    };
  }
};
