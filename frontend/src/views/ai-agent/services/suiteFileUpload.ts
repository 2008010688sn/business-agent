import { Http } from '@/service/request';

export interface SuiteUploadResult {
  path: string;
  previewUrl?: string;
  fileName: string;
  contentType: string;
  size: number;
  url?: string;
}

interface UploadResponse {
  url?: string;
  previewUrl?: string;
  path?: string;
  fileName?: string;
  contentType?: string;
  size?: number;
}

const resolveUploadData = (response: unknown): UploadResponse => {
  const payload = (response as { data?: unknown })?.data ?? response;
  return (payload || {}) as UploadResponse;
};

export const suiteFileUploadApi = {
  async upload(file: File): Promise<SuiteUploadResult> {
    const form = new FormData();
    form.append('file', file);

    const data = resolveUploadData(await Http.post('/ai/files/upload', form));

    const absoluteUrl = data.url || data.previewUrl || '';
    if (!absoluteUrl) {
      throw new Error('File upload url is unavailable');
    }

    return {
      path: data.path || absoluteUrl,
      previewUrl: absoluteUrl,
      url: absoluteUrl,
      fileName: data.fileName || file.name,
      contentType: data.contentType || file.type,
      size: data.size ?? file.size
    };
  }
};
