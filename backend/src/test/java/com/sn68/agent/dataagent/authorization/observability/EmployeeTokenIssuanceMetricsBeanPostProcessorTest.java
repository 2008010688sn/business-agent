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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.employee.auth.EmployeeAuthContextException;
import com.sn68.agent.dataagent.employee.auth.EmployeeAuthTokenContext;
import com.sn68.agent.dataagent.employee.auth.EmployeeExecutionContextClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * IAM 签发耗时观测代理聚焦单测（PR-10 指标埋点：无侵入计时 + 异常拆包 + quiet 兜底）。
 *
 * @author Ray (PR-10 可观测性与灰度基建)
 */
class EmployeeTokenIssuanceMetricsBeanPostProcessorTest {

	private SimpleMeterRegistry registry;

	private AuthorizationMetrics metrics;

	@SuppressWarnings("unchecked")
	private final ObjectProvider<AuthorizationMetrics> metricsProvider = mock(ObjectProvider.class);

	private EmployeeExecutionContextClient target;

	private EmployeeTokenIssuanceMetricsBeanPostProcessor processor;

	@BeforeEach
	void setUp() {
		registry = new SimpleMeterRegistry();
		metrics = new AuthorizationMetrics(registry);
		target = mock(EmployeeExecutionContextClient.class);
		processor = new EmployeeTokenIssuanceMetricsBeanPostProcessor(metricsProvider);
	}

	@Test
	void unrelatedBeanPassesThroughUntouched() {
		Object bean = new Object();

		Object processed = processor.postProcessAfterInitialization(bean, "unrelatedBean");

		assertSame(bean, processed);
		assertTrue(registry.getMeters().isEmpty());
	}

	@Test
	void wrapsClientBeanIntoTimingProxy() {
		Object processed = processor.postProcessAfterInitialization(proxyTarget(), "employeeClient");

		assertNotSame(proxyTarget(), processed);
		assertTrue(processed instanceof EmployeeExecutionContextClient);
	}

	@Test
	void successCallIsTimedAndResultPassedThrough() {
		EmployeeAuthTokenContext context = new EmployeeAuthTokenContext("tok-1", "Bearer", 1800L, 3L);
		when(target.issueContext("1", "sp-1", "员工A")).thenReturn(context);

		EmployeeAuthTokenContext result = proxy().issueContext("1", "sp-1", "员工A");

		assertSame(context, result);
		verify(target).issueContext("1", "sp-1", "员工A");
		assertEquals(1L, registry.get(AuthorizationMetrics.METRIC_TOKEN_ISSUANCE_DURATION)
			.tag("method", "issueContext")
			.tag("outcome", "SUCCESS")
			.timer()
			.count());
	}

	@Test
	void failureIsUnwrappedAndCountedAsError() {
		EmployeeAuthContextException iamDown = new EmployeeAuthContextException("WAITING_AUTH", "iam down", null);
		when(target.issueContext(any(), any(), any())).thenThrow(iamDown);

		EmployeeAuthContextException thrown = assertThrows(EmployeeAuthContextException.class,
				() -> proxy().issueContext("1", "sp-1", "员工A"));

		// InvocationTargetException 拆包：业务侧拿到原始异常（语义零改变）
		assertSame(iamDown, thrown);
		assertEquals(1L, registry.get(AuthorizationMetrics.METRIC_TOKEN_ISSUANCE_DURATION)
			.tag("method", "issueContext")
			.tag("outcome", "ERROR")
			.timer()
			.count());
	}

	@Test
	void objectMethodsPassThroughWithoutTiming() {
		EmployeeExecutionContextClient clientProxy = proxy();

		assertEquals(target.toString(), clientProxy.toString());
		assertEquals(target.hashCode(), clientProxy.hashCode());
		// Object 方法不计时：签发指标仅在接口方法调用路径出现
		assertNull(registry.find(AuthorizationMetrics.METRIC_TOKEN_ISSUANCE_DURATION).timer());
	}

	@Test
	void proceedsQuietlyWhenMetricsUnavailable() {
		when(metricsProvider.getIfAvailable()).thenReturn(null);

		assertDoesNotThrow(() -> proxy().invalidate("1", "sp-1"));
		verify(target).invalidate("1", "sp-1");
	}

	private EmployeeExecutionContextClient proxyTarget() {
		return target;
	}

	private EmployeeExecutionContextClient proxy() {
		when(metricsProvider.getIfAvailable()).thenReturn(metrics);
		return (EmployeeExecutionContextClient) processor.postProcessAfterInitialization(target, "employeeClient");
	}

}
