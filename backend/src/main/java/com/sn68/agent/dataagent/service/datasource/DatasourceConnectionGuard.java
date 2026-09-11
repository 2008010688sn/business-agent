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
package com.sn68.agent.dataagent.service.datasource;

import com.sn68.agent.framework.commons.exception.CheckedException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;

/**
 * 数据源连接要素守卫（PRD S-2）。
 *
 * <p>JDBC 连接串由服务端按固定模板组装，本类负责保证模板里插入的三个变量（host / port / databaseName）
 * 不能改变连接串的结构或语义：字符集被收窄到「合法主机名 / 点分 IPv4」与「库名可用字符」，因此调用方无法
 * 借 {@code ?}、{@code &}、{@code ;} 追加任何驱动参数——这就是「拒绝调用方提供的 JDBC 参数」的落点。
 *
 * <p><b>为什么是黑名单而不是白名单</b>：数据源是租户自带的外部数据库，主机清单随客户开通实时变化，
 * 编译期无从枚举；可配置白名单需要产品与运维先定义「谁能登记哪些网段」，属未决事项。因此这里退一步，
 * 拒绝那些<b>永远不可能是合法业务数据库</b>的地址：回环、本网络、链路本地（含 169.254.169.254）、
 * 组播/广播，以及各云厂商的实例元数据端点——它们只可能出现在 SSRF 载荷里。
 */
public final class DatasourceConnectionGuard {

	/**
	 * 单个标签最长 63 字符、整体不接受下划线与尾点；关键是不含 {@code :/?&;#@%[]} 与空白，
	 * 否则 host 就能把自己拼成一段带参数的 URL。
	 */
	private static final Pattern HOSTNAME_PATTERN = Pattern
		.compile("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");

	/**
	 * 纯数字与点组成的 host 必须是严格点分四段 IPv4。Java 17 的 {@code InetAddress} 仍接受
	 * {@code 2130706433}、{@code 0177.0.0.1} 这类 inet_aton 变体，放行就等于给回环黑名单开后门。
	 */
	private static final Pattern NUMERIC_HOST_PATTERN = Pattern.compile("^[0-9.]+$");

	private static final Pattern IPV4_PATTERN = Pattern
		.compile("^(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)){3}$");

