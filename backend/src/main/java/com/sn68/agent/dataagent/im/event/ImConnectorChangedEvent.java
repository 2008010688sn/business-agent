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
package com.sn68.agent.dataagent.im.event;

/**
 * IM 连接器变更事件。
 *
 * <p>connectorCode 仅在租户内唯一，跨租户可能重码，因此以 connectorId 作为定位主键，
 * connectorCode 仅用于日志展示。
 */
public record ImConnectorChangedEvent(Long connectorId, String connectorCode) {
}
