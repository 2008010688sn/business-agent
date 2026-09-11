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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引用校验器真实实现测试（PR-5 替换 PR-1 失败关闭桩）：
 * 委托 lifecycle 服务完成归属校验，失败异常原样透传。
 */
class EmployeeReleaseReferenceCheckerImplTest {

	private final EmployeeReleaseLifecycleService lifecycleService = mock(EmployeeReleaseLifecycleService.class);

	private final EmployeeReleaseReferenceCheckerImpl checker = new EmployeeReleaseReferenceCheckerImpl(
			lifecycleService);

	@Test
	@DisplayName("校验透传：按原参数委托 lifecycle 已发布归属校验")
	void delegatesToLifecycleService() {
		checker.validateReleaseOwner(9L, 3L);

		verify(lifecycleService).requirePublishedReleaseOwnedByEmployee(9L, 3L);
	}

	@Test
	@DisplayName("校验失败：lifecycle 抛出的拒绝异常原样透传（不再失败关闭整体入口）")
	void propagatesValidationFailure() {
		doThrow(CheckedException.badRequest("发布版本不属于该数字员工")).when(lifecycleService)
			.requirePublishedReleaseOwnedByEmployee(9L, 999L);

		CheckedException ex = assertThrows(CheckedException.class, () -> checker.validateReleaseOwner(9L, 999L));

		assertTrue(ex.getMessage().contains("不属于"), ex.getMessage());
	}

	@Test
	@DisplayName("SEALED 拒绝：任务绑定只接受 PUBLISHED")
	void rejectsSealedRelease() {
		doThrow(CheckedException.badRequest("任务只能绑定已发布的员工版本，当前状态: SEALED")).when(lifecycleService)
			.requirePublishedReleaseOwnedByEmployee(9L, 3L);

		CheckedException ex = assertThrows(CheckedException.class, () -> checker.validateReleaseOwner(9L, 3L));

		assertTrue(ex.getMessage().contains("已发布"), ex.getMessage());
	}

}
