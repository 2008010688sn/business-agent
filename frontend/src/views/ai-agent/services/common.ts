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

export interface ApiResponse<T = unknown> {
  success: boolean;
  message: string;
  data?: T;
  code?: number;
  successful?: boolean;
  timestamp?: number;
}

export interface PageResponse<T = unknown> {
  success: boolean;
  message: string;
  data: T;
  total: number;
  pageNum: number;
  pageSize: number;
  totalPages: number;
}

export interface XxCloudResult<T = unknown> {
  successful: boolean;
  code: number;
  message: string;
  timestamp?: number;
  data?: T;
}

export interface MybatisPage<T = unknown> {
  records?: T[];
  total?: number;
  size?: number;
  current?: number;
  pages?: number;
}

const isRecord = (value: unknown): value is Record<string, unknown> => {
  return value !== null && typeof value === 'object';
};

const isApiResponse = <T>(value: unknown): value is ApiResponse<T> => {
  return isRecord(value) && typeof value.success === 'boolean';
};

const isXxCloudResult = <T>(value: unknown): value is XxCloudResult<T> => {
  return isRecord(value) && typeof value.successful === 'boolean' && typeof value.code === 'number';
};

export const normalizeApiResponse = <T>(value: ApiResponse<T> | XxCloudResult<T> | T): ApiResponse<T> => {
  if (isApiResponse<T>(value)) {
    return value;
  }
  if (isXxCloudResult<T>(value)) {
    return {
      success: value.successful,
      successful: value.successful,
      code: value.code,
      message: value.message,
      timestamp: value.timestamp,
      data: value.data
    };
  }
  return {
    success: true,
    successful: true,
    code: 200,
    message: '操作成功',
    data: value as T
  };
};

export const unwrapData = <T>(value: ApiResponse<T> | XxCloudResult<T> | T): T => {
  const response = normalizeApiResponse<T>(value);
  if (!response.success) {
    throw new Error(response.message || '请求失败');
  }
  return response.data as T;
};

export const unwrapDataOr = <T>(value: ApiResponse<T> | XxCloudResult<T> | T, fallback: T): T => {
  const response = normalizeApiResponse<T>(value);
  if (!response.success) {
    throw new Error(response.message || '请求失败');
  }
  return response.data ?? fallback;
};

export const normalizeList = <T>(value: unknown): T[] => {
  if (Array.isArray(value)) {
    return value as T[];
  }

  if (isRecord(value)) {
    const candidates = [value.records, value.data, value.list, value.content, value.rows];
    const list = candidates.find(Array.isArray);
    if (list) {
      return list as T[];
    }
  }

  return [];
};

export const unwrapList = <T>(value: ApiResponse<unknown> | XxCloudResult<unknown> | unknown): T[] => {
  return normalizeList<T>(unwrapDataOr<unknown>(value, []));
};

export const unwrapSuccess = (value: ApiResponse | XxCloudResult | unknown): boolean => {
  return normalizeApiResponse(value).success;
};

export const getResponseStatus = (error: unknown): number | undefined => {
  if (!isRecord(error)) {
    return undefined;
  }
  const response = isRecord(error.response) ? error.response : undefined;
  const status = response?.status ?? error.status;
  return typeof status === 'number' ? status : undefined;
};

export const extractApiErrorMessage = (error: unknown, fallback: string): string => {
  if (isRecord(error)) {
    const response = isRecord(error.response) ? error.response : undefined;
    const data = isRecord(response?.data) ? response.data : undefined;
    const responseMessage = data?.message;

    if (typeof responseMessage === 'string' && responseMessage.trim()) {
      return responseMessage;
    }

    if (typeof error.message === 'string' && error.message.trim()) {
      return error.message;
    }
  }

  if (error instanceof Error && error.message.trim()) {
    return error.message;
  }

  return fallback;
};

export const shouldShowLocalApiError = (error: unknown): boolean => {
  if (!isRecord(error)) {
    return true;
  }

  const response = isRecord(error.response) ? error.response : undefined;
  const code = typeof error.code === 'string' ? error.code : '';

  if (response || error.isAxiosError === true) {
    return false;
  }

  return !['ERR_CANCELED', 'ECONNABORTED', 'ERR_NETWORK', 'ERR_BAD_REQUEST', 'ERR_BAD_RESPONSE'].includes(code);
};

export const isCanceledRequest = (error: unknown): boolean => {
  if (!isRecord(error)) {
    return false;
  }

  const code = error.code;
  const name = error.name;
  const message = error.message;

  return (
    code === 'ERR_CANCELED' ||
    code === 'ECONNABORTED_CANCELED' ||
    name === 'CanceledError' ||
    name === 'AbortError' ||
    (typeof message === 'string' && /cancel|abort|canceled|cancelled/i.test(message))
  );
};

export const toPageResponse = <T>(
  value: ApiResponse<MybatisPage<T>> | XxCloudResult<MybatisPage<T>> | MybatisPage<T>
): PageResponse<T[]> => {
  const page = unwrapDataOr<MybatisPage<T>>(value, {});
  const records = page.records ?? [];
  const total = Number(page.total ?? records.length);
  const pageSize = Number(page.size ?? records.length);
  const pageNum = Number(page.current ?? 1);
  const totalPages = Number(page.pages ?? (pageSize > 0 ? Math.ceil(total / pageSize) : 0));
  return {
    success: true,
    message: '操作成功',
    data: records,
    total,
    pageNum,
    pageSize,
    totalPages
  };
};
