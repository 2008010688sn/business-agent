/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.agentscope.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AgentRuntimeDeadlineTest {

	@Test
	void componentTimeoutUsesRemainingBudgetAndFinishBuffer() {
		AgentRuntimeDeadline deadline = AgentRuntimeDeadline.start(Duration.ofSeconds(5));

		assertTrue(deadline.timeoutFor(Duration.ofSeconds(45), Duration.ofSeconds(2)).compareTo(Duration.ofSeconds(3)) <= 0);
		assertEquals(Duration.ZERO, deadline.timeoutFor(Duration.ofSeconds(1), Duration.ofSeconds(10)));
	}

	@Test
	void expiredDeadlineCannotStartAnotherCall() {
		AgentRuntimeDeadline deadline = AgentRuntimeDeadline.startAt(System.nanoTime() - Duration.ofSeconds(3).toNanos(),
				Duration.ofSeconds(1));

		assertTrue(!deadline.canStart(Duration.ZERO));
	}

	@Test
	void capUsesOriginalStartAndNeverExtendsExistingDeadline() {
		long tenSecondsAgo = System.nanoTime() - Duration.ofSeconds(10).toNanos();
		AgentRuntimeDeadline capped = AgentRuntimeDeadline.startAt(tenSecondsAgo, Duration.ofSeconds(120))
			.capFromStart(Duration.ofSeconds(20));
		AgentRuntimeDeadline alreadyShorter = AgentRuntimeDeadline.startAt(tenSecondsAgo, Duration.ofSeconds(15))
			.capFromStart(Duration.ofSeconds(20));

		assertTrue(capped.remaining().compareTo(Duration.ofSeconds(10)) <= 0);
		assertTrue(capped.remaining().compareTo(Duration.ofSeconds(9)) > 0);
		assertTrue(alreadyShorter.remaining().compareTo(Duration.ofSeconds(5)) <= 0);
	}

	@Test
	void totalTimeoutMustBePositive() {
		assertThrows(IllegalArgumentException.class, () -> AgentRuntimeDeadline.start(null));
		assertThrows(IllegalArgumentException.class, () -> AgentRuntimeDeadline.start(Duration.ZERO));
	}

}
