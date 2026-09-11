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
package com.sn68.agent.dataagent.dto.chat;

import com.sn68.agent.dataagent.entity.DataChatMessage;
import com.sn68.agent.dataagent.entity.DataChatSession;
import com.sn68.agent.dataagent.entity.DataChatTurn;
import com.sn68.agent.dataagent.observability.AnswerTraceExplainStore.AnswerTraceExplainView;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 数据问答会话单轮详情：轮次消息、答案溯源与调用链诊断信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "会话轮次详情")
public class DataChatTurnDetailResp {

	@Schema(description = "轮次信息")
	private DataChatTurn turn;

	@Schema(description = "所属会话信息")
	private DataChatSession session;

	@Schema(description = "轮次消息列表")
	private List<DataChatMessage> messages;

	@Schema(description = "思考过程消息列表")
	private List<DataChatMessage> thinkingMessages;

	@Schema(description = "答案溯源解释")
	private AnswerTraceExplainView answerExplain;

	@Schema(description = "调用链信息")
	private SessionCallChainResp callChain;

	@Schema(description = "是否可查看思考过程")
	private Boolean canViewThinking;

	@Schema(description = "是否可查看答案来源")
	private Boolean canViewAnswerSource;

	@Schema(description = "是否可查看调用链")
	private Boolean canViewCallChain;

	@Schema(description = "诊断信息是否可用")
	private Boolean diagnosticsAvailable;

	@Schema(description = "诊断提示信息")
	private String diagnosticsMessage;

}
