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
package com.sn68.agent.dataagent.linking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppOriginMatcherTest {

	@Test
	void matchesDefaultHttpsPortTrailingSlashAndUserinfo() {
		List<String> origins = List.of("https://i.example.com/");
		assertTrue(AppOriginMatcher.matches(URI.create("https://i.example.com:443/a/b?id=1"), origins));
		assertTrue(AppOriginMatcher.matches(URI.create("https://USER:pass@i.example.com/a"), origins));
		assertFalse(AppOriginMatcher.matches(URI.create("https://preview.cortp.com/a"), origins));
	}

	@Test
	void matchesExplicitDevPortAndIgnoresPath() {
		List<String> origins = List.of("http://10.0.0.1:31770");
		assertTrue(AppOriginMatcher.matches(
				URI.create("http://10.0.0.1:31770/dual-effect/receivable/account/detail?id=1"), origins));
		assertFalse(AppOriginMatcher.matches(URI.create("http://10.0.0.1:8080/detail?id=1"), origins));
	}

	@Test
	void normalizesIpv6BracketsAndRedactsUserinfo() {
		URI ipv6 = URI.create("http://[::1]:8080/detail?id=1");
		assertTrue(AppOriginMatcher.matches(ipv6, List.of("http://[::1]:8080/")));
		assertEquals("http://host/x", AppOriginMatcher.redactUserInfo("http://alice:secret@host/x"));
		assertFalse(AppOriginMatcher.truncateForLog("http://alice:secret@host/very-long").contains("alice"));
	}

	@Test
	void httpDefaultPortEqualsExplicit80() {
		assertTrue(AppOriginMatcher.matches(URI.create("http://example.com:80/x"), List.of("http://example.com")));
		assertTrue(AppOriginMatcher.matches(URI.create("http://example.com/x"), List.of("http://example.com:80")));
	}

	@Test
	void rfc1918AndLocalhostAreOwnSiteWithoutOrigins() {
		assertTrue(AppOriginMatcher.isOwnSite(URI.create("http://10.0.0.1:31770/detail?id=1"), List.of()));
		assertTrue(AppOriginMatcher.isOwnSite(URI.create("http://127.0.0.1:8080/"), List.of()));
		assertFalse(AppOriginMatcher.isOwnSite(URI.create("https://preview.cortp.com/a"), List.of()));
		assertTrue(AppOriginMatcher.isOwnSite(URI.create("https://preview.cortp.com/a"),
				List.of("https://preview.cortp.com")));
	}

}
