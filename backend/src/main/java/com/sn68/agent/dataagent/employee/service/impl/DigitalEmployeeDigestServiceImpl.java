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

import com.sn68.agent.dataagent.employee.dto.EmployeeDigestResp;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployee;
import com.sn68.agent.dataagent.employee.entity.DigitalEmployeeDigest;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeDigestMapper;
import com.sn68.agent.dataagent.employee.repository.DigitalEmployeeMapper;
import com.sn68.agent.dataagent.employee.service.DigitalEmployeeDigestService;
import com.sn68.agent.dataagent.runtime.durable.entity.AgentRuntimeRun;
import com.sn68.agent.dataagent.runtime.durable.enums.RuntimeRunState;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunFeedbackMapper;
import com.sn68.agent.dataagent.runtime.durable.repository.AgentRuntimeRunMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 按 Asia/Shanghai 自然日汇总。单员工失败不阻断整批。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigitalEmployeeDigestServiceImpl implements DigitalEmployeeDigestService {

	public static final ZoneId DIGEST_ZONE = ZoneId.of("Asia/Shanghai");

	private final DigitalEmployeeMapper employeeMapper;

	private final DigitalEmployeeDigestMapper digestMapper;

	private final AgentRuntimeRunMapper runMapper;

	private final AgentRuntimeRunFeedbackMapper feedbackMapper;

	private final AuthenticationContext authenticationContext;

	@Override
	public int generateForDate(LocalDate digestDate) {
		if (digestDate == null) {
			throw CheckedException.badRequest("汇总日期不能为空");
		}
		List<DigitalEmployee> employees = employeeMapper.listEnabledWithManager();
		int written = 0;
		for (DigitalEmployee employee : employees) {
			try {
				if (upsertDigest(employee, digestDate)) {
					written++;
				}
			}
			catch (RuntimeException ex) {
				log.error("员工每日汇总失败, employeeId={}, date={}", employee.getId(), digestDate, ex);
			}
		}
		log.info("员工每日汇总完成. date={}, scanned={}, written={}", digestDate, employees.size(), written);
		return written;
	}

	@Override
	public EmployeeDigestResp findLatest(Long employeeId) {
		if (employeeId == null) {
			throw CheckedException.badRequest("数字员工ID不能为空");
		}
		DigitalEmployee employee = employeeMapper.findByIdAndTenantId(employeeId, currentTenantId());
		if (employee == null) {
			throw CheckedException.notFound("数字员工不存在: " + employeeId);
		}
		DigitalEmployeeDigest row = digestMapper.findLatest(employee.getTenantId(), employeeId);
		if (row == null) {
			return null;
		}
		return toResp(row);
	}

	private boolean upsertDigest(DigitalEmployee employee, LocalDate digestDate) {
		if (employee == null || employee.getId() == null || !StringUtils.hasText(employee.getTenantId())) {
			return false;
		}
		String tenantId = employee.getTenantId().trim();
		if (digestMapper.findByTenantEmployeeDate(tenantId, employee.getId(), digestDate) != null) {
			return false;
		}
		Instant from = digestDate.atStartOfDay(DIGEST_ZONE).toInstant();
		Instant to = digestDate.plusDays(1).atStartOfDay(DIGEST_ZONE).toInstant();
		List<AgentRuntimeRun> runs = runMapper.listByEmployeeCreatedBetween(tenantId, employee.getId(), from, to);
		int total = runs.size();
		int failed = 0;
		int waiting = 0;
		List<Long> runIds = new ArrayList<>(runs.size());
		for (AgentRuntimeRun run : runs) {
			if (run.getId() != null) {
				runIds.add(run.getId());
			}
			if (RuntimeRunState.FAILED.getValue().equals(run.getState())
					|| RuntimeRunState.TIMED_OUT.getValue().equals(run.getState())) {
				failed++;
			}
			if (RuntimeRunState.WAITING_APPROVAL.getValue().equals(run.getState())) {
				waiting++;
			}
		}
		int down = (int) feedbackMapper.countDownByRunIds(tenantId, runIds);
		String summary = "昨日运行 " + total + " 次，失败 " + failed + "，待审批 " + waiting + "，差评 " + down;
		Instant now = Instant.now();
		DigitalEmployeeDigest row = DigitalEmployeeDigest.builder()
			.tenantId(tenantId)
			.employeeId(employee.getId())
			.digestDate(digestDate)
			.managerUserId(employee.getManagerUserId())
			.runTotal(total)
			.runFailed(failed)
			.waitingApproval(waiting)
			.feedbackDown(down)
			.summary(summary)
			.createTime(now)
			.lastModifyTime(now)
			.deleted(false)
			.build();
		try {
			if (digestMapper.insert(row) != 1) {
				throw CheckedException.fail("员工每日汇总写入失败, employeeId=" + employee.getId());
			}
		}
		catch (DuplicateKeyException duplicate) {
			log.info("员工每日汇总命中唯一键, 跳过. employeeId={}, date={}", employee.getId(), digestDate);
			return false;
		}
		log.info("员工每日汇总已写入. employeeId={}, managerUserId={}, summary={}", employee.getId(),
				employee.getManagerUserId(), summary);
		return true;
	}

	private EmployeeDigestResp toResp(DigitalEmployeeDigest row) {
		return new EmployeeDigestResp(row.getDigestDate(), row.getManagerUserId(), row.getRunTotal(),
				row.getRunFailed(), row.getWaitingApproval(), row.getFeedbackDown(), row.getSummary());
	}

	private String currentTenantId() {
		String tenantId;
		try {
			tenantId = authenticationContext.tenantId();
		}
		catch (Exception ex) {
			throw CheckedException.forbidden("无法解析当前登录租户，禁止查询员工汇总");
		}
		if (!StringUtils.hasText(tenantId)) {
			throw CheckedException.forbidden("当前登录租户为空，禁止查询员工汇总");
		}
		return tenantId.trim();
	}

}
