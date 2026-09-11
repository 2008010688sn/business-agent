import { computed, nextTick, ref, shallowRef } from 'vue';
import type { RouteRecordRaw } from 'vue-router';
import { defineStore } from 'pinia';
import { useBoolean } from '@sa/hooks';
import type { CustomRoute, ElegantConstRoute, LastLevelRouteKey, RouteKey, RouteMap } from '@elegant-router/types';
import { router } from '@/router';
import { SetupStoreId } from '@/enum';
import { createStaticRoutes, getAuthVueRoutes } from '@/router/routes';
import { ROOT_ROUTE } from '@/router/routes/builtin';
import { getRouteName, getRoutePath } from '@/router/elegant/transform';
import { useAuthStore } from '../auth';
import { useTabStore } from '../tab';
import {
  getBreadcrumbsByRoute,
  getCacheRouteNames,
  getGlobalMenusByAuthRoutes,
  getSelectedMenuKeyPathByKey,
  sortRoutesByOrder,
  transformMenuToSearchMenus,
  updateLocaleOfGlobalMenus
} from './shared';

export const useRouteStore = defineStore(SetupStoreId.Route, () => {
  const authStore = useAuthStore();
  const tabStore = useTabStore();
  const { bool: isInitConstantRoute, setBool: setIsInitConstantRoute } = useBoolean();
  const { bool: isInitAuthRoute, setBool: setIsInitAuthRoute } = useBoolean();

  const authRouteMode = ref(import.meta.env.VITE_AUTH_ROUTE_MODE);
  const routeHome = ref(import.meta.env.VITE_ROUTE_HOME);

  function setRouteHome(routeKey: LastLevelRouteKey) {
    routeHome.value = routeKey;
  }

  const constantRoutes = shallowRef<ElegantConstRoute[]>([]);

  function addConstantRoutes(routes: ElegantConstRoute[]) {
    const constantRoutesMap = new Map<string, ElegantConstRoute>([]);
    routes.forEach(route => {
      constantRoutesMap.set(route.name, route);
    });
    constantRoutes.value = Array.from(constantRoutesMap.values());
  }

  const authRoutes = shallowRef<ElegantConstRoute[]>([]);

  function addAuthRoutes(routes: ElegantConstRoute[]) {
    const authRoutesMap = new Map<string, ElegantConstRoute>([]);
    routes.forEach(route => {
      authRoutesMap.set(route.name, route);
    });
    authRoutes.value = Array.from(authRoutesMap.values());
  }

  const removeRouteFns: (() => void)[] = [];
  const menus = ref<App.Global.Menu[]>([]);
  const searchMenus = computed(() => transformMenuToSearchMenus(menus.value));

  function getGlobalMenus(routes: ElegantConstRoute[]) {
    menus.value = getGlobalMenusByAuthRoutes(routes);
  }

  function updateGlobalMenusByLocale() {
    menus.value = updateLocaleOfGlobalMenus(menus.value);
  }

  const cacheRoutes = ref<RouteKey[]>([]);
  const excludeCacheRoutes = ref<RouteKey[]>([]);

  function getCacheRoutes(routes: RouteRecordRaw[]) {
    cacheRoutes.value = getCacheRouteNames(routes);
  }

  async function resetRouteCache(routeKey?: RouteKey) {
    const routeName = routeKey || (router.currentRoute.value.name as RouteKey);
    excludeCacheRoutes.value.push(routeName);
    await nextTick();
    excludeCacheRoutes.value = [];
  }

  const breadcrumbs = computed(() => getBreadcrumbsByRoute(router.currentRoute.value, menus.value));

  async function resetStore() {
    const routeStore = useRouteStore();
    routeStore.$reset();
    resetVueRoutes();
    await initConstantRoute();
  }

  function resetVueRoutes() {
    removeRouteFns.forEach(fn => fn());
    removeRouteFns.length = 0;
  }

  async function initConstantRoute() {
    if (isInitConstantRoute.value) return;
    const staticRoute = createStaticRoutes();
    addConstantRoutes(staticRoute.constantRoutes);
    handleConstantAndAuthRoutes();
    setIsInitConstantRoute(true);
    tabStore.initHomeTab();
  }

  async function initAuthRoute() {
    try {
      if (!authStore.userInfo.userId) {
        await authStore.initUserInfo();
      }
    } catch {
      await authStore.initUserInfo();
    }
    initStaticAuthRoute();
    tabStore.initHomeTab();
  }

  function initStaticAuthRoute() {
    const { authRoutes: staticAuthRoutes } = createStaticRoutes();
    addAuthRoutes(staticAuthRoutes);
    handleConstantAndAuthRoutes();
    setIsInitAuthRoute(true);
  }

  function handleConstantAndAuthRoutes() {
    const allRoutes = [...constantRoutes.value, ...authRoutes.value];
    const sortRoutes = sortRoutesByOrder(allRoutes);
    const vueRoutes = getAuthVueRoutes(sortRoutes);
    resetVueRoutes();
    addRoutesToVueRouter(vueRoutes);
    getGlobalMenus(sortRoutes);
    getCacheRoutes(vueRoutes);
  }

  function addRoutesToVueRouter(routes: RouteRecordRaw[]) {
    routes.forEach(route => {
      const removeFn = router.addRoute(route);
      removeRouteFns.push(removeFn);
    });
  }

  function handleUpdateRootRouteRedirect(redirectKey: LastLevelRouteKey) {
    const redirect = getRoutePath(redirectKey);
    if (redirect) {
      const rootRoute: CustomRoute = { ...ROOT_ROUTE, redirect };
      router.removeRoute(rootRoute.name);
      const [rootVueRoute] = getAuthVueRoutes([rootRoute]);
      router.addRoute(rootVueRoute);
    }
  }

  async function getIsAuthRouteExist(routePath: RouteMap[RouteKey]) {
    const routeName = getRouteName(routePath);
    return Boolean(routeName);
  }

  function getSelectedMenuKeyPath(selectedKey: string) {
    return getSelectedMenuKeyPathByKey(selectedKey, menus.value);
  }

  async function onRouteSwitchWhenLoggedIn() {
    await authStore.initUserInfo();
  }

  async function onRouteSwitchWhenNotLoggedIn() {
    await authStore.initUserInfo();
  }

  return {
    resetStore,
    routeHome,
    menus,
    searchMenus,
    updateGlobalMenusByLocale,
    cacheRoutes,
    excludeCacheRoutes,
    resetRouteCache,
    breadcrumbs,
    initConstantRoute,
    isInitConstantRoute,
    initAuthRoute,
    isInitAuthRoute,
    setIsInitAuthRoute,
    getIsAuthRouteExist,
    getSelectedMenuKeyPath,
    onRouteSwitchWhenLoggedIn,
    onRouteSwitchWhenNotLoggedIn,
    authRouteMode,
    setRouteHome,
    handleUpdateRootRouteRedirect
  };
});
