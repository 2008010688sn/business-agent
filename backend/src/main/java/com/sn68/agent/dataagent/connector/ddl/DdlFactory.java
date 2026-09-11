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
package com.sn68.agent.dataagent.connector.ddl;

import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.enums.BizDataSourceTypeEnum;
import com.sn68.agent.framework.commons.exception.CheckedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DDL 方言实现工厂：启动时收集所有 {@link Ddl} 实现并按数据源类型注册，运行期按类型路由到对应方言实现。
 */
@Component
public class DdlFactory {

	private final Map<String, Ddl> ddlExecutorSet = new ConcurrentHashMap<>();

	public DdlFactory(List<Ddl> ddls) {
		ddls.forEach(this::registry);
	}

	public void registry(Ddl ddlExecutor) {
		ddlExecutorSet.put(ddlExecutor.getDdlType(), ddlExecutor);
	}

	public boolean isRegistered(String type) {
		return ddlExecutorSet.containsKey(type);
	}

	public Ddl getDdlExecutorByDbConfig(DbConfigBO dbConfig) {
		BizDataSourceTypeEnum type = BizDataSourceTypeEnum.fromTypeName(dbConfig.getDialectType());
		if (type == null) {
			throw CheckedException.badRequest("不支持的数据源类型: " + dbConfig.getDialectType());
		}
		return getDdlExecutorByDbType(type);
	}

	// todo: 写一层缓存
	public Ddl getDdlExecutorByDbType(BizDataSourceTypeEnum type) {
		return ddlExecutorSet.values()
			.stream()
			.filter(d -> d.supportedDataSourceType(type))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("no ddl executor found for " + type));
	}

	public Ddl getDdlExecutorByType(String type) {
		return ddlExecutorSet.get(type);
	}

}
