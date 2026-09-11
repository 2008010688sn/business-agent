import { localStg } from '@/utils/storage';
import type { RequestInstanceState } from './type';

export function getAuthorization() {
  const token = localStg.get('token');
  return token ? `Bearer ${token}` : null;
}

export function showErrorMsg(state: RequestInstanceState, message: string) {
  if (!state.errMsgStack?.length) {
    state.errMsgStack = [];
  }

  if (state.errMsgStack.includes(message)) {
    return;
  }

  state.errMsgStack.push(message);
  window.$message?.error({
    message,
    onClose: () => {
      state.errMsgStack = state.errMsgStack.filter(msg => msg !== message);
      setTimeout(() => {
        state.errMsgStack = [];
      }, 5000);
    }
  });
}
