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
package com.sn68.agent.dataagent.market.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import com.sn68.agent.framework.commons.exception.CheckedException;

/**
 * 能力市场条目审核状态机。
 *
 * <pre>
 * DRAFT ──提交审核──▶ REVIEWING ──审核通过──▶ APPROVED ──撤销──▶ REVOKED（终态）
 *   ▲                    │
 *   │                    └──审核驳回──▶ REJECTED ──重新提交──▶ REVIEWING
 *   └──（DRAFT/REJECTED 可修改、可删除）
 * </pre>
 */
public enum MarketListingStatus implements DictEnum<String> {

	DRAFT("DRAFT", "草稿"),

	REVIEWING("REVIEWING", "审核中"),

	APPROVED("APPROVED", "审核通过"),

	REJECTED("REJECTED", "已驳回"),

	REVOKED("REVOKED", "已撤销");

	@EnumValue
	@JsonValue
	private final String value;

	private final String label;

	MarketListingStatus(String value, String label) {
		this.value = value;
		this.label = label;
	}

	@Override
	public String getValue() {
		return value;
	}

	@Override
	public String getLabel() {
		return label;
	}

	/**
	 * 当前状态是否允许流转到目标状态。
	 */
	public boolean canTransitionTo(MarketListingStatus target) {
		if (target == null) {
			return false;
		}
		return switch (this) {
			case DRAFT, REJECTED -> target == REVIEWING;
			case REVIEWING -> target == APPROVED || target == REJECTED;
			case APPROVED -> target == REVOKED;
			case REVOKED -> false;
		};
	}

	/**
	 * 校验状态流转，非法流转抛业务异常。
	 */
	public void requireTransitionTo(MarketListingStatus target) {
		if (!canTransitionTo(target)) {
			throw CheckedException.badRequest(
					"市场条目状态不允许从[" + getLabel() + "]流转到[" + (target == null ? "空" : target.getLabel()) + "]");
		}
	}

	/**
	 * 是否允许修改条目内容（仅草稿与已驳回可改）。
	 */
	public boolean modifiable() {
		return this == DRAFT || this == REJECTED;
	}

}
