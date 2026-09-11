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
package com.sn68.agent.dataagent.im.controller;

import com.sn68.agent.dataagent.im.dto.ImCallbackResponse;
import com.sn68.agent.dataagent.im.service.ImCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * IM 平台回调入口。
 */
@RestController
@RequestMapping("/im-callbacks")
@RequiredArgsConstructor
@Tag(name = "IM 回调", description = "接收外部 IM 平台回调消息")
public class ImCallbackController {

	private final ImCallbackService callbackService;

	@Operation(summary = "接收IM 回调", description = "接收IM 回调，用于IM 回调相关管理和运行场景。")
	@PostMapping("/{provider}/{connectorCode}/callback")
	public Object callback(@PathVariable String provider, @PathVariable String connectorCode,
			HttpServletRequest request) throws Exception {
		String rawBody = StreamUtils.copyToString(request.getInputStream(), request.getCharacterEncoding() == null
				? java.nio.charset.StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(request.getCharacterEncoding()));
		ImCallbackResponse response = callbackService.handleCallback(provider, connectorCode, headers(request),
				queryParams(request), rawBody);
		return response.responsePayload() == null || response.responsePayload().isEmpty() ? response
				: response.responsePayload();
	}

	private Map<String, String> headers(HttpServletRequest request) {
		Map<String, String> result = new HashMap<>();
		Collections.list(request.getHeaderNames())
			.forEach(name -> result.put(name, request.getHeader(name)));
		return result;
	}

	private Map<String, String> queryParams(HttpServletRequest request) {
		Map<String, String> result = new HashMap<>();
		request.getParameterMap().forEach((key, values) -> result.put(key, values == null || values.length == 0 ? null : values[0]));
		return result;
	}

}
