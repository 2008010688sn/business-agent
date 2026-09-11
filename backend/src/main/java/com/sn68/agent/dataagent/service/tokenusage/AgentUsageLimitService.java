/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.service.tokenusage;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sn68.agent.dataagent.agentscope.runtime.AgentRuntimeErrorCode;
import com.sn68.agent.dataagent.dto.tokenusage.AgentUsageLimitPolicyQueryReq;
import com.sn68.agent.dataagent.dto.tokenusage.AgentUsageLimitPolicyReq;
import com.sn68.agent.dataagent.entity.AgentUsageLimitPolicy;
import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.repository.AgentUsageLimitPolicyMapper;
import com.sn68.agent.dataagent.repository.DataAgentMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent用量Limit组件，封装 DataAgent 对应业务入口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentUsageLimitService {

	public static final String POLICY_TOKEN_QUOTA = "TOKEN_QUOTA";

	public static final String POLICY_TOKEN_RATE = "TOKEN_RATE";

	public static final String POLICY_REQUEST_RATE = "REQUEST_RATE";

	public static final String ACTION_WARN = "WARN";

	public static final String ACTION_BLOCK = "BLOCK";

	private static final ZoneId WINDOW_ZONE = ZoneId.of("Asia/Shanghai");

	private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

	private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");

	private static final DefaultRedisScript<Long> INCREMENT_WITH_EXPIRE_SCRIPT = new DefaultRedisScript<>(
			"local current = redis.call('INCRBY', KEYS[1], ARGV[1]); "
					+ "local ttl = tonumber(ARGV[2]); "
					+ "if ttl and ttl > 0 then redis.call('EXPIRE', KEYS[1], ttl); end; "
					+ "return current",
			Long.class);

	private final AgentUsageLimitPolicyMapper policyMapper;

	private final DataAgentMapper dataAgentMapper;

	private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

	/**
	 * 处理Agent用量Limit。
	 */
	public AgentUsageReservation preCheckAndReserve(AgentTokenUsageContext context, long estimatedTokens) {
		List<AgentUsageReservation.Entry> reserved = new ArrayList<>();
		List<AgentUsageLimitPolicy> policies = listMatchedPolicies(context);
		if (policies.isEmpty()) {
			return AgentUsageReservation.empty();
		}
		StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
		if (redisTemplate == null) {
			throw CheckedException.badRequest(AgentRuntimeErrorCode.UPSTREAM_UNAVAILABLE.getValue()
					+ ": Redis 不可用，无法执行 DataAgent 用量限制策略。");
		}
		try {
			for (AgentUsageLimitPolicy policy : policies) {
				long amount = reserveAmount(policy, estimatedTokens);
				if (amount <= 0L) {
					continue;
				}
				Window window = resolveWindow(policy);
				String key = redisKey(policy, context, window.windowStart());
				Long current = incrementWithExpire(redisTemplate, key, amount, window.ttlSeconds());
				boolean tokenPolicy = !POLICY_REQUEST_RATE.equalsIgnoreCase(policy.getPolicyType());
				reserved.add(new AgentUsageReservation.Entry(policy.getId(), key, amount, tokenPolicy));
				if (current != null && policy.getLimitValue() != null && current > policy.getLimitValue()
						&& ACTION_BLOCK.equalsIgnoreCase(policy.getAction())) {
					throw limitExceeded(policy);
				}
			}
			return new AgentUsageReservation(List.copyOf(reserved));
		}
		catch (RuntimeException ex) {
			release(redisTemplate, reserved);
			throw ex;
		}
	}

	public void settleActualUsage(AgentUsageReservation reservation, long actualTokens) {
		if (reservation == null || reservation.isEmpty()) {
			return;
		}
		StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
		if (redisTemplate == null) {
			return;
		}
		for (AgentUsageReservation.Entry entry : reservation.entries()) {
			if (!entry.tokenPolicy()) {
				continue;
			}
			long delta = actualTokens - entry.reservedAmount();
			if (delta != 0L) {
				redisTemplate.opsForValue().increment(entry.key(), delta);
			}
		}
	}

	/**
	 * 执行Agent用量Limit。
	 */
	public void releaseReservation(AgentUsageReservation reservation) {
		if (reservation == null || reservation.isEmpty()) {
			return;
		}
		StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
		if (redisTemplate == null) {
			return;
		}
		List<AgentUsageReservation.Entry> releasable = reservation.entries()
			.stream()
			.filter(AgentUsageReservation.Entry::tokenPolicy)
			.toList();
		release(redisTemplate, releasable);
	}

	/**
	 * 查询Agent用量Limit。
	 */
	public List<AgentUsageLimitPolicy> listMatchedPolicies(AgentTokenUsageContext context) {
		return policyMapper.listEnabled().stream().filter(policy -> evaluatePolicy(policy, context)).toList();
	}

	/**
	 * 执行Agent用量Limit。
	 */
	public boolean evaluatePolicy(AgentUsageLimitPolicy policy, AgentTokenUsageContext context) {
		return matchesScope(policy, context)
				&& (policy.getAgentId() == null || policy.getAgentId().equals(context == null ? null : context.agentId()))
				&& (policy.getModelConfigId() == null
						|| policy.getModelConfigId().equals(context == null ? null : context.modelConfigId()));
	}

	/**
	 * 查询Agent用量Limit。
	 */
	public IPage<AgentUsageLimitPolicy> queryPoliciesPage(AgentUsageLimitPolicyQueryReq request) {
		AgentUsageLimitPolicyQueryReq query = request == null ? new AgentUsageLimitPolicyQueryReq() : request;
		return policyMapper.selectPolicyPage(query.buildPage(), query);
	}

	/**
	 * 创建Agent用量Limit。
	 */
	public AgentUsageLimitPolicy createPolicy(AgentUsageLimitPolicyReq request) {
		AgentUsageLimitPolicy policy = toPolicy(new AgentUsageLimitPolicy(), request);
		Instant now = Instant.now();
		policy.setCreateTime(now);
		policy.setLastModifyTime(now);
		policy.setDeleted(false);
		policyMapper.insert(policy);
		return policyMapper.selectById(policy.getId());
	}

	/**
	 * 保存Agent用量Limit。
	 */
	public AgentUsageLimitPolicy updatePolicy(Long id, AgentUsageLimitPolicyReq request) {
		AgentUsageLimitPolicy policy = loadActivePolicy(id);
		toPolicy(policy, request);
		policy.setLastModifyTime(Instant.now());
		policyMapper.updateById(policy);
		return policyMapper.selectById(id);
	}

	/**
	 * 保存Agent用量Limit。
	 */
	public void updatePolicyStatus(Long id, Boolean enabled) {
		AgentUsageLimitPolicy policy = loadActivePolicy(id);
		policy.setEnabled(Boolean.TRUE.equals(enabled));
		policy.setLastModifyTime(Instant.now());
		policyMapper.updateById(policy);
	}

	/**
	 * 清理Agent用量Limit。
	 */
	public void deletePolicy(Long id) {
		AgentUsageLimitPolicy policy = loadActivePolicy(id);
		policy.setDeleted(true);
		policy.setEnabled(false);
		policy.setLastModifyTime(Instant.now());
		policyMapper.updateById(policy);
	}

	private void release(StringRedisTemplate redisTemplate, List<AgentUsageReservation.Entry> entries) {
		for (AgentUsageReservation.Entry entry : entries) {
			if (entry.reservedAmount() > 0L) {
				redisTemplate.opsForValue().increment(entry.key(), -entry.reservedAmount());
			}
		}
	}

	private Long incrementWithExpire(StringRedisTemplate redisTemplate, String key, long amount, long ttlSeconds) {
		return redisTemplate.execute(INCREMENT_WITH_EXPIRE_SCRIPT, List.of(key), String.valueOf(amount),
				String.valueOf(ttlSeconds));
	}

	private boolean matchesScope(AgentUsageLimitPolicy policy, AgentTokenUsageContext context) {
		if (policy == null || context == null || !StringUtils.hasText(policy.getScopeType())) {
			return false;
		}
		String scopeType = policy.getScopeType().trim().toUpperCase();
		String scopeId = normalizeScopeId(policy.getScopeId());
		if ("GLOBAL".equals(scopeType)) {
			return true;
		}
		if ("TENANT".equals(scopeType)) {
			return StringUtils.hasText(context.tenantId()) && context.tenantId().equals(scopeId);
		}
		if ("USER".equals(scopeType)) {
			return StringUtils.hasText(context.userId()) && context.userId().equals(scopeId);
		}
		return false;
	}

	private AgentUsageLimitPolicy loadActivePolicy(Long id) {
		if (id == null) {
			throw CheckedException.badRequest("策略ID不能为空");
		}
		AgentUsageLimitPolicy policy = policyMapper.selectById(id);
		if (policy == null || Boolean.TRUE.equals(policy.getDeleted())) {
			throw CheckedException.notFound("用量限制策略不存在");
		}
		return policy;
	}

	private AgentUsageLimitPolicy toPolicy(AgentUsageLimitPolicy policy, AgentUsageLimitPolicyReq request) {
		if (request == null) {
			throw CheckedException.badRequest("策略配置不能为空");
		}
		String scopeType = normalizeUpper(request.getScopeType());
		if (!List.of("GLOBAL", "TENANT", "USER").contains(scopeType)) {
			throw CheckedException.badRequest("策略范围类型不合法");
		}
		String policyType = normalizeUpper(request.getPolicyType());
		if (!List.of(POLICY_TOKEN_QUOTA, POLICY_TOKEN_RATE, POLICY_REQUEST_RATE).contains(policyType)) {
			throw CheckedException.badRequest("策略类型不合法");
		}
		String windowType = normalizeUpper(firstText(request.getWindowType(), "DAY"));
		if (!List.of("DAY", "MONTH", "ROLLING").contains(windowType)) {
			throw CheckedException.badRequest("窗口类型不合法");
		}
		Long limitValue = request.getLimitValue();
		if (limitValue == null || limitValue < 1L) {
			throw CheckedException.badRequest("限制值必须大于0");
		}
		if ("ROLLING".equals(windowType) && (request.getWindowSeconds() == null || request.getWindowSeconds() < 1L)) {
			throw CheckedException.badRequest("滚动窗口秒数必须大于0");
		}
		String action = normalizeUpper(firstText(request.getAction(), ACTION_BLOCK));
		if (!List.of(ACTION_WARN, ACTION_BLOCK).contains(action)) {
			throw CheckedException.badRequest("限制动作不合法");
		}
		String scopeId = "GLOBAL".equals(scopeType) ? "*" : requiredScopeId(request.getScopeId());
		policy.setScopeType(scopeType);
		policy.setScopeId(scopeId);
		policy.setUserNickName("USER".equals(scopeType) ? resolveUserNickName(scopeId) : null);
		policy.setAgentId(request.getAgentId());
		policy.setAgentName(resolveAgentName(request.getAgentId()));
		policy.setModelConfigId(request.getModelConfigId());
		policy.setPolicyType(policyType);
		policy.setWindowType(windowType);
		policy.setWindowSeconds(request.getWindowSeconds());
		policy.setLimitValue(limitValue);
		policy.setWarnThresholdRatio(request.getWarnThresholdRatio());
		policy.setAction(action);
		policy.setEnabled(request.getEnabled() == null ? true : request.getEnabled());
		policy.setDescription(request.getDescription());
		return policy;
	}

	private String resolveUserNickName(String userId) {
		return null;
	}

	private String resolveAgentName(Long agentId) {
		if (agentId == null) {
			return null;
		}
		try {
			DataAgent agent = dataAgentMapper.findById(agentId);
			return agent == null ? null : firstText(agent.getName());
		}
		catch (RuntimeException ex) {
			log.warn("Failed to resolve the agent name for the usage limit view. agentId={}", agentId, ex);
			return null;
		}
	}

	private long reserveAmount(AgentUsageLimitPolicy policy, long estimatedTokens) {
		if (policy == null || !StringUtils.hasText(policy.getPolicyType())) {
			return 0L;
		}
		if (POLICY_REQUEST_RATE.equalsIgnoreCase(policy.getPolicyType())) {
			return 1L;
		}
		return Math.max(1L, estimatedTokens);
	}

	private CheckedException limitExceeded(AgentUsageLimitPolicy policy) {
		AgentRuntimeErrorCode code = POLICY_REQUEST_RATE.equalsIgnoreCase(policy.getPolicyType())
				? AgentRuntimeErrorCode.RATE_LIMITED : AgentRuntimeErrorCode.QUOTA_EXHAUSTED;
		return CheckedException.badRequest(code.getValue() + ": " + code.getLabel());
	}

	private String redisKey(AgentUsageLimitPolicy policy, AgentTokenUsageContext context, String windowStart) {
		String prefix = switch (policy.getPolicyType() == null ? "" : policy.getPolicyType().toUpperCase()) {
			case POLICY_REQUEST_RATE -> "dataagent:usage:request-rate";
			case POLICY_TOKEN_RATE -> "dataagent:usage:token-rate";
			default -> "dataagent:usage:quota";
		};
		String scopeId = resolveRuntimeScopeId(policy, context);
		String agent = context == null || context.agentId() == null ? "all" : String.valueOf(context.agentId());
		String model = context == null || context.modelConfigId() == null ? "all" : String.valueOf(context.modelConfigId());
		return String.join(":", prefix, String.valueOf(policy.getId()), windowStart, scopeId, agent, model);
	}

	private String resolveRuntimeScopeId(AgentUsageLimitPolicy policy, AgentTokenUsageContext context) {
		if (policy == null || !StringUtils.hasText(policy.getScopeType())) {
			return "*";
		}
		return switch (policy.getScopeType().trim().toUpperCase()) {
			case "TENANT" -> safeKeyPart(context == null ? null : context.tenantId());
			case "USER" -> safeKeyPart(context == null ? null : context.userId());
			default -> "*";
		};
	}

	private Window resolveWindow(AgentUsageLimitPolicy policy) {
		ZonedDateTime now = ZonedDateTime.now(WINDOW_ZONE);
		String type = policy == null || policy.getWindowType() == null ? "DAY" : policy.getWindowType().trim().toUpperCase();
		if ("MONTH".equals(type)) {
			YearMonth month = YearMonth.from(now);
			ZonedDateTime nextMonth = month.plusMonths(1).atDay(1).atStartOfDay(WINDOW_ZONE);
			return new Window(month.format(MONTH_FORMATTER), ttlSeconds(now, nextMonth));
		}
		if ("ROLLING".equals(type)) {
			long windowSeconds = policy == null || policy.getWindowSeconds() == null || policy.getWindowSeconds() <= 0L
					? 3600L : policy.getWindowSeconds();
			long epoch = now.toEpochSecond();
			long windowStart = epoch - (epoch % windowSeconds);
			return new Window(String.valueOf(windowStart), windowSeconds + 60L);
		}
		LocalDate date = now.toLocalDate();
		ZonedDateTime nextDay = date.plusDays(1).atStartOfDay(WINDOW_ZONE);
		return new Window(date.format(DAY_FORMATTER), ttlSeconds(now, nextDay));
	}

	private long ttlSeconds(ZonedDateTime now, ZonedDateTime end) {
		return Math.max(60L, Duration.between(now, end).getSeconds() + 60L);
	}

	private String requiredScopeId(String value) {
		if (!StringUtils.hasText(value)) {
			throw CheckedException.badRequest("租户或用户范围策略必须填写 scopeId");
		}
		return value.trim();
	}

	private String normalizeUpper(String value) {
		return StringUtils.hasText(value) ? value.trim().toUpperCase() : "";
	}

	private String normalizeScopeId(String value) {
		return StringUtils.hasText(value) ? value.trim() : "*";
	}

	private String firstText(String... values) {
		if (values == null) {
			return null;
		}
		for (String value : values) {
			if (StringUtils.hasText(value)) {
				return value.trim();
			}
		}
		return null;
	}

	private String safeKeyPart(String value) {
		return StringUtils.hasText(value) ? value.trim() : "_";
	}

	private record Window(String windowStart, long ttlSeconds) {
	}

}
