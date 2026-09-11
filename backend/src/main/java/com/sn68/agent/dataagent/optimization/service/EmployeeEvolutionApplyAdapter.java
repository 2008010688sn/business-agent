/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.optimization.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeModifyReq;
import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeDeploymentActivateReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeReleaseCreateReq;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDeployment;
import com.sn68.agent.dataagent.employee.enums.DeploymentEnvironmentDict;
import com.sn68.agent.dataagent.employee.service.DigitalEmployeeService;
import com.sn68.agent.dataagent.employee.service.EmployeeDeploymentService;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数字员工进化 Apply：草稿 overlay → Seal/Publish 新 Release → 先激活 SANDBOX，
 * 二次确认后再激活 PRODUCTION。禁止跳过 SANDBOX 直发生产。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeEvolutionApplyAdapter {

	private final DigitalEmployeeService employeeService;

	private final EmployeeReleaseLifecycleService releaseLifecycleService;

	private final EmployeeDeploymentService deploymentService;

	private final ObjectMapper objectMapper;

	public record ApplyResult(Long employeeReleaseId, Long sandboxActiveReleaseId, Long productionActiveReleaseId) {
	}

	public String captureBeforeSnapshot(Long employeeId) {
		DigitalEmployeeResp employee = employeeService.getDetail(employeeId);
		Map<String, Object> prompt = new LinkedHashMap<>();
		prompt.put("systemInstruction", employee.getSystemInstruction());
		prompt.put("prompt", employee.getSystemInstruction());
		Map<String, Object> model = new LinkedHashMap<>();
		model.put("chatModelConfigId", employee.getModelConfigId());
		Map<String, Object> snapshot = new LinkedHashMap<>();
		snapshot.put("promptSnapshot", prompt);
		snapshot.put("modelConfigSnapshot", model);
		return writeJson(snapshot);
	}

	public ApplyResult applyToSandbox(Long employeeId, Map<String, String> overlays) {
		applyOverlaysToDraft(employeeId, overlays);
		Long releaseId = releaseLifecycleService.createDraft(employeeId, new EmployeeReleaseCreateReq());
		releaseLifecycleService.seal(releaseId);
		releaseLifecycleService.publish(releaseId);
		activate(employeeId, DeploymentEnvironmentDict.SANDBOX.getValue(), releaseId);
		DigitalEmployeeDeployment sandbox = deploymentService.findCurrent(employeeId,
				DeploymentEnvironmentDict.SANDBOX.getValue());
		DigitalEmployeeDeployment production = deploymentService.findCurrent(employeeId,
				DeploymentEnvironmentDict.PRODUCTION.getValue());
		Long sandboxActive = sandbox == null ? null : sandbox.getActiveReleaseId();
		Long productionActive = production == null ? null : production.getActiveReleaseId();
		if (!releaseId.equals(sandboxActive)) {
			throw CheckedException.fail("SANDBOX 激活后指针未指向新 Release, employeeId=" + employeeId);
		}
		if (releaseId.equals(productionActive)) {
			throw CheckedException.fail("SANDBOX 激活不得改动 PRODUCTION 指针, employeeId=" + employeeId);
		}
		log.info("数字员工进化已激活 SANDBOX. employeeId={}, releaseId={}, productionActiveReleaseId={}", employeeId,
				releaseId, productionActive);
		return new ApplyResult(releaseId, sandboxActive, productionActive);
	}

	public void promoteToProduction(Long employeeId, Long employeeReleaseId) {
		DigitalEmployeeDeployment sandbox = deploymentService.findCurrent(employeeId,
				DeploymentEnvironmentDict.SANDBOX.getValue());
		if (sandbox == null || !employeeReleaseId.equals(sandbox.getActiveReleaseId())) {
			throw CheckedException.badRequest("数字员工须先把该 Release 激活到 SANDBOX，才能提升 PRODUCTION");
		}
		activate(employeeId, DeploymentEnvironmentDict.PRODUCTION.getValue(), employeeReleaseId);
		log.info("数字员工进化已激活 PRODUCTION. employeeId={}, releaseId={}", employeeId, employeeReleaseId);
	}

	/**
	 * @return true 已按 previousReleaseId 回滚；false 该环境没有历史版本（首次激活只能恢复草稿）
	 */
	public boolean rollbackEnvironment(Long employeeId, String environment) {
		DigitalEmployeeDeployment current = deploymentService.findCurrent(employeeId, environment);
		if (current == null || current.getPreviousReleaseId() == null) {
			log.warn("该环境无可回滚的历史版本, 仅能恢复草稿. employeeId={}, environment={}", employeeId, environment);
			return false;
		}
		int expectVersion = current.getDeploymentVersion() == null ? 0 : current.getDeploymentVersion();
		deploymentService.rollback(employeeId, environment, expectVersion);
		return true;
	}

	public void restoreDraft(Long employeeId, String beforeSnapshotJson) {
		applyOverlaysToDraft(employeeId, parseSnapshotAsOverlays(beforeSnapshotJson));
	}

	private void activate(Long employeeId, String environment, Long releaseId) {
		DigitalEmployeeDeployment current = deploymentService.findCurrent(employeeId, environment);
		int expectVersion = current == null || current.getDeploymentVersion() == null ? 0
				: current.getDeploymentVersion();
		EmployeeDeploymentActivateReq request = new EmployeeDeploymentActivateReq();
		request.setReleaseId(releaseId);
		request.setEnvironment(environment);
		request.setExpectVersion(expectVersion);
		deploymentService.activate(employeeId, request);
	}

	private void applyOverlaysToDraft(Long employeeId, Map<String, String> overlays) {
		if (overlays == null || overlays.isEmpty()) {
			return;
		}
		DigitalEmployeeModifyReq request = new DigitalEmployeeModifyReq();
		boolean any = false;
		String promptSnapshot = overlays.get("promptSnapshot");
		if (StringUtils.hasText(promptSnapshot)) {
			JsonNode node = readTree(promptSnapshot);
			if (node.hasNonNull("systemInstruction")) {
				request.setSystemInstruction(node.get("systemInstruction").asText());
				any = true;
			}
			else if (node.hasNonNull("prompt")) {
				request.setSystemInstruction(node.get("prompt").asText());
				any = true;
			}
		}
		String modelSnapshot = overlays.get("modelConfigSnapshot");
		if (StringUtils.hasText(modelSnapshot)) {
			JsonNode node = readTree(modelSnapshot);
			if (node.hasNonNull("chatModelConfigId")) {
				request.setModelConfigId(node.get("chatModelConfigId").asLong());
				any = true;
			}
		}
		if (any) {
			employeeService.modify(employeeId, request);
		}
	}

	private Map<String, String> parseSnapshotAsOverlays(String snapshotJson) {
		if (!StringUtils.hasText(snapshotJson)) {
			return Map.of();
		}
		JsonNode node = readTree(snapshotJson);
		Map<String, String> overlays = new LinkedHashMap<>();
		if (node.has("promptSnapshot")) {
			overlays.put("promptSnapshot", node.get("promptSnapshot").toString());
		}
		if (node.has("modelConfigSnapshot")) {
			overlays.put("modelConfigSnapshot", node.get("modelConfigSnapshot").toString());
		}
		return overlays;
	}

	private JsonNode readTree(String json) {
		try {
			return objectMapper.readTree(json);
		}
		catch (Exception ex) {
			throw CheckedException.badRequest("员工进化快照无法解析");
		}
	}

	private String writeJson(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (Exception ex) {
			throw CheckedException.fail("员工进化快照序列化失败");
		}
	}

}
