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
package com.sn68.agent.dataagent.service.report;

import com.sn68.agent.dataagent.entity.DatasourceColumn;
import com.sn68.agent.dataagent.entity.DatasourceTable;
import com.sn68.agent.dataagent.entity.SemanticModel;
import com.sn68.agent.dataagent.repository.DatasourceColumnMapper;
import com.sn68.agent.dataagent.repository.DatasourceTableMapper;
import com.sn68.agent.dataagent.repository.SemanticModelMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 按数据源 + Skill 装载列业务名，供 SEARCH 结果命名。失败时返回空词表，不阻断查询。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchResultColumnLexiconFactory {

	private final DatasourceColumnMapper datasourceColumnMapper;

	private final DatasourceTableMapper datasourceTableMapper;

	private final SemanticModelMapper semanticModelMapper;

	public SearchResultColumnNamer.Lexicon load(Long datasourceId, Long skillId, List<String> tables) {
		SearchResultColumnNamer.Lexicon lexicon = SearchResultColumnNamer.lexicon();
		if (datasourceId == null || tables == null || tables.isEmpty()) {
			return lexicon;
		}
		try {
			for (String table : tables) {
				if (!StringUtils.hasText(table)) {
					continue;
				}
				DatasourceTable tableMeta = datasourceTableMapper.selectByDatasourceIdAndTableName(datasourceId, table);
				if (tableMeta != null) {
					lexicon.addTable(table, tableMeta.getBusinessName(), tableMeta.getTableComment());
				}
				List<DatasourceColumn> columns = datasourceColumnMapper.selectByDatasourceIdAndTableName(datasourceId,
						table);
				if (columns == null) {
					continue;
				}
				for (DatasourceColumn column : columns) {
					if (column == null) {
						continue;
					}
					lexicon.addColumn(table, column.getColumnName(), column.getBusinessName(), column.getColumnComment());
				}
			}
			if (skillId != null) {
				List<SemanticModel> models = semanticModelMapper
					.selectEnabledBySkillIdAndDatasourceIdAndTableNames(skillId, datasourceId, tables);
				if (models != null) {
					for (SemanticModel model : models) {
						if (model == null) {
							continue;
						}
						lexicon.addColumn(model.getTableName(), model.getColumnName(), model.getBusinessName(),
								model.getColumnComment(), firstSynonym(model.getSynonyms()));
					}
				}
			}
		}
		catch (RuntimeException ex) {
			log.warn("Failed to load result column lexicon. datasourceId={}, skillId={}", datasourceId, skillId, ex);
		}
		return lexicon;
	}

	private static String firstSynonym(String synonyms) {
		if (!StringUtils.hasText(synonyms)) {
			return "";
		}
		for (String item : synonyms.split("[,，;；/|、\\s]+")) {
			if (StringUtils.hasText(item)) {
				return item.trim();
			}
		}
		return "";
	}

}
