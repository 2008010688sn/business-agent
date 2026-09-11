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
package com.sn68.agent.dataagent.authorization.pep;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sn68.agent.dataagent.authorization.model.AuthorizationAction;
import com.sn68.agent.dataagent.authorization.model.AuthorizationOwnerType;
import com.sn68.agent.dataagent.authorization.pdp.SubjectKind;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * PEP 空主体校验聚焦单测（PR-3c 交付物 4：Hook 拒绝空主体）。
 *
 * @author James (PR-3c PEP 内核扩展)
 */
class InvocationSubjectGuardTest {

	private final InvocationSubjectGuard guard = new InvocationSubjectGuard();

	@Test
	void completeSubjectIsCompliant() {
		PepDecisionContext context = PepDecisionContext.builder()
			.tenantId("1")
			.subjectKind(SubjectKind.CALLER)
			.subjectId("user-1")
			.ownerType(AuthorizationOwnerType.DATA_AGENT)
			.ownerId(10L)
			.action(AuthorizationAction.EXECUTE)
			.build();
		InvocationSubjectGuard.SubjectCheckResult result = guard.checkSubject(context,
				PepAuthorizationMode.SHADOW);
		assertTrue(result.isCompliant());
		assertTrue(result.missingFields().isEmpty());
	}

	@Test
	void missingTenantAndSubjectCollected() {
		PepDecisionContext context = PepDecisionContext.builder().build();
		InvocationSubjectGuard.SubjectCheckResult result = guard.checkSubject(context,
				PepAuthorizationMode.ENFORCE);
		assertFalse(result.isCompliant());
		List<String> missing = result.missingFields();
		assertTrue(missing.contains("tenantId"));
		assertTrue(missing.contains("subjectKind"));
		assertTrue(missing.contains("subjectId"));
	}

	@Test
	void blankTenantTreatedAsMissing() {
		PepDecisionContext context = PepDecisionContext.builder()
			.tenantId("  ")
			.subjectKind(SubjectKind.DIGITAL_EMPLOYEE)
			.subjectId("emp-1")
			.build();
		InvocationSubjectGuard.SubjectCheckResult result = guard.checkSubject(context,
				PepAuthorizationMode.SHADOW);
		assertFalse(result.isCompliant());
		assertTrue(result.missingFields().contains("tenantId"));
	}

	@Test
	void rejectIfMissingSubjectThrowsWithBusinessDeniedSemantics() {
		InvocationSubjectGuard.SubjectCheckResult result = new InvocationSubjectGuard.SubjectCheckResult(false,
				List.of("tenantId", "subjectId"));
		CheckedException ex = assertThrows(CheckedException.class,
				() -> guard.rejectIfMissingSubject(result, "HOOK_EXECUTE"));
		assertTrue(ex.getMessage().contains("BUSINESS_DENIED"));
		assertTrue(ex.getMessage().contains("tenantId"));
		assertTrue(ex.getMessage().contains("subjectId"));
		assertTrue(ex.getMessage().contains("HOOK_EXECUTE"));
	}

	@Test
	void rejectIfMissingSubjectPassesWhenCompliant() {
		InvocationSubjectGuard.SubjectCheckResult result = new InvocationSubjectGuard.SubjectCheckResult(true,
				List.of());
		guard.rejectIfMissingSubject(result, "HOOK_EXECUTE");
	}

}
