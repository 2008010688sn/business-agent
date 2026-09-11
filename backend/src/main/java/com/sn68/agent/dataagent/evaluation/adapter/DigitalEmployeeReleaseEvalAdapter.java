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
package com.sn68.agent.dataagent.evaluation.adapter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeRelease;
import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalCase;
import com.sn68.agent.dataagent.evaluation.entity.DataAgentEvalRun;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import com.sn68.agent.dataagent.runtime.durable.service.RuntimeRunService;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 数字员工发布版本评估适配器。
 *
 * <p>{@code evalMode=REPLAY}（缺省）：回放历史 RuntimeRun 的 finalAnswer，只用于建基线用例，
 * 不得作为进化门禁。</p>
 * <p>{@code evalMode=INVOKE}：按员工 SANDBOX 部署指针真跑 DRY_RUN；没有 SANDBOX 失败关闭，
 * 不回落到 PRODUCTION。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DigitalEmployeeReleaseEvalAdapter implements AgentEvalAdapter {

	/**
	 * 适配器码（与主体类型同值，走 adapterRegistry 路由）。
	 */
	public static final String ADAPTER_CODE = "DIGITAL_EMPLOYEE_RELEASE";

	/**
	 * 评测主体类型：数字员工发布版本。
	 */
	public static final String SUBJECT_TYPE = "DIGITAL_EMPLOYEE_RELEASE";

	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
	};

	private final RuntimeRunService runtimeRunService;

	private final ObjectMapper objectMapper;

	private final DigitalEmployeeEvalInvoker evalInvoker;

	private final EmployeeReleaseLifecycleService releaseLifecycleService;

	@Override
	public String adapterCode() {
		return ADAPTER_CODE;
	}

	@Override
	public boolean supports(String subjectType) {
		return SUBJECT_TYPE.equalsIgnoreCase(subjectType);
	}

	@Override
	public EvalInvocationResult invoke(EvalInvocationPrepared prepared) {
		if (prepared != null && EvalRunHarness.isInvoke(prepared.run(), objectMapper)) {
			return invokeSandbox(prepared);
		}
		return replay(prepared);
	}

	private EvalInvocationResult invokeSandbox(EvalInvocationPrepared prepared) {
		Long releaseId = parseReleaseId(prepared.subject() == null ? null : prepared.subject().getSubjectId());
		if (releaseId == null) {
			return new EvalInvocationResult(false, null, null, null, null, null, null, null, null, 0, 0,
					"INVOKE 失败: 评估对象未绑定合法的数字员工 Release ID");
		}
		try {
			DigitalEmployeeRelease release = releaseLifecycleService.getDetail(releaseId);
			Long employeeId = release == null ? null : release.getEmployeeId();
			if (employeeId == null) {
				return new EvalInvocationResult(false, null, null, null, null, null, null, null, null, 0, 0,
						"INVOKE 失败: Release 缺少数字员工 ID, releaseId=" + releaseId);
			}
			return evalInvoker.invoke(prepared, employeeId, EvalRunHarness.overlayInstruction(prepared.run(), objectMapper));
		}
		catch (RuntimeException ex) {
			log.warn("Digital employee release invoke failed. releaseId={}", releaseId, ex);
			return new EvalInvocationResult(false, null, null, null, null, null, null, null, null, 0, 0,
					"INVOKE 失败: " + ex.getMessage());
		}
	}

	private EvalInvocationResult replay(EvalInvocationPrepared prepared) {
		long start = System.nanoTime();
		DataAgentEvalCase evalCase = prepared.evalCase();
		DataAgentEvalRun run = prepared.run();
		Long runtimeRunId = resolveRuntimeRunId(evalCase);
		if (runtimeRunId == null) {
			return replayFailure(run, null, "用例缺少 RUNTIME_RUN 溯源（traceSnapshotJson.runtimeRunId），无法回放");
		}
		String tenantId = parseNumericTenantId(run.getTenantId());
		if (tenantId == null) {
			return replayFailure(run, runtimeRunId, "评估运行租户快照缺失，禁止回放");
		}
		try {
			RuntimeRunDetailResp detail = runtimeRunService.detail(tenantId, runtimeRunId);
			RuntimeRunResp sourceRun = detail == null ? null : detail.run();
			if (sourceRun == null) {
				return replayFailure(run, runtimeRunId, "回放源运行不存在或无权访问");
			}
			String finalAnswer = detail.finalAnswer();
			if (!StringUtils.hasText(finalAnswer)) {
				return replayFailure(run, runtimeRunId, "回放源运行没有最终回答，无法作为回放输出");
			}
			return new EvalInvocationResult(true, finalAnswer, null, sourceRun.threadId(),
					sourceRun.runtimeRequestId(), durationMs(start), null, null, null, 0, 0, null);
		}
		catch (RuntimeException ex) {
			log.warn("Digital employee release replay failed. evalRunId={}, caseId={}, runtimeRunId={}",
					run == null ? null : run.getId(), evalCase == null ? null : evalCase.getId(), runtimeRunId, ex);
			return replayFailure(run, runtimeRunId, ex.getMessage());
		}
	}

	/**
	 * 从用例溯源快照解析回放源运行 ID（RUNTIME_RUN 取材口径），缺失或非法返回 null。
	 */
	private Long resolveRuntimeRunId(DataAgentEvalCase evalCase) {
		if (evalCase == null || !StringUtils.hasText(evalCase.getTraceSnapshotJson())) {
			return null;
		}
		try {
			Map<String, Object> trace = objectMapper.readValue(evalCase.getTraceSnapshotJson(), MAP_TYPE);
			Object runtimeRunId = trace.get("runtimeRunId");
			if (runtimeRunId instanceof Number number) {
				return number.longValue();
			}
			return runtimeRunId == null ? null : Long.valueOf(String.valueOf(runtimeRunId).trim());
		}
		catch (Exception ex) {
			log.warn("Failed to parse eval case trace snapshot for replay. caseId={}", evalCase.getId(), ex);
			return null;
		}
	}

	private String parseNumericTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			return null;
		}
		return tenantId.trim();
	}

	private long durationMs(long start) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
	}

	private EvalInvocationResult replayFailure(DataAgentEvalRun run, Long runtimeRunId, String errorMessage) {
		return new EvalInvocationResult(false, null, null, null, null, null, null, null, null, 0, 0,
				"REPLAY回放失败: " + errorMessage + (runtimeRunId == null ? "" : ", runtimeRunId=" + runtimeRunId));
	}

	private Long parseReleaseId(String subjectId) {
		if (!StringUtils.hasText(subjectId)) {
			return null;
		}
		try {
			return Long.valueOf(subjectId.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

}
