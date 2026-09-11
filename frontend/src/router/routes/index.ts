import type { CustomRoute, ElegantConstRoute, ElegantRoute } from '@elegant-router/types';
import { layouts, views } from '../elegant/imports';
import { generatedRoutes } from '../elegant/routes';
import { transformElegantRoutesToVueRoutes } from '../elegant/transform';

export const customRoutes: CustomRoute[] = [
  {
    name: '403',
    path: '/403',
    component: 'layout.blank$view.403',
    meta: { title: '403', i18nKey: 'route.403', constant: true, hideInMenu: true }
  },
  {
    name: '404',
    path: '/404',
    component: 'layout.blank$view.404',
    meta: { title: '404', i18nKey: 'route.404', constant: true, hideInMenu: true }
  },
  {
    name: '500',
    path: '/500',
    component: 'layout.blank$view.500',
    meta: { title: '500', i18nKey: 'route.500', constant: true, hideInMenu: true }
  },
  {
    name: 'ai-agent',
    path: '/ai-agent',
    component: 'layout.base',
    meta: {
      title: 'AI智能体',
      i18nKey: 'route.ai-agent',
      icon: 'hugeicons:ai-brain-04',
      order: 1,
      hideInMenu: true
    },
    children: [
      {
        name: 'ai-agent_workspace',
        path: '/ai-agent/workspace',
        meta: {
          title: '智能体',
          i18nKey: 'route.ai-agent_workspace',
          icon: 'hugeicons:bot',
          order: 1
        },
        children: [
          {
            name: 'ai-agent_agents',
            path: '/ai-agent/agents',
            component: 'view.ai-agent_agents',
            meta: { title: '智能体管理', i18nKey: 'route.ai-agent_agents', icon: 'hugeicons:bot', order: 1 }
          },
          {
            name: 'ai-agent_skills',
            path: '/ai-agent/skills',
            component: 'view.ai-agent_skills',
            meta: { title: '技能中心', i18nKey: 'route.ai-agent_skills', icon: 'hugeicons:ai-idea', order: 2 }
          },
          {
            name: 'ai-agent_tools',
            path: '/ai-agent/tools',
            component: 'view.ai-agent_tools',
            meta: { title: '工具中心', i18nKey: 'route.ai-agent_tools', icon: 'hugeicons:tools', order: 3 }
          },
          {
            name: 'ai-agent_agent',
            path: '/ai-agent/agent',
            meta: { title: '智能体中心', i18nKey: 'route.ai-agent_agent', hideInMenu: true },
            children: [
              {
                name: 'ai-agent_agent_create',
                path: '/ai-agent/agent/create',
                component: 'view.ai-agent_agent_create',
                meta: {
                  title: '创建智能体',
                  i18nKey: 'route.ai-agent_agent_create',
                  hideInMenu: true,
                  activeMenu: 'ai-agent_agents'
                }
              },
              {
                name: 'ai-agent_agent_detail',
                path: '/ai-agent/agent/detail',
                component: 'view.ai-agent_agent_detail',
                meta: {
                  title: '智能体详情',
                  i18nKey: 'route.ai-agent_agent_detail',
                  hideInMenu: true,
                  activeMenu: 'ai-agent_agents'
                }
              },
              {
                name: 'ai-agent_agent_run',
                path: '/ai-agent/agent/run',
                component: 'view.ai-agent_agent_run',
                meta: {
                  title: '运行智能体',
                  i18nKey: 'route.ai-agent_agent_run',
                  hideInMenu: true,
                  activeMenu: 'ai-agent_agents'
                }
              },
              {
                name: 'ai-agent_agent_ai-run',
                path: '/ai-agent/agent/ai-run',
                component: 'view.ai-agent_agent_ai-run',
                meta: {
                  title: '运行智能体',
                  i18nKey: 'route.ai-agent_agent_ai-run',
                  hideInMenu: true,
                  activeMenu: 'ai-agent_agents'
                }
              },
              {
                name: 'ai-agent_agent_visibility-applications',
                path: '/ai-agent/agent/visibility-applications',
                component: 'view.ai-agent_agent_visibility-applications',
                meta: {
                  title: '智能体审批管理',
                  i18nKey: 'route.ai-agent_agent_visibility-applications',
                  hideInMenu: true
                }
              }
            ]
          }
        ]
      },
      {
        name: 'ai-agent_catalog',
        path: '/ai-agent/catalog',
        meta: {
          title: '数据与模型',
          i18nKey: 'route.ai-agent_catalog',
          icon: 'hugeicons:database',
          order: 2
        },
        children: [
          {
            name: 'ai-agent_data-sources',
            path: '/ai-agent/data-sources',
            component: 'view.ai-agent_data-sources',
            meta: { title: '数据中心', i18nKey: 'route.ai-agent_data-sources', icon: 'hugeicons:database', order: 1 },
            children: [
              {
                name: 'ai-agent_data-sources_detail',
                path: '/ai-agent/data-sources/detail',
                component: 'view.ai-agent_data-sources_detail',
                meta: {
                  title: '数据源详情',
                  i18nKey: 'route.ai-agent_data-sources_detail',
                  hideInMenu: true,
                  activeMenu: 'ai-agent_data-sources'
                }
              }
            ]
          },
          {
            name: 'ai-agent_model-config',
            path: '/ai-agent/model-config',
            component: 'view.ai-agent_model-config',
            meta: { title: '模型中心', i18nKey: 'route.ai-agent_model-config', icon: 'hugeicons:ai-chip', order: 2 }
          }
        ]
      },
      {
        name: 'ai-agent_employees',
        path: '/ai-agent/employees',
        meta: {
          title: '数字员工',
          i18nKey: 'route.ai-agent_employees',
          icon: 'hugeicons:user-multiple',
          order: 3
        },
        children: [
          {
            name: 'ai-agent_digital-employees',
            path: '/ai-agent/digital-employees',
            component: 'view.ai-agent_digital-employees',
            meta: {
              title: '数字员工管理',
              i18nKey: 'route.ai-agent_digital-employees',
              icon: 'hugeicons:user-multiple',
              order: 1
            },
            children: [
              {
                name: 'ai-agent_digital-employees_detail',
                path: '/ai-agent/digital-employees/detail',
                component: 'view.ai-agent_digital-employees_detail',
                meta: {
                  title: '数字员工详情',
                  i18nKey: 'route.ai-agent_digital-employees_detail',
                  hideInMenu: true,
                  activeMenu: 'ai-agent_digital-employees'
                }
              }
            ]
          },
          {
            name: 'ai-agent_skill-market',
            path: '/ai-agent/skill-market',
            component: 'view.ai-agent_skill-market',
            meta: { title: '技能市场', i18nKey: 'route.ai-agent_skill-market', icon: 'hugeicons:store-01', order: 2 }
          },
          {
            name: 'ai-agent_authorizations',
            path: '/ai-agent/authorizations',
            component: 'view.ai-agent_authorizations',
            meta: { title: '权限中心', i18nKey: 'route.ai-agent_authorizations', icon: 'hugeicons:key-01', order: 3 }
          }
        ]
      },
      {
        name: 'ai-agent_ops',
        path: '/ai-agent/ops',
        meta: {
          title: '运行',
          i18nKey: 'route.ai-agent_ops',
          icon: 'hugeicons:analytics-01',
          order: 4
        },
        children: [
          {
            name: 'ai-agent_runtime-runs',
            path: '/ai-agent/runtime-runs',
            component: 'view.ai-agent_runtime-runs',
            meta: { title: '运行记录', i18nKey: 'route.ai-agent_runtime-runs', icon: 'hugeicons:analytics-01', order: 1 }
          },
          {
            name: 'ai-agent_tasks',
            path: '/ai-agent/tasks',
            component: 'view.ai-agent_tasks',
            meta: { title: '任务中心', i18nKey: 'route.ai-agent_tasks', icon: 'hugeicons:task-01', order: 2 }
          },
          {
            name: 'ai-agent_diagnostics',
            path: '/ai-agent/diagnostics',
            component: 'view.ai-agent_diagnostics',
            meta: { title: '会话排障', i18nKey: 'route.ai-agent_diagnostics', order: 3 }
          },
          {
            name: 'ai-agent_token-usage',
            path: '/ai-agent/token-usage',
            component: 'view.ai-agent_token-usage',
            meta: { title: 'Token 用量', i18nKey: 'route.ai-agent_token-usage', order: 4 }
          }
        ]
      },
      {
        name: 'ai-agent_quality',
        path: '/ai-agent/quality',
        meta: {
          title: '评估',
          i18nKey: 'route.ai-agent_quality',
          icon: 'hugeicons:analytics-01',
          order: 5
        },
        children: [
          {
            name: 'ai-agent_evaluations',
            path: '/ai-agent/evaluations',
            component: 'view.ai-agent_evaluations',
            meta: { title: '智能体评估', i18nKey: 'route.ai-agent_evaluations', order: 1 }
          },
          {
            name: 'ai-agent_optimizations',
            path: '/ai-agent/optimizations',
            component: 'view.ai-agent_optimizations',
            meta: { title: '智能体自优化', i18nKey: 'route.ai-agent_optimizations', order: 2 }
          },
          {
            name: 'ai-agent_evaluation-results',
            path: '/ai-agent/evaluation-results',
            component: 'view.ai-agent_evaluation-results',
            meta: { title: '评估排障', i18nKey: 'route.ai-agent_evaluation-results', hideInMenu: true }
          }
        ]
      },
      {
        name: 'ai-agent_integration',
        path: '/ai-agent/integration',
        meta: {
          title: '集成',
          i18nKey: 'route.ai-agent_integration',
          icon: 'hugeicons:tools',
          order: 6
        },
        children: [
          {
            name: 'ai-agent_mcp-exposure',
            path: '/ai-agent/mcp-exposure',
            component: 'view.ai-agent_mcp-exposure',
            meta: { title: 'MCP中心', i18nKey: 'route.ai-agent_mcp-exposure', order: 1 }
          },
          {
            name: 'ai-agent_runtime-hooks',
            path: '/ai-agent/runtime-hooks',
            component: 'view.ai-agent_runtime-hooks',
            meta: { title: '事件触发中心', i18nKey: 'route.ai-agent_runtime-hooks', order: 2 }
          },
          {
            name: 'ai-agent_im-connector',
            path: '/ai-agent/im-connector',
            component: 'view.ai-agent_im-connector',
            meta: { title: 'IM 连接器', i18nKey: 'route.ai-agent_im-connector', order: 3 }
          },
          {
            name: 'ai-agent_notification-center',
            path: '/ai-agent/notification-center',
            component: 'view.ai-agent_notification-center',
            meta: { title: '通知中心', i18nKey: 'route.ai-agent_notification-center', order: 4 }
          }
        ]
      },
      {
        name: 'ai-agent_not-found',
        path: '/ai-agent/not-found',
        component: 'view.ai-agent_not-found',
        meta: { title: '页面不存在', i18nKey: 'route.ai-agent_not-found', hideInMenu: true }
      }
    ]
  }
];

export function createStaticRoutes() {
  const constantRoutes: ElegantRoute[] = [];
  const authRoutes: ElegantRoute[] = [];

  [...customRoutes, ...generatedRoutes].forEach(item => {
    if (item.meta?.constant) {
      constantRoutes.push(item);
    } else {
      authRoutes.push(item);
    }
  });

  return {
    constantRoutes,
    authRoutes
  };
}

export function getAuthVueRoutes(routes: ElegantConstRoute[]) {
  return transformElegantRoutesToVueRoutes(routes, layouts, views);
}
