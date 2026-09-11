/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sn68.agent.dataagent.employee.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeCreateReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeModifyReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeOptionResp;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeePageQueryReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeCapabilityBindReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeCapability;
import java.util.List;

/**
 * 数字员工档案服务（CRUD / 启用停用 CAS / 能力绑定）。
 */
public interface DigitalEmployeeService {

	/**
	 * 分页查询当前租户员工。
	 */
	IPage<DigitalEmployeeResp> queryPage(DigitalEmployeePageQueryReq request);

	/**
	 * 创建员工（DRAFT；rollout 未开启时不调 IAM，principal_status 留 PENDING）。
	 * @return 员工档案（id 序列化为字符串）
	 */
	DigitalEmployeeResp create(DigitalEmployeeCreateReq request);

	/**
	 * 修改草稿、停用或启用态员工（null 字段不更新；draft_revision +1）。已封存不可改。
	 */
	void modify(Long id, DigitalEmployeeModifyReq request);

	/**
	 * 封存员工（DISABLED/ARCHIVED 前置校验 + CAS）。
	 */
	void archive(Long id, Long expectStateVersion);

	/**
	 * 删除员工（仅 DRAFT 且无 Release/Deployment 引用；软删）。
	 */
	void delete(Long id);

	/**
	 * 查询员工详情。
	 */
	DigitalEmployeeResp getDetail(Long id);

	/**
	 * 员工选择器摘要（权限中心 / 筛选下拉）。
	 */
	List<DigitalEmployeeOptionResp> listOptions(String keyword, String status);

	/**
	 * 按 Principal ID 反查员工摘要（当前租户）。
	 */
	DigitalEmployeeResp findByPrincipalId(String principalId);

	/**
	 * 启用员工（rollout 开关 + Principal READY 前置校验 + state_version CAS）。
	 */
	void enable(Long id, Long expectStateVersion);

	/**
	 * 停用员工（state_version CAS）。
	 */
	void disable(Long id, Long expectStateVersion);

	/**
	 * 手动触发 Principal 开通（rollout=true 时管理员重试入口）。
	 * READY 成功返回；SKIPPED / FAILED 抛业务异常。
	 */
	void provisionPrincipal(Long id);

	/**
	 * 发布当前草稿配置：Seal + Publish + 激活 PRODUCTION。未发布不能启用。
	 */
	void publishCurrentConfig(Long id);

	/**
	 * 绑定能力（幂等：同 skillVersionId 重复绑定直接成功/恢复启用）。
	 * @return 绑定记录 ID
	 */
	Long bindCapability(Long employeeId, EmployeeCapabilityBindReq request);

	/**
	 * 解绑能力。
	 */
	void unbindCapability(Long employeeId, Long capabilityId);

	/**
	 * 启用或停用员工能力绑定（仅修改草稿态绑定，已发布快照不受影响）。
	 */
	void updateCapabilityEnabled(Long employeeId, Long capabilityId, Boolean enabled);

	/**
	 * 查询员工能力绑定清单（含停用）。
	 */
	List<DigitalEmployeeCapability> listCapabilities(Long employeeId);

}
