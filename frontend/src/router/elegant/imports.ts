import type { RouteComponent } from 'vue-router';
import type { LastLevelRouteKey, RouteLayout } from '@elegant-router/types';

import BaseLayout from '@/layouts/base-layout/index.vue';
import BlankLayout from '@/layouts/blank-layout/index.vue';

export const layouts: Record<RouteLayout, RouteComponent | (() => Promise<RouteComponent>)> = {
  base: BaseLayout,
  blank: BlankLayout
};

export const views: Record<LastLevelRouteKey, RouteComponent | (() => Promise<RouteComponent>)> = {
  403: () => import('@/views/_builtin/403/index.vue'),
  404: () => import('@/views/_builtin/404/index.vue'),
  500: () => import('@/views/_builtin/500/index.vue'),
  'ai-agent_agent_ai-run': () => import('@/views/ai-agent/agent/ai-run/index.vue'),
  'ai-agent_agent_create': () => import('@/views/ai-agent/agent/create/index.vue'),
  'ai-agent_agent_detail': () => import('@/views/ai-agent/agent/detail/index.vue'),
  'ai-agent_agent_run': () => import('@/views/ai-agent/agent/run/index.vue'),
  'ai-agent_agent_visibility-applications': () => import('@/views/ai-agent/agent/visibility-applications/index.vue'),
  'ai-agent_agents': () => import('@/views/ai-agent/agents/index.vue'),
  'ai-agent_authorizations': () => import('@/views/ai-agent/authorizations/index.vue'),
  'ai-agent_data-sources_detail': () => import('@/views/ai-agent/data-sources/detail/index.vue'),
  'ai-agent_data-sources': () => import('@/views/ai-agent/data-sources/index.vue'),
  'ai-agent_diagnostics': () => import('@/views/ai-agent/diagnostics/index.vue'),
  'ai-agent_digital-employees': () => import('@/views/ai-agent/digital-employees/index.vue'),
  'ai-agent_digital-employees_detail': () => import('@/views/ai-agent/digital-employees/detail/index.vue'),
  'ai-agent_evaluation-results': () => import('@/views/ai-agent/evaluation-results/index.vue'),
  'ai-agent_evaluations': () => import('@/views/ai-agent/evaluations/index.vue'),
  'ai-agent_im-connector': () => import('@/views/ai-agent/im-connector/index.vue'),
  'ai-agent_mcp-exposure': () => import('@/views/ai-agent/mcp-exposure/index.vue'),
  'ai-agent_model-config': () => import('@/views/ai-agent/model-config/index.vue'),
  'ai-agent_not-found': () => import('@/views/ai-agent/not-found/index.vue'),
  'ai-agent_notification-center': () => import('@/views/ai-agent/notification-center/index.vue'),
  'ai-agent_optimizations': () => import('@/views/ai-agent/optimizations/index.vue'),
  'ai-agent_runtime-hooks': () => import('@/views/ai-agent/runtime-hooks/index.vue'),
  'ai-agent_runtime-runs': () => import('@/views/ai-agent/runtime-runs/index.vue'),
  'ai-agent_skill-market': () => import('@/views/ai-agent/skill-market/index.vue'),
  'ai-agent_skills': () => import('@/views/ai-agent/skills/index.vue'),
  'ai-agent_tasks': () => import('@/views/ai-agent/tasks/index.vue'),
  'ai-agent_token-usage': () => import('@/views/ai-agent/token-usage/index.vue'),
  'ai-agent_tools': () => import('@/views/ai-agent/tools/index.vue')
};
