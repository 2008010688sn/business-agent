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
package com.sn68.agent.dataagent.task.service;

import com.sn68.agent.framework.commons.exception.CheckedException;

/**
 * 数字员工发布版本引用校验器。
 *
 * <p>任务定义创建/修改时，必须验证 employeeReleaseId 指向的员工存在、Release 属于该员工，
 * 且状态为 PUBLISHED（SEALED 不可绑任务；运行解析同样只接受已发布版本）。
 */
public interface EmployeeReleaseReferenceChecker {

	/**
	 * 校验 Release 是否属于指定数字员工且已发布。
	 *
	 * @param digitalEmployeeId 数字员工 ID
	 * @param employeeReleaseId 数字员工发布版本 ID
	 * @throws CheckedException 若校验失败
	 */
	void validateReleaseOwner(Long digitalEmployeeId, Long employeeReleaseId);
}
