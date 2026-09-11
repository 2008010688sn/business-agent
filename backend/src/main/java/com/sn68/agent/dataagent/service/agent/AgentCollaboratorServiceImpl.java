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

import com.sn68.agent.dataagent.constant.AgentTypeConstant;
import com.sn68.agent.dataagent.dto.agent.AgentCollaboratorResp;
import com.sn68.agent.dataagent.dto.agent.AgentCollaboratorSaveReq;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.entity.AgentCollaborator;
import com.sn68.agent.dataagent.repository.AgentCollaboratorMapper;
import com.sn68.agent.dataagent.routing.CollaboratorCapabilityResolver;
import com.sn68.agent.dataagent.routing.RouteRulesService;
import com.sn68.agent.dataagent.routing.model.DelegationMode;
import com.sn68.agent.dataagent.routing.model.RouteRules;
import com.sn68.agent.dataagent.service.routing.RouteArtifactService;
import com.sn68.agent.framework.commons.exception.CheckedException;
import cn.hutool.core.util.IdUtil;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Agent 协作者管理实现：维护主 Agent 与协作 Agent 的绑定关系及其配置。
 */
@Service
@AllArgsConstructor
public class AgentCollaboratorServiceImpl implements AgentCollaboratorService {

	private final AgentCollaboratorMapper agentCollaboratorMapper;

	private final DataAgentService agentService;

	private final RouteRulesService routeRulesService;

	private final RouteArtifactService routeArtifactService;

	private final CollaboratorCapabilityResolver capabilityResolver;

	private final TransactionTemplate transactionTemplate;

	@Override
	public List<AgentCollaboratorResp> list(Long agentId) {
		agentService.requireAgent(agentId);
		return agentCollaboratorMapper.findByAgentId(agentId).stream().map(this::toDTO).toList();
	}

	@Override
	public List<AgentCollaborator> listEnabled(Long agentId) {
		return agentCollaboratorMapper.findEnabledByAgentId(agentId);
	}

	@Override
	public AgentCollaborator create(Long agentId, AgentCollaborator collaborator) {
		if (collaborator == null) {
			throw CheckedException.badRequest("Collaborator config cannot be null");
		}
		validateOwner(agentId);
		DataAgent target = validateCollaboratorTarget(agentId, collaborator.getCollaboratorAgentId(), true);
		AgentCollaborator existing = agentCollaboratorMapper.findActive(agentId, collaborator.getCollaboratorAgentId());
		if (existing != null) {
			throw CheckedException.badRequest("Collaborator already exists");
		}
		prepareDefaults(agentId, collaborator);
		validateDelegationMode(collaborator, target);
		collaborator.setRoutingRules(routeRulesService.toMap(normalizeCollaboratorRules(collaborator.getRoutingRules())));
		routeArtifactService.prepareCollaborator(collaborator);
		return transactionTemplate.execute(status -> {
			agentCollaboratorMapper.insert(collaborator);
			return agentCollaboratorMapper.selectById(collaborator.getId());
		});
	}

	@Override
	public AgentCollaboratorResp create(Long agentId, AgentCollaboratorSaveReq request) {
		return toDTO(create(agentId, toEntity(request)));
	}

