package com.sn68.agent.framework.commons.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * @author Levin
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DynamicTableDTO {

    @Schema(description = "表头信息")
    private TableHeader headers;

    @Schema(description = "表格数据")
    private List<Map<String, Object>> rowList;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableHeader {

        /**
         * 固定列名列表
         */
        @Schema(description = "固定列名列表")
        private List<String> fixedColumns;
        /**
         * 动态列名列表
         */
        @Schema(description = "动态列名列表")
        private List<String> dynamicColumns;

        @Schema(description = "汇总列")
        private String summaryColumn;
    }
}