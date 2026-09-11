import type { App } from 'vue';
import { haveAuth } from '@/mixins/userAuth.js';
/**
 * 用户权限指令
 *
 * @directive 单个权限验证（v-auth="xxx"）
 * @directive 多个权限验证，满足一个则显示（v-auths="[xxx,xxx]"）
 * @directive 多个权限验证，全部满足则显示（v-auth-all="[xxx,xxx]"）
 */
export function authDirective(app: App) {
  // 单个权限验证（v-auth="xxx"）
  app.directive('auth', {
    mounted(el, binding) {
      if (!haveAuth(binding.value)) el.parentNode.removeChild(el);
    }
  });
  // 无权限按钮置灰
  app.directive('auth-dis', {
    mounted(el, binding) {
      if (!haveAuth(binding.value)) {
        el.style.color = '#303133';
        el.disabled = true;
      }
    }
  });
  // 多个权限验证，满足一个则显示（v-auths="[xxx,xxx]"）
  app.directive('auths', {
    mounted(el, binding) {
      let flag = false;
      binding.value.forEach((v: string) => {
        if (haveAuth(v)) flag = true;
      });
      if (!flag) el.parentNode.removeChild(el);
    }
  });
  // 多个权限验证，全部满足则显示（v-auth-all="[xxx,xxx]"）
  app.directive('auth-all', {
    mounted(el, binding) {
      // const authStore = useAuthStore();
      // const flag = judementSameArr(binding.value, authStore.userInfo.buttons);
      // if (!flag) el.parentNode.removeChild(el);
    }
  });
}
