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
package com.sn68.agent.dataagent.connector.impls.dameng;

import com.sn68.agent.dataagent.connector.accessor.AbstractAccessor;
import com.sn68.agent.dataagent.connector.ddl.DdlFactory;
import com.sn68.agent.dataagent.connector.pool.DBConnectionPoolFactory;
import com.sn68.agent.dataagent.enums.BizDataSourceTypeEnum;
import org.springframework.stereotype.Service;

/**
 * 达梦（DM）数据源访问器，组合达梦方言 DDL 读取与达梦连接池，对上层提供统一的库表元数据访问入口。
 */
@Service("damengAccessor")
public class DamengDBAccessor extends AbstractAccessor {

	private static final String ACCESSOR_TYPE = "Dameng_Accessor";

	protected DamengDBAccessor(DdlFactory ddlFactory, DBConnectionPoolFactory poolFactory) {
		super(ddlFactory, poolFactory.getPoolByDbType(BizDataSourceTypeEnum.DAMENG.getTypeName()));
	}

	@Override
	public String getAccessorType() {
		return ACCESSOR_TYPE;
	}

	@Override
	public boolean supportedDataSourceType(String type) {
		return BizDataSourceTypeEnum.DAMENG.getTypeName().equalsIgnoreCase(type);
	}

}
