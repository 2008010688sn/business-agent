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
import com.sn68.agent.dataagent.dto.schema.SemanticModelImportItem;
import com.sn68.agent.dataagent.dto.schema.SemanticModelUpdateReq;
import com.sn68.agent.dataagent.entity.SkillDatasource;
import com.sn68.agent.dataagent.entity.SemanticModel;
import com.sn68.agent.dataagent.repository.SkillDatasourceMapper;
import com.sn68.agent.dataagent.repository.SemanticModelMapper;
import com.sn68.agent.dataagent.service.skill.PublishedSkillResourceReferenceService;
import com.sn68.agent.dataagent.vo.BatchImportResult;
import com.sn68.agent.framework.commons.exception.CheckedException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 语义模型管理实现：维护字段级语义配置的增删改查与向量同步。
 */
@Service
@AllArgsConstructor
@Slf4j
public class SemanticModelServiceImpl implements SemanticModelService {

	private static final String DUPLICATE_SEMANTIC_MODEL_MESSAGE = "当前数据源下该表字段的语义模型已存在，请修改已有语义模型后再新增";

	private static final String TEMPLATE_FILE_NAME = "semantic_model_template.xlsx";

	private static final String TEMPLATE_RESOURCE_PATH = "dataagent/excel/" + TEMPLATE_FILE_NAME;

	private final SemanticModelMapper semanticModelMapper;

	private final SkillDatasourceMapper skillDatasourceMapper;

	private final SemanticModelExcelService excelService;

	private final PublishedSkillResourceReferenceService publishedResourceReferenceService;

	@Override
	public List<SemanticModel> listSemanticModels(String keyword, Long skillId) {
		if (skillId != null && StringUtils.hasText(keyword)) {
			return searchBySkillId(skillId, keyword);
		}
		if (StringUtils.hasText(keyword)) {
			return search(keyword);
		}
		if (skillId != null) {
			return getBySkillId(skillId);
		}
		// semantic_model 无 tenant_id 列（PRD P0-2），skillId 是这里唯一的归属边界。
		// 原先此处退化为 selectAll() 全表查询，一旦有调用方不传 skillId 就会跨租户返回语义模型；
		// 唯一入口 SkillSemanticModelController 的 skillId 是 @PathVariable 必然非空，故改为显式拒绝。
		throw CheckedException.badRequest("skillId不能为空");
	}

	@Override
	public List<SemanticModel> getEnabledBySkillId(Long skillId) {
		return semanticModelMapper.selectEnabledBySkillId(skillId);
	}

	@Override
	public List<SemanticModel> getEnabledBySkillIdAndDatasourceId(Long skillId, Long datasourceId) {
		if (skillId == null || datasourceId == null) {
			return List.of();
		}
		return semanticModelMapper.selectEnabledBySkillIdAndDatasourceId(skillId, datasourceId);
	}

	@Override
	public List<SemanticModel> getBySkillIdAndDatasourceIdAndIds(Long skillId, Long datasourceId, List<Long> ids) {
		if (skillId == null || datasourceId == null || ids == null || ids.isEmpty()) {
			return List.of();
		}
		return semanticModelMapper.selectByIds(ids).stream()
			.filter(model -> model != null && !Boolean.TRUE.equals(model.getDeleted()))
			.filter(model -> Objects.equals(skillId, model.getSkillId()))
			.filter(model -> Objects.equals(datasourceId, model.getDatasourceId()))
			.toList();
	}

	@Override
	public List<SemanticModel> getBySkillIdAndTableNames(Long skillId, List<String> tableNames) {
		if (skillId == null || tableNames == null || tableNames.isEmpty()) {
			return List.of();
		}

		return semanticModelMapper.selectEnabledBySkillIdAndTableNames(skillId, normalizeTableNames(tableNames));
	}

	@Override
	public List<SemanticModel> getEnabledBySkillIdAndDatasourceIdAndTableNames(Long skillId, Long datasourceId,
			List<String> tableNames) {
		if (skillId == null || datasourceId == null || tableNames == null || tableNames.isEmpty()) {
			return List.of();
		}
		return semanticModelMapper.selectEnabledBySkillIdAndDatasourceIdAndTableNames(skillId, datasourceId,
				normalizeTableNames(tableNames));
	}

	@Override
	public SemanticModel getById(Long id) {
		return semanticModelMapper.selectById(id);
	}

