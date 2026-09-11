/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

/**
 * 统一能力网关。所有能力调用统一在此完成：租户与 Workspace 校验、Release/SkillVersion 固定、
 * 能力安装与授权检查、数据权限与资源范围、凭据检查、风险与审批、参数校验、限流预算配额、
 * 幂等键生成与调用记录、审计脱敏、结构化结果转换。任一检查失败抛出
 * {@code CheckedException}（中文说明被哪项检查拒绝），失败关闭，绝不降级放行。
 */
public interface CapabilityGateway {

	/**
	 * 调用工具目录（agent_execution_resource）中声明的能力：按能力编码寻址，检查链通过后经
	 * ToolTransportInvoker 执行。适用于 Runtime Hook、任务等目录能力调用。
	 */
	ResultEnvelope invoke(InvocationRequest request);

	/**
	 * 调用进程内能力（如 AgentScope 工具回调）：检查链通过后执行给定执行器并把原始结果装入信封。
	 */
	ResultEnvelope invoke(InvocationRequest request, CapabilityExecutor executor);

}
