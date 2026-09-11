/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import { unwrapData, unwrapList } from './common';
import type { ApiResponse, XxCloudResult } from './common';

export type RuntimeInvocationId = string;

export type RuntimeInvocationState = 'OUTCOME_UNKNOWN' | 'RECONCILING' | 'SUCCESS' | 'FAILED' | string;

export interface RuntimeInvocation {
  id?: RuntimeInvocationId;
  runId?: RuntimeInvocationId;
  idempotencyKey?: string;
  capabilityHandle?: string;
  invocationType?: string;
  state?: RuntimeInvocationState;
  errorCode?: string;
  errorMessage?: string;
  requestDigest?: string;
  sideEffectReceipt?: string;
  sentAt?: string;
  createTime?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const API_BASE_URL = '/ai/runtime-invocations';

class RuntimeInvocationService {
  async listPendingReconcile(limit = 200): Promise<RuntimeInvocation[]> {
    const response = await Http.get(`${API_BASE_URL}/pending-reconcile`, { limit });
    return unwrapList<RuntimeInvocation>(response);
  }

  async reconcile(id: RuntimeInvocationId, success: boolean, comment?: string): Promise<void> {
    const response = await Http.post(`${API_BASE_URL}/${encodeURIComponent(String(id))}/reconcile`, {
      success,
      comment
    });
    unwrapData(response as ServiceResponse<void>);
  }
}

export default new RuntimeInvocationService();
