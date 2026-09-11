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
package com.sn68.agent.dataagent.notification.service;

import com.sn68.agent.dataagent.entity.DataAgentSkill;
import com.sn68.agent.dataagent.entity.DataAgentSkillToolRef;
import com.sn68.agent.dataagent.notification.dto.NotificationAuthorizationDecision;
import com.sn68.agent.dataagent.notification.dto.NotificationSendRequest;
import com.sn68.agent.dataagent.notification.entity.AgentNotificationAuthorization;
import com.sn68.agent.dataagent.notification.enums.NotificationConfirmPolicy;
import com.sn68.agent.dataagent.notification.repository.AgentNotificationAuthorizationMapper;
import com.sn68.agent.dataagent.repository.DataAgentSkillToolRefMapper;
import com.sn68.agent.dataagent.service.skill.SkillCatalogService;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class NotificationAuthorizationServiceTest {

	private final AgentNotificationAuthorizationMapper authorizationMapper = mock(
			AgentNotificationAuthorizationMapper.class);

	private final DataAgentSkillToolRefMapper skillToolRefMapper = mock(DataAgentSkillToolRefMapper.class);

	private final SkillCatalogService skillCatalogService = mock(SkillCatalogService.class);

	private final AuthenticationContext authenticationContext = mock(AuthenticationContext.class);

	private final NotificationAuthorizationService service = new NotificationAuthorizationService(authorizationMapper,
			skillToolRefMapper, skillCatalogService, authenticationContext);

	@Test
	void decideKeepsSkillToolRefCheckForOrdinaryNotificationCalls() {
		DataAgentSkill skill = new DataAgentSkill();
		skill.setPublishedVersionId(11L);
		when(skillCatalogService.findVisible("skill-a", null)).thenReturn(skill);
		when(skillToolRefMapper.findBySkillVersionId(11L)).thenReturn(List.of());

		NotificationAuthorizationDecision decision = service.decide(request(), "target-a", "template-a");

		assertFalse(decision.allowed());
		assertEquals("The pinned Skill version has not referenced notification.send.", decision.message());
		verifyNoInteractions(authorizationMapper);
	}

	@Test
	void decideForHookSkipsTriggerObjectToolRefCheckAndUsesNotificationAuthorization() {
		AgentNotificationAuthorization authorization = new AgentNotificationAuthorization();
		authorization.setConfirmPolicy(NotificationConfirmPolicy.NONE.name());
		when(authorizationMapper.findActive(7L, "skill-a", 11L, NotificationAuthorizationService.RESOURCE_KEY,
				"target-a", "template-a"))
			.thenReturn(List.of(authorization));

		NotificationAuthorizationDecision decision = service.decideForHook(request(), "target-a", "template-a");

		assertTrue(decision.allowed());
		assertEquals(NotificationConfirmPolicy.NONE.name(), decision.confirmPolicy());
		verify(authorizationMapper).findActive(7L, "skill-a", 11L, NotificationAuthorizationService.RESOURCE_KEY,
				"target-a", "template-a");
		verifyNoInteractions(skillToolRefMapper, skillCatalogService);
	}

	private NotificationSendRequest request() {
		return new NotificationSendRequest(7L, "skill-a", 11L, NotificationAuthorizationService.RESOURCE_KEY,
				"session-1", "runtime-1", "target-a", "template-a", Map.of(), "idem-1", true);
	}

}
