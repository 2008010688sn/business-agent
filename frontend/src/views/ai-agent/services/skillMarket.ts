/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { Http } from '@/service/request';
import { toPageResponse, unwrapData } from '@/views/ai-agent/services/common';
import type { ApiResponse, MybatisPage, PageResponse, XxCloudResult } from '@/views/ai-agent/services/common';

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

/**
 * 市场条目审核状态机：DRAFT → REVIEWING → APPROVED/REJECTED，APPROVED → REVOKED（终态）；
 * REJECTED 可重新提交，DRAFT/REJECTED 可编辑、可删除。
 */
export type MarketListingStatus = 'DRAFT' | 'REVIEWING' | 'APPROVED' | 'REJECTED' | 'REVOKED';

export type MarketRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export type MarketReviewConclusion = 'APPROVED' | 'REJECTED';

/** 平台审核（通过/驳回/撤销）权限码，对应后端 SkillMarketConstant.PERMISSION_LISTING_REVIEW */
export const SKILL_MARKET_REVIEW_PERMISSION = 'ai-agent:skill-market:review';

/**
 * 市场条目维护（查询/创建/编辑/提交审核/删除）权限码，对应后端 SkillMarketConstant.PERMISSION_LISTING_MANAGE。
 * 控制器已按该码强制校验，前端按钮同码控制，避免出现「能点但点了 403」。
 */
export const SKILL_MARKET_MANAGE_PERMISSION = 'ai-agent:skill-market:manage';

export interface SkillMarketListing {
  id: string;
  listingName: string;
  description?: string;
  category?: string;
  publisherId?: string;
  publisherName?: string;
  publisherTenantId?: string;
  currentVersionId?: string;
  /** 当前市场版本所引用的 DataAgent SkillVersion ID。 */
  currentSkillVersionId?: string;
  currentVersionNo?: number;
  /** 输入输出 Schema 摘要（JSON 文本） */
  ioSchemaSummary?: string;
  permissionScope?: string;
  dataScope?: string;
  riskLevel?: MarketRiskLevel;
  /** 依赖资源（JSON 文本） */
  dependentResources?: string;
  /** 使用配额默认值（次/日） */
  defaultUsageQuota?: number;
  compatibleEngineVersion?: string;
  revoked?: boolean;
  reviewStatus: MarketListingStatus;
  createTime?: string;
  lastModifyTime?: string;
}

export interface SkillMarketListingVersion {
  id: string;
  listingId?: string;
  versionNo?: number;
  skillVersionId?: string;
  contentHash?: string;
  changeNote?: string;
  createTime?: string;
  createName?: string;
}

export interface SkillMarketReview {
  id: string;
  listingId?: string;
  listingVersionId?: string;
  reviewerId?: string;
  reviewerName?: string;
  conclusion?: MarketReviewConclusion;
  opinion?: string;
  createTime?: string;
}

export interface SkillMarketListingDetail {
  listing: SkillMarketListing;
  versions: SkillMarketListingVersion[];
  reviews: SkillMarketReview[];
}

export interface SkillMarketListingPageQuery {
  current?: number;
  size?: number;
  listingName?: string;
  category?: string;
  publisherName?: string;
  reviewStatus?: MarketListingStatus;
  riskLevel?: MarketRiskLevel;
  revoked?: boolean;
}

/** 创建/修改条目共用的表单字段（后端 create/modify 两个请求体字段一致） */
export interface SkillMarketListingPayload {
  listingName: string;
  description?: string;
  category?: string;
  ioSchemaSummary?: string;
  permissionScope?: string;
  dataScope?: string;
  riskLevel?: MarketRiskLevel;
  dependentResources?: string;
  defaultUsageQuota?: number;
  compatibleEngineVersion?: string;
}

export interface SkillMarketSubmitReviewPayload {
  /** 引用的已发布 Skill 版本ID（data_agent_skill_version.id） */
  skillVersionId: string;
  changeNote?: string;
}

export interface SkillMarketReviewPayload {
  conclusion: MarketReviewConclusion;
  opinion?: string;
}

const BASE_URL = '/ai/skill-market/listings';

const compactQuery = (query: SkillMarketListingPageQuery): Record<string, unknown> =>
  Object.entries(query).reduce<Record<string, unknown>>((result, [key, value]) => {
    if (value === undefined || value === null || value === '') {
      return result;
    }
    result[key] = value;
    return result;
  }, {});

class SkillMarketService {
  async page(query: SkillMarketListingPageQuery): Promise<PageResponse<SkillMarketListing[]>> {
    const response = await Http.post(`${BASE_URL}/page`, compactQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<SkillMarketListing>>);
  }

  /**
   * 查询当前租户可安装到数字员工的市场技能。
   * 后端会固定收敛为 APPROVED 且未撤销的条目，并按租户可见范围过滤。
   */
  async availableForEmployee(
    query: SkillMarketListingPageQuery = {}
  ): Promise<PageResponse<SkillMarketListing[]>> {
    const response = await Http.post(`${BASE_URL}/available-for-employee`, compactQuery(query));
    return toPageResponse(response as ServiceResponse<MybatisPage<SkillMarketListing>>);
  }

  async create(payload: SkillMarketListingPayload): Promise<void> {
    await Http.post(`${BASE_URL}/create`, payload);
  }

  async modify(id: string, payload: SkillMarketListingPayload): Promise<void> {
    await Http.put(`${BASE_URL}/${id}/modify`, payload);
  }

  async detail(id: string): Promise<SkillMarketListingDetail> {
    const response = await Http.get(`${BASE_URL}/${id}/detail`);
    return unwrapData(response as ServiceResponse<SkillMarketListingDetail>);
  }

  async submitReview(id: string, payload: SkillMarketSubmitReviewPayload): Promise<void> {
    await Http.post(`${BASE_URL}/${id}/submit-review`, payload);
  }

  async review(id: string, payload: SkillMarketReviewPayload): Promise<void> {
    await Http.post(`${BASE_URL}/${id}/review`, payload);
  }

  async revoke(id: string): Promise<void> {
    await Http.post(`${BASE_URL}/${id}/revoke`);
  }

  async remove(id: string): Promise<void> {
    await Http.delete(`${BASE_URL}/${id}`);
  }
}

/** 条目是否允许编辑/删除（仅草稿与已驳回） */
export const isListingModifiable = (status?: MarketListingStatus): boolean =>
  status === 'DRAFT' || status === 'REJECTED';

export const MARKET_LISTING_STATUS_OPTIONS: Array<{ label: string; value: MarketListingStatus }> = [
  { label: '草稿', value: 'DRAFT' },
  { label: '审核中', value: 'REVIEWING' },
  { label: '审核通过', value: 'APPROVED' },
  { label: '已驳回', value: 'REJECTED' },
  { label: '已撤销', value: 'REVOKED' }
];

export const MARKET_RISK_LEVEL_OPTIONS: Array<{ label: string; value: MarketRiskLevel }> = [
  { label: '低风险', value: 'LOW' },
  { label: '中风险', value: 'MEDIUM' },
  { label: '高风险', value: 'HIGH' }
];

export const marketListingStatusLabel = (status?: MarketListingStatus): string =>
  MARKET_LISTING_STATUS_OPTIONS.find(item => item.value === status)?.label || status || '-';

export const marketRiskLevelLabel = (level?: MarketRiskLevel): string =>
  MARKET_RISK_LEVEL_OPTIONS.find(item => item.value === level)?.label || level || '-';

export const skillMarketService = new SkillMarketService();

export default skillMarketService;
