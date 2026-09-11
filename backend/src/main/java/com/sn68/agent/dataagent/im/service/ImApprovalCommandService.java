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
package com.sn68.agent.dataagent.im.service;

import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.iam.dto.DelegatedAuthContextResp;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * IM 入站审批指令处理（一期简易协议）。
 *
 * <p><b>协议约定：</b>文本消息去除 @提及片段并 trim 后，须整体匹配
 * 「{@link ImConstants#APPROVAL_COMMAND_APPROVE 同意} &lt;审批ID&gt; [备注]」或
 * 「{@link ImConstants#APPROVAL_COMMAND_REJECT 拒绝} &lt;审批ID&gt; [备注]」；
 * 审批ID 为纯数字（允许 # 前缀，便于直接复制审批卡片中的编号），备注可选。
 * 不匹配的消息不属于审批指令，按普通对话交给 Agent。后续版本演进为交互卡片回调后本协议可下线。
 *
 * <p><b>权限守卫（沿用 IM 输入不得提权的既有约束）：</b>调用方必须先完成
 * DelegatedAuthContext 换取（发送者已绑定系统账号且租户与连接器一致）才能执行本服务；
 * 审批人即绑定用户本人，租户取验签通过的连接器租户，不做任何身份/租户切换。
 *
 * <p><b>H-1 修复：显式功能权限判定。</b>IM 指令不经过 Spring MVC，
 * {@code AgentApprovalController} 的 Web 权限码完全不参与；
 * {@code DelegatedAuthContextService.executeWith} 只把委托 Token 放进出站请求头，
 * 因此对同进程内的 service 调用没有强制力。
 * 修复前任何完成 IM 绑定的租户成员回一句「同意 &lt;自增ID&gt;」即可批准本租户任意高风险写操作，
 * 故此处按 IAM 颁发的 {@code funcPermissions} 显式判定同一权限码，判定不通过即拒绝执行。
 *
 * <p>授权模型: 委托上下文
 * 非 HTTP 入口：调用方须先完成 DelegatedAuthContext 换取，本服务按绑定用户本人 + IAM 权限码
 * {@code ai-agent:approval:review} 判定，不切换身份、不提升权限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImApprovalCommandService {

	/**
	 * 指令格式：动词 + 空白/冒号分隔 + [#]数字ID + 可选备注（截断到 200 字，防止超长入库）。
	 */
	private static final Pattern COMMAND_PATTERN = Pattern.compile(
			"^(" + ImConstants.APPROVAL_COMMAND_APPROVE + "|" + ImConstants.APPROVAL_COMMAND_REJECT + ")"
					+ "[\\s:：]*[#＃]?(\\d{1,18})(?:[\\s,，:：]+(\\S[\\s\\S]{0,199}))?$");

	/** 去除消息前部的 @提及片段（如「@数字员工 同意 1」），@ 后连续非空白视为被提及者名称。 */
	private static final Pattern LEADING_MENTION_PATTERN = Pattern.compile("^(?:@\\S+\\s+)+");

	private final AgentApprovalService approvalService;

	/**
	 * 尝试把文本解析为审批指令；不匹配返回 null（表示普通对话消息）。
	 */
	public ApprovalCommand parse(String text) {
		if (!StringUtils.hasText(text)) {
			return null;
		}
		String normalized = LEADING_MENTION_PATTERN.matcher(text.trim()).replaceFirst("").trim();
		Matcher matcher = COMMAND_PATTERN.matcher(normalized);
		if (!matcher.matches()) {
			return null;
		}
		boolean approve = ImConstants.APPROVAL_COMMAND_APPROVE.equals(matcher.group(1));
		long approvalId;
		try {
			approvalId = Long.parseLong(matcher.group(2));
		}
		catch (NumberFormatException ex) {
			return null;
		}
		String comment = matcher.group(3);
		return new ApprovalCommand(approve, approvalId, comment == null ? null : comment.trim());
	}

	/**
	 * 执行审批指令并返回回复文案。权限不足/审批不存在/已过期/非法流转等业务失败抛 CheckedException，
	 * 由调用方转为 IM 失败回复；成功路径返回确认文案。
	 */
	public String execute(AgentImConnector connector, DelegatedAuthContextResp context, ApprovalCommand command) {
		String tenantId = parseTenantId(connector.getTenantId());
		String approver = context.getUserId();
		requireApprovalPermission(connector, context, tenantId, approver, command);
		if (command.approve()) {
			approvalService.approve(tenantId, approver, command.approvalId(),
					StringUtils.hasText(command.comment()) ? command.comment() : "IM 指令同意");
			log.info("IM 审批指令已同意。tenantId={}, approvalId={}, approver={}", tenantId, command.approvalId(),
					approver);
			return "已同意审批 #" + command.approvalId() + "。";
		}
		approvalService.reject(tenantId, approver, command.approvalId(),
				StringUtils.hasText(command.comment()) ? command.comment() : "IM 指令拒绝");
		log.info("IM 审批指令已拒绝。tenantId={}, approvalId={}, approver={}", tenantId, command.approvalId(),
				approver);
		return "已拒绝审批 #" + command.approvalId() + "。";
	}

	/**
	 * 审批权限判定：只认 IAM 在委托上下文里颁发的 funcPermissions，
	 * 与 Web 端审批权限码读取的是同一份权限列表
	 * （IAM 把它同时写进 Sa-Token 会话与 DelegatedAuthContextResp），因此两条链路判定结果一致。
	 *
	 * <p>匹配规则沿用 Sa-Token：先精确命中，再按通配符模式匹配（如 {@code ai-agent:approval:*}），
	 * 避免 IM 链路比 Web 端更严导致管理员被误拒。权限列表为空一律拒绝（失败关闭）——
	 * IAM 侧按 clientId 过滤权限，权限码未建/未授权/未挂到该 clientId 都会落到这里。
	 */
	private void requireApprovalPermission(AgentImConnector connector, DelegatedAuthContextResp context, String tenantId,
			String approver, ApprovalCommand command) {
		if (hasPermission(context.getFuncPermissions(), ImConstants.PERMISSION_APPROVAL_REVIEW)) {
			return;
		}
		// 审计留痕：只记定位信息，不记审批备注与审批内容。
		log.warn("IM 审批指令权限不足已拒绝。tenantId={}, approver={}, approvalId={}, approve={}, provider={}, "
				+ "connectorCode={}, requiredPermission={}", tenantId, approver, command.approvalId(),
				command.approve(), connector.getProvider(), connector.getConnectorCode(),
				ImConstants.PERMISSION_APPROVAL_REVIEW);
		throw CheckedException.badRequest(ImErrorDict.APPROVAL_PERMISSION_DENIED.getValue(),
				ImErrorDict.APPROVAL_PERMISSION_DENIED.getLabel());
	}

	private boolean hasPermission(Collection<String> granted, String required) {
		if (granted == null || granted.isEmpty()) {
			return false;
		}
		if (granted.contains(required)) {
			return true;
		}
		return granted.stream().anyMatch(pattern -> StringUtils.hasText(pattern)
				&& vagueMatch(pattern, required));
	}

	static boolean vagueMatch(String pattern, String value) {
		if (!StringUtils.hasText(pattern) || !StringUtils.hasText(value)) {
			return false;
		}
		if ("*".equals(pattern) || pattern.equals(value)) {
			return true;
		}
		String regex = pattern.replace(".", "\\.").replace("*", ".*");
		return value.matches(regex);
	}

	private String parseTenantId(String tenantId) {
		if (!StringUtils.hasText(tenantId) || "0".equals(tenantId.trim())) {
			throw CheckedException.badRequest("IM 连接器租户不合法，无法执行审批指令");
		}
		return tenantId.trim();
	}

	/**
	 * 审批指令载体。
	 *
	 * @param approve true 同意 / false 拒绝
	 * @param approvalId 审批记录ID（agent_runtime_approval.id）
	 * @param comment 可选备注（作为审批意见）
	 */
	public record ApprovalCommand(boolean approve, long approvalId, String comment) {
	}

}