	@Override
	public AgentCollaborator update(Long agentId, Long id, AgentCollaborator collaborator) {
		if (collaborator == null) {
			throw CheckedException.badRequest("Collaborator config cannot be null");
		}
		AgentCollaborator existing = agentCollaboratorMapper.findByIdAndAgentId(id, agentId);
		if (existing == null) {
			throw CheckedException.notFound("Collaborator config does not exist");
		}
		Long collaboratorAgentId = collaborator.getCollaboratorAgentId() == null ? existing.getCollaboratorAgentId()
				: collaborator.getCollaboratorAgentId();
		validateOwner(agentId);
		DataAgent target = validateCollaboratorTarget(agentId, collaboratorAgentId, true);
		AgentCollaborator prospective = new AgentCollaborator();
		prospective.setId(existing.getId());
		prospective.setAgentId(agentId);
		prospective.setCollaboratorAgentId(collaboratorAgentId);
		prospective.setRoleName(collaborator.getRoleName());
		prospective.setCapabilityDescription(collaborator.getCapabilityDescription());
		prospective.setDelegationMode(collaborator.getDelegationMode());
		prospective.setRoutingRules(routeRulesService.toMap(normalizeCollaboratorRules(collaborator.getRoutingRules())));
		prospective.setPriority(defaultInteger(collaborator.getPriority(), 0));
		prospective.setEnabled(collaborator.getEnabled() == null || collaborator.getEnabled());
		validateDelegationMode(prospective, target);
		routeArtifactService.prepareCollaborator(prospective);
		Instant expectedLastModifyTime = existing.getLastModifyTime();
		return transactionTemplate.execute(status -> {
			AgentCollaborator current = agentCollaboratorMapper.findByIdAndAgentId(id, agentId);
			if (current == null || !Objects.equals(expectedLastModifyTime, current.getLastModifyTime())) {
				throw CheckedException.badRequest("Collaborator config changed while route Artifact was building");
			}
			current.setCollaboratorAgentId(prospective.getCollaboratorAgentId());
			current.setRoleName(prospective.getRoleName());
			current.setCapabilityDescription(prospective.getCapabilityDescription());
			current.setDelegationMode(prospective.getDelegationMode());
			current.setRoutingRules(prospective.getRoutingRules());
			current.setPriority(prospective.getPriority());
			current.setEnabled(prospective.getEnabled());
			current.setLastModifyTime(Instant.now());
			agentCollaboratorMapper.updateById(current);
			return agentCollaboratorMapper.selectById(id);
		});
	}

