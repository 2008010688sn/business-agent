/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.context.DataAgentAsyncContextBridge;
import com.sn68.agent.dataagent.context.DataAgentOutboundContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthContextException;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthTokenContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.enums.EmployeeStatusDict;
import com.sn68.agent.dataagent.employee.enums.PrincipalProvisionStatusDict;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.entity.DataAgentFlowInstance;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 管理端审批续跑时还原填单人身份。standalone 无 IAM 换票，用户路径以当前演示上下文继续。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FlowManagerApprovalIdentityRestorer {

	private final DataAgentAsyncContextBridge asyncContextBridge;

	private final EmployeeExecutionContextClient employeeExecutionContextClient;

	private final DigitalEmployeeMapper digitalEmployeeMapper;

	public <T> T runAsCaller(DataAgentFlowInstance instance, Map<String, Object> snapshot, Supplier<T> action) {
		Map<String, Object> safe = snapshot == null ? Map.of() : snapshot;
		String ownerType = text(safe.get("ownerType"));
		if (AuthorizationOwnerType.DIGITAL_EMPLOYEE.name().equalsIgnoreCase(ownerType)) {
			return runAsEmployee(instance, safe, action);
		}
		return runAsDelegatedUser(instance, safe, action);
	}

	private <T> T runAsEmployee(DataAgentFlowInstance instance, Map<String, Object> snapshot, Supplier<T> action) {
		Long ownerId = longValue(snapshot.get("ownerId"));
		String tenantId = firstText(text(snapshot.get("tenantId")), instance.getTenantId());
		DigitalEmployee employee = ownerId == null ? null : digitalEmployeeMapper.findByIdAndTenantId(ownerId, tenantId);
		if (employee == null || !EmployeeStatusDict.ENABLED.getValue().equals(employee.getStatus())) {
			throw CheckedException.fail("审批续跑换票失败：数字员工不存在或未启用");
		}
		if (!StringUtils.hasText(employee.getIamPrincipalId())
				|| !PrincipalProvisionStatusDict.READY.getValue().equals(employee.getPrincipalStatus())) {
			throw CheckedException.fail("审批续跑换票失败：数字员工执行主体未就绪");
		}
		try {
			EmployeeAuthTokenContext token = employeeExecutionContextClient.issueContext(tenantId,
					employee.getIamPrincipalId(), employee.getEmployeeName());
			DataAgentOutboundContext.Snapshot headers = DataAgentOutboundContext.withPrincipalToken(
					DataAgentOutboundContext.Snapshot.empty(), token.tokenValue(), tenantId);
			DataAgentAsyncContextBridge.Snapshot delegated = asyncContextBridge.snapshotForDelegatedToken(
					token.tokenValue(), headers);
			return asyncContextBridge.supplyWith(delegated, action);
		}
		catch (EmployeeAuthContextException ex) {
			throw CheckedException.fail("审批续跑换票失败：数字员工执行身份暂不可用");
		}
	}

	private <T> T runAsDelegatedUser(DataAgentFlowInstance instance, Map<String, Object> snapshot, Supplier<T> action) {
		String userId = firstText(text(snapshot.get("userId")), instance.getUserId());
		log.info("Standalone 无 IAM 委托换票，审批续跑沿用当前演示用户. flowInstanceId={}, userId={}",
				instance == null ? null : instance.getId(), userId);
		return action.get();
	}

	private static String text(Object value) {
		return value == null ? "" : String.valueOf(value).trim();
	}

	private static String firstText(String left, String right) {
		return StringUtils.hasText(left) ? left : right;
	}

	private static Long longValue(Object value) {
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value == null || !StringUtils.hasText(String.valueOf(value))) {
			return null;
		}
		try {
			return Long.valueOf(String.valueOf(value).trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
