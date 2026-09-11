/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

/**
 * 进程内能力执行器。把 Spring 容器内已注册的能力（如 AgentScope 工具回调）交给
 * {@link CapabilityGateway} 统一完成检查链后再执行；执行器本身不承担任何权限判断。
 */
@FunctionalInterface
public interface CapabilityExecutor {

	/**
	 * 执行进程内能力并返回原始结果。
	 */
	Object execute() throws Exception;

}
