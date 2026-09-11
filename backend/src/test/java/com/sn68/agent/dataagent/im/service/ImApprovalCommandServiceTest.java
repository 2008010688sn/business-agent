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

import com.sn68.agent.dataagent.iam.dto.DelegatedAuthContextResp;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.runtime.durable.service.AgentApprovalService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * IM 审批指令一期简易协议：解析与执行。
 */
class ImApprovalCommandServiceTest {

	private final AgentApprovalService approvalService = mock(AgentApprovalService.class);

	private final ImApprovalCommandService commandService = new ImApprovalCommandService(approvalService);

	@Test
	void parsesApproveAndRejectCommands() {
		ImApprovalCommandService.ApprovalCommand approve = commandService.parse("同意 123");
		assertTrue(approve.approve());
		assertEquals(123L, approve.approvalId());
		assertNull(approve.comment());

		ImApprovalCommandService.ApprovalCommand reject = commandService.parse("@数字员工 拒绝 #456 参数有误");
		assertFalse(reject.approve());
		assertEquals(456L, reject.approvalId());
		assertEquals("参数有误", reject.comment());

		ImApprovalCommandService.ApprovalCommand colon = commandService.parse("同意：789");
		assertTrue(colon.approve());
		assertEquals(789L, colon.approvalId());
	}

	@Test
	void nonCommandTextsAreTreatedAsNormalConversation() {
		assertNull(commandService.parse(null));
		assertNull(commandService.parse("   "));
		assertNull(commandService.parse("同意"));
		assertNull(commandService.parse("同意 12x"));
		assertNull(commandService.parse("帮我查询昨天的订单，我同意 100 元预算"));
		assertNull(commandService.parse("请审批一下 123"));
	}

	@Test
	void executeApproveUsesConnectorTenantAndBoundUser() {
		String reply = commandService.execute(connector("100"), context("42"),
				new ImApprovalCommandService.ApprovalCommand(true, 123L, null));

		verify(approvalService).approve("100", "42", 123L, "IM 指令同意");
		assertEquals("已同意审批 #123。", reply);
	}

	@Test
	void executeRejectPassesCommentAsDecisionOpinion() {
		String reply = commandService.execute(connector("100"), context("42"),
				new ImApprovalCommandService.ApprovalCommand(false, 456L, "参数有误"));

		verify(approvalService).reject("100", "42", 456L, "参数有误");
		assertEquals("已拒绝审批 #456。", reply);
	}

	@Test
	void invalidConnectorTenantFailsClosed() {
		assertThrows(CheckedException.class, () -> commandService.execute(connector("not-a-number"), context("42"),
				new ImApprovalCommandService.ApprovalCommand(true, 1L, null)));
	}

	/**
	 * H-1：IM 指令不经过 Spring MVC，Controller 上的 @SaCheckPermission 完全不参与，
	 * 必须在服务内显式判定；否则任何完成 IM 绑定的租户成员回一句「同意 <ID>」即可批准任意高风险操作。
	 */
	@Test
	void userWithoutReviewPermissionCannotApproveOrRejectViaIm() {
		DelegatedAuthContextResp bound = context("42", "ai-agent:approval:query", "agent:memory:export");

		CheckedException approveDenied = assertThrows(CheckedException.class, () -> commandService.execute(
				connector("100"), bound, new ImApprovalCommandService.ApprovalCommand(true, 123L, null)));
		CheckedException rejectDenied = assertThrows(CheckedException.class, () -> commandService.execute(
				connector("100"), bound, new ImApprovalCommandService.ApprovalCommand(false, 123L, "不同意")));

		assertEquals(ImErrorDict.APPROVAL_PERMISSION_DENIED.getValue(), approveDenied.getCode());
		assertEquals("您无权处理该审批，请联系管理员", approveDenied.getMessage());
		assertEquals(ImErrorDict.APPROVAL_PERMISSION_DENIED.getValue(), rejectDenied.getCode());
		verify(approvalService, never()).approve(anyString(), anyString(), anyLong(), any());
		verify(approvalService, never()).reject(anyString(), anyString(), anyLong(), any());
	}

	@Test
	void emptyPermissionSnapshotFailsClosed() {
		DelegatedAuthContextResp noPermissions = context("42");
		noPermissions.setFuncPermissions(List.of());

		CheckedException denied = assertThrows(CheckedException.class, () -> commandService.execute(connector("100"),
				noPermissions, new ImApprovalCommandService.ApprovalCommand(true, 123L, null)));

		assertEquals(ImErrorDict.APPROVAL_PERMISSION_DENIED.getValue(), denied.getCode());
		verify(approvalService, never()).approve(anyString(), anyString(), anyLong(), any());
	}

	/** 与 Sa-Token 的 @SaCheckPermission 匹配语义对齐：通配符权限码同样放行，IM 链路不得比 Web 端更严。 */
	@Test
	void wildcardPermissionIsAcceptedLikeSaTokenAnnotation() {
		String reply = commandService.execute(connector("100"), context("42", "ai-agent:approval:*"),
				new ImApprovalCommandService.ApprovalCommand(true, 321L, null));

		verify(approvalService).approve("100", "42", 321L, "IM 指令同意");
		assertEquals("已同意审批 #321。", reply);
	}

	private AgentImConnector connector(String tenantId) {
		AgentImConnector connector = new AgentImConnector();
		connector.setTenantId(tenantId);
		connector.setProvider("DINGTALK");
		connector.setConnectorCode("conn");
		return connector;
	}

	private DelegatedAuthContextResp context(String userId) {
		return context(userId, ImConstants.PERMISSION_APPROVAL_REVIEW);
	}

	private DelegatedAuthContextResp context(String userId, String... funcPermissions) {
		DelegatedAuthContextResp context = new DelegatedAuthContextResp();
		context.setUserId(userId);
		context.setTenantId("100");
		context.setFuncPermissions(List.of(funcPermissions));
		return context;
	}

}
