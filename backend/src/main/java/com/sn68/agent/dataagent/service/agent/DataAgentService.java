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

import com.sn68.agent.dataagent.entity.DataAgent;
import com.sn68.agent.dataagent.vo.ApiKeyResp;

import java.util.List;

/**
 * DataAgent服务契约。
 */
public interface DataAgentService {

	/**
	 * 查询DataAgent。
	 */
	List<DataAgent> list(String status, String keyword);

	/**
	 * 查询DataAgent。
	 */
	List<DataAgent> findAll();

	/**
	 * 查询DataAgent。
	 */
	DataAgent findById(Long id);

	/**
	 * 查询DataAgent。
	 */
	DataAgent findByIdWithPreview(Long id);

	/**
	 * 校验DataAgent。
	 */
	DataAgent requireAgent(Long id);

	/**
	 * 校验DataAgent。
	 */
	DataAgent requireAgentWithPreview(Long id);

	/**
	 * 查询DataAgent。
	 */
	List<DataAgent> findByStatus(String status);

	/**
	 * 查询DataAgent。
	 */
	List<DataAgent> findByStatusRaw(String status);

	/**
	 * 查询DataAgent。
	 */
	List<DataAgent> search(String keyword);

	/**
	 * 创建DataAgent。
	 */
	DataAgent create(DataAgent dataAgent);

	/**
	 * 保存DataAgent。
	 */
	DataAgent update(Long id, DataAgent dataAgent);

	/**
	 * 保存DataAgent。
	 */
	DataAgent publish(Long id);

	/**
	 * 保存DataAgent。
	 */
	DataAgent offline(Long id);

	/**
	 * 保存DataAgent。
	 */
	DataAgent save(DataAgent dataAgent);

	/**
	 * 删除DataAgent。
	 */
	void deleteById(Long id);

	/**
	 * 查询DataAgent。
	 */
	ApiKeyResp getApiKey(Long id);

	/**
	 * 创建DataAgent。
	 */
	ApiKeyResp generateApiKeyResponse(Long id);

	/**
	 * 保存DataAgent。
	 */
	ApiKeyResp resetApiKeyResponse(Long id);

	/**
	 * 删除DataAgent。
	 */
	ApiKeyResp deleteApiKeyResponse(Long id);

	/**
	 * 保存DataAgent。
	 */
	ApiKeyResp toggleApiKeyResponse(Long id, boolean enabled);

	/**
	 * 创建DataAgent。
	 */
	DataAgent generateApiKey(Long id);

	/**
	 * 保存DataAgent。
	 */
	DataAgent resetApiKey(Long id);

	/**
	 * 删除DataAgent。
	 */
	DataAgent deleteApiKey(Long id);

	/**
	 * 保存DataAgent。
	 */
	DataAgent toggleApiKey(Long id, boolean enabled);

	/**
	 * 查询DataAgent。
	 */
	String getApiKeyMasked(Long id);

}
