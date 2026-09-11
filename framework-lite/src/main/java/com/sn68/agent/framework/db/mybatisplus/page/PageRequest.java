/*
 * Copyright (c) 2023 xx-cloud Authors. All Rights Reserved.
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sn68.agent.framework.db.mybatisplus.page;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 分页参数
 *
 * @author Levin
 * @since 2020-07-08
 */
@Data
@Slf4j
@Schema(description = "分页对象")
public class PageRequest {

    @Schema(description = "当前页码", example = "1")
    @Parameter(description = "当前页码", example = "1")
    private int current = 1;

    @Schema(description = "分页大小", example = "20")
    @Parameter(description = "分页大小", example = "20")
    private int size = 20;

    @Schema(description = "排序字段", example = "id", hidden = true)
    @Parameter(description = "排序字段", hidden = true)
    private String column;

    @Schema(description = "排序规则", example = "true", hidden = true)
    @Parameter(description = "排序规则", hidden = true)
    private Boolean asc = true;

    @JsonIgnore
    public <T> Page<T> buildPage() {
        return buildPage(true);
    }

    /**
     * 相同的表 JOIN 就不需要擦除了，否则会影响分页准确性,不同表JOIN 可以擦除，提升分页性能
     *
     * @param optimizeJoinOfCountSql 自动优化 COUNT SQL 是否把 join 查询部分移除
     * @param <T>                    分页结果
     * @return 分页参数
     */
    @JsonIgnore
    public <T> Page<T> buildPage(boolean optimizeJoinOfCountSql) {
        PageRequest params = this;
        Page<T> page = new Page<>(params.getCurrent(), params.getSize());
        page.setOptimizeJoinOfCountSql(optimizeJoinOfCountSql);
        if (StringUtils.isBlank(params.getColumn())) {
            return page;
        }
        List<OrderItem> orders = new ArrayList<>();
        // 简单的 驼峰 转 下划线
        String column = StrUtil.toUnderlineCase(params.getColumn());
        orders.add(params.getAsc() ? OrderItem.asc(column) : OrderItem.desc(column));
        page.setOrders(orders);
        return page;
    }
}
