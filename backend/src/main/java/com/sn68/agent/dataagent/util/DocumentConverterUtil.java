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
package com.sn68.agent.dataagent.util;

import com.sn68.agent.dataagent.bo.schema.ColumnInfoBO;
import com.sn68.agent.dataagent.bo.schema.TableInfoBO;
import com.sn68.agent.dataagent.constant.Constant;
import com.sn68.agent.dataagent.constant.DocumentMetadataConstant;
import com.sn68.agent.dataagent.entity.BusinessKnowledge;
import com.sn68.agent.dataagent.entity.SkillKnowledge;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.document.Document;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Utility class for converting business objects to Document objects. Provides common
 * document conversion functionality for vector store operations.
 */
@Slf4j
public class DocumentConverterUtil {

	public static List<Document> convertColumnsToDocuments(Long skillId, Long datasourceId,
			List<TableInfoBO> tables) {
		List<Document> documents = new ArrayList<>();
		for (TableInfoBO table : tables) {
			// 使用已经处理过的列数据，避免重复查询
			List<ColumnInfoBO> columns = table.getColumns();
			if (columns != null) {
				for (ColumnInfoBO column : columns) {
					documents.add(DocumentConverterUtil.convertColumnToDocument(skillId, datasourceId, table, column));
				}
			}
		}
		return documents;
	}

	/**
	 * Converts a column info object to a Document for vector storage.
	 * @param datasourceId the datasource ID
	 * @param tableInfoBO the table information containing schema details
	 * @param columnInfoBO the column information to convert
	 * @return Document object with column metadata
	 */
	public static Document convertColumnToDocument(Long skillId, Long datasourceId, TableInfoBO tableInfoBO,
			ColumnInfoBO columnInfoBO) {
		String text = StringUtils.isBlank(columnInfoBO.getDescription()) ? columnInfoBO.getName()
				: columnInfoBO.getDescription();
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("name", columnInfoBO.getName());
		metadata.put("tableName", tableInfoBO.getName());
		metadata.put("description", Optional.ofNullable(columnInfoBO.getDescription()).orElse(""));
		metadata.put("type", columnInfoBO.getType());
		metadata.put("primary", columnInfoBO.isPrimary());
		metadata.put("notnull", columnInfoBO.isNotnull());
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.COLUMN);
		metadata.put(Constant.SKILL_ID, skillId.toString());
		metadata.put(Constant.DATASOURCE_ID, datasourceId.toString());

		if (columnInfoBO.getSamples() != null) {
			metadata.put("samples", columnInfoBO.getSamples());
		}