	@Override
	public SemanticModel requireById(Long id) {
		SemanticModel semanticModel = getById(id);
		if (semanticModel == null) {
			throw CheckedException.notFound("Semantic model does not exist");
		}
		return semanticModel;
	}

	@Override
	public SemanticModel requireBySkillId(Long skillId, Long id) {
		if (skillId == null || id == null) {
			throw CheckedException.badRequest("skillId and semanticModelId cannot be null");
		}
		SemanticModel model = requireById(id);
		if (!Objects.equals(skillId, model.getSkillId())) {
			throw CheckedException.notFound("Semantic model does not exist");
		}
		return model;
	}

	@Override
	public void addSemanticModel(SemanticModel semanticModel) {
		insertSemanticModel(semanticModel, DUPLICATE_SEMANTIC_MODEL_MESSAGE);
	}

	@Override
	public boolean addSemanticModel(SemanticModelAddDTO dto) {
		validateSkillDatasourceBinding(dto.getSkillId(), dto.getDatasourceId());

		SemanticModel existing = semanticModelMapper.selectBySkillIdAndDatasourceIdAndTableNameAndColumnName(
				dto.getSkillId(), dto.getDatasourceId(), dto.getTableName(), dto.getColumnName());
		if (existing != null) {
			throw CheckedException.badRequest(DUPLICATE_SEMANTIC_MODEL_MESSAGE);
		}

		SemanticModel semanticModel = SemanticModel.builder()
			.skillId(dto.getSkillId())
			.datasourceId(dto.getDatasourceId())
			.tableName(dto.getTableName())
			.columnName(dto.getColumnName())
			.businessName(dto.getBusinessName())
			.synonyms(dto.getSynonyms())
			.businessDescription(dto.getBusinessDescription())
			.columnComment(dto.getColumnComment())
			.dataType(dto.getDataType())
			.status(true)
			.build();

		insertSemanticModel(semanticModel, DUPLICATE_SEMANTIC_MODEL_MESSAGE);
		return true;
	}

	@Override
	public void createSemanticModel(SemanticModelAddDTO dto) {
		try {
			if (!addSemanticModel(dto)) {
				throw CheckedException.fail("Failed to create semantic model");
			}
		}
		catch (IllegalArgumentException e) {
			throw CheckedException.badRequest(e.getMessage());
		}
	}

	private SkillDatasource validateSkillDatasourceBinding(Long skillId, Long datasourceId) {
		if (skillId == null) {
			throw CheckedException.badRequest("skillId不能为空");
		}
		if (datasourceId == null) {
			throw CheckedException.badRequest("datasourceId不能为空");
		}
		SkillDatasource skillDatasource = skillDatasourceMapper.selectBySkillIdAndDatasourceId(skillId, datasourceId);
		if (skillDatasource == null) {
			throw CheckedException.notFound(
					"未找到对应的Skill与数据源绑定关系: skillId=%s, datasourceId=%s".formatted(skillId, datasourceId));
		}
		return skillDatasource;
	}

	@Override
	public void enableSemanticModel(Long id) {
		SemanticModel model = requireById(id);
		if (publishedResourceReferenceService.isSemanticModelReferenced(model.getSkillId(), model.getId())) {
			throw CheckedException.badRequest("Published Skill semantic model status cannot be changed");
		}
		semanticModelMapper.enableById(id);
	}

	@Override
	public void disableSemanticModel(Long id) {
		SemanticModel model = requireById(id);
		if (publishedResourceReferenceService.isSemanticModelReferenced(model.getSkillId(), model.getId())) {
			throw CheckedException.badRequest("Published Skill semantic model status cannot be changed");
		}
		semanticModelMapper.disableById(id);
	}

	@Override
	public List<SemanticModel> getBySkillId(Long skillId) {
		return semanticModelMapper.selectBySkillId(skillId);
	}

	@Override
	public List<SemanticModel> search(String keyword) {
		return semanticModelMapper.searchByKeyword(keyword);
	}

	@Override
	public List<SemanticModel> searchBySkillId(Long skillId, String keyword) {
		if (skillId == null) {
			return List.of();
		}
		return semanticModelMapper.searchByKeywordAndSkillId(skillId, keyword);
	}

