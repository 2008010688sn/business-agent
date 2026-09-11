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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sn68.agent.dataagent.im.dto.ImCallbackMessage;
import com.sn68.agent.dataagent.im.dto.ImUserBindSessionCreateRequest;
import com.sn68.agent.dataagent.im.dto.ImUserBindSessionDTO;
import com.sn68.agent.dataagent.im.entity.AgentImConnector;
import com.sn68.agent.dataagent.im.entity.AgentImUserBindSession;
import com.sn68.agent.dataagent.im.entity.AgentImUserIdentity;
import com.sn68.agent.dataagent.im.enums.ImConstants;
import com.sn68.agent.dataagent.im.enums.ImErrorDict;
import com.sn68.agent.dataagent.im.repository.AgentImUserBindSessionMapper;
import com.sn68.agent.dataagent.im.repository.AgentImUserIdentityMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ImUserBindSessionServiceTest {

	private AgentImUserBindSessionMapper bindSessionMapper;
	private AgentImUserIdentityMapper identityMapper;
	private AuthenticationContext authenticationContext;
	private ImUserBindSessionService service;

	@BeforeEach
	void setUp() {
		bindSessionMapper = Mockito.mock(AgentImUserBindSessionMapper.class);
		identityMapper = Mockito.mock(AgentImUserIdentityMapper.class);
		authenticationContext = Mockito.mock(AuthenticationContext.class);
		service = new ImUserBindSessionService(bindSessionMapper, identityMapper, authenticationContext);
	}

	@Test
	void parseBindCodeIgnoresNormalChat() {
		assertNull(service.parseBindCode("你好"));
		assertNull(service.parseBindCode("帮我下单"));
		assertNull(service.parseBindCode("BIND-hello"));
		assertNull(service.parseBindCode("BIND-123"));
	}

	@Test
	void parseBindCodeAcceptsMentionPrefixAndLowerCase() {
		assertEquals("BIND-A2B3C4D5", service.parseBindCode("@机器人 bind-a2b3c4d5"));
		assertEquals("BIND-A2B3C4D5", service.parseBindCode("  BIND-A2B3C4D5  "));
	}

	@Test
	void consumeIgnoresNonBindText() {
		ImUserBindSessionService.ConsumeResult result = service.consume(connector(),
				message("你好"));
		assertFalse(result.bindCommand());
		verify(bindSessionMapper, never()).findPendingByCode(any(), any());
	}

	@Test
	void consumeRejectsUnknownCode() {
		when(bindSessionMapper.findPendingByCode("1", "BIND-A2B3C4D5")).thenReturn(null);
		ImUserBindSessionService.ConsumeResult result = service.consume(connector(), message("BIND-A2B3C4D5"));
		assertTrue(result.bindCommand());
		assertFalse(result.success());
		assertEquals(ImErrorDict.BIND_CODE_INVALID, result.error());
	}

	@Test
	void consumeRejectsExpiredCode() {
		AgentImUserBindSession session = pendingSession();
		session.setExpireTime(LocalDateTime.now().minusMinutes(1));
		when(bindSessionMapper.findPendingByCode("1", "BIND-A2B3C4D5")).thenReturn(session);
		when(bindSessionMapper.expirePending(9L)).thenReturn(1);
		ImUserBindSessionService.ConsumeResult result = service.consume(connector(), message("BIND-A2B3C4D5"));
		assertTrue(result.bindCommand());
		assertFalse(result.success());
		assertEquals(ImErrorDict.BIND_CODE_INVALID, result.error());
		verify(bindSessionMapper, never()).markConsumed(anyLong(), any(), any());
	}

	@Test
	void consumeRejectsOccupiedExternalUser() {
		AgentImUserBindSession session = pendingSession();
		when(bindSessionMapper.findPendingByCode("1", "BIND-A2B3C4D5")).thenReturn(session);
		AgentImUserIdentity occupied = new AgentImUserIdentity();
		occupied.setUserId("99");
		occupied.setBindStatus(ImConstants.STATUS_ENABLED);
		when(identityMapper.findByExternalUser("1", ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"manager4081")).thenReturn(occupied);
		ImUserBindSessionService.ConsumeResult result = service.consume(connector(), message("BIND-A2B3C4D5"));
		assertTrue(result.bindCommand());
		assertFalse(result.success());
		assertEquals(ImErrorDict.BIND_IDENTITY_OCCUPIED, result.error());
		verify(identityMapper, never()).insert(any(AgentImUserIdentity.class));
	}

	@Test
	void consumeBindsSameUserIdempotently() {
		AgentImUserBindSession session = pendingSession();
		when(bindSessionMapper.findPendingByCode("1", "BIND-A2B3C4D5")).thenReturn(session);
		when(bindSessionMapper.markConsumed(eq(9L), eq("manager4081"), any())).thenReturn(1);
		AgentImUserIdentity existing = new AgentImUserIdentity();
		existing.setId(3L);
		existing.setUserId("42");
		existing.setBindStatus(ImConstants.STATUS_ENABLED);
		when(identityMapper.findByExternalUser("1", ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"manager4081")).thenReturn(existing);
		ImUserBindSessionService.ConsumeResult result = service.consume(connector(), message("BIND-A2B3C4D5"));
		assertTrue(result.success());
		assertEquals("42", result.userId());
		verify(identityMapper).updateById(any(AgentImUserIdentity.class));
		verify(identityMapper, never()).insert(any(AgentImUserIdentity.class));
	}

	@Test
	void createSnapshotsCurrentLoginUser() {
		when(authenticationContext.tenantId()).thenReturn("1");
		when(authenticationContext.userId()).thenReturn("42");
		when(authenticationContext.nickName()).thenReturn("平台管理员");
		when(bindSessionMapper.findPendingByUser("1", "42", "dingtalk-customer-service")).thenReturn(List.of());
		when(bindSessionMapper.insert(any(AgentImUserBindSession.class))).thenAnswer(invocation -> {
			AgentImUserBindSession session = invocation.getArgument(0);
			session.setId(11L);
			return 1;
		});
		ImUserBindSessionDTO dto = service.create(new ImUserBindSessionCreateRequest("dingtalk-customer-service"));
		assertEquals(11L, dto.id());
		assertEquals("42", dto.userId());
		assertEquals("平台管理员", dto.nickName());
		assertEquals(ImConstants.BIND_SESSION_PENDING, dto.status());
		assertTrue(dto.code().startsWith(ImConstants.BIND_CODE_PREFIX));
		assertEquals(8, dto.code().substring(ImConstants.BIND_CODE_PREFIX.length()).length());
		assertEquals(dto.code(), dto.qrContent());
	}

	@Test
	void statusHidesOtherUsersSessions() {
		when(authenticationContext.tenantId()).thenReturn("1");
		when(authenticationContext.userId()).thenReturn("42");
		AgentImUserBindSession session = pendingSession();
		session.setUserId("99");
		when(bindSessionMapper.findByIdInTenant("1", 9L)).thenReturn(session);
		assertThrows(CheckedException.class, () -> service.status(9L));
	}

	@Test
	void consumeRejectsMismatchedConnector() {
		AgentImUserBindSession session = pendingSession();
		session.setConnectorCode("other-connector");
		when(bindSessionMapper.findPendingByCode("1", "BIND-A2B3C4D5")).thenReturn(session);
		ImUserBindSessionService.ConsumeResult result = service.consume(connector(), message("BIND-A2B3C4D5"));
		assertTrue(result.bindCommand());
		assertFalse(result.success());
		assertEquals(ImErrorDict.BIND_CODE_INVALID, result.error());
		verify(bindSessionMapper, never()).markConsumed(anyLong(), any(), any());
	}

	@Test
	void consumeRateLimitsRepeatedInvalidCodes() {
		when(bindSessionMapper.findPendingByCode("1", "BIND-A2B3C4D5")).thenReturn(null);
		for (int i = 0; i < ImConstants.BIND_FAIL_MAX_ATTEMPTS; i++) {
			assertEquals(ImErrorDict.BIND_CODE_INVALID,
					service.consume(connector(), message("BIND-A2B3C4D5")).error());
		}
		ImUserBindSessionService.ConsumeResult limited = service.consume(connector(), message("BIND-A2B3C4D5"));
		assertEquals(ImErrorDict.BIND_CODE_RATE_LIMITED, limited.error());
		verify(bindSessionMapper, times(ImConstants.BIND_FAIL_MAX_ATTEMPTS)).findPendingByCode("1", "BIND-A2B3C4D5");
	}

	@Test
	void consumeInsertsNewIdentity() {
		AgentImUserBindSession session = pendingSession();
		when(bindSessionMapper.findPendingByCode("1", "BIND-A2B3C4D5")).thenReturn(session);
		when(bindSessionMapper.markConsumed(eq(9L), eq("manager4081"), any())).thenReturn(1);
		when(identityMapper.findByExternalUser("1", ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service",
				"manager4081")).thenReturn(null);
		ImUserBindSessionService.ConsumeResult result = service.consume(connector(), message("BIND-A2B3C4D5"));
		assertTrue(result.success());
		assertTrue(result.reply().contains("平台管理员"));
		ArgumentCaptor<AgentImUserIdentity> captor = ArgumentCaptor.forClass(AgentImUserIdentity.class);
		verify(identityMapper).insert(captor.capture());
		assertEquals("manager4081", captor.getValue().getExternalUserId());
		assertEquals("42", captor.getValue().getUserId());
		assertEquals(ImConstants.BIND_SOURCE_QR_PAIR, captor.getValue().getBindSource());
		assertNotNull(captor.getValue().getTenantId());
	}

	private AgentImConnector connector() {
		AgentImConnector connector = new AgentImConnector();
		connector.setTenantId("1");
		connector.setProvider(ImConstants.PROVIDER_DINGTALK);
		connector.setConnectorCode("dingtalk-customer-service");
		return connector;
	}

	private ImCallbackMessage message(String text) {
		return new ImCallbackMessage(ImConstants.PROVIDER_DINGTALK, "dingtalk-customer-service", "msg-1",
				ImConstants.CONVERSATION_SINGLE, "cid-1", "manager4081", null, null, "TEXT", text, false,
				"https://oapi.dingtalk.com/robot/sendBySession?session=x", null);
	}

	private AgentImUserBindSession pendingSession() {
		AgentImUserBindSession session = new AgentImUserBindSession();
		session.setId(9L);
		session.setTenantId("1");
		session.setBindCode("BIND-A2B3C4D5");
		session.setConnectorCode("dingtalk-customer-service");
		session.setUserId("42");
		session.setUsername("admin");
		session.setNickName("平台管理员");
		session.setStatus(ImConstants.BIND_SESSION_PENDING);
		session.setExpireTime(LocalDateTime.now().plusMinutes(10));
		return session;
	}

}
