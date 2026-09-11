import type { RouteRecordRaw, RouteComponent } from 'vue-router';
import type { ElegantConstRoute } from '@elegant-router/vue';
import type { RouteMap, RouteKey, RoutePath } from '@elegant-router/types';

export function transformElegantRoutesToVueRoutes(
  routes: ElegantConstRoute[],
  layouts: Record<string, RouteComponent | (() => Promise<RouteComponent>)>,
  views: Record<string, RouteComponent | (() => Promise<RouteComponent>)>
) {
  return routes.flatMap(route => transformElegantRouteToVueRoute(route, layouts, views));
}

function transformElegantRouteToVueRoute(
  route: ElegantConstRoute,
  layouts: Record<string, RouteComponent | (() => Promise<RouteComponent>)>,
  views: Record<string, RouteComponent | (() => Promise<RouteComponent>)>
) {
  const LAYOUT_PREFIX = 'layout.';
  const VIEW_PREFIX = 'view.';
  const ROUTE_DEGREE_SPLITTER = '_';
  const FIRST_LEVEL_ROUTE_COMPONENT_SPLIT = '$';

  function isLayout(component: string) {
    return component.startsWith(LAYOUT_PREFIX);
  }

  function getLayoutName(component: string) {
    const layout = component.replace(LAYOUT_PREFIX, '');
    if (!layouts[layout]) {
      throw new Error(`Layout component "${layout}" not found`);
    }
    return layout;
  }

  function isView(component: string) {
    return component.startsWith(VIEW_PREFIX);
  }

  function getViewName(component: string) {
    const view = component.replace(VIEW_PREFIX, '');
    if (!views[view]) {
      throw new Error(`View component "${view}" not found`);
    }
    return view;
  }

  function isFirstLevelRoute(item: ElegantConstRoute) {
    return !item.name.includes(ROUTE_DEGREE_SPLITTER);
  }

  function isSingleLevelRoute(item: ElegantConstRoute) {
    return isFirstLevelRoute(item) && !item.children?.length;
  }

  function getSingleLevelRouteComponent(component: string) {
    const [layout, view] = component.split(FIRST_LEVEL_ROUTE_COMPONENT_SPLIT);
    return {
      layout: getLayoutName(layout),
      view: getViewName(view)
    };
  }

  const vueRoutes: RouteRecordRaw[] = [];

  if (route.path.includes(':') && !route.props) {
    route.props = true;
  }

  const { name, path, component, children, ...rest } = route;
  const vueRoute = { name, path, ...rest } as RouteRecordRaw;

  try {
    if (component) {
      if (isSingleLevelRoute(route)) {
        const { layout, view } = getSingleLevelRouteComponent(component);
        const singleLevelRoute: RouteRecordRaw = {
          path,
          component: layouts[layout],
          meta: {
            title: route.meta?.title || ''
          },
          children: [
            {
              name,
              path: '',
              component: views[view],
              ...rest
            } as RouteRecordRaw
          ]
        };
        return [singleLevelRoute];
      }

      if (isLayout(component)) {
        vueRoute.component = layouts[getLayoutName(component)];
      }

      if (isView(component)) {
        vueRoute.component = views[getViewName(component)];
      }
    }
  } catch (error: unknown) {
    console.error(`Error transforming route "${route.name}": ${String(error)}`);
    return [];
  }

  // Only folder/group routes (no page of their own) redirect to the first child.
  // List pages with a hidden detail child must keep their own path.
  if (children?.length && !vueRoute.redirect && !component) {
    vueRoute.redirect = { name: children[0].name };
  }

  if (children?.length) {
    const childRoutes = children.flatMap(child => transformElegantRouteToVueRoute(child, layouts, views));
    if (isFirstLevelRoute(route)) {
      vueRoute.children = childRoutes;
    } else {
      vueRoutes.push(...childRoutes);
    }
  }

  vueRoutes.unshift(vueRoute);
  return vueRoutes;
}

const routeMap: RouteMap = {
  root: '/',
  'not-found': '/:pathMatch(.*)*',
  '403': '/403',
  '404': '/404',
  '500': '/500',
  'ai-agent': '/ai-agent',
  'ai-agent_workspace': '/ai-agent/workspace',
  'ai-agent_catalog': '/ai-agent/catalog',
  'ai-agent_employees': '/ai-agent/employees',
  'ai-agent_ops': '/ai-agent/ops',
  'ai-agent_quality': '/ai-agent/quality',
  'ai-agent_integration': '/ai-agent/integration',
  'ai-agent_agent': '/ai-agent/agent',
  'ai-agent_agent_ai-run': '/ai-agent/agent/ai-run',
  'ai-agent_agent_create': '/ai-agent/agent/create',
  'ai-agent_agent_detail': '/ai-agent/agent/detail',
  'ai-agent_agent_run': '/ai-agent/agent/run',
  'ai-agent_agent_visibility-applications': '/ai-agent/agent/visibility-applications',
  'ai-agent_agents': '/ai-agent/agents',
  'ai-agent_authorizations': '/ai-agent/authorizations',
  'ai-agent_data-sources': '/ai-agent/data-sources',
  'ai-agent_data-sources_detail': '/ai-agent/data-sources/detail',
  'ai-agent_diagnostics': '/ai-agent/diagnostics',
  'ai-agent_digital-employees': '/ai-agent/digital-employees',
  'ai-agent_digital-employees_detail': '/ai-agent/digital-employees/detail',
  'ai-agent_evaluation-results': '/ai-agent/evaluation-results',
  'ai-agent_evaluations': '/ai-agent/evaluations',
  'ai-agent_im-connector': '/ai-agent/im-connector',
  'ai-agent_mcp-exposure': '/ai-agent/mcp-exposure',
  'ai-agent_model-config': '/ai-agent/model-config',
  'ai-agent_not-found': '/ai-agent/not-found',
  'ai-agent_notification-center': '/ai-agent/notification-center',
  'ai-agent_optimizations': '/ai-agent/optimizations',
  'ai-agent_runtime-hooks': '/ai-agent/runtime-hooks',
  'ai-agent_runtime-runs': '/ai-agent/runtime-runs',
  'ai-agent_skill-market': '/ai-agent/skill-market',
  'ai-agent_skills': '/ai-agent/skills',
  'ai-agent_tasks': '/ai-agent/tasks',
  'ai-agent_token-usage': '/ai-agent/token-usage',
  'ai-agent_tools': '/ai-agent/tools'
};

export function getRoutePath<T extends RouteKey>(name: T) {
  return routeMap[name];
}

export function getRouteName(path: RoutePath) {
  const routeEntries = Object.entries(routeMap) as [RouteKey, RoutePath][];
  const routeName: RouteKey | null = routeEntries.find(([, routePath]) => routePath === path)?.[0] || null;
  return routeName;
}
