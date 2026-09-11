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
package com.sn68.agent.dataagent.notification.enums;

import com.sn68.agent.framework.commons.entity.DictEnum;

/**
 * 通知渠道类型字典。枚举值与历史落库字符串保持一致（value 恒等于 name），仅补充中文标签。
 */
public enum NotificationChannelType implements DictEnum<String> {

	DINGTALK_ROBOT("钉钉群机器人"),

	DINGTALK_WORK_NOTICE("钉钉工作通知"),

	WECOM_ROBOT("企业微信群机器人"),

	WECOM_APP("企业微信应用消息"),

	WECHAT_MP_TEMPLATE("微信公众号模板消息"),

	WECHAT_MINI_SUBSCRIBE("微信小程序订阅消息"),

	SMS_ALIYUN("阿里云短信");

	private final String label;

	NotificationChannelType(String label) {
		this.label = label;
	}

	@Override
	public String getValue() {
		return name();
	}

	@Override
	public String getLabel() {
		return label;
	}

}
