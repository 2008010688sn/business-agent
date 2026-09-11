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
package com.sn68.agent.dataagent.employee.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.employee.dto.EmployeeRunDetailResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeBudgetReportResp;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunPageQueryReq;
import com.sn68.agent.dataagent.runtime.durable.dto.RuntimeRunResp;
import java.time.Instant;

/**
 * 数字员工履历查询：强制按路径员工 id 过滤，不走通用 runtime-runs 权限域。
 */
public interface EmployeeRunQueryService {

	IPage<RuntimeRunResp> pageRuns(Long employeeId, RuntimeRunPageQueryReq request);

	EmployeeRunDetailResp getRunDetail(Long employeeId, Long runtimeRunId);

	/**
	 * 本员工运行成本：路径 id 定归属，不走 runtime-run 权限域。
	 */
	RuntimeBudgetReportResp budgetReport(Long employeeId, Instant fromTime, Instant toTime);

}
