/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.flow;

import com.sn68.agent.dataagent.dto.ModelConfigDTO;
import com.sn68.agent.dataagent.entity.DataAgentModelStructuredCapability;
import com.sn68.agent.dataagent.enums.ModelReasoningMode;
import com.sn68.agent.dataagent.enums.ModelReasoningProtocol;
import com.sn68.agent.dataagent.enums.ModelType;
import com.sn68.agent.dataagent.repository.DataAgentModelStructuredCapabilityMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Flow-only cache of protocols proven by real extraction calls. It deliberately
 * does not run probes, schedule work, or participate in normal chat routing.
 */
@Service
@Slf4j
public class FlowStructuredCapabilityService {

	private static final String PROFILE = StructuredTaskProfile.FLOW_PATCH_V1.name();

	private static final String CACHE_VERSION = "flow-runtime-negotiation/v1";

	private final DataAgentModelStructuredCapabilityMapper capabilityMapper;

	private final FlowStructuredCapabilityFingerprint fingerprint;

	private final FlowSchemaCompiler schemaCompiler;

	private final ConcurrentHashMap<String, StructuredCapabilityState> runtimeStates = new ConcurrentHashMap<>();

	public FlowStructuredCapabilityService(DataAgentModelStructuredCapabilityMapper capabilityMapper,
			FlowStructuredCapabilityFingerprint fingerprint, FlowSchemaCompiler schemaCompiler) {
		this.capabilityMapper = capabilityMapper;
		this.fingerprint = fingerprint;
		this.schemaCompiler = schemaCompiler;
	}

	/**
	 * Returns protocols for one real FLOW extraction attempt. Previously proven
	 * protocols are preferred; known unsupported protocols are skipped.
	 */
	public List<FlowStructuredOutputProtocol> candidateProtocols(ModelConfigDTO modelConfig) {
		if (!isChat(modelConfig) || modelConfig.getId() == null) {
			return List.of();
		}
		EnumMap<FlowStructuredOutputProtocol, StructuredCapabilityState> states = states(modelConfig.getId(),
				fingerprint.calculate(modelConfig));
		List<FlowStructuredOutputProtocol> candidates = new ArrayList<>();
		for (FlowStructuredOutputProtocol protocol : protocols(modelConfig)) {
			if (states.get(protocol) == StructuredCapabilityState.SUPPORTED) {
				candidates.add(protocol);
			}
		}
		for (FlowStructuredOutputProtocol protocol : protocols(modelConfig)) {
			StructuredCapabilityState state = states.get(protocol);
			if (state != StructuredCapabilityState.SUPPORTED && state != StructuredCapabilityState.UNSUPPORTED) {
				candidates.add(protocol);
			}
		}
		return List.copyOf(candidates);
	}

	public void recordSuccess(ModelConfigDTO modelConfig, FlowStructuredOutputProtocol protocol) {
		persist(modelConfig, protocol, StructuredCapabilityState.SUPPORTED, null);
	}

	public void recordProtocolRejected(ModelConfigDTO modelConfig, FlowStructuredOutputProtocol protocol) {
		persist(modelConfig, protocol, StructuredCapabilityState.UNSUPPORTED,
				FlowExtractionException.PROTOCOL_INCOMPATIBLE);
	}

	private void persist(ModelConfigDTO modelConfig, FlowStructuredOutputProtocol protocol,
			StructuredCapabilityState state, String failureCode) {
		if (!isChat(modelConfig) || modelConfig.getId() == null || protocol == null
				|| protocol == FlowStructuredOutputProtocol.NONE) {
			return;
		}
		String modelFingerprint = fingerprint.calculate(modelConfig);
		remember(modelConfig.getId(), modelFingerprint, protocol, state);
		DataAgentModelStructuredCapability record = findRecord(modelConfig.getId(), modelFingerprint, protocol);
		if (record == null) {
			try {
				capabilityMapper.insert(DataAgentModelStructuredCapability.builder()
					.modelConfigId(modelConfig.getId())
					.taskProfile(PROFILE)
					.protocol(protocol.name())
					.modelFingerprint(modelFingerprint)
					.compilerVersion(schemaCompiler.version())
					.probeVersion(CACHE_VERSION)
					.state(state.name())
					.attemptCount(1)
					.checkedAt(Instant.now())
					.failureCode(failureCode)
					.build());
				return;
			}
			catch (RuntimeException ex) {
				// Another request may have inserted the same unique cache row.
				record = findRecord(modelConfig.getId(), modelFingerprint, protocol);
				if (record == null) {
					log.debug("FLOW protocol cache outcome was not persisted. modelConfigId={}, protocol={}",
							modelConfig.getId(), protocol, ex);
					return;
				}
			}
		}
		record.setState(state.name());
		record.setCheckedAt(Instant.now());
		record.setExpiresAt(null);
		record.setNextAttemptAt(null);
		record.setClaimLease(null);
		record.setLeaseUntil(null);
		record.setAttemptCount(record.getAttemptCount() == null ? 1 : record.getAttemptCount() + 1);
		record.setLatencyMs(null);
		record.setPlatformTokens(null);
		record.setFailureCode(failureCode);
		try {
			capabilityMapper.updateById(record);
		}
		catch (RuntimeException ex) {
			log.debug("FLOW protocol cache outcome update failed. modelConfigId={}, protocol={}", modelConfig.getId(),
					protocol, ex);
		}
	}

