import { useTitle } from '@vueuse/core';
import type { Router } from 'vue-router';
import { $t } from '@/locales';

export function createDocumentTitleGuard(router: Router) {
  router.afterEach(to => {
    // vue-router 会合并所有 matched 记录的 meta，叶子路由自身没配 i18nKey 时会继承父级的
    // i18nKey（如 route.ai-agent），标题被回落成父级菜单名。与页签取值链路（tab/shared.ts
    // getTabByRoute）保持一致：叶子记录自身给了标题信息就只用它，否则才退回合并后的 meta。
    const ownMeta = to.matched.find(record => record.name === to.name)?.meta;
    const { i18nKey, title } = ownMeta && (ownMeta.i18nKey || ownMeta.title) ? ownMeta : to.meta;
    const tabTitle = typeof to.query?.tabTitle === 'string' ? to.query.tabTitle : '';
    const documentTitle = tabTitle || (i18nKey ? $t(i18nKey) : title);

    useTitle(documentTitle);
  });
}
