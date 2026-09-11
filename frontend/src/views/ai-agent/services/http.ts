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

import axios, { AxiosHeaders } from 'axios';
import { nanoid } from '@sa/utils';
import { baseURL } from '@/service/request';
import { getToken } from '@/store/modules/auth/shared';

const LOCAL_TOKEN = (import.meta.env.VITE_DATA_AGENT_TOKEN || '').trim();
const LOCAL_AUTH_ENABLED = import.meta.env.VITE_DATA_AGENT_LOCAL_AUTH_ENABLED !== 'false' && Boolean(LOCAL_TOKEN);

const normalizeBearerToken = (token: string): string => {
  const value = token.trim();
  return value.toLowerCase().startsWith('bearer ') ? value : `Bearer ${value}`;
};

export const DATA_AGENT_AUTH_HEADER = 'V4-Authorization';

export const DATA_AGENT_AUTH_VALUE = LOCAL_AUTH_ENABLED ? normalizeBearerToken(LOCAL_TOKEN) : null;

const DATA_AGENT_AUTH_PATH_PREFIXES = ['/ai/', '/api/ai/', '/uploads'];
const ABSOLUTE_URL_RE = /^([a-z][a-z\d+\-.]*:)?\/\//i;

export const resolveDataAgentRequestUrl = (url: string): string => {
  if (!url || ABSOLUTE_URL_RE.test(url)) {
    return url;
  }

  const normalizedUrl = url.startsWith('/') ? url : `/${url}`;
  const normalizedBaseURL = (baseURL || '').replace(/\/+$/, '');

  if (!normalizedBaseURL || normalizedUrl === normalizedBaseURL || normalizedUrl.startsWith(`${normalizedBaseURL}/`)) {
    return normalizedUrl;
  }

  return `${normalizedBaseURL}${normalizedUrl}`;
};

const resolvePathname = (url: string): string => {
  try {
    return new URL(url, 'http://data-agent.local').pathname;
  } catch {
    return url;
  }
};

const resolveAxiosUrl = (url?: string, baseURL?: string): string => {
  const requestUrl = url ?? '';
  if (!baseURL || requestUrl.startsWith('/') || requestUrl.startsWith('http')) {
    return requestUrl;
  }
  return `${baseURL.replace(/\/$/, '')}/${requestUrl.replace(/^\//, '')}`;
};

const shouldAttachLocalAuth = (url: string): boolean => {
  const pathname = resolvePathname(url);
  return DATA_AGENT_AUTH_PATH_PREFIXES.some(prefix => pathname.startsWith(prefix));
};

if (DATA_AGENT_AUTH_VALUE) {
  axios.defaults.headers.common[DATA_AGENT_AUTH_HEADER] = DATA_AGENT_AUTH_VALUE;
  axios.interceptors.request.use(config => {
    if (shouldAttachLocalAuth(resolveAxiosUrl(config.url, config.baseURL))) {
      const headers = AxiosHeaders.from(config.headers);
      headers.set(DATA_AGENT_AUTH_HEADER, DATA_AGENT_AUTH_VALUE);
      config.headers = headers;
    }
    return config;
  });
}

export const dataAgentAuthHeaders = (): Record<string, string> => {
  const token = getToken();
  return {
    ...(token ? { [DATA_AGENT_AUTH_HEADER]: token } : {}),
    ...(DATA_AGENT_AUTH_VALUE ? { [DATA_AGENT_AUTH_HEADER]: DATA_AGENT_AUTH_VALUE } : {})
  };
};

export interface SseMessage {
  event: string;
  data: string;
}

export class StreamHttpError extends Error {
  status: number;

  constructor(status: number, message = `Stream connection failed: ${status}`) {
    super(message);
    this.name = 'StreamHttpError';
    this.status = status;
  }
}

export const isStreamAuthError = (error: unknown): boolean =>
  error instanceof StreamHttpError && (error.status === 401 || error.status === 403);