	@Override
	public AgentCollaboratorResp update(Long agentId, Long id, AgentCollaboratorSaveReq request) {
		return toDTO(update(agentId, id, toEntity(request)));
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void delete(Long agentId, Long id) {
		if (agentCollaboratorMapper.softDelete(id, agentId) == 0) {
			throw CheckedException.notFound("Collaborator config does not exist");
		}
	}

	@Override
	public AgentCollaborator requireEnabled(Long agentId, Long collaboratorAgentId) {
		AgentCollaborator collaborator = agentCollaboratorMapper.findActive(agentId, collaboratorAgentId);
		if (collaborator == null || !Boolean.TRUE.equals(collaborator.getEnabled())) {
			throw CheckedException.badRequest("Collaborator is not in enabled whitelist");
		}
		DataAgent target = validateCollaboratorTarget(agentId, collaboratorAgentId, true);
		validateDelegationMode(collaborator, target);
		return collaborator;
	}

	private void validateOwner(Long agentId) {
		DataAgent owner = agentService.requireAgent(agentId);
		if (!AgentTypeConstant.isOrchestrator(owner.getAgentType())) {
			throw CheckedException.badRequest("Only orchestrator Agent can configure collaborators");
		}
	}

	private DataAgent validateCollaboratorTarget(Long agentId, Long collaboratorAgentId, boolean requirePublished) {
		if (collaboratorAgentId == null) {
			throw CheckedException.badRequest("collaboratorAgentId cannot be null");
		}
		if (agentId != null && agentId.equals(collaboratorAgentId)) {
			throw CheckedException.badRequest("Orchestrator cannot call itself");
		}
		DataAgent owner = agentService.requireAgent(agentId);
		DataAgent target = agentService.requireAgent(collaboratorAgentId);
		if (!Objects.equals(owner.getTenantId(), target.getTenantId())) {
			throw CheckedException.forbidden();
		}
		if (AgentTypeConstant.isOrchestrator(target.getAgentType())) {
			throw CheckedException.badRequest("Orchestrator cannot call another orchestrator Agent");
		}
		if (requirePublished && !"published".equalsIgnoreCase(target.getStatus())) {
			throw CheckedException.badRequest("Collaborator Agent must be published");
		}
		return target;
	}

	private void prepareDefaults(Long agentId, AgentCollaborator collaborator) {
		if (collaborator.getId() == null) {
			collaborator.setId(IdUtil.getSnowflakeNextId());
		}
		collaborator.setAgentId(agentId);
		if (collaborator.getDelegationMode() == null || collaborator.getDelegationMode().isBlank()) {
			collaborator.setDelegationMode(DelegationMode.INTERACTIVE.name());
		}
		collaborator.setPriority(defaultInteger(collaborator.getPriority(), 0));
		collaborator.setEnabled(collaborator.getEnabled() == null || collaborator.getEnabled());
		collaborator.setCreateTime(Instant.now());
		collaborator.setLastModifyTime(Instant.now());
		collaborator.setDeleted(false);
	}

	private int defaultInteger(Integer value, int defaultValue) {
		return value == null ? defaultValue : value;
	}

	private AgentCollaborator toEntity(AgentCollaboratorSaveReq request) {
		if (request == null) {
			throw CheckedException.badRequest("Collaborator config cannot be null");
		}
		AgentCollaborator collaborator = new AgentCollaborator();
		collaborator.setId(request.id());
		collaborator.setAgentId(request.agentId());
		collaborator.setCollaboratorAgentId(request.collaboratorAgentId());
		collaborator.setRoleName(request.roleName());
		collaborator.setCapabilityDescription(request.capabilityDescription());
		collaborator.setRoutingRules(request.routingRules());
		collaborator.setPriority(request.priority());
		collaborator.setEnabled(request.enabled());
		collaborator.setDelegationMode(request.delegationMode());
		return collaborator;
	}

	private void validateDelegationMode(AgentCollaborator collaborator, DataAgent target) {
		DelegationMode mode;
		try {
			mode = DelegationMode.resolve(collaborator.getDelegationMode());
			CollaboratorCapabilityResolver.CollaboratorCapability capability = capabilityResolver.resolve(target.getId(),
					target.getTenantId());
			capabilityResolver.requireSupported(mode, capability);
		}
		catch (IllegalArgumentException ex) {
			throw CheckedException.badRequest(ex.getMessage());
		}
		collaborator.setDelegationMode(mode.name());
	}

	private RouteRules normalizeCollaboratorRules(Map<String, Object> rules) {
		RouteRules normalized = routeRulesService.normalize(rules);
		if (normalized.allowFlowAutoSelect()) {
			throw CheckedException.badRequest("allowFlowAutoSelect is not valid for collaborator routing rules");
		}
		return normalized;
	}

	private AgentCollaboratorResp toDTO(AgentCollaborator collaborator) {
		DataAgent dataAgent = agentService.findById(collaborator.getCollaboratorAgentId());
		DelegationMode delegationMode = DelegationMode.resolve(collaborator.getDelegationMode());
		CollaboratorCapabilityResolver.CollaboratorCapability capability = dataAgent == null
				? CollaboratorCapabilityResolver.CollaboratorCapability.unknown()
				: capabilityResolver.resolve(dataAgent.getId(), dataAgent.getTenantId());
		return AgentCollaboratorResp.builder()
			.id(collaborator.getId())
			.agentId(collaborator.getAgentId())
			.collaboratorAgentId(collaborator.getCollaboratorAgentId())
			.roleName(collaborator.getRoleName())
			.capabilityDescription(collaborator.getCapabilityDescription())
			.delegationMode(delegationMode.name())
			.effectiveRisk(capability.effectiveRisk())
			.planConfirmEligible(capability.planConfirmEligible())
			.routingRules(routeRulesService.toResponseDTO(routeRulesService.normalize(collaborator.getRoutingRules())))
			.priority(collaborator.getPriority())
			.enabled(collaborator.getEnabled())
			.collaboratorDataAgent(dataAgent)
			.build();
	}

}
