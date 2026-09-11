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
package com.sn68.agent.dataagent.task.service;

/**
 * API 触发请求载体：幂等键 + 开放签名协议请求头 + 请求体原文。
 * rawBody 保持 HTTP 请求体原始字符串参与验签（业务参数在验签通过后再反序列化）。
 */
public record ApiTriggerCommand(String idempotencyKey, String timestamp, String nonce, String signature,
		String rawBody) {
}
