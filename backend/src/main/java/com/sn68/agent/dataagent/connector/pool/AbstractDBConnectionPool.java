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
package com.sn68.agent.dataagent.connector.pool;

import cn.hutool.crypto.SecureUtil;
import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.pool.DruidDataSourceFactory;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import com.sn68.agent.dataagent.enums.BizDataSourceTypeEnum;
import com.sn68.agent.dataagent.enums.ErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;

/**
 * JDBC 连接池抽象基类：按连接参数用 Caffeine 缓存数据源（容量上限 + 空闲淘汰并关闭池），
 * 子类只需提供驱动类名（getDriver）与 SQLState 错误码映射（errorMapping）。
 */
@Slf4j
public abstract class AbstractDBConnectionPool implements DBConnectionPool {

	/**
	 * 缓存连接池数量上限。每一项都是一个最多 20 连接的 Druid 池，而数据源创建入口目前没有鉴权
	 * （PRD P0-1），无上限的缓存等于给出一条低成本的内存 / 目标库连接耗尽路径。
	 */
	private static final int MAX_CACHED_DATA_SOURCES = 64;

	/**
	 * 连接池空闲多久后淘汰。淘汰后必须关闭池（见 {@link #closeCachedDataSource}），
	 * 只从缓存里丢掉引用会把连接永久挂在目标库上，比不淘汰更糟。
	 */
	private static final Duration DATA_SOURCE_IDLE_TIMEOUT = Duration.ofMinutes(30);

	/**
	 * DataSource cache to ensure that each configuration creates DataSource only once.
	 * <p>
	 * {@code executor(Runnable::run)} 让淘汰回调同步执行，保持与原先
	 * {@code evict}/{@code close} 一致的「返回时池已关闭」语义；
	 * {@code scheduler} 用于让空闲淘汰及时发生，而不是等到下一次缓存访问。
	 */
	private static final Cache<String, DataSource> DATA_SOURCE_CACHE = Caffeine.newBuilder()
		.maximumSize(MAX_CACHED_DATA_SOURCES)
		.expireAfterAccess(DATA_SOURCE_IDLE_TIMEOUT)
		.scheduler(Scheduler.systemScheduler())
		.executor(Runnable::run)
		.<String, DataSource>removalListener(AbstractDBConnectionPool::closeCachedDataSource)
		.build();

	/**
	 * Driver
	 */
	public abstract String getDriver();

	/**
	 * Error message mapping
	 */
	public abstract ErrorCodeEnum errorMapping(String sqlState);

	protected String getSelectSchemaSQL() {
		return "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?";
	}

	public ErrorCodeEnum ping(DbConfigBO config) {
		String jdbcUrl = config.getUrl();
		try (Connection connection = DriverManager.getConnection(jdbcUrl, config.getUsername(), config.getPassword())) {
			if (BizDataSourceTypeEnum.isPgDialect(config.getConnectionType())) {
				try (PreparedStatement stmt = connection.prepareStatement(getSelectSchemaSQL())) {
					stmt.setString(1, config.getSchema());
					try (ResultSet rs = stmt.executeQuery()) {
						if (rs.next() && rs.getInt(1) == 0) {
							log.info("the specified schema '{}' does not exist.", config.getSchema());
							return ErrorCodeEnum.SCHEMA_NOT_EXIST_3D070;
						}
					}
				}
			}
			return ErrorCodeEnum.SUCCESS;
		}
		catch (SQLException e) {
			log.error("test db connection error, endpoint:{}, state:{}, message:{}", describeEndpoint(jdbcUrl),
					e.getSQLState(), e.getMessage());
			return errorMapping(e.getSQLState());
		}
	}

