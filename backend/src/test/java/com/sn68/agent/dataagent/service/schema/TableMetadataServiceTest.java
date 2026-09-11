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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sn68.agent.dataagent.bo.DbConfigBO;
import com.sn68.agent.dataagent.bo.schema.ColumnInfoBO;
import com.sn68.agent.dataagent.bo.schema.ResultSetBO;
import com.sn68.agent.dataagent.bo.schema.TableInfoBO;
import com.sn68.agent.dataagent.connector.DbQueryParameter;
import com.sn68.agent.dataagent.connector.accessor.Accessor;
import com.sn68.agent.dataagent.connector.accessor.AccessorFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TableMetadataServiceTest {

	private static final String TABLE_NAME = "orders";

	private static final String COLUMN_NAME = "remark";

	private final Accessor accessor = mock(Accessor.class);

	private final ObjectMapper objectMapper = new ObjectMapper();

	private TableMetadataService service;

	@BeforeEach
	void setUp() throws Exception {
		when(accessor.getAccessorType()).thenReturn("test");
		when(accessor.supportedDataSourceType("postgresql")).thenReturn(true);
		when(accessor.showColumns(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenReturn(List.of(ColumnInfoBO.builder().name(COLUMN_NAME).build()));

		service = new TableMetadataService(new AccessorFactory(List.of(accessor)), objectMapper);
	}

	@Test
	void oversizedLeadingValuesDoNotStarveColumnSamples() throws Exception {
		stubSampleRows(oversizedValue('A'), oversizedValue('B'), oversizedValue('C'), "已完成", "已取消");

		assertEquals(List.of("已完成", "已取消"), enrichAndReadSamples());
	}

	@Test
	void samplesStillStopAtThreeShortValues() throws Exception {
		stubSampleRows("待支付", "已支付", "已完成", "已取消", "已退款");

		assertEquals(List.of("待支付", "已支付", "已完成"), enrichAndReadSamples());
	}

	@Test
	void repeatedValuesAreDeduplicatedBeforeTheSampleCap() throws Exception {
		stubSampleRows("已完成", "已完成", "已完成", "已取消", "已退款");

		assertEquals(List.of("已完成", "已取消", "已退款"), enrichAndReadSamples());
	}

	private List<String> enrichAndReadSamples() throws Exception {
		TableInfoBO table = TableInfoBO.builder().name(TABLE_NAME).build();

		service.batchEnrichTableMetadata(new ArrayList<>(List.of(table)), dbConfig(), Map.of());

		return objectMapper.readValue(table.getColumns().get(0).getSamples(), new TypeReference<List<String>>() {
		});
	}

	private void stubSampleRows(String... values) throws Exception {
		List<Map<String, String>> rows = new ArrayList<>();
		for (String value : values) {
			Map<String, String> row = new LinkedHashMap<>();
			row.put(COLUMN_NAME, value);
			rows.add(row);
		}
		when(accessor.executeSqlAndReturnObject(any(DbConfigBO.class), any(DbQueryParameter.class)))
			.thenReturn(ResultSetBO.builder().column(List.of(COLUMN_NAME)).data(rows).build());
	}

	/** 超过样例值 100 字符上限的长文本，例如富文本备注、JSON 快照字段。 */
	private String oversizedValue(char filler) {
		return String.valueOf(filler).repeat(101);
	}

	private DbConfigBO dbConfig() {
		return DbConfigBO.builder().dialectType("PostgreSQL").connectionType("jdbc").build();
	}

}