	@Override
	public void deleteSemanticModel(Long id) {
		SemanticModel model = requireById(id);
		if (publishedResourceReferenceService.isSemanticModelReferenced(model.getSkillId(), model.getId())) {
			throw CheckedException.badRequest("Published Skill semantic model cannot be deleted");
		}
		semanticModelMapper.deleteById(id);
	}

	@Override
	public BatchImportResult batchImport(SemanticModelBatchImportDTO dto) {
		BatchImportResult result = BatchImportResult.builder()
			.total(dto.getItems().size())
			.successCount(0)
			.failCount(0)
			.build();

		try {
			validateSkillDatasourceBinding(dto.getSkillId(), dto.getDatasourceId());
		}
		catch (Exception e) {
			log.error("校验Skill数据源绑定失败: skillId={}, datasourceId={}", dto.getSkillId(), dto.getDatasourceId(), e);
			result.setFailCount(dto.getItems().size());
			result.addError("校验Skill数据源绑定失败: " + e.getMessage());
			return result;
		}

		// 整批只读一次已存在的语义模型，替代逐行回表判重；
		// 谓词与逐行查询保持一致（skillId + datasourceId + supersededById IS NULL，不看 status）
		Set<String> existingKeys = semanticModelMapper.selectBySkillId(dto.getSkillId()).stream()
			.filter(model -> model != null && Objects.equals(dto.getDatasourceId(), model.getDatasourceId()))
			.map(model -> semanticModelKey(model.getTableName(), model.getColumnName()))
			.collect(Collectors.toCollection(HashSet::new));

		for (int i = 0; i < dto.getItems().size(); i++) {
			SemanticModelImportItem item = dto.getItems().get(i);
			try {
				String itemKey = semanticModelKey(item.getTableName(), item.getColumnName());
				// 本批已导入的也要计入，否则同一个 Excel 里重复两行会双双插入
				if (!existingKeys.add(itemKey)) {
					result.setFailCount(result.getFailCount() + 1);
					result.addError(buildDuplicateImportMessage(i + 1, item));
					continue;
				}

				SemanticModel newModel = SemanticModel.builder()
					.skillId(dto.getSkillId())
					.datasourceId(dto.getDatasourceId())
					.tableName(item.getTableName())
					.columnName(item.getColumnName())
					.businessName(item.getBusinessName())
					.synonyms(item.getSynonyms())
					.businessDescription(item.getBusinessDescription())
					.columnComment(item.getColumnComment())
					.dataType(item.getDataType())
					.status(true)
					.createTime(item.getCreateTime() != null ? item.getCreateTime() : Instant.now())
					.build();
				insertSemanticModel(newModel, buildDuplicateImportMessage(i + 1, item));
				log.info("插入语义模型: skillId={}, datasourceId={}, tableName={}, columnName={}", dto.getSkillId(),
						dto.getDatasourceId(), item.getTableName(), item.getColumnName());

				result.setSuccessCount(result.getSuccessCount() + 1);
			}
			catch (Exception e) {
				log.error("导入第{}条记录失败: datasourceId={}, tableName={}, columnName={}", i + 1, dto.getDatasourceId(),
						item.getTableName(), item.getColumnName(), e);
				result.setFailCount(result.getFailCount() + 1);
				result.addError(String.format("第%d条记录导入失败（%s.%s）: %s", i + 1, item.getTableName(), item.getColumnName(),
						e.getMessage()));
			}
		}

		return result;
	}

	@Override
	public BatchImportResult importFromExcel(InputStream inputStream, String filename, Long skillId,
			Long datasourceId) {
		log.info("开始Excel导入: skillId={}, datasourceId={}, 文件={}", skillId, datasourceId, filename);

		try {
			List<SemanticModelImportItem> items = excelService.parseExcel(inputStream, filename);

			SemanticModelBatchImportDTO dto = SemanticModelBatchImportDTO.builder()
				.skillId(skillId)
				.datasourceId(datasourceId)
				.items(items)
				.build();

			BatchImportResult result = batchImport(dto);
			log.info("Excel导入完成: 总数={}, 成功={}, 失败={}", result.getTotal(), result.getSuccessCount(),
					result.getFailCount());

			return result;
		}
		catch (Exception e) {
			log.error("Excel导入失败", e);
			BatchImportResult result = BatchImportResult.builder().total(0).successCount(0).failCount(0).build();
			result.addError("Excel导入失败: " + e.getMessage());
			return result;
		}
	}

	@Override
	public Mono<BatchImportResult> importFromExcel(SemanticModelExcelImportReq request) {
		return Mono.fromCallable(() -> {
			try (InputStream inputStream = request.getFile().getInputStream()) {
				return importFromExcel(inputStream, request.getFile().getOriginalFilename(), request.getSkillId(),
						request.getDatasourceId());
			}
		}).subscribeOn(Schedulers.boundedElastic()).onErrorResume(IllegalArgumentException.class, e -> {
			log.error("Excel import failed: {}", e.getMessage());
			return Mono.error(CheckedException.badRequest("Excel import failed: " + e.getMessage()));
		}).onErrorResume(Exception.class, e -> {
			log.error("Excel import failed", e);
			return Mono.error(CheckedException.fail("Excel import failed: " + e.getMessage()));
		});
	}

	@Override
	public byte[] downloadTemplate(HttpServletResponse response) {
		ClassPathResource resource = new ClassPathResource(TEMPLATE_RESOURCE_PATH);
		if (!resource.exists()) {
			throw CheckedException.notFound("Template file does not exist");
		}

		try (InputStream inputStream = resource.getInputStream()) {
			byte[] template = StreamUtils.copyToByteArray(inputStream);
			response.setContentType(MediaType.APPLICATION_OCTET_STREAM_VALUE);
			response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
					"attachment; filename=\"" + TEMPLATE_FILE_NAME + "\"");
			return template;
		}
		catch (IOException e) {
			log.error("Failed to read semantic model template", e);
			throw CheckedException.fail("Failed to read semantic model template");
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public void updateSemanticModel(Long id, SemanticModelUpdateReq semanticModelUpdateDto) {
		SemanticModel existing = semanticModelMapper.selectById(id);
		if (existing == null) {
			throw CheckedException.notFound("语义模型不存在, id=" + id);
		}
		if (publishedResourceReferenceService.isSemanticModelReferenced(existing.getSkillId(), existing.getId())) {
			SemanticModel replacement = SemanticModel.builder()
				.skillId(existing.getSkillId())
				.datasourceId(existing.getDatasourceId())
				.tableName(existing.getTableName())
				.columnName(existing.getColumnName())
				.businessName(semanticModelUpdateDto.getBusinessName())
				.synonyms(semanticModelUpdateDto.getSynonyms())
				.businessDescription(semanticModelUpdateDto.getBusinessDescription())
				.columnComment(semanticModelUpdateDto.getColumnComment())
				.dataType(semanticModelUpdateDto.getDataType())
				.status(existing.getStatus())
				.supersededById(existing.getId())
				.build();
			insertSemanticModel(replacement, DUPLICATE_SEMANTIC_MODEL_MESSAGE);
			existing.setSupersededById(replacement.getId());
			semanticModelMapper.updateById(existing);
			replacement.setSupersededById(null);
			semanticModelMapper.updateById(replacement);
			return;
		}
		existing.setBusinessName(semanticModelUpdateDto.getBusinessName());
		existing.setSynonyms(semanticModelUpdateDto.getSynonyms());
		existing.setBusinessDescription(semanticModelUpdateDto.getBusinessDescription());
		existing.setColumnComment(semanticModelUpdateDto.getColumnComment());
		existing.setDataType(semanticModelUpdateDto.getDataType());
		semanticModelMapper.updateById(existing);
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public SemanticModel updateSemanticModelAndReturn(Long id, SemanticModelUpdateReq semanticModelUpdateDto) {
		updateSemanticModel(id, semanticModelUpdateDto);
		return requireById(id);
	}

	private List<String> normalizeTableNames(List<String> tableNames) {
		return tableNames.stream()
			.filter(StringUtils::hasText)
			.map(String::trim)
			.map(tableName -> tableName.toLowerCase(Locale.ROOT))
			.distinct()
			.toList();
	}

	private void insertSemanticModel(SemanticModel semanticModel, String duplicateMessage) {
		try {
			semanticModelMapper.insert(semanticModel);
		}
		catch (DuplicateKeyException e) {
			throw CheckedException.badRequest(duplicateMessage);
		}
	}

	private String semanticModelKey(String tableName, String columnName) {
		return tableName + "\u0000" + columnName;
	}

	private String buildDuplicateImportMessage(int rowNumber, SemanticModelImportItem item) {
		return String.format("第%d条记录已存在，请修改后再导入（%s.%s）", rowNumber, item.getTableName(), item.getColumnName());
	}

}