	public Connection getConnection(DbConfigBO config) {

		String jdbcUrl = config.getUrl();
		String endpoint = describeEndpoint(jdbcUrl);
		String cacheKey = generateCacheKey(config);
		int maxRetries = 3;
		int retryDelay = 1000; // 1 second

		for (int attempt = 1; attempt <= maxRetries; attempt++) {
			try {
				// Use get(key, mappingFunction) to ensure thread safety and avoid
				// duplicate
				// DataSource creation
				DataSource dataSource = DATA_SOURCE_CACHE.get(cacheKey, key -> {
					try {
						log.debug("Creating new DataSource for endpoint: {}", endpoint);
						return createdDataSource(jdbcUrl, config.getUsername(), config.getPassword());
					}
					catch (Exception e) {
						log.error("Failed to create DataSource for endpoint: {}", endpoint, e);
						throw new IllegalStateException("Failed to create DataSource", e);
					}
				});

				// 记录连接池状态
				if (dataSource instanceof DruidDataSource druidDataSource) {
					log.debug("Connection pool status - Active: {}, Idle: {}, Total: {}, WaitCount: {}",
							druidDataSource.getActiveCount(), druidDataSource.getPoolingCount(),
							druidDataSource.getActiveCount() + druidDataSource.getPoolingCount(),
							druidDataSource.getWaitThreadCount());
				}

				return dataSource.getConnection();
			}
			catch (Exception e) {
				log.warn("Attempt {} to get database connection failed: {}", attempt, e.getMessage());

				if (attempt == maxRetries) {
					evict(config);
					log.error("Failed to get database connection after {} attempts, endpoint: {}", maxRetries, endpoint,
							e);
					throw new IllegalStateException(
							"Failed to get database connection after " + maxRetries + " attempts", e);
				}

				// Wait before retry with exponential backoff
				try {
					Thread.sleep((long) retryDelay * attempt);
				}
				catch (InterruptedException interrupted) {
					// Swallowing this also cleared the interrupt flag, so the retry loop ignored cancellation.
					Thread.currentThread().interrupt();
					log.warn("Interrupted while backing off before connection retry {}, endpoint: {}", attempt, endpoint);
				}
			}
		}
		return null;
	}

	/**
	 * 生成连接池缓存键：租户 + 数据源 ID 划出归属边界，连接参数摘要保证凭据变更后不会复用旧池。
	 * <p>
	 * 摘要不能用 {@code Objects.hashCode(password)}：String 的 32 位 hashCode 可被轻易构造碰撞
	 * （如 "Aa" 与 "BB"），同 URL + 同用户名下伪造一个碰撞口令即可命中并复用他人已缓存的连接池，
	 * 等于拿到对方的数据库凭据。这里对 URL + 用户名 + 口令整体取 SHA-256，键本身也不再含明文。
	 * @param config the database configuration
	 * @return the cache key
	 */
	protected String generateCacheKey(DbConfigBO config) {
		String scope = keySegment(config.getTenantId()) + "|" + keySegment(config.getDatasourceId());
		String credentials = SecureUtil
			.sha256(String.join("\n", nullToEmpty(config.getUrl()), nullToEmpty(config.getUsername()),
					nullToEmpty(config.getPassword())));
		return scope + "|" + credentials;
	}