	private DataAgentModelStructuredCapability findRecord(Long modelConfigId, String modelFingerprint,
			FlowStructuredOutputProtocol protocol) {
		return currentRecords(modelConfigId, modelFingerprint).stream()
			.filter(record -> protocol.name().equals(record.getProtocol()))
			.findFirst()
			.orElse(null);
	}

	private EnumMap<FlowStructuredOutputProtocol, StructuredCapabilityState> states(Long modelConfigId,
			String modelFingerprint) {
		EnumMap<FlowStructuredOutputProtocol, StructuredCapabilityState> states = new EnumMap<>(
				FlowStructuredOutputProtocol.class);
		for (DataAgentModelStructuredCapability record : currentRecords(modelConfigId, modelFingerprint)) {
			FlowStructuredOutputProtocol protocol = parseProtocol(record.getProtocol());
			StructuredCapabilityState state = parseState(record.getState());
			if (protocol != FlowStructuredOutputProtocol.NONE && state != null) {
				states.put(protocol, state);
			}
		}
		for (FlowStructuredOutputProtocol protocol : allProtocols()) {
			StructuredCapabilityState remembered = runtimeStates.get(runtimeKey(modelConfigId, modelFingerprint, protocol));
			if (remembered != null) {
				states.put(protocol, remembered);
			}
		}
		return states;
	}

	private void remember(Long modelConfigId, String modelFingerprint, FlowStructuredOutputProtocol protocol,
			StructuredCapabilityState state) {
		if (modelConfigId == null || protocol == null || state == null) {
			return;
		}
		runtimeStates.put(runtimeKey(modelConfigId, modelFingerprint, protocol), state);
	}

	private static String runtimeKey(Long modelConfigId, String modelFingerprint, FlowStructuredOutputProtocol protocol) {
		return modelConfigId + ":" + firstText(modelFingerprint) + ":" + protocol.name();
	}

	private static String firstText(String value) {
		return value == null ? "" : value;
	}

	private List<DataAgentModelStructuredCapability> currentRecords(Long modelConfigId, String modelFingerprint) {
		List<DataAgentModelStructuredCapability> records = capabilityMapper.findCurrent(modelConfigId, PROFILE,
				modelFingerprint, schemaCompiler.version(), CACHE_VERSION);
		return records == null ? List.of() : records;
	}

	private List<FlowStructuredOutputProtocol> protocols(ModelConfigDTO modelConfig) {
		if (thinkingBlocksStructuredTools(modelConfig)) {
			return List.of(FlowStructuredOutputProtocol.JSON_OBJECT);
		}
		return allProtocols();
	}

	private List<FlowStructuredOutputProtocol> allProtocols() {
		return List.of(FlowStructuredOutputProtocol.FUNCTION_CALL, FlowStructuredOutputProtocol.STRICT_JSON_SCHEMA,
				FlowStructuredOutputProtocol.JSON_OBJECT);
	}

	private boolean thinkingBlocksStructuredTools(ModelConfigDTO modelConfig) {
		if (modelConfig == null) {
			return false;
		}
		if (ModelReasoningMode.ENABLED.name().equalsIgnoreCase(trimToEmpty(modelConfig.getReasoningMode()))) {
			return true;
		}
		String protocol = trimToEmpty(modelConfig.getReasoningProtocol()).toUpperCase(Locale.ROOT);
		return ModelReasoningProtocol.THINKING_OBJECT.name().equals(protocol)
				|| ModelReasoningProtocol.THINKING_OBJECT_WITH_EFFORT.name().equals(protocol)
				|| ModelReasoningProtocol.ENABLE_THINKING.name().equals(protocol)
				|| ModelReasoningProtocol.ENABLE_THINKING_ONLY.name().equals(protocol);
	}

	private static String trimToEmpty(String value) {
		return value == null ? "" : value.trim();
	}

	private FlowStructuredOutputProtocol parseProtocol(String value) {
		try {
			return FlowStructuredOutputProtocol.valueOf(value == null ? "NONE" : value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			log.warn("Unknown FLOW structured output protocol, falling back to NONE. protocol={}", value);
			return FlowStructuredOutputProtocol.NONE;
		}
	}

	private StructuredCapabilityState parseState(String value) {
		try {
			return StructuredCapabilityState.valueOf(value == null ? "" : value.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private boolean isChat(ModelConfigDTO config) {
		return config != null && config.getModelType() != null && ModelType.CHAT.getCode().equalsIgnoreCase(config.getModelType());
	}

}
