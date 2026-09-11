/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.routing;

import com.sn68.agent.dataagent.constant.Constant;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 路由引擎切流配置：{@code spring.ai.agent.routing.engine.mode = LEGACY | SHADOW | NATIVE}，
 * 默认 LEGACY（与改造前行为完全一致），可经配置中心切换；SHADOW 打开 V1→V2 影子编译对比。
 *
 * <p>由 {@code routing.shadow.ShadowRouteConfiguration} 通过 @EnableConfigurationProperties 注册。
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = Constant.PROJECT_PROPERTIES_PREFIX + ".routing.engine")
public class RouteEngineModeProperties {

	/** 路由引擎运行模式，默认 LEGACY。 */
	@NotNull
	private RouteEngineMode mode = RouteEngineMode.LEGACY;

	/**
	 * NATIVE 执行链路仍未启用，维持「启动校验拒绝」而非「运行时回落 LEGACY 并 warn」：
	 * 误配置应在部署时立即失败暴露；静默回落会让运维误以为已切换 native，掩盖真实配置状态
	 * （对齐根 AGENTS.md「暴露真实失败」）。若运行期经配置中心动态刷新绕过启动校验，
	 * ShadowRouteCompileService 仍会按 LEGACY 行为兜底并 warn 一次。
	 *
	 * <p>NATIVE 最小执行路径设计（W2 已评估，本轮不落地，理由与顺序如下）：</p>
	 * <ol>
	 * <li>运行时基座已就绪：编排链路的协作者执行已由 RuntimeDagScheduler 事件驱动调度
	 * （EventDrivenCollaboratorEngine，经权威 agent_runtime_step 释放依赖），调度器具备
	 * 租约/fence、max_attempts 重试、WAITING 挂起与取消级联；NATIVE 与其差异只剩
	 * 「步骤来源」（CompiledPlan 而非 RoutePlan 镜像）与「步骤执行体」（能力调用而非协作者调用）。</li>
	 * <li>待补一（步骤来源）：mode=NATIVE 时对编排链路执行 编译（PlanCompiler）→
	 * CompiledPlanPersistenceService#save → 按 CompiledPlanStep 生成 agent_runtime_step
	 * （stepKey/dependsOn/inputBindings/maxAttempts 均已有列）→ RuntimeDagScheduler#start。
	 * 本轮已在 LEGACY/事件驱动路径开跑前调用 save() 写 ACTIVE；按 CompiledPlanStep 物化步骤
	 * 仍属 NATIVE，本轮不落地。
	 * 已知缺口：V1 候选未声明端口（PortSpec），带 inputMappings 的多步计划编译必然
	 * PORT_NOT_FOUND——最小路径先支持无绑定的单步/纯控制依赖计划，并为候选适配器补
	 * 「无端口能力」的默认 PortSpec；该缺口不影响本轮事件驱动链路（其依赖输入走
	 * 编排运行时的内存结果集，不走端口绑定）。</li>
	 * <li>待补二（步骤执行体）：capabilityHandle → CapabilityGateway 调用（经
	 * RuntimeInvocationService#open 记录幂等、超时/中断落 OUTCOME_UNKNOWN）。该网关
	 * 正由审批/预算工作流并行建设，本轮接线会与其产生跨工作流冲突，故顺序上必须后置。</li>
	 * <li>待补三（事件流）：RuntimeRunController 事件回放已具备（run 内单调 seq），
	 * NATIVE 编排完成后 SSE 从权威事件流投递、其余链路回落 legacy 并 warn，届时本校验
	 * 放开为「仅编排链路生效」。</li>
	 * </ol>
	 */
	@AssertTrue(message = "route engine mode NATIVE 尚未实现执行链路，请配置 LEGACY 或 SHADOW")
	public boolean isModeExecutable() {
		return mode != RouteEngineMode.NATIVE;
	}

}
