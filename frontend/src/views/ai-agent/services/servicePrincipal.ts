/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */

import { Http } from '@/service/request';
import { unwrapData, unwrapDataOr } from './common';
import type { ApiResponse, XxCloudResult } from './common';

/**
 * IAM Service Principal 管理服务封装（PR-8 切片 4：执行权限抽屉）。
 *
 * 对应后端 ServicePrincipalController 管理端点（网关用户态真人 Token）：
 * - PUT /service-principals/{id}/roles：全量替换角色，空数组=清空=MODEL_ONLY；
 *   仅允许 service_assignable=true 且同租户的启用角色（后端硬校验）。
 * - PUT /service-principals/{id}/status：ENABLED/DISABLED；停用将 kickout 并递增 auth_revision。
 *
 * 硬红线（与后端一致）：
 * - 禁止前端直连 /users/create 等 IAM 真人接口建技术账号；
 * - Principal 不写 t_user；
 * - 不得调用 /internal/** 端点（SaSame-Token 服务间专用，前端调用必 403）。
 *
 * 角色列表走 IAM RoleController（POST /roles/list）。
 * RolePageResp 已暴露 serviceAssignable/superRole/readonly；前端按 isRoleAssignable 过滤下拉。
 * 数字员工执行权限的写操作请走 AI Facade（/ai/digital-employees/{id}/principal/*），不要直连 IAM 管理写端点。
 */

/** 按钮权限码，与后端 @SaCheckPermission 一致（失败关闭） */
export const SERVICE_PRINCIPAL_ROLE_PERMISSION = 'service-principal:role:assign';
export const SERVICE_PRINCIPAL_STATUS_PERMISSION = 'service-principal:status:update';

/** Principal 状态（与 IAM ServicePrincipal entity 一致） */
export type PrincipalStatus = 'ENABLED' | 'DISABLED';

export interface ServicePrincipalRolesReplaceRequest {
  /** 角色 ID 列表；空数组=清空=MODEL_ONLY */
  roleIds: string[];
}

export interface ServicePrincipalStatusRequest {
  status: PrincipalStatus;
}

/** 角色列表项，对应后端 RolePageResp */
export interface RoleOption {
  id?: string;
  name?: string;
  code?: string;
  description?: string;
  status?: string;
  statusName?: string;
  scopeType?: string;
  userCount?: number;
  /** 后端 RolePageResp：是否可分配给 Service Principal */
  serviceAssignable?: boolean;
  /** 超级角色（后端 DB 列 `super`） */
  superRole?: boolean;
  /** 内置只读角色 */
  readonly?: boolean;
}

type ServiceResponse<T> = ApiResponse<T> | XxCloudResult<T> | T;

const PRINCIPAL_BASE_URL = '/ai/service-principals';
const ROLE_BASE_URL = '/ai/roles';

/** 判断角色是否可分配给 Principal：启用 + service_assignable + 非超级/只读（字段缺失时按启用兜底） */
export const isRoleAssignable = (role: RoleOption): boolean => {
  if (role.status !== undefined && role.status !== 'enabled') {
    return false;
  }
  if (role.serviceAssignable === false) {
    return false;
  }
  if (role.superRole === true || role.readonly === true) {
    return false;
  }
  return Boolean(role.id);
};

class ServicePrincipalService {
  /** 全量替换 Principal 角色（PUT /service-principals/{id}/roles）；成功后原子递增 auth_revision 并 kickout */
  async replaceRoles(principalId: string, payload: ServicePrincipalRolesReplaceRequest): Promise<void> {
    const response = await Http.put(`${PRINCIPAL_BASE_URL}/${encodeURIComponent(String(principalId))}/roles`, {
      roleIds: payload.roleIds
    });
    unwrapData(response as ServiceResponse<void>);
  }

  /** 变更 Principal 状态（PUT /service-principals/{id}/status）；停用将 kickout 并递增 auth_revision */
  async updateStatus(principalId: string, payload: ServicePrincipalStatusRequest): Promise<void> {
    const response = await Http.put(`${PRINCIPAL_BASE_URL}/${encodeURIComponent(String(principalId))}/status`, {
      status: payload.status
    });
    unwrapData(response as ServiceResponse<void>);
  }

  /**
   * 查询角色列表（POST /roles/list，RoleController；不分页全量）。
   * 前端过滤可分配角色的口径见 isRoleAssignable。
   */
  async listRoles(keyword?: string): Promise<RoleOption[]> {
    const response = await Http.post(`${ROLE_BASE_URL}/list`, keyword ? { keyword } : {});
    return unwrapDataOr(response as ServiceResponse<RoleOption[]>, []);
  }
}

export default new ServicePrincipalService();
