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
package com.sn68.agent.dataagent.agentscope.session;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataAgentRuntimeRegistryTest {

	@Test
	void tryRegisterExclusive_rejectsSecondActiveRuntimeInSameThread() {
		AgentRuntimeRegistry registry = new AgentRuntimeRegistry();

		assertTrue(registry.tryRegisterExclusive("100", "run-1"));
		assertFalse(registry.tryRegisterExclusive("100", "run-2"));
		assertTrue(registry.tryRegisterExclusive("101", "run-3"));
	}

	@Test
	void tryRegisterExclusive_allowsNextRuntimeAfterPreviousFinished() {
		AgentRuntimeRegistry registry = new AgentRuntimeRegistry();

		assertTrue(registry.tryRegisterExclusive("100", "run-1"));
		registry.finish("100", "run-1");

		assertTrue(registry.tryRegisterExclusive("100", "run-2"));
	}

	@Test
	void tryRegisterExclusive_rejectsNewRuntimeUntilCancelledRuntimeIsFinished() {
		AgentRuntimeRegistry registry = new AgentRuntimeRegistry();

		assertTrue(registry.tryRegisterExclusive("100", "run-1"));
		registry.markCancelled("100", "run-1");

		assertFalse(registry.tryRegisterExclusive("100", "run-2"));
		registry.finish("100", "run-1");
		assertTrue(registry.tryRegisterExclusive("100", "run-2"));
	}

	@Test
	void markCancelled_cascadesToInFlightChildrenAndInterruptsThem() throws Exception {
		AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
		registry.tryRegisterExclusive("100", "run-1");
		registry.registerChild("100", "run-1", "child-thread-1", "child-run-1");
		registry.registerChild("100", "run-1", "child-thread-2", "child-run-2");
		CountDownLatch childStarted = new CountDownLatch(1);
		CountDownLatch childInterrupted = new CountDownLatch(1);
		Thread childThread = new Thread(() -> {
			childStarted.countDown();
			try {
				Thread.sleep(5000L);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				childInterrupted.countDown();
			}
		});
		childThread.start();
		try {
			assertTrue(childStarted.await(3, TimeUnit.SECONDS));
			registry.markRunning("child-thread-1", "child-run-1", childThread);

			assertTrue(registry.markCancelled("100", "run-1"));

			assertTrue(registry.isCancelled("child-thread-1", "child-run-1"));
			assertTrue(registry.isCancelled("child-thread-2", "child-run-2"));
			assertTrue(childInterrupted.await(3, TimeUnit.SECONDS));
		}
		finally {
			childThread.interrupt();
			childThread.join(3000L);
		}
	}

	@Test
	void registerChild_inheritsCancellationDecidedBeforeTheChildWasLinked() {
		AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
		registry.tryRegisterExclusive("100", "run-1");
		registry.markCancelled("100", "run-1");

		registry.registerChild("100", "run-1", "child-thread-1", "child-run-1");

		assertTrue(registry.isCancelled("child-thread-1", "child-run-1"));
	}

	@Test
	void markCancelled_doesNotReviveChildrenThatAlreadyFinished() {
		AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
		registry.tryRegisterExclusive("100", "run-1");
		registry.registerChild("100", "run-1", "child-thread-1", "child-run-1");
		registry.registerChild("100", "run-1", "child-thread-2", "child-run-2");
		registry.finish("child-thread-1", "child-run-1");

		registry.markCancelled("100", "run-1");

		assertFalse(registry.isCancelled("child-thread-1", "child-run-1"));
		assertTrue(registry.isCancelled("child-thread-2", "child-run-2"));
	}

	@Test
	void finish_releasesBothRequestStateAndParentChildLinks() {
		AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
		for (int round = 0; round < 3; round++) {
			registry.tryRegisterExclusive("100", "run-" + round);
			registry.registerChild("100", "run-" + round, "child-thread-a" + round, "child-run-a" + round);
			registry.registerChild("100", "run-" + round, "child-thread-b" + round, "child-run-b" + round);
			registry.finish("child-thread-a" + round, "child-run-a" + round);
			registry.finish("child-thread-b" + round, "child-run-b" + round);
			registry.finish("100", "run-" + round);
		}

		assertEquals(0, registry.trackedRequestCount());
		assertEquals(0, registry.trackedParentLinkCount());
	}

	@Test
	void finish_releasesParentChildLinksWhenTheParentGivesUpFirst() {
		AgentRuntimeRegistry registry = new AgentRuntimeRegistry();
		registry.tryRegisterExclusive("100", "run-1");
		registry.registerChild("100", "run-1", "child-thread-1", "child-run-1");

		registry.finish("100", "run-1");
		registry.finish("child-thread-1", "child-run-1");

		assertEquals(0, registry.trackedRequestCount());
		assertEquals(0, registry.trackedParentLinkCount());
	}

}
