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
package com.sn68.agent.dataagent.context;

import com.alibaba.ttl.TransmittableThreadLocal;
import com.sn68.agent.dataagent.config.OpenAccessFilter;
import com.sn68.agent.dataagent.iam.LocalPrincipalStore;
import com.sn68.agent.framework.commons.threadlocal.ThreadLocalHolder;
import com.sn68.agent.framework.security.domain.UserInfoDetails;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.i18n.LocaleContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 异步上下文桥接器：捕获请求侧 ThreadLocal 演示用户、Locale 与出站快照，并在异步线程中还原。
 */
@Slf4j
@Component
public class DataAgentAsyncContextBridge {

	/** 与 AuthenticationContextConfiguration / OpenAccessFilter 同源。 */
	static final String USER_INFO_KEY = OpenAccessFilter.USER_INFO_KEY;

	static final String USER_ANONYMOUS_KEY = OpenAccessFilter.USER_ANONYMOUS_KEY;

	private static final String MERGED_DATA_PERMISSION_KEY = "MERGED_DATA_PERMISSION_KEY";

	/** 外层 supplyWith/callWith 的快照；工具切线程后 capture() 优先复用。 */
	private static final TransmittableThreadLocal<Snapshot> CURRENT_SNAPSHOT = new TransmittableThreadLocal<>();

	private final ObjectProvider<LocalPrincipalStore> principalStoreProvider;

	public DataAgentAsyncContextBridge() {
		this(null);
	}

	@Autowired
	public DataAgentAsyncContextBridge(ObjectProvider<LocalPrincipalStore> principalStoreProvider) {
		this.principalStoreProvider = principalStoreProvider;
	}

	public Snapshot capture() {
		Snapshot current = CURRENT_SNAPSHOT.get();
		if (current != null) {
			return current;
		}
		return new Snapshot(null, LocaleContextHolder.getLocale(), copyThreadLocalData(ThreadLocalHolder.getAll()),
				DataAgentOutboundContext.get());
	}

	/**
	 * 为委托用户构造请求快照。不能复用当前线程已缓存的用户、匿名标记或合并数据权限。
	 */
	public Snapshot snapshotForDelegatedToken(String tokenValue, DataAgentOutboundContext.Snapshot outboundContext) {
		Map<String, Object> threadLocalData = new LinkedHashMap<>(copyThreadLocalData(ThreadLocalHolder.getAll()));
		threadLocalData.remove(USER_INFO_KEY);
		threadLocalData.remove(USER_ANONYMOUS_KEY);
		threadLocalData.remove(MERGED_DATA_PERMISSION_KEY);
		return new Snapshot(tokenValue, LocaleContextHolder.getLocale(), threadLocalData, outboundContext, true);
	}

	public <T> T callWith(Snapshot snapshot, Callable<T> callable) throws Exception {
		Map<String, Object> previousThreadLocal = copyThreadLocalData(ThreadLocalHolder.getAll());
		LocaleContext previousLocaleContext = LocaleContextHolder.getLocaleContext();
		DataAgentOutboundContext.Snapshot previousOutboundContext = DataAgentOutboundContext.get();
		Snapshot previousCurrent = CURRENT_SNAPSHOT.get();
		try {
			CURRENT_SNAPSHOT.set(snapshot);
			restore(snapshot);
			return callable.call();
		}
		finally {
			restorePrevious(previousThreadLocal, previousLocaleContext, previousOutboundContext);
			if (previousCurrent == null) {
				CURRENT_SNAPSHOT.remove();
			}
			else {
				CURRENT_SNAPSHOT.set(previousCurrent);
			}
		}
	}

	public <T> T supplyWith(Snapshot snapshot, Supplier<T> supplier) {
		try {
			return callWith(snapshot, supplier::get);
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	public void runWith(Snapshot snapshot, Runnable runnable) {
		supplyWith(snapshot, () -> {
			runnable.run();
			return null;
		});
	}

	private void restore(Snapshot snapshot) {
		Snapshot safeSnapshot = snapshot == null ? Snapshot.empty() : snapshot;
		ThreadLocalHolder.clear();
		safeSnapshot.threadLocalData().forEach(ThreadLocalHolder::set);
		if (safeSnapshot.locale() != null) {
			LocaleContextHolder.setLocale(safeSnapshot.locale());
		}
		else {
			LocaleContextHolder.resetLocaleContext();
		}
		DataAgentOutboundContext.set(safeSnapshot.outboundContext());
		if (safeSnapshot.delegated()) {
			hydrateDelegatedUserInfo(safeSnapshot.tokenValue());
		}
		else if (ThreadLocalHolder.get(USER_INFO_KEY) == null) {
			ThreadLocalHolder.set(USER_INFO_KEY, OpenAccessFilter.demoUser());
			ThreadLocalHolder.set(USER_ANONYMOUS_KEY, Boolean.FALSE);
		}
	}

	private void hydrateDelegatedUserInfo(String tokenValue) {
		UserInfoDetails details = null;
		LocalPrincipalStore store = principalStoreProvider == null ? null : principalStoreProvider.getIfAvailable();
		if (store != null && StringUtils.hasText(tokenValue)) {
			LocalPrincipalStore.Record record = store.findByToken(tokenValue);
			details = record == null ? null : record.userInfo();
		}
		if (details == null || !StringUtils.hasText(details.getUserId())) {
			throw new IllegalStateException("Principal token session 缺少 UserInfoDetails，无法建立授权上下文");
		}
		ThreadLocalHolder.set(USER_INFO_KEY, details);
		ThreadLocalHolder.set(USER_ANONYMOUS_KEY, false);
	}

	private void restorePrevious(Map<String, Object> previousThreadLocal, LocaleContext previousLocaleContext,
			DataAgentOutboundContext.Snapshot previousOutboundContext) {
		ThreadLocalHolder.clear();
		previousThreadLocal.forEach(ThreadLocalHolder::set);
		if (previousLocaleContext == null) {
			LocaleContextHolder.resetLocaleContext();
		}
		else {
			LocaleContextHolder.setLocaleContext(previousLocaleContext);
		}
		DataAgentOutboundContext.set(previousOutboundContext);
	}

	private Map<String, Object> copyThreadLocalData(Map<String, Object> source) {
		if (source == null || source.isEmpty()) {
			return Map.of();
		}
		return Collections.unmodifiableMap(new LinkedHashMap<>(source));
	}

	public record Snapshot(String tokenValue, Locale locale, Map<String, Object> threadLocalData,
			DataAgentOutboundContext.Snapshot outboundContext, boolean delegated) {

		public Snapshot(String tokenValue, Locale locale, Map<String, Object> threadLocalData,
				DataAgentOutboundContext.Snapshot outboundContext) {
			this(tokenValue, locale, threadLocalData, outboundContext, false);
		}

		public Snapshot {
			threadLocalData = threadLocalData == null || threadLocalData.isEmpty() ? Map.of()
					: Collections.unmodifiableMap(new LinkedHashMap<>(threadLocalData));
			outboundContext = outboundContext == null ? DataAgentOutboundContext.Snapshot.empty() : outboundContext;
		}

		public static Snapshot empty() {
			return new Snapshot(null, null, Map.of(), DataAgentOutboundContext.Snapshot.empty(), false);
		}

	}

}
