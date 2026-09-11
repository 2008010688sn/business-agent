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
package com.sn68.agent.dataagent.service.semantic;

import com.sn68.agent.dataagent.dto.schema.SemanticModelAddDTO;
import com.sn68.agent.dataagent.dto.schema.SemanticModelBatchImportDTO;
import com.sn68.agent.dataagent.dto.schema.SemanticModelExcelImportReq;
import com.sn68.agent.dataagent.dto.schema.SemanticModelUpdateReq;
import com.sn68.agent.dataagent.entity.SemanticModel;
import com.sn68.agent.dataagent.vo.BatchImportResult;
import jakarta.servlet.http.HttpServletResponse;
import java.io.InputStream;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * 语义模型服务契约。
 */
public interface SemanticModelService {

	/**
	 * 查询语义模型。
	 */
	List<SemanticModel> listSemanticModels(String keyword, Long skillId);

	/**
	 * 查询语义模型。
	 */
	List<SemanticModel> getEnabledBySkillId(Long skillId);

	/**
	 * 查询语义模型。
	 */
	List<SemanticModel> getEnabledBySkillIdAndDatasourceId(Long skillId, Long datasourceId);

	/**
	 * Resolves only the IDs frozen by a published Skill version, regardless of later editor status.
	 */
	List<SemanticModel> getBySkillIdAndDatasourceIdAndIds(Long skillId, Long datasourceId, List<Long> ids);

	/**
	 * 查询语义模型。
	 */
	List<SemanticModel> getBySkillIdAndTableNames(Long skillId, List<String> tableNames);

	/**
	 * 查询指定技能与数据源下、给定表名集合内启用中的语义模型。
	 */
	List<SemanticModel> getEnabledBySkillIdAndDatasourceIdAndTableNames(Long skillId, Long datasourceId,
			List<String> tableNames);

	/**
	 * 查询语义模型。
	 */
	SemanticModel getById(Long id);

	/**
	 * 校验语义模型。
	 */
	SemanticModel requireById(Long id);

	/**
	 * Resolve a semantic model only when it belongs to the specified Skill.
	 */
	SemanticModel requireBySkillId(Long skillId, Long id);

	/**
	 * 创建语义模型。
	 */
	void addSemanticModel(SemanticModel semanticModel);

	/**
	 * 创建语义模型。
	 */
	boolean addSemanticModel(SemanticModelAddDTO dto);

	/**
	 * 创建语义模型。
	 */
	void createSemanticModel(SemanticModelAddDTO dto);

	/**
	 * 处理语义模型。
	 */
	void enableSemanticModel(Long id);

	/**
	 * 处理语义模型。
	 */
	void disableSemanticModel(Long id);

	/**
	 * 查询语义模型。
	 */
	List<SemanticModel> getBySkillId(Long skillId);

	/**
	 * 查询语义模型。
	 */
	List<SemanticModel> search(String keyword);

	/**
	 * 查询语义模型。
	 */
	List<SemanticModel> searchBySkillId(Long skillId, String keyword);

	/**
	 * 删除语义模型。
	 */
	void deleteSemanticModel(Long id);

	/**
	 * 保存语义模型。
	 */
	void updateSemanticModel(Long id, SemanticModelUpdateReq semanticModelUpdateDto);

	/**
	 * 保存语义模型。
	 */
	SemanticModel updateSemanticModelAndReturn(Long id, SemanticModelUpdateReq semanticModelUpdateDto);

	/**
	 * 批量新增语义模型（逐个新增并联动写入向量）。
	 */
	default void addSemanticModels(List<SemanticModel> semanticModels) {
		semanticModels.forEach(this::addSemanticModel);
	}

	/**
	 * 批量启用语义模型。
	 */
	default void enableSemanticModels(List<Long> ids) {
		ids.forEach(this::enableSemanticModel);
	}

	/**
	 * 批量停用语义模型。
	 */
	default void disableSemanticModels(List<Long> ids) {
		ids.forEach(this::disableSemanticModel);
	}

	/**
	 * 批量删除语义模型（逐个删除并联动清理向量）。
	 */
	default void deleteSemanticModels(List<Long> ids) {
		ids.forEach(this::deleteSemanticModel);
	}

	/**
	 * 处理语义模型。
	 */
	BatchImportResult batchImport(SemanticModelBatchImportDTO dto);

	/**
	 * 从Excel文件导入语义模型
	 * @param inputStream Excel文件输入流
	 * @param filename 文件名
	 * @param skillId Skill ID
	 * @param datasourceId 数据源ID
	 * @return 导入结果
	 */
	BatchImportResult importFromExcel(InputStream inputStream, String filename, Long skillId, Long datasourceId);

	/**
	 * 处理语义模型。
	 */
	Mono<BatchImportResult> importFromExcel(SemanticModelExcelImportReq request);

	/**
	 * 处理语义模型。
	 */
	byte[] downloadTemplate(HttpServletResponse response);

}
