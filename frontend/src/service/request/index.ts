import type { AxiosRequestConfig } from 'axios';
import axios from 'axios';
import { BACKEND_ERROR_CODE, createFlatRequest, createRequest } from '@sa/axios';
import { ElMessage } from 'element-plus';
import { getToken } from '@/store/modules/auth/shared';
import { $t } from '@/locales';
import { getServiceBaseURL } from '@/utils/service';
import { getAuthorization, showErrorMsg } from './shared';
import type { RequestInstanceState } from './type';

const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);

export { baseURL };

const pending = new Map<string, (message?: string) => void>();

const addPending = (config: AxiosRequestConfig) => {
  const url = [config.method, config.url, JSON.stringify(config.params), JSON.stringify(config.data)].join('&');
  config.cancelToken ||= new axios.CancelToken(cancel => {
    if (!pending.has(url)) {
      pending.set(url, cancel);
    }
  });
};

export const removePending = (config: AxiosRequestConfig) => {
  const url = [config.method, config.url, JSON.stringify(config.params), JSON.stringify(config.data)].join('&');
  if (pending.has(url)) {
    const cancel = pending.get(url);
    cancel?.(url);
    pending.delete(url);
  }
};

export const flatRequest = createFlatRequest<App.Service.Response, RequestInstanceState>(
  { baseURL },
  {
    async onRequest(config) {
      const Authorization = getAuthorization();
      Object.assign(config.headers, { Authorization });
      return config;
    },
    isBackendSuccess(response) {
      return String(response.data.code) === import.meta.env.VITE_SERVICE_SUCCESS_CODE || String(response.data.code) === '200';
    },
    async onBackendFail() {
      return null;
    },
    transformBackendResponse(response) {
      return response.data.data;
    },
    onError(error) {
      let message = error.message;
      if (error.code === BACKEND_ERROR_CODE) {
        message = error.response?.data?.message || message;
      }
      if (error.code === 'ECONNABORTED') {
        message = $t('request.timeOut');
      }
      if (error.code !== 'ERR_CANCELED') {
        showErrorMsg(flatRequest.state, message);
      }
    }
  }
);

const handleRequest = createRequest<App.Service.Response>(
  {
    baseURL,
    timeout: 30 * 1000
  },
  {
    async onRequest(config) {
      if (config.repeatable !== true) {
        removePending(config);
        addPending(config);
      }
      const { headers, responseType } = config;
      if (responseType === 'arraybuffer') {
        config.timeout = 180 * 1000;
      }

      const lang = localStorage.getItem(`${import.meta.env.VITE_STORAGE_PREFIX || ''}lang`) || 'zh-CN';
      const Authorization = getToken() || null;
      Object.assign(headers, {
        'V4-Authorization': Authorization,
        'Cache-Control': 'no-cache',
        Pragma: 'no-cache',
        'Accept-Language': lang === '"zh-CN"' || lang === 'zh-CN' ? 'zh-CN' : lang.includes('ja') ? 'ja-JP' : 'en-US'
      });
      const timestamp = Date.now();
      config.params = {
        ...config.params,
        _t: timestamp
      };
      return config;
    },
    isBackendSuccess(response) {
      if (Object.hasOwn(response.data, 'code')) {
        const result =
          String(response.data.code) === import.meta.env.VITE_SERVICE_SUCCESS_CODE || Number(response.data.code) === 0;
        if (result && response.config?.showSuccess) {
          ElMessage.success(`${response.config?.successMsg || '操作成功'}`);
        }
        return result;
      }
      return Boolean(response.data);
    },
    async onBackendFail() {
      return null;
    },
    transformBackendResponse(response) {
      return response.data;
    },
    onError(error) {
      let message = error.message;
      if (error.code === BACKEND_ERROR_CODE) {
        message = error.response?.data?.message || message;
      }
      if (error.code === 'ECONNABORTED') {
        message = $t('request.timeOut');
      }
      if (error.code !== 'ERR_CANCELED') {
        window.$message?.error(message);
      }
    }
  }
);

const Http = {
  post(url: string, data = {}, config = {}) {
    return handleRequest({
      url,
      method: 'post',
      data,
      ...config
    });
  },
  get(url: string, params = {}, config = {}) {
    return handleRequest({
      url,
      method: 'get',
      params,
      ...config
    });
  },
  delete(url: string, data = {}, config = {}) {
    return handleRequest({
      url,
      method: 'delete',
      data,
      ...config
    });
  },
  put(url: string, data = {}, config = {}) {
    return handleRequest({
      url,
      method: 'put',
      data,
      ...config
    });
  },
  all(promises: Promise<unknown>[]) {
    return Promise.all(promises);
  },
  native() {
    return axios;
  }
};

export { Http };
