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

import com.sn68.agent.dataagent.employee.service.EmployeeReleaseLifecycleService;
import com.sn68.agent.dataagent.task.service.EmployeeReleaseReferenceChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 数字员工发布版本引用校验器（PR-5 真实实现，替换并移除 PR-1 失败关闭桩
 * UnavailableEmployeeReleaseReferenceChecker）。
 *
 * <p>校验语义：digital_employee_release 存在、属于当前租户与指定数字员工，
 * 且状态必须为 PUBLISHED（任务定义绑定/运行只接受已发布版本；SEALED 不可绑任务）。
 * 不满足即抛 CheckedException。本类是 EmployeeReleaseReferenceChecker 的唯一实现 Bean
 * （PR-1 桩已物理删除，避免 NoUniqueBeanDefinitionException）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeReleaseReferenceCheckerImpl implements EmployeeReleaseReferenceChecker {

	private final EmployeeReleaseLifecycleService releaseLifecycleService;

	@Override
	public void validateReleaseOwner(Long digitalEmployeeId, Long employeeReleaseId) {
		log.debug("任务定义引用数字员工发布版本校验. digitalEmployeeId={}, employeeReleaseId={}", digitalEmployeeId,
				employeeReleaseId);
		releaseLifecycleService.requirePublishedReleaseOwnedByEmployee(digitalEmployeeId, employeeReleaseId);
	}

}
