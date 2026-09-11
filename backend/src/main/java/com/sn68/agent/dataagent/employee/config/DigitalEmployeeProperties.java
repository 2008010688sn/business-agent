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
package com.sn68.agent.dataagent.employee.config;

import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 数字员工域配置（PR-5）。
 *
 * <p>rollout 开关默认 <b>false</b>：未开启时不调用任何 IAM Service Principal 新接口
 * （开通/角色/快照/执行上下文），员工创建/编辑/Seal/Publish/部署均可用但不可启用；
 * 对话 Facade 走默认 Guard 放行纯模型对话（不换 Principal token）。</p>
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "spring.ai.agent.digital-employee")
public class DigitalEmployeeProperties {

	/**
	 * 灰度开关：false 时 Principal 开通一律跳过（不调 IAM），员工不可启用。
	 */
	private Rollout rollout = new Rollout();

	/**
	 * 执行上下文缓存配置。
	 */
	private AuthCache authCache = new AuthCache();

	@Getter
	@Setter
	public static class Rollout {

		/**
		 * 是否开启数字员工灰度（开通 IAM Principal + 换 token 执行）。
		 */
		private boolean enabled = false;

	}

	@Getter
	@Setter
	public static class AuthCache {

		/**
		 * 缓存 TTL 上限（分钟）。实际 Redis TTL 再与 token 剩余寿命取短，避免长过 token。
		 */
		@Min(1)
		private long ttlMinutes = 30L;

		/**
		 * 剩余寿命低于该秒数则视为过期并重签（覆盖时钟偏差与 hydrate 窗口）。
		 */
		@Min(0)
		private long skewSeconds = 60L;

	}

	/**
	 * 签发执行上下文请求的 displayName 之外的默认 token 有效期（秒）；<=0 使用 IAM 服务端默认。
	 */
	@Min(0)
	private long executionContextTimeoutSeconds = 600L;

}
