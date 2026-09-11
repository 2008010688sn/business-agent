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
package com.sn68.agent.dataagent.authorization.model;

/**
 * 授权动作枚举。
 *
 * <p>PDP 内核契约的一部分，定义了可授权的动作类型。</p>
 *
 * @author Felix (PR-3a PDP 内核)
 */
public enum AuthorizationAction {

	/**
	 * 读取能力元数据（查询）。
	 */
	READ("READ"),

	/**
	 * 写入或修改能力配置。
	 */
	WRITE("WRITE"),

	/**
	 * 执行能力（调用工具、运行 SQL、执行技能等）。
	 */
	EXECUTE("EXECUTE"),

	/**
	 * 启动无人值守任务（数字员工定时/事件触发）。
	 */
	START_UNATTENDED("START_UNATTENDED"),

	/**
	 * 发现/浏览可用能力列表。
	 */
	DISCOVER("DISCOVER"),

	/**
	 * 使用能力（进入业务场景）。
	 */
	USE("USE"),

	/**
	 * 读取知识库内容。
	 */
	READ_KNOWLEDGE("READ_KNOWLEDGE"),

	/**
	 * 读取记忆内容（用户/员工记忆）。
	 */
	READ_MEMORY("READ_MEMORY"),

	/**
	 * 写入记忆内容。
	 */
	WRITE_MEMORY("WRITE_MEMORY");

	private final String code;

	AuthorizationAction(String code) {
		this.code = code;
	}

	/**
	 * 获取动作码。
	 *
	 * @return 动作码
	 */
	public String getCode() {
		return code;
	}

	/**
	 * 按代码查找枚举值（不区分大小写）。
	 *
	 * @param code 动作码
	 * @return 枚举值，未找到返回 null
	 */
	public static AuthorizationAction fromCode(String code) {
		if (code == null) {
			return null;
		}
		for (AuthorizationAction action : values()) {
			if (action.getCode().equalsIgnoreCase(code)) {
				return action;
			}
		}
		return null;
	}
}