		return new Document(text, metadata);
	}

	/**
	 * Converts a table info object to a Document for vector storage.
	 * @param datasourceId the datasource ID
	 * @param tableInfoBO the table information to convert
	 * @return Document object with table metadata
	 */
	public static Document convertTableToDocument(Long skillId, Long datasourceId, TableInfoBO tableInfoBO) {
		String text = StringUtils.isBlank(tableInfoBO.getDescription()) ? tableInfoBO.getName()
				: tableInfoBO.getDescription();
		Map<String, Object> metadata = new HashMap<>();
		metadata.put("schema", Optional.ofNullable(tableInfoBO.getSchema()).orElse(""));
		metadata.put("name", tableInfoBO.getName());
		metadata.put("description", Optional.ofNullable(tableInfoBO.getDescription()).orElse(""));
		metadata.put("foreignKey", Optional.ofNullable(tableInfoBO.getForeignKey()).orElse(""));
		metadata.put("primaryKey", Optional.ofNullable(tableInfoBO.getPrimaryKeys()).orElse(new ArrayList<>()));
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.TABLE);
		metadata.put(Constant.SKILL_ID, skillId.toString());
		metadata.put(Constant.DATASOURCE_ID, datasourceId.toString());
		return new Document(text, metadata);
	}

	public static List<Document> convertTablesToDocuments(Long skillId, Long datasourceId,
			List<TableInfoBO> tables) {
		return tables.stream()
			.map(table -> DocumentConverterUtil.convertTableToDocument(skillId, datasourceId, table))
			.collect(Collectors.toList());
	}

	public static Document convertBusinessKnowledgeToDocument(BusinessKnowledge businessKnowledge) {

		// 构建文档内容，包含业务名词、说明和同义词
		String businessTerm = businessKnowledge.getBusinessTerm();
		String description = Optional.ofNullable(businessKnowledge.getDescription()).orElse("无");
		String synonyms = Optional.ofNullable(businessKnowledge.getSynonyms()).orElse("无");

		String content = String.format("业务名词: %s, 说明: %s, 同义词: %s", businessTerm, description, synonyms);

		// 构建元数据
		Map<String, Object> metadata = new HashMap<>();
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.BUSINESS_TERM);
		metadata.put(Constant.SKILL_ID, businessKnowledge.getSkillId().toString());
		metadata.put(DocumentMetadataConstant.DB_BUSINESS_TERM_ID, businessKnowledge.getId().toString());

		return new Document(content, metadata);
	}

	public static Document convertSkillKnowledgeToDocument(SkillKnowledge knowledge) {
		String title = Optional.ofNullable(knowledge.getTitle()).orElse("");
		String question = Optional.ofNullable(knowledge.getQuestion()).orElse("");
		String answer = Optional.ofNullable(knowledge.getContent()).orElse("");
		String content = String.format("Title: %s\nQuestion: %s\nAnswer: %s", title, question, answer).trim();
		Map<String, Object> metadata = new HashMap<>();
		metadata.put(Constant.SKILL_ID, String.valueOf(knowledge.getSkillId()));
		metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.SKILL_KNOWLEDGE);
		metadata.put(DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID, String.valueOf(knowledge.getId()));
		metadata.put(DocumentMetadataConstant.CONCRETE_SKILL_KNOWLEDGE_TYPE,
				knowledge.getType() == null ? null : knowledge.getType().getCode());
		return new Document(content, metadata);
	}

	public static List<Document> convertSkillKnowledgeDocumentsWithMetadata(List<Document> documents,
			SkillKnowledge knowledge) {
		List<Document> result = new ArrayList<>();
		for (Document document : documents == null ? List.<Document>of() : documents) {
			Map<String, Object> metadata = new HashMap<>(document.getMetadata());
			metadata.put(Constant.SKILL_ID, String.valueOf(knowledge.getSkillId()));
			metadata.put(DocumentMetadataConstant.VECTOR_TYPE, DocumentMetadataConstant.SKILL_KNOWLEDGE);
			metadata.put(DocumentMetadataConstant.DB_SKILL_KNOWLEDGE_ID, String.valueOf(knowledge.getId()));
			metadata.put(DocumentMetadataConstant.CONCRETE_SKILL_KNOWLEDGE_TYPE,
					knowledge.getType() == null ? null : knowledge.getType().getCode());
			result.add(new Document(document.getId(), document.getText(), metadata));
		}
		return result;
	}

	/**
	 * 给待写入的文档补写「这条向量由哪个 embedding 模型产生」。
	 * <p>
	 * 换成同维度的另一个模型时写入照常成功，但新旧向量处在不同语义空间，余弦比较不再有意义。没有这两个
	 * 字段时检索只会静默退化且事后无从排查，所以写入侧必须留下模型身份。ROUTE 文档在自己的链路上已经写了
	 * 同名指纹，这里不重复覆盖。
	 * @param documents 待写入的文档，metadata 会被就地补写
	 * @param fingerprint embedding 模型指纹，为空表示模型身份未知，此时不写入以免留下假证据
	 * @param dimensions 向量维度
	 */
	public static void stampEmbeddingIdentity(List<Document> documents, String fingerprint, int dimensions) {
		if (documents == null || StringUtils.isBlank(fingerprint) || dimensions <= 0) {
			return;
		}
		for (Document document : documents) {
			if (document == null || document.getMetadata() == null) {
				continue;
			}
			if (DocumentMetadataConstant.ROUTE.equals(document.getMetadata().get(DocumentMetadataConstant.VECTOR_TYPE))) {
				continue;
			}
			document.getMetadata().put(DocumentMetadataConstant.EMBEDDING_FINGERPRINT, fingerprint);
			document.getMetadata().put(DocumentMetadataConstant.EMBEDDING_DIMENSION, dimensions);
		}
	}

	/**
	 * Private constructor to prevent instantiation.
	 */
	private DocumentConverterUtil() {
		throw new AssertionError("Cannot instantiate utility class");
	}

}
