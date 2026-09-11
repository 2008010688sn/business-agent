declare module '@elegant-router/types' {
  type ElegantConstRoute = import('@elegant-router/vue').ElegantConstRoute;

  export type RouteLayout = 'base' | 'blank';

  export type RouteMap = {
    root: '/';
    'not-found': '/:pathMatch(.*)*';
    '403': '/403';
    '404': '/404';
    '500': '/500';
    'ai-agent': '/ai-agent';
    'ai-agent_workspace': '/ai-agent/workspace';
    'ai-agent_catalog': '/ai-agent/catalog';
    'ai-agent_employees': '/ai-agent/employees';
    'ai-agent_ops': '/ai-agent/ops';
    'ai-agent_quality': '/ai-agent/quality';
    'ai-agent_integration': '/ai-agent/integration';
    'ai-agent_agent': '/ai-agent/agent';
    'ai-agent_agent_ai-run': '/ai-agent/agent/ai-run';
    'ai-agent_agent_create': '/ai-agent/agent/create';
    'ai-agent_agent_detail': '/ai-agent/agent/detail';
    'ai-agent_agent_run': '/ai-agent/agent/run';
    'ai-agent_agent_visibility-applications': '/ai-agent/agent/visibility-applications';
    'ai-agent_agents': '/ai-agent/agents';
    'ai-agent_authorizations': '/ai-agent/authorizations';
    'ai-agent_data-sources': '/ai-agent/data-sources';
    'ai-agent_data-sources_detail': '/ai-agent/data-sources/detail';
    'ai-agent_diagnostics': '/ai-agent/diagnostics';
    'ai-agent_digital-employees': '/ai-agent/digital-employees';
    'ai-agent_digital-employees_detail': '/ai-agent/digital-employees/detail';
    'ai-agent_evaluation-results': '/ai-agent/evaluation-results';
    'ai-agent_evaluations': '/ai-agent/evaluations';
    'ai-agent_im-connector': '/ai-agent/im-connector';
    'ai-agent_mcp-exposure': '/ai-agent/mcp-exposure';
    'ai-agent_model-config': '/ai-agent/model-config';
    'ai-agent_not-found': '/ai-agent/not-found';
    'ai-agent_notification-center': '/ai-agent/notification-center';
    'ai-agent_optimizations': '/ai-agent/optimizations';
    'ai-agent_runtime-hooks': '/ai-agent/runtime-hooks';
    'ai-agent_runtime-runs': '/ai-agent/runtime-runs';
    'ai-agent_skill-market': '/ai-agent/skill-market';
    'ai-agent_skills': '/ai-agent/skills';
    'ai-agent_tasks': '/ai-agent/tasks';
    'ai-agent_token-usage': '/ai-agent/token-usage';
    'ai-agent_tools': '/ai-agent/tools';
  };

  export type RouteKey = keyof RouteMap;
  export type RoutePath = RouteMap[RouteKey];
  export type GeneratedRouteKey = RouteKey;
  export type CustomRouteKey = RouteKey;
  export type FirstLevelRouteKey = Extract<RouteKey, '403' | '404' | '500' | 'ai-agent'>;
  export type LastLevelRouteKey = Exclude<
    RouteKey,
    | 'root'
    | 'not-found'
    | 'ai-agent'
    | 'ai-agent_workspace'
    | 'ai-agent_catalog'
    | 'ai-agent_employees'
    | 'ai-agent_ops'
    | 'ai-agent_quality'
    | 'ai-agent_integration'
    | 'ai-agent_agent'
  >;

  export type CustomRoute = ElegantConstRoute;
  export type GeneratedRoute = ElegantConstRoute;
  export type ElegantRoute = ElegantConstRoute;
}
