import type { LocationQuery, RouteLocationRaw, Router } from 'vue-router';
import type { AgentPageContext } from '@/views/ai-agent/services/graph';

export const AGENT_RUN_ROUTE_NAME = 'ai-agent_agent_run' as const;
export const PAGE_CONTEXT_KEYS_QUERY = 'pageContextKeys';
export const PAGE_CONTEXT_OBJECT_TYPE_QUERY = 'pageContextObjectType';
export const AGENT_RUN_PREFILL_QUERY = 'q';

export interface OpenAgentWithPageContextOptions {
  agentId?: string | null;
  keys: Record<string, string>;
  objectType?: string;
  query?: string;
}

const firstQueryText = (value: unknown): string => {
  const raw = Array.isArray(value) ? value[0] : value;
  return typeof raw === 'string' ? raw.trim() : '';
};

export const toPageContextKeyRecord = (keys: Record<string, unknown> | null | undefined): Record<string, string> => {
  const result: Record<string, string> = {};
  if (!keys || typeof keys !== 'object' || Array.isArray(keys)) {
    return result;
  }
  Object.entries(keys).forEach(([key, value]) => {
    if (typeof key !== 'string' || !key.trim()) {
      return;
    }
    if (typeof value !== 'string') {
      return;
    }
    const trimmed = value.trim();
    if (!trimmed) {
      return;
    }
    result[key.trim()] = trimmed;
  });
  return result;
};

export const parsePageContextFromQuery = (
  query: LocationQuery | Record<string, unknown> | null | undefined
): AgentPageContext | undefined => {
  if (!query || typeof query !== 'object') {
    return undefined;
  }
  const source = query as Record<string, unknown>;
  let keys: Record<string, string> = {};
  const rawKeys = firstQueryText(source[PAGE_CONTEXT_KEYS_QUERY]);
  if (rawKeys) {
    try {
      const parsed = JSON.parse(rawKeys) as unknown;
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        keys = toPageContextKeyRecord(parsed as Record<string, unknown>);
      }
    } catch {
      keys = {};
    }
  }
  const objectType = firstQueryText(source[PAGE_CONTEXT_OBJECT_TYPE_QUERY]);
  if (!Object.keys(keys).length && !objectType) {
    return undefined;
  }
  return objectType ? { keys, objectType } : { keys };
};

export const parseAgentRunPrefillQuery = (
  query: LocationQuery | Record<string, unknown> | null | undefined
): string => {
  if (!query || typeof query !== 'object') {
    return '';
  }
  return firstQueryText((query as Record<string, unknown>)[AGENT_RUN_PREFILL_QUERY]);
};

export const buildAgentRunRoute = (options: OpenAgentWithPageContextOptions): RouteLocationRaw => {
  const keys = toPageContextKeyRecord(options.keys);
  const query: Record<string, string> = {};
  const agentId = typeof options.agentId === 'string' ? options.agentId.trim() : '';
  if (agentId) {
    query.agentId = agentId;
  }
  if (Object.keys(keys).length) {
    query[PAGE_CONTEXT_KEYS_QUERY] = JSON.stringify(keys);
  }
  const objectType = typeof options.objectType === 'string' ? options.objectType.trim() : '';
  if (objectType) {
    query[PAGE_CONTEXT_OBJECT_TYPE_QUERY] = objectType;
  }
  const prefill = typeof options.query === 'string' ? options.query.trim() : '';
  if (prefill) {
    query[AGENT_RUN_PREFILL_QUERY] = prefill;
  }
  return {
    name: AGENT_RUN_ROUTE_NAME,
    query
  };
};

export const openAgentWithPageContext = (router: Router, options: OpenAgentWithPageContextOptions) => {
  return router.push(buildAgentRunRoute(options));
};
