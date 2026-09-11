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

package com.sn68.agent.framework.db.mybatisplus.handler.type;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * List 类型处理器（智能兼容）
 * 
 * 支持两种格式：
 * 1. JSON 数组格式：[{"key":"value"}] -> List<Map<String, Object>>
 * 2. 逗号分隔格式：a,b,c -> List<String>
 * 
 * 自动识别数据格式，兼容处理
 *
 * @author Levin
 */
@Slf4j
public class JsonListMapTypeHandler extends BaseTypeHandler<List<?>> {

    private static final TypeReference<List<Map<String, Object>>> TYPE_REF = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<?> parameter, JdbcType jdbcType) throws SQLException {
        if (parameter == null || parameter.isEmpty()) {
            ps.setString(i, null);
            return;
        }
        // 判断是否是 Map 类型的 List，如果是则用 JSON，否则用逗号分隔
        Object first = parameter.get(0);
        if (first instanceof Map) {
            ps.setString(i, JSON.toJSONString(parameter));
        } else {
            ps.setString(i, StrUtil.join(",", parameter));
        }
    }

    @Override
    public List<?> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseValue(rs.getString(columnName));
    }

    @Override
    public List<?> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseValue(rs.getString(columnIndex));
    }

    @Override
    public List<?> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseValue(cs.getString(columnIndex));
    }

    /**
     * 智能解析值
     * - JSON 数组格式：尝试 JSON 解析
     * - 否则：按逗号分隔处理
     */
    private List<?> parseValue(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        // 使用 Fastjson2 的 isValidArray 判断是否为 JSON 数组
        if (JSON.isValidArray(value)) {
            try {
                return JSON.parseObject(value, TYPE_REF);
            } catch (Exception e) {
                log.warn("JSON 解析失败，回退到逗号分隔处理: {}", e.getMessage());
            }
        }
        // 非 JSON 格式，按逗号分隔处理
        return StrUtil.split(value, ",");
    }
}