	/** 库名进入 URL 路径段（SQL Server 与 H2 则进入 {@code ;} 分隔的属性段），字符集必须比 host 更保守。 */
	private static final Pattern DATABASE_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_.\\-|]{1,255}$");

	private static final int MAX_HOST_LENGTH = 255;

	private static final int MIN_PORT = 1;

	private static final int MAX_PORT = 65535;

	private static final Set<String> DENIED_HOSTS = Set.of("localhost", "localhost.localdomain", "ip6-localhost",
			"ip6-loopback", "metadata", "metadata.google.internal", "metadata.goog", "metadata.tencentyun.com",
			"instance-data", "instance-data.ec2.internal");

	private static final String LOCALHOST_SUFFIX = ".localhost";

	/** 阿里云实例元数据端点，不落在链路本地网段内，需要单列。 */
	private static final String ALIYUN_METADATA_IP = "100.100.100.200";

	private static final Set<String> ALLOWED_JDBC_SCHEMES = Set.of("mysql", "postgresql", "oracle", "sqlserver", "dm",
			"hive2", "h2");

	/**
	 * 只可能出现在攻击载荷里的驱动参数：MySQL 侧用于读取服务端文件与触发反序列化，PostgreSQL 侧用于加载
	 * 任意工厂类，H2 侧用于建库时执行脚本。服务端模板从未生成过其中任何一个，因此运行期同样按此拒绝，
	 * 可以顺带让历史上被写入的恶意连接串失效。
	 */
	private static final List<String> DANGEROUS_URL_TOKENS = List.of("autodeserialize", "allowloadlocalinfile",
			"allowurlinlocalinfile", "queryinterceptors", "statementinterceptors", "propertiestransform",
			"detectcustomcollations", "socketfactory", "sslfactory", "sslhostnameverifier", "sslpasswordcallback",
			"loggerfile", "runscript", "init=");

	/**
	 * 仅对服务端刚生成的连接串生效。{@code allowMultiQueries=true} 曾是 MySQL 模板的一部分（PRD S-1 已删除），
	 * 库里还留着带该参数的历史连接串，运行期一并拒绝会让所有存量 MySQL 数据源立刻不可用。
	 */
	private static final List<String> GENERATED_URL_DENIED_TOKENS = List.of("allowmultiqueries");

	private DatasourceConnectionGuard() {
	}

	/**
	 * 校验主机地址并返回去除首尾空白后的值。
	 */
	public static String requireSafeHost(String host) {
		String normalized = StringUtils.trimToEmpty(host);
		if (StringUtils.isEmpty(normalized)) {
			throw CheckedException.badRequest("主机地址不能为空");
		}
		if (normalized.length() > MAX_HOST_LENGTH) {
			throw CheckedException.badRequest("主机地址长度不能超过" + MAX_HOST_LENGTH);
		}
		if (!HOSTNAME_PATTERN.matcher(normalized).matches()) {
			throw CheckedException.badRequest("主机地址只能是合法的域名或 IPv4 地址: " + normalized);
		}
		if (NUMERIC_HOST_PATTERN.matcher(normalized).matches()) {
			requireRoutableIpv4(normalized);
			return normalized;
		}
		requireRoutableHostname(normalized);
		return normalized;
	}

	/**
	 * 校验端口区间并返回原值。
	 */
	public static int requirePortInRange(Integer port) {
		if (port == null) {
			throw CheckedException.badRequest("端口不能为空");
		}
		if (port < MIN_PORT || port > MAX_PORT) {
			throw CheckedException.badRequest("端口必须在" + MIN_PORT + "-" + MAX_PORT + "之间: " + port);
		}
		return port;
	}

	/**
	 * 校验数据库名并返回去除首尾空白后的值。
	 *
	 * <p>竖线是既有约定（PostgreSQL / Oracle 用 {@code database|schema} 表达 schema），必须放行。
	 */
	public static String requireSafeDatabaseName(String databaseName) {
		String normalized = StringUtils.trimToEmpty(databaseName);
		if (StringUtils.isEmpty(normalized)) {
			throw CheckedException.badRequest("数据库名称不能为空");
		}
		if (!DATABASE_NAME_PATTERN.matcher(normalized).matches()) {
			throw CheckedException.badRequest("数据库名称只能包含字母、数字、下划线、中划线、点与竖线: " + normalized);
		}
		return normalized;
	}

	/**
	 * 校验服务端刚生成的 JDBC 连接串：scheme 必须在白名单内，且不含任何危险驱动参数。
	 *
	 * <p>模板是服务端常量，正常情况下必然通过；这道检查是为了让日后改模板时「顺手加了个危险参数」
	 * 立刻失败，而不是等到被利用。
	 */
	public static void requireGeneratedJdbcUrl(String url) {
		if (StringUtils.isBlank(url)) {
			throw CheckedException.badRequest("无法根据主机、端口与数据库名生成连接串，请检查数据源类型与连接信息");
		}
		String lowerUrl = url.toLowerCase(Locale.ROOT);
		if (!lowerUrl.startsWith("jdbc:")) {
			throw CheckedException.fail("生成的连接串不是合法的 JDBC 连接串");
		}
		String scheme = StringUtils.substringBefore(lowerUrl.substring("jdbc:".length()), ":");
		if (!ALLOWED_JDBC_SCHEMES.contains(scheme)) {
			throw CheckedException.fail("生成的连接串使用了未授权的 JDBC 协议: " + scheme);
		}
		for (String token : GENERATED_URL_DENIED_TOKENS) {
			if (lowerUrl.contains(token)) {
				throw CheckedException.fail("生成的连接串包含被禁用的驱动参数: " + token);
			}
		}
		requireNoDangerousJdbcParameters(url);
	}

	/**
	 * 拒绝含危险驱动参数的连接串。运行期读取库内连接串时同样调用，使历史上被写入的恶意串失效。
	 */
	public static void requireNoDangerousJdbcParameters(String url) {
		if (StringUtils.isBlank(url)) {
			return;
		}
		String lowerUrl = url.toLowerCase(Locale.ROOT);
		for (String token : DANGEROUS_URL_TOKENS) {
			if (lowerUrl.contains(token)) {
				throw CheckedException.badRequest("连接串包含被禁用的驱动参数: " + token);
			}
		}
	}

	private static void requireRoutableIpv4(String host) {
		if (!IPV4_PATTERN.matcher(host).matches()) {
			throw CheckedException.badRequest("主机地址不是合法的点分 IPv4 地址: " + host);
		}
		String[] octets = host.split("\\.");
		int first = Integer.parseInt(octets[0]);
		int second = Integer.parseInt(octets[1]);
		if (first == 0) {
			throw CheckedException.badRequest("主机地址不能使用本网络地址: " + host);
		}
		if (first == 127) {
			throw CheckedException.badRequest("主机地址不能使用回环地址: " + host);
		}
		if (first == 169 && second == 254) {
			throw CheckedException.badRequest("主机地址不能使用链路本地或云元数据地址: " + host);
		}
		if (first >= 224) {
			throw CheckedException.badRequest("主机地址不能使用组播或广播地址: " + host);
		}
		if (ALIYUN_METADATA_IP.equals(host)) {
			throw CheckedException.badRequest("主机地址不能使用云元数据地址: " + host);
		}
	}

	private static void requireRoutableHostname(String host) {
		String lowerHost = host.toLowerCase(Locale.ROOT);
		if (DENIED_HOSTS.contains(lowerHost) || lowerHost.endsWith(LOCALHOST_SUFFIX)) {
			throw CheckedException.badRequest("主机地址不能使用本机或云元数据域名: " + host);
		}
	}

}
