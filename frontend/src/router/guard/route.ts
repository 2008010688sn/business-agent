import type { NavigationGuardNext, RouteLocationNormalized, RouteLocationRaw, Router } from 'vue-router';
import type { RouteKey } from '@elegant-router/types';
import { useAppStore } from '@/store/modules/app';
import { useRouteStore } from '@/store/modules/route';
import { normalizeUrlLang } from '@/locales/url-lang';

export function createRouteGuard(router: Router) {
  router.beforeEach(async (to, from, next) => {
    applyUrlLang(to);

    const location = await initRoute(to);
    if (location) {
      next(location);
      return;
    }

    handleRouteSwitch(to, from, next);
  });
}

function applyUrlLang(to: RouteLocationNormalized) {
  const lang = normalizeUrlLang(to.query.lang);
  if (!lang) return;
  useAppStore().changeLocale(lang);
}

async function initRoute(to: RouteLocationNormalized): Promise<RouteLocationRaw | null> {
  const routeStore = useRouteStore();
  const notFoundRoute: RouteKey = 'not-found';
  const isNotFoundRoute = to.name === notFoundRoute;

  if (!routeStore.isInitConstantRoute) {
    await routeStore.initConstantRoute();
    return {
      path: to.fullPath,
      replace: true,
      query: to.query,
      hash: to.hash
    };
  }

  if (!routeStore.isInitAuthRoute) {
    await routeStore.initAuthRoute();
    if (isNotFoundRoute) {
      return {
        path: to.redirectedFrom?.name === 'root' ? '/' : to.fullPath,
        replace: true,
        query: to.query,
        hash: to.hash
      };
    }
  }

  return null;
}

function handleRouteSwitch(to: RouteLocationNormalized, from: RouteLocationNormalized, next: NavigationGuardNext) {
  if (to.meta.href) {
    window.open(to.meta.href, '_blank');
    next({ path: from.fullPath, replace: true, query: from.query, hash: to.hash });
    return;
  }
  next();
}