/** 对齐 packages/axios 的 REQUEST_ID_KEY 约定；后端网关按现有链路透传该追踪头 */
export const STREAM_TRACE_HEADER = 'X-Request-Id';

export const streamWithAuth = async (
  url: string,
  onEvent: (message: SseMessage) => Promise<void> | void,
  onError?: (error: Error) => Promise<void> | void,
  onEnd?: () => Promise<void> | void,
  init?: Omit<RequestInit, 'signal'>
): Promise<() => void> => {
  const controller = new AbortController();
  const headers = new Headers(init?.headers);
  Object.entries(dataAgentAuthHeaders()).forEach(([key, value]) => headers.set(key, value));
  // SSE 请求补充链路追踪短 ID，与 axios 链路的 X-Request-Id 注入保持一致
  if (!headers.has(STREAM_TRACE_HEADER)) {
    headers.set(STREAM_TRACE_HEADER, nanoid());
  }
  const requestUrl = resolveDataAgentRequestUrl(url);
  const response = await fetch(requestUrl, {
    ...init,
    method: init?.method || 'GET',
    headers,
    signal: controller.signal
  });

  if (!response.ok || !response.body) {
    throw new StreamHttpError(response.status);
  }

  const decoder = new TextDecoder('utf-8');
  const reader = response.body.getReader();
  let buffer = '';

  const consume = async () => {
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        const chunks = buffer.split(/\r?\n\r?\n/);
        buffer = chunks.pop() ?? '';
        for (const chunk of chunks) {
          const message = parseSseChunk(chunk);
          if (message.data || message.event !== 'message') {
            await onEvent(message);
          }
        }
      }
      const tail = buffer.trim();
      if (tail) {
        const message = parseSseChunk(tail);
        if (message.data || message.event !== 'message') {
          await onEvent(message);
        }
      }
      if (!controller.signal.aborted) {
        await onEnd?.();
      }
    } catch (error) {
      if (!controller.signal.aborted && onError) {
        await onError(error instanceof Error ? error : new Error('Stream connection failed'));
      }
    }
  };

  void consume();

  return () => {
    controller.abort();
    void reader.cancel().catch(() => undefined);
  };
};

const parseSseChunk = (chunk: string): SseMessage => {
  let event = 'message';
  const data: string[] = [];
  chunk.split(/\r?\n/).forEach(line => {
    if (line.startsWith('event:')) {
      event = line.slice(6).trim();
    } else if (line.startsWith('data:')) {
      data.push(line.slice(5).trimStart());
    }
  });
  return { event, data: data.join('\n') };
};

const attachLocalAuthHeaders = (...headersList: Array<HeadersInit | undefined>): Headers => {
  const nextHeaders = new Headers();
  headersList.forEach(headers => {
    if (!headers) {
      return;
    }
    new Headers(headers).forEach((value, key) => nextHeaders.set(key, value));
  });
  if (DATA_AGENT_AUTH_VALUE) {
    nextHeaders.set(DATA_AGENT_AUTH_HEADER, DATA_AGENT_AUTH_VALUE);
  }
  return nextHeaders;
};

const installFetchLocalAuth = (): void => {
  if (!DATA_AGENT_AUTH_VALUE || typeof window === 'undefined' || window.fetch == null) {
    return;
  }
  const originalFetch = window.fetch.bind(window);
  window.fetch = (input: RequestInfo | URL, init?: RequestInit) => {
    const url = input instanceof Request ? input.url : input.toString();
    if (!shouldAttachLocalAuth(url)) {
      return originalFetch(input, init);
    }
    if (input instanceof Request) {
      return originalFetch(
        new Request(input, { ...init, headers: attachLocalAuthHeaders(input.headers, init?.headers) })
      );
    }
    return originalFetch(input, { ...init, headers: attachLocalAuthHeaders(init?.headers) });
  };
};

installFetchLocalAuth();
