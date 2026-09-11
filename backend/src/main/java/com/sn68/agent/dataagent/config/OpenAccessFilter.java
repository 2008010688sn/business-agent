/*
 * Copyright (c) sn68. All Rights Reserved.
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
package com.sn68.agent.dataagent.config;

import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.threadlocal.ThreadLocalHolder;
import com.sn68.agent.framework.security.domain.UserInfoDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Open-access demo identity: every request gets a PLATFORM_ADMIN user in ThreadLocal.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpenAccessFilter extends OncePerRequestFilter {

	/** Must match AuthenticationContextConfiguration.USER_INFO. */
	public static final String USER_INFO_KEY = "USER_INFO_KEY";

	public static final String USER_ANONYMOUS_KEY = "USER_ANONYMOUS_KEY";

	public static UserInfoDetails demoUser() {
		return UserInfoDetails.builder()
			.userId("1")
			.username("sn68")
			.nickName("Demo User")
			.tenantId("default")
			.tenantCode("default")
			.type(UserType.PLATFORM_ADMIN)
			.enabled(Boolean.TRUE)
			.build();
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		ThreadLocalHolder.set(USER_INFO_KEY, demoUser());
		ThreadLocalHolder.set(USER_ANONYMOUS_KEY, Boolean.FALSE);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			ThreadLocalHolder.clear();
		}
	}

}
