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
package com.sn68.agent.dataagent.service.schema;

import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.dto.datasource.SchemaInitReq;
import com.sn68.agent.dataagent.dto.schema.SchemaDTO;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Schema服务契约。
 */
public interface SchemaService {

	/**
	 * 处理Schema。
	 */
	Boolean schema(Long datasourceId, SchemaInitReq schemaInitRequest) throws Exception;

	/**
	 * 查询Schema。
	 */
	List<Document> getTableDocumentsByDatasource(String skillId, Long datasourceId, String query);

	/**
	 * 处理Schema。
	 */
	void extractDatabaseName(SchemaDTO schemaDTO, DbConfigBO dbConfig);

	/**
	 * 构建Schema服务所需的数据。
	 */
	void buildSchemaFromDocuments(String skillId, List<Document> columnDocumentList, List<Document> tableDocuments,
			SchemaDTO schemaDTO);

	/**
	 * 查询Schema。
	 */
	List<Document> getTableDocuments(String skillId, Long datasourceId, List<String> tableNames);

	/**
	 * 查询Schema。
	 */
	List<Document> getColumnDocumentsByTableName(String skillId, Long datasourceId, List<String> tableNames);

}
