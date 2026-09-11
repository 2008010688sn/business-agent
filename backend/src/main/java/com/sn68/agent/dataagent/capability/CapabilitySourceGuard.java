/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.sn68.agent.dataagent.capability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.agentscope.tool.AgentModelToolName;
import com.sn68.agent.dataagent.entity.AgentExecutionResource;
import com.sn68.agent.dataagent.repository.AgentExecutionResourceMapper;
import com.sn68.agent.framework.commons.exception.CheckedException;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 能力来源白名单守卫。按方案「能力市场不得允许任意 Shell、任意 SQL、未声明网络地址」的精神，
 * 集中承载两类白名单规则：
 * <ul>
 * <li>能力编码必须在工具目录（agent_execution_resource）中声明且启用，未声明一律拒绝；</li>
 * <li>参数不得携带未声明网络地址：任何位置出现 jdbc 等连接串一律拒绝；指向网络端点的参数键
 * （url/endpoint/webhook/callback 等）携带的地址主机必须与能力声明的 endpointUrl/baseUrl
 * 或资源 extConfig 中 {@code trustedHosts} 声明的附加可信主机一致。</li>
 * </ul>
 * 执行传输层的出网目标只由资源配置决定、参数无法改写，因此自由文本参数中的普通链接不在拒绝
 * 范围内，只拦截「用参数指定网络端点」的行为，避免误伤引用了链接的正常业务文本。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CapabilitySourceGuard {

	private static final int MAX_CODE_LENGTH = 128;

	private static final int MAX_SCAN_DEPTH = 32;

	private static final Pattern CODE_PATTERN = Pattern.compile("[\\w./:-]+");

	private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(?:https?|wss?|ftp)://([^/\\s:?#]+)");

	/** 数据库/中间件连接串前缀：参数携带即视为企图指定未声明的数据源或网络地址。 */
	private static final Pattern CONNECTION_STRING_PATTERN = Pattern
		.compile("(?i)\\b(?:jdbc:|redis://|rediss://|mongodb(?:\\+srv)?://|amqp://|ldap://)");

	/** 表达「网络端点」语义的参数键片段：命中且值携带未声明地址时拒绝。 */
	private static final Set<String> ENDPOINT_KEY_TOKENS = Set.of("url", "uri", "endpoint", "webhook", "callback",
			"host", "address", "proxy");

	/**
	 * 资源 extConfig（JSON 对象）中声明附加可信主机的字段名，值为字符串数组，元素可为主机名
	 * 或完整 URL（取其 host），如 {@code {"trustedHosts":["open.example.com","https://cb.example.com"]}}。
	 * 无声明时行为不变，只认 endpointUrl/baseUrl。
	 */
	private static final String EXT_CONFIG_TRUSTED_HOSTS = "trustedHosts";

	private final AgentExecutionResourceMapper resourceMapper;

	private final ObjectMapper objectMapper;

	/**
	 * 校验能力编码已在工具目录中声明且启用，返回目录中的执行资源；未声明一律拒绝。
	 */
	public AgentExecutionResource requireDeclaredResource(InvocationRequest request) {
		requireCapabilityCode(request);
		AgentExecutionResource resource = resourceMapper.findEnabledByResourceKey(request.capabilityCode());
		if (resource == null) {
			throw CheckedException.forbidden(
					"能力来源白名单检查拒绝：能力编码未在工具目录中声明或已停用，capabilityCode=" + request.capabilityCode());
		}
		return resource;
	}

	/**
	 * 校验能力编码非空且形状合法（进程内能力没有目录条目，但编码仍需可审计、可追溯）。
	 */
	public void requireCapabilityCode(InvocationRequest request) {
		String code = request == null ? null : request.capabilityCode();
		if (!StringUtils.hasText(code) || code.trim().length() > MAX_CODE_LENGTH
				|| !CODE_PATTERN.matcher(code.trim()).matches()) {
			throw CheckedException.badRequest("能力来源白名单检查拒绝：能力编码为空或格式非法");
		}
	}

	/**
	 * 校验参数中不出现未声明网络地址。声明地址来自能力在工具目录中的 endpointUrl/baseUrl，
	 * 以及资源 extConfig 中 {@code trustedHosts} 声明的附加可信主机；进程内能力没有声明地址，
	 * 端点类参数键携带任何网络地址都会被拒绝。
	 */
	public void requireDeclaredNetworkAddresses(InvocationRequest request, AgentExecutionResource resource) {
		if (isWebFetchCapability(request)) {
			return;
		}
		Map<String, Object> arguments = request == null ? null : request.arguments();
		if (arguments == null || arguments.isEmpty()) {
			return;
		}
		scanValue(null, arguments, declaredHosts(resource), 0);
	}

	/**
	 * 定 B：仅 {@code capabilityCode} 精确等于 {@code web_fetch} 时跳过 declared-hosts。
	 * 禁止 {@code web_*} 通配；出网仍由工具内 SSRF 硬底线负责。
	 */
	private boolean isWebFetchCapability(InvocationRequest request) {
		return request != null && AgentModelToolName.WEB_FETCH.equals(request.capabilityCode());
	}

	private void scanValue(String key, Object value, Set<String> declaredHosts, int depth) {
		if (value == null) {
			return;
		}
		if (depth > MAX_SCAN_DEPTH) {
			throw CheckedException.badRequest("能力来源白名单检查拒绝：参数嵌套层级过深，无法完成网络地址检查");
		}
		if (value instanceof String text) {
			checkText(key, text, declaredHosts);
			return;
		}
		if (value instanceof Map<?, ?> map) {
			map.forEach((itemKey, itemValue) -> scanValue(itemKey == null ? key : String.valueOf(itemKey), itemValue,
					declaredHosts, depth + 1));
			return;
		}
		if (value instanceof Collection<?> collection) {
			collection.forEach(item -> scanValue(key, item, declaredHosts, depth + 1));
		}
	}

	private void checkText(String key, String text, Set<String> declaredHosts) {
		if (!StringUtils.hasText(text)) {
			return;
		}
		if (CONNECTION_STRING_PATTERN.matcher(text).find()) {
			throw CheckedException.forbidden(
					"能力来源白名单检查拒绝：参数不允许携带数据库或中间件连接串（未声明网络地址），parameterKey=" + key);
		}
		if (!isEndpointKey(key)) {
			return;
		}
		Matcher matcher = URL_PATTERN.matcher(text);
		while (matcher.find()) {
			String host = matcher.group(1).toLowerCase(Locale.ROOT);
			if (!declaredHosts.contains(host)) {
				// 只记主机名，不落完整地址：完整 URL 可能携带业务查询参数。
				throw CheckedException.forbidden("能力来源白名单检查拒绝：参数携带未声明网络地址，parameterKey=" + key
						+ ", host=" + host);
			}
		}
	}

	private boolean isEndpointKey(String key) {
		if (!StringUtils.hasText(key)) {
			return false;
		}
		String normalized = key.toLowerCase(Locale.ROOT);
		return ENDPOINT_KEY_TOKENS.stream().anyMatch(normalized::contains);
	}

	private Set<String> declaredHosts(AgentExecutionResource resource) {
		Set<String> hosts = new LinkedHashSet<>();
		if (resource == null) {
			return hosts;
		}
		addHost(hosts, resource.getEndpointUrl());
		addHost(hosts, resource.getBaseUrl());
		addExtConfigTrustedHosts(hosts, resource);
		return hosts;
	}

	/**
	 * 解析 extConfig 声明的附加可信主机并入白名单。失败关闭：解析失败只损失附加声明
	 * （回到仅认 endpointUrl/baseUrl），绝不因脏配置放宽校验。
	 */
	private void addExtConfigTrustedHosts(Set<String> hosts, AgentExecutionResource resource) {
		if (!StringUtils.hasText(resource.getExtConfig())) {
			return;
		}
		try {
			JsonNode declared = objectMapper.readTree(resource.getExtConfig()).path(EXT_CONFIG_TRUSTED_HOSTS);
			if (!declared.isArray()) {
				return;
			}
			for (JsonNode item : declared) {
				if (!item.isTextual() || !StringUtils.hasText(item.asText())) {
					continue;
				}
				String value = item.asText().trim();
				if (value.contains("://")) {
					addHost(hosts, value);
				}
				else {
					hosts.add(value.toLowerCase(Locale.ROOT));
				}
			}
		}
		catch (Exception ex) {
			log.warn("解析资源 extConfig 附加可信主机失败, 忽略附加声明只认 endpointUrl/baseUrl. resourceKey={}",
					resource.getResourceKey());
		}
	}

	private void addHost(Set<String> hosts, String url) {
		if (!StringUtils.hasText(url)) {
			return;
		}
		try {
			String host = URI.create(url.trim()).getHost();
			if (StringUtils.hasText(host)) {
				hosts.add(host.toLowerCase(Locale.ROOT));
			}
		}
		catch (RuntimeException ex) {
			log.debug("解析能力声明地址失败，忽略该声明地址. url 长度={}", url.length());
		}
	}

}
