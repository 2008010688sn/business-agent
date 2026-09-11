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
package com.sn68.agent.dataagent.im.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.sn68.agent.framework.commons.entity.DictEnum;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * IM 对话连接器错误码。
 */
@Getter
@AllArgsConstructor
public enum ImErrorDict implements DictEnum<Integer> {

	CONNECTOR_NOT_FOUND(480001, "IM 对话连接器不存在或未启用"),

	CALLBACK_SIGNATURE_INVALID(480002, "IM 回调验签失败"),

	MESSAGE_NOT_SUPPORTED(480003, "暂时只支持文本消息"),

	AGENT_NOT_BOUND(480004, "当前 IM 会话未绑定 Agent"),

	USER_NOT_BOUND(480005, "IM 用户未绑定系统账号"),

	AUTH_SNAPSHOT_EMPTY(480006, "授权快照缺失"),

	AGENT_INVOKE_FAILED(480007, "Agent 执行失败"),

	SETUP_SESSION_NOT_FOUND(480008, "IM 接入安装会话不存在"),

	SETUP_SESSION_EXPIRED(480009, "IM 接入安装会话已过期"),

	SETUP_REQUEST_INVALID(480010, "IM 接入向导请求无效"),

	DINGTALK_CREDENTIAL_INVALID(480011, "钉钉凭据校验失败"),

	DINGTALK_STREAM_START_FAILED(480012, "钉钉 Stream 连接启动失败"),

	DINGTALK_USER_RESOLVE_FAILED(480013, "钉钉用户解析失败"),

	STREAM_RUNTIME_UNAVAILABLE(480014, "IM Stream 运行时不可用"),

	PROVIDER_CONFIG_NOT_INITIALIZED(480015, "IM 平台配置未初始化"),

	AGENT_INVOKE_TIMEOUT(480016, "Agent 分析超时"),

	REPLY_WEBHOOK_INVALID(480017, "IM 回复 Webhook 不安全或未在白名单中"),

	PROVIDER_MISMATCH(480018, "IM 回调平台与连接器配置不一致"),

	APPROVAL_COMMAND_FAILED(480019, "IM 审批指令处理失败"),

	APPROVAL_PERMISSION_DENIED(480020, "您无权处理该审批，请联系管理员"),

	CALLBACK_TIMESTAMP_EXPIRED(480021, "IM 回调时间戳超出允许窗口"),

	CALLBACK_REPLAYED(480022, "IM 回调重复提交，已按重放拒绝"),

	CALLBACK_IDENTITY_MISMATCH(480023, "IM 回调报文身份与连接器配置不一致"),

	CONNECTOR_CODE_AMBIGUOUS(480024, "IM 连接器编码在多个租户命中同一密钥，已拒绝处理"),

	BIND_SESSION_NOT_FOUND(480025, "IM 绑定会话不存在"),

	BIND_CODE_INVALID(480026, "绑定码无效或已过期"),

	BIND_IDENTITY_OCCUPIED(480027, "该钉钉用户已绑定其他系统账号"),

	BIND_CODE_RATE_LIMITED(480028, "绑定尝试过于频繁，请稍后再试");

	@EnumValue
	@JsonValue
	private final Integer value;

	private final String label;

}
