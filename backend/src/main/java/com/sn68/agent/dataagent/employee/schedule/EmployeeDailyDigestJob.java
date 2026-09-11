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
package com.sn68.agent.dataagent.employee.schedule;

import com.sn68.agent.dataagent.employee.service.DigitalEmployeeDigestService;
import com.sn68.agent.dataagent.employee.service.impl.DigitalEmployeeDigestServiceImpl;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 员工昨日工作汇总。需在 Snail Job 控制台注册执行器 {@code employeeDailyDigestJob}。
 *
 * <p>授权模型: 服务主体
 * Snail Job 系统作业以平台身份按日生成汇总，不接受终端用户会话。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmployeeDailyDigestJob {

	private final DigitalEmployeeDigestService digestService;

	public void jobExecute() {
		LocalDate yesterday = LocalDate.now(DigitalEmployeeDigestServiceImpl.DIGEST_ZONE).minusDays(1);
		int written = digestService.generateForDate(yesterday);
		log.info("员工每日汇总作业完成. date={}, written={}", yesterday, written);
	}

}
