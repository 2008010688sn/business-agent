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
package com.sn68.agent.dataagent.authorization.observability;

import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.stereotype.Component;

/**
 * IAM 令牌签发耗时观测挂点（PR-10 指标埋点：IAM 令牌签发 P99）。
 *
 * <p>对 {@link EmployeeExecutionContextClient} 的容器 bean 包一层 JDK 动态代理，
 * 方法调用旁路计时进 {@code data.agent.employee.token.issuance.duration}
 * （tag: method/outcome，P95/P99 客户端百分位）——零改动既有实现类（employee/auth
 * 域归属 PR-5），指标与业务完全解耦：registry 未就绪或指标记录异常均不影响签发调用。</p>
 *
 * <p>Metrics 经 {@link ObjectProvider} 懒取（BeanPostProcessor 依赖其它 bean 必须
 * 延迟，避免容器过早初始化告警）；单测直接 new 实现类不经过容器，天然不受影响。</p>
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeTokenIssuanceMetricsBeanPostProcessor implements BeanPostProcessor {

	private final ObjectProvider<AuthorizationMetrics> metricsProvider;

	/**
	 * 懒取缓存（首次成功解析后固定；metrics bean 缺失时保持 null，每次调用重试解析）。
	 */
	private volatile AuthorizationMetrics cachedMetrics;

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!(bean instanceof EmployeeExecutionContextClient)) {
			return bean;
		}
		log.info("员工执行上下文客户端已挂载签发耗时观测代理. beanName={}", beanName);
		return Proxy.newProxyInstance(bean.getClass().getClassLoader(),
				new Class<?>[] { EmployeeExecutionContextClient.class },
				new IssuanceTimingHandler(bean, this::metricsQuietly));
	}

	private AuthorizationMetrics metricsQuietly() {
		AuthorizationMetrics cached = cachedMetrics;
		if (cached != null) {
			return cached;
		}
		cached = metricsProvider.getIfAvailable();
		if (cached != null) {
			cachedMetrics = cached;
		}
		return cached;
	}

	/**
	 * 签发调用计时代理：业务异常原样拆包上抛（不吞不改），指标记录全程 quiet。
	 */
	@RequiredArgsConstructor
	static final class IssuanceTimingHandler implements InvocationHandler {

		private final Object target;

		private final java.util.function.Supplier<AuthorizationMetrics> metricsSupplier;

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			if (method.getDeclaringClass() == Object.class) {
				return method.invoke(target, args);
			}
			long startNanos = System.nanoTime();
			try {
				Object result = method.invoke(target, args);
				recordQuietly(method.getName(), "SUCCESS", System.nanoTime() - startNanos);
				return result;
			}
			catch (InvocationTargetException ex) {
				recordQuietly(method.getName(), "ERROR", System.nanoTime() - startNanos);
				throw ex.getCause();
			}
		}

		private void recordQuietly(String method, String outcome, long durationNanos) {
			try {
				AuthorizationMetrics metrics = metricsSupplier.get();
				if (metrics != null) {
					metrics.recordTokenIssuance(method, outcome, durationNanos);
				}
			}
			catch (Exception ex) {
				// 指标旁路永不影响签发主流程
				log.warn("IAM 签发耗时指标记录失败(quiet). method={}, outcome={}, errorType={}, errorMessage={}",
						method, outcome, ex.getClass().getSimpleName(), ex.getMessage());
			}
		}

	}

}
