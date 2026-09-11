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
package com.sn68.agent.dataagent.service.agent;

import com.sn68.agent.dataagent.dto.agent.SavePresetQuestionReq;
import com.sn68.agent.dataagent.entity.AgentPresetQuestion;
import com.sn68.agent.dataagent.repository.AgentPresetQuestionMapper;
import com.sn68.agent.framework.db.mybatisplus.ext.SuperServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AgentPresetQuestion Service Class
 */
@Service
@RequiredArgsConstructor
public class AgentPresetQuestionServiceImpl extends SuperServiceImpl<AgentPresetQuestionMapper, AgentPresetQuestion>
		implements AgentPresetQuestionService {

	private final DataAgentService dataAgentService;

	@Override
	public List<AgentPresetQuestion> findByAgentId(Long agentId) {
		requireOwnedAgent(agentId);
		return baseMapper.selectByAgentId(agentId);
	}

	@Override
	public List<AgentPresetQuestion> findAllByAgentId(Long agentId) {
		requireOwnedAgent(agentId);
		return baseMapper.selectAllByAgentId(agentId);
	}

	@Override
	public AgentPresetQuestion create(AgentPresetQuestion question) {
		// Ensure default values
		if (question.getSortOrder() == null) {
			question.setSortOrder(0);
		}
		if (question.getIsActive() == null) {
			question.setIsActive(true);
		}

		baseMapper.insert(question);
		return question; // ID will be auto-filled by MyBatis
	}

	@Override
	public void update(Long id, AgentPresetQuestion question) {
		question.setId(id); // Ensure the ID is set
		baseMapper.update(question);
	}

	@Override
	public void deleteById(Long id) {
		AgentPresetQuestion existing = baseMapper.selectById(id);
		if (existing != null) {
			requireOwnedAgent(existing.getAgentId());
		}
		baseMapper.deleteById(id);
	}

	@Override
	public void deleteByAgentId(Long agentId) {
		requireOwnedAgent(agentId);
		baseMapper.deleteByAgentId(agentId);
	}

	@Override
	public void batchSave(Long agentId, List<AgentPresetQuestion> questions) {
		requireOwnedAgent(agentId);
		// Step 1: Delete all existing preset questions for the agent
		baseMapper.deleteByAgentId(agentId);

		// Step 2: Insert new questions with proper order and active status
		for (int i = 0; i < questions.size(); i++) {
			AgentPresetQuestion question = questions.get(i);
			question.setAgentId(agentId);
			question.setSortOrder(i);
			if (question.getIsActive() == null) {
				question.setIsActive(true);
			}
			create(question); // Reuses create() which sets defaults and inserts
		}
	}

	@Override
	public void batchSaveRequests(Long agentId, List<SavePresetQuestionReq> requests) {
		List<AgentPresetQuestion> questions = requests.stream().map(request -> {
			AgentPresetQuestion question = new AgentPresetQuestion();
			question.setQuestion(request.getQuestion());
			question.setIsActive(request.getIsActive() == null || request.getIsActive());
			return question;
		}).toList();
		batchSave(agentId, questions);
	}

	private void requireOwnedAgent(Long agentId) {
		dataAgentService.requireAgent(agentId);
	}

}
