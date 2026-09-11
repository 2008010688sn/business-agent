import { computed, reactive, ref } from 'vue';
import { defineStore } from 'pinia';
import { SetupStoreId } from '@/enum';
import { localStg } from '@/utils/storage';
import { clearAuthStorage, getToken } from './shared';

const DEMO_USER: Api.Auth.UserInfo = {
  userId: 'demo',
  userName: 'Demo',
  username: 'demo',
  nickName: 'Demo',
  mobile: '',
  email: '',
  tenantId: 'local',
  tenantName: 'Local',
  tenantCode: 'local',
  avatar: '',
  orgId: '',
  type: '0',
  buttons: [],
  funcPermissions: [],
  roles: [import.meta.env.VITE_STATIC_SUPER_ROLE]
};

export const useAuthStore = defineStore(SetupStoreId.Auth, () => {
  const token = ref(getToken() || 'demo');

  const userInfo: Api.Auth.UserInfo = reactive({ ...DEMO_USER, ...(localStg.get('userInfo') as Api.Auth.UserInfo) });

  const isStaticSuper = computed(() => true);

  const isLogin = computed(() => true);

  const isPlatformAdmin = computed(() => true);

  async function resetStore() {
    clearAuthStorage();
    Object.assign(userInfo, DEMO_USER);
    token.value = 'demo';
  }

  async function initUserInfo() {
    Object.assign(userInfo, DEMO_USER);
    localStg.set('userInfo', userInfo as never);
    localStg.set('token', token.value);
    window.localStorage.setItem('userPermissions', JSON.stringify(['*']));
  }

  return {
    token,
    userInfo,
    isStaticSuper,
    isLogin,
    isPlatformAdmin,
    resetStore,
    initUserInfo,
    requestLogout: resetStore
  };
});
