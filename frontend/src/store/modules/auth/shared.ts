import { localStg } from '@/utils/storage';

export function getToken() {
  return localStg.get('token') || '';
}

export function setToken(token: string) {
  return localStg.set('token', token);
}

export function clearAuthStorage() {
  localStg.remove('token');
  localStg.remove('userInfo');
}

export function getLoginInfo() {
  return (localStg.get('loginInfo') as Record<string, unknown>) || {};
}

export function setLoginInfo(info: unknown) {
  return localStg.set('loginInfo', info as never);
}

export function clearLoginInfo() {
  localStg.remove('loginInfo');
}
