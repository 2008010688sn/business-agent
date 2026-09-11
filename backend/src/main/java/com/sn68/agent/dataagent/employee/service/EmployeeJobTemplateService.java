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

import com.sn68.agent.dataagent.employee.dto.DigitalEmployeeCreateReq;
import com.sn68.agent.dataagent.employee.dto.EmployeeJobTemplateResp;
import com.sn68.agent.dataagent.employee.dto.EmployeeJobTemplateSkillHint;
import com.sn68.agent.dataagent.employee.dto.EmployeeJobTemplateTaskHint;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 数字员工岗位模板：创建时预填档案字段，不绑定未发布技能、不在草稿期创建任务定义。
 */
@Service
public class EmployeeJobTemplateService {

	public static final String TEMPLATE_OPS_ANALYST = "OPS_ANALYST";

	public static final String JOB_TEMPLATE_CODE_KEY = "jobTemplateCode";

	private static final EmployeeJobTemplateResp OPS_ANALYST = opsAnalyst();

	public List<EmployeeJobTemplateResp> list() {
		return List.of(OPS_ANALYST);
	}

	/**
	 * 按编码取模板；未知编码失败关闭，避免向导提交后静默变成空白档案。
	 */
	public EmployeeJobTemplateResp require(String templateCode) {
		if (!StringUtils.hasText(templateCode)) {
			throw CheckedException.badRequest("岗位模板编码不能为空");
		}
		String code = templateCode.trim();
		if (TEMPLATE_OPS_ANALYST.equalsIgnoreCase(code)) {
			return OPS_ANALYST;
		}
		throw CheckedException.badRequest("未知岗位模板: " + code + "，当前仅支持 " + TEMPLATE_OPS_ANALYST);
	}

	/**
	 * 用模板填充创建请求中的空白字段；调用方已填的岗位/提示词不被覆盖。
	 * 不写入 SkillVersion ID，不创建 TaskDefinition。
	 */
	public void applyDefaults(DigitalEmployeeCreateReq request) {
		if (request == null || !StringUtils.hasText(request.getTemplateCode())) {
			return;
		}
		EmployeeJobTemplateResp template = require(request.getTemplateCode());
		if (!StringUtils.hasText(request.getJobTitle())) {
			request.setJobTitle(template.getJobTitle());
		}
		if (!StringUtils.hasText(request.getDescription())) {
			request.setDescription(template.getDescription());
		}
		if (!StringUtils.hasText(request.getSystemInstruction())) {
			request.setSystemInstruction(template.getSystemInstruction());
		}
		if (!StringUtils.hasText(request.getGreeting())) {
			request.setGreeting(template.getGreeting());
		}
		if (!StringUtils.hasText(request.getAutonomyLevel())) {
			request.setAutonomyLevel(template.getAutonomyLevel());
		}
		Map<String, Object> policy = request.getExecutionPolicy() == null ? new LinkedHashMap<>()
				: new LinkedHashMap<>(request.getExecutionPolicy());
		policy.put(JOB_TEMPLATE_CODE_KEY, template.getTemplateCode());
		request.setExecutionPolicy(policy);
		request.setTemplateCode(template.getTemplateCode());
	}

	private static EmployeeJobTemplateResp opsAnalyst() {
		return EmployeeJobTemplateResp.builder()
			.templateCode(TEMPLATE_OPS_ANALYST)
			.displayName("运营分析")
			.jobTitle("运营分析")
			.description("面向运营的数字员工：按日汇总库存、异常箱、调度与签收等经营指标，交结构化快报。")
			.systemInstruction("""
					你是箱箱云运营分析数字员工。回答必须基于工具返回的结构化结果，禁止编造数字。
					优先给出结论、关键指标和风险；不要输出 SQL、物理表名或内部工具名。
					无人值守任务用自然语言交卷，表格与图表由系统从工具结果生成。
					""")
			.greeting("我是运营分析助手，可以帮你看库存、异常箱和经营指标。")
			.autonomyLevel("ASSISTED")
			.recommendedSkills(List.of(
					EmployeeJobTemplateSkillHint.builder()
						.skillCode("ops-metrics")
						.displayName("经营指标查询")
						.hint("请到技能市场安装已发布的数据探查/报表技能后再绑定。模板不预填 SkillVersion ID。")
						.build(),
					EmployeeJobTemplateSkillHint.builder()
						.skillCode("ops-report")
						.displayName("分析报告")
						.hint("有已发布报表技能再绑定；没有则任务仍可跑，结论来自员工绑定的能力。")
						.build()))
			.recommendedTask(EmployeeJobTemplateTaskHint.builder()
				.taskName("每日运营快报")
				.taskDescription("汇总昨日运营情况：库存结存、异常箱数量、调度/出库/签收关键指标，给出结论、变化（如有数据）、风险与建议。不要输出 SQL。")
				.taskType("REPORT")
				.defaultAutonomyLevel("ASSISTED")
				.highRiskWrite(Boolean.FALSE)
				.triggerHint("发布生产 Release 后再创建任务，并加定时触发器（建议工作日 08:30，Asia/Shanghai）。草稿员工不能建任务。")
				.build())
			.build();
	}

}