	private static String keySegment(Object value) {
		return value == null ? "-" : String.valueOf(value);
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	/**
	 * 关闭被移出缓存的连接池。容量淘汰、空闲淘汰与显式 {@code evict} 都会走到这里——
	 * 被淘汰的 Druid 池若不关闭，池内连接会一直挂在目标库上，泄漏比不淘汰更严重。
	 * @param cacheKey 缓存键，已是「租户|数据源|摘要」形式，不含 URL / 账号 / 口令，可安全打日志
	 * @param dataSource the evicted data source
	 * @param cause the removal cause
	 */
	private static void closeCachedDataSource(String cacheKey, DataSource dataSource, RemovalCause cause) {
		if (!(dataSource instanceof DruidDataSource druidDataSource)) {
			return;
		}
		try {
			druidDataSource.close();
			log.info("Closed cached DataSource, cacheKey:{}, cause:{}", cacheKey, cause);
		}
		catch (Exception e) {
			log.warn("Failed to close cached DataSource, cacheKey:{}, cause:{}", cacheKey, cause, e);
		}
	}

	/**
	 * JDBC URL 由调用方提供，可能内嵌 {@code ?user=&password=}，日志只保留
	 * {@code host:port/database}。
	 * @param jdbcUrl the raw JDBC URL
	 * @return the endpoint safe to log
	 */
	protected static String describeEndpoint(String jdbcUrl) {
		if (jdbcUrl == null || jdbcUrl.isBlank()) {
			return "unknown";
		}
		int schemeEnd = jdbcUrl.indexOf("//");
		int start = schemeEnd >= 0 ? schemeEnd + 2 : jdbcUrl.indexOf('@') + 1;
		if (start <= 0 || start >= jdbcUrl.length()) {
			return "unknown";
		}
		String endpoint = jdbcUrl.substring(start);
		int queryStart = endpoint.indexOf('?');
		int propsStart = endpoint.indexOf(';');
		int cut = Math.min(queryStart < 0 ? endpoint.length() : queryStart,
				propsStart < 0 ? endpoint.length() : propsStart);
		endpoint = endpoint.substring(0, cut);
		int pathStart = endpoint.indexOf('/');
		int userInfoEnd = endpoint.lastIndexOf('@', pathStart < 0 ? endpoint.length() : pathStart);
		if (userInfoEnd >= 0) {
			endpoint = endpoint.substring(userInfoEnd + 1);
		}
		return endpoint.isBlank() ? "unknown" : endpoint;
	}

	@Override
	public void evict(DbConfigBO config) {
		if (config == null || config.getUrl() == null) {
			return;
		}
		// 淘汰回调（closeCachedDataSource）同步执行，返回时池已关闭
		DATA_SOURCE_CACHE.invalidate(generateCacheKey(config));
	}

	@Override
	public void close() {
		DATA_SOURCE_CACHE.invalidateAll();
		DATA_SOURCE_CACHE.cleanUp();
		log.info("DataSource cache cleared");
	}

	/**
	 * Clear DataSource cache and close all cached DataSource instances. This method is
	 * useful for resource cleanup in special scenarios.
	 */

	public DataSource createdDataSource(String url, String username, String password) throws Exception {

		String driver = getDriver();

		String filters = "wall,stat";
		if (driver != null && driver.toLowerCase().contains("dm.jdbc.driver.dmdriver")) {
			filters = "stat";
		}

		java.util.Map<String, String> props = new java.util.HashMap<>();
		props.put(DruidDataSourceFactory.PROP_DRIVERCLASSNAME, driver);
		props.put(DruidDataSourceFactory.PROP_URL, url);
		props.put(DruidDataSourceFactory.PROP_USERNAME, username);
		props.put(DruidDataSourceFactory.PROP_PASSWORD, password);
		props.put(DruidDataSourceFactory.PROP_INITIALSIZE, "0");
		props.put(DruidDataSourceFactory.PROP_MINIDLE, "0");
		props.put(DruidDataSourceFactory.PROP_MAXACTIVE, "20");
		props.put(DruidDataSourceFactory.PROP_MAXWAIT, "10000");
		props.put(DruidDataSourceFactory.PROP_TIMEBETWEENEVICTIONRUNSMILLIS, "60000");
		props.put(DruidDataSourceFactory.PROP_FILTERS, filters);

		DruidDataSource dataSource = (DruidDataSource) DruidDataSourceFactory.createDataSource(props);
		dataSource.setInitialSize(0);
		dataSource.setMinIdle(0);
		dataSource.setBreakAfterAcquireFailure(Boolean.TRUE);
		dataSource.setConnectionErrorRetryAttempts(2);
		dataSource.setTestWhileIdle(false);

		// 记录数据源创建信息
		log.info(
				"Created new DataSource with optimized parameters - InitialSize: 0, MinIdle: 0, MaxActive: 20, MaxWait: 10000ms");

		return dataSource;
	}

}
