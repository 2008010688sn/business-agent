/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import { toPageResponse, unwrapData, unwrapDataOr } from './common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from './common';

/**
 * 数字员工发布版本（Digital Employee Release）服务封装。
 * 对应后端 DigitalEmployeeReleaseController（/digital-employee-releases）。
 * 生命周期：DRAFT → SEALED（封版冻结快照）→ PUBLISHED（仅已发布可部署）→ RETIRED。
 * 注意：DataAgent 发布管理（data_agent_release）已下线，本服务只覆盖数字员工发布版本。
 */

/** 发布状态：DRAFT/SEALED/PUBLISHED/RETIRED */
export type EmployeeReleaseStatus = 'DRAFT' | 'SEALED' | 'PUBLISHED' | 'RETIRED';

/** 发布版本记录，对应后端 DigitalEmployeeRelease entity（快照列 Seal 时冻结，只读展示） */
export interface DigitalEmployeeRelease {
  id?: string;
  tenantId?: string;
  employeeId?: string;
  releaseNo?: number;
  schemaVersion?: string;
  /** 完整运行规范快照 JSON 字符串（Seal 冻结），仅只读展示 */
  snapshot?: string;
  /** 快照 SHA-256（Seal 时计算） */
  specHash?: string;
  baseReleaseId?: string;
  sourceType?: string;
  sourceAgentId?: string;
  sourceReleaseId?: string;
  status?: EmployeeReleaseStatus;
  sealedAt?: string;
  sealedBy?: string;
  publishedAt?: string;
  publishedBy?: string;
  authorizationPolicyVersionId?: string;
  authorizationPolicyHash?: string;
  createBy?: string;
  createName?: string;
  createTime?: string;
  lastModifyTime?: string;
}

export interface EmployeeReleasePageQuery {
  current?: number;
  size?: number;
  employeeId?: string | '';
  status?: EmployeeReleaseStatus | '';
}

export interface EmployeeReleaseCreateRequest {
  /** 基于哪个历史 Release 创建（可空；空则从员工当前草稿装配） */
  baseReleaseId?: string;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

/** 网关 ai 域前缀 + 后端 Controller @RequestMapping("/digital-employee-releases") */
const API_BASE_URL = '/ai/digital-employee-releases';

const releaseUrl = (id: string, suffix = ''): string => {
  return `${API_BASE_URL}/${encodeURIComponent(String(id))}${suffix}`;
};

class DigitalEmployeeReleaseService {
  /** 分页查询（POST /page；按员工与状态过滤） */
  async fetchPage(query: EmployeeReleasePageQuery): Promise<PageResponse<DigitalEmployeeRelease[]>> {
    const response = await Http.post(
      `${API_BASE_URL}/page`,
      normalizeObject({
        ...query,
        current: query.current || 1,
        size: query.size || 10
      })
    );
    return toPageResponse(response as ServiceResponse<MybatisPage<DigitalEmployeeRelease>>);
  }

  /** 创建发布草稿（POST /{employeeId}/create；从员工当前草稿或指定 base Release） */
  async createDraft(employeeId: string, payload?: EmployeeReleaseCreateRequest): Promise<void> {
    const response = await Http.post(
      `${API_BASE_URL}/${encodeURIComponent(String(employeeId))}/create`,
      normalizeObject(payload ?? {})
    );
    unwrapData(response as ServiceResponse<void>);
  }

  /** 发布详情（GET /{releaseId}/detail；含冻结快照与 spec_hash） */
  async fetchDetail(releaseId: string): Promise<DigitalEmployeeRelease> {
    const response = await Http.get(releaseUrl(releaseId, '/detail'));
    return unwrapDataOr(response as ServiceResponse<DigitalEmployeeRelease>, {});
  }

  /** 封版（POST /{releaseId}/seal；DRAFT → SEALED，以 Seal 时刻草稿+启用能力重新装配快照） */
  async seal(releaseId: string): Promise<void> {
    const response = await Http.post(releaseUrl(releaseId, '/seal'));
    unwrapData(response as ServiceResponse<void>);
  }

  /** 发布（POST /{releaseId}/publish；SEALED → PUBLISHED，仅已发布版本可部署） */
  async publish(releaseId: string): Promise<void> {
    const response = await Http.post(releaseUrl(releaseId, '/publish'));
    unwrapData(response as ServiceResponse<void>);
  }

  /** 退役（POST /{releaseId}/retire；PUBLISHED → RETIRED；仍被任务/部署引用时后端拒绝） */
  async retire(releaseId: string): Promise<void> {
    const response = await Http.post(releaseUrl(releaseId, '/retire'));
    unwrapData(response as ServiceResponse<void>);
  }
}

const normalizeObject = <T extends object>(value: T): Partial<T> => {
  return Object.entries(value as Record<string, unknown>).reduce<Partial<T>>((result, [key, item]) => {
    if (item !== '' && item !== undefined && item !== null) {
      (result as Record<string, unknown>)[key] = item;
    }
    return result;
  }, {});
};

export default new DigitalEmployeeReleaseService();
