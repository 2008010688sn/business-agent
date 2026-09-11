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
package com.sn68.agent.dataagent.employee.service.impl;

import com.sn68.agent.dataagent.employee.config.DigitalEmployeeProperties;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.PrincipalProvisioningService;
import com.sn68.agent.dataagent.iam.LocalPrincipalStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 数字员工 Service Principal 开通实现（standalone：本地存储，不开通 IAM）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrincipalProvisioningServiceImpl implements PrincipalProvisioningService {

	private final LocalPrincipalStore principalStore;

	private final DigitalEmployeeMapper employeeMapper;

	private final DigitalEmployeeProperties properties;

	@Override
	public ProvisionOutcome provision(DigitalEmployee employee) {
		if (employee == null || employee.getId() == null) {
			throw new IllegalArgumentException("employee 及其 id 不能为空");
		}
		if (!properties.getRollout().isEnabled()) {
			log.info("数字员工灰度未开启，跳过 Principal 开通（员工保持 PENDING，不可启用）. employeeId={}",
					employee.getId());
			return new ProvisionOutcome(ProvisionOutcome.STATUS_SKIPPED, null,
					"数字员工灰度未开启（spring.ai.agent.digital-employee.rollout.enabled=false），未调用 IAM 开通接口");
		}
		if (PrincipalProvisionStatusDict.READY.getValue().equals(employee.getPrincipalStatus())
				&& StringUtils.hasText(employee.getIamPrincipalId())) {
			return new ProvisionOutcome(ProvisionOutcome.STATUS_READY, employee.getIamPrincipalId(), "已开通");
		}
		try {
			LocalPrincipalStore.Record record = principalStore.provision(employee.getTenantId(),
					String.valueOf(employee.getId()), employee.getEmployeeName());
			markReady(employee, record.principalId(), record.authRevision());
			log.info("数字员工 Principal 本地开通成功. employeeId={}, principalId={}, authRevision={}",
					employee.getId(), record.principalId(), record.authRevision());
			return new ProvisionOutcome(ProvisionOutcome.STATUS_READY, record.principalId(), "开通成功");
		}
		catch (Exception ex) {
			String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			log.error("数字员工 Principal 开通失败（回写 FAILED，不回退创建人身份）. employeeId={}, tenantId={}, reason={}",
					employee.getId(), employee.getTenantId(), reason, ex);
			markFailed(employee, reason);
			return new ProvisionOutcome(ProvisionOutcome.STATUS_FAILED, null, "本地开通失败: " + reason);
		}
	}

	private void markReady(DigitalEmployee employee, String principalId, Long authRevision) {
		String currentStatus = employee.getPrincipalStatus() == null
				? PrincipalProvisionStatusDict.PENDING.getValue()
				: employee.getPrincipalStatus();
		int updated = employeeMapper.casUpdateProvision(employee.getId(), employee.getTenantId(), currentStatus,
				principalId, PrincipalProvisionStatusDict.READY.getValue(), authRevision);
		if (updated == 0) {
			log.warn("数字员工开通结果回写命中 0 行（并发开通或状态已更新），以库内状态为准. employeeId={}", employee.getId());
		}
	}

	private void markFailed(DigitalEmployee employee, String reason) {
		String currentStatus = employee.getPrincipalStatus() == null
				? PrincipalProvisionStatusDict.PENDING.getValue()
				: employee.getPrincipalStatus();
		int updated = employeeMapper.casUpdateProvision(employee.getId(), employee.getTenantId(), currentStatus,
				employee.getIamPrincipalId(), PrincipalProvisionStatusDict.FAILED.getValue(),
				employee.getPrincipalRevision());
		if (updated == 0) {
			log.warn("数字员工开通失败状态回写命中 0 行（状态已变更，保留库内状态）. employeeId={}, reason={}", employee.getId(),
					reason);
		}
	}

}
