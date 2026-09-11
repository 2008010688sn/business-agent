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

package com.sn68.agent.framework.db.mybatisplus.datascope.util;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.sn68.agent.framework.commons.entity.Entity;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.commons.security.DataPermission;
import com.sn68.agent.framework.commons.security.DataRefType;
import com.sn68.agent.framework.commons.security.DataScopeType;
import com.sn68.agent.framework.db.mybatisplus.datascope.annotation.DataScope;
import com.sn68.agent.framework.db.mybatisplus.datascope.handler.DataPermissionRule;
import com.sn68.agent.framework.db.mybatisplus.datascope.holder.DataPermissionRuleHolder;
import com.sn68.agent.framework.db.utils.MyBatisUtils;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ParenthesedExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * @author Levin
 */
@Slf4j
public final class DataPermissionHelper {

    /**
     * 数据权限本地缓存,减少解析耗时 越用越流畅 遥遥领先
     */
    private static final Map<String, DataPermissionRule> DATA_SCOPE_CACHE = Maps.newConcurrentMap();

    private DataPermissionHelper() {

    }

    /**
     * 使用指定的数据权限执行任务
     *
     * @param supplier 待执行的动作
     */
    public static <T> T withDefaultStrategy(Supplier<T> supplier) {
        // 根据默认权限规则查询
        DataPermissionRule rule = DataPermissionRule.builder().columns(List.of(new DataPermissionRule.Column())).build();
        return withStrategy(rule, supplier);
    }

    /**
     * 使用指定的数据权限执行任务
     *
     * @param supplier 待执行的动作
     */
    public static <T> T withStrategy(boolean condition, DataPermissionRule rule, Supplier<T> supplier) {
        if (!condition) {
            return supplier.get();
        }
        return withStrategy(rule, supplier);
    }

    /**
     * 使用指定的数据权限执行任务
     *
     * @param rule     当前任务执行时使用的数据权限规则
     * @param supplier 待执行的动作
     */
    public static <T> T withStrategy(DataPermissionRule rule, Supplier<T> supplier) {
        try {
            DataPermissionRuleHolder.push(rule);
            return supplier.get();
        } finally {
            DataPermissionRuleHolder.poll();
        }
    }

    @SneakyThrows
    public static DataPermissionRule getRuleByMappedStatementId(String mappedStatementId) {
        if (DATA_SCOPE_CACHE.containsKey(mappedStatementId)) {
            return DATA_SCOPE_CACHE.get(mappedStatementId);
        }
        final Class<?> clazz = Class.forName(mappedStatementId.substring(0, mappedStatementId.lastIndexOf(".")));
        final Method[] methods = clazz.getMethods();
        for (Method method : methods) {
            if (!method.getName().equals(getMethodName(mappedStatementId))) {
                continue;
            }
            var rule = buildPermissionRule(method.getAnnotation(DataScope.class));
            if (rule == null) {
                return null;
            }
            DATA_SCOPE_CACHE.put(mappedStatementId, rule);
            return rule;
        }
        return null;
    }

    private static DataPermissionRule buildPermissionRule(DataScope scope) {
        if (scope == null) {
            return null;
        }
        final List<DataPermissionRule.Column> columns = Arrays.stream(scope.columns())
                .map(column -> DataPermissionRule.Column.builder().alias(column.alias()).name(column.name())
                        .dataType(column.dataType()).javaClass(column.javaClass()).build())
                .toList();
        return DataPermissionRule.builder().ignore(scope.ignore()).columns(columns).build();
    }

    public static List<Expression> buildConditions(AuthenticationContext context, final Table table, final List<DataPermissionRule.Column> columns) {
        final DataPermission permission = Optional.ofNullable(context.dataPermission()).orElseGet(DataPermission::new);
        final Map<DataRefType, List<Object>> dataPermissionMap = Optional.ofNullable(permission.getDataPermissionMap()).orElseGet(Map::of);
        final List<Expression> conditions = Lists.newArrayList();
        for (DataPermissionRule.Column column : columns) {
            final DataScopeType permissionScopeType = Optional.ofNullable(permission.getScopeType()).orElse(DataScopeType.IGNORE);
            final DataScopeType scopeType = column.getScopeType() == DataScopeType.IGNORE ? permissionScopeType : column.getScopeType();
            if (scopeType == DataScopeType.ALL) {
                continue;
            }
            Alias alias = table.getAlias();
            if (alias == null && StrUtil.isNotBlank(column.getAlias())) {
                continue;
            }
            if (alias != null && !StrUtil.equals(alias.getName(), column.getAlias())) {
                continue;
            }
            final List<?> valList = dataPermissionMap.get(column.getDataType());
            if (CollUtil.isEmpty(valList) || (scopeType == DataScopeType.SELF && column.getDataType() == DataRefType.USER)) {
                //20250724 没配置默认查询全部.
                //如果是用户维度的隔离,当没有权限的时候还是增加一下只能查当前创建人的数据
                if (column.getDataType() == DataRefType.USER) {
                    final Column selfColumn = MyBatisUtils.buildColumn(table, Entity.CREATE_USER_COLUMN);
                    conditions.add(new EqualsTo(selfColumn, new StringValue(context.userId())));
                    log.warn("当前数据类型 {},下未分配权限,查询自己创建的数据", column.getDataType());
                }else {
                    log.warn("当前数据类型 {},下未分配权限,默认查询全部", column.getDataType());
                }
                continue;
            }
            if (valList.contains(DataRefType.ALL.getType())) {
                log.warn("当前数据类型 {},分配了全部数据,跳过数据权限的织入动作", column.getDataType());
                continue;
            }
            final Class<?> javaClass = column.getJavaClass();
            var itemsList = new ParenthesedExpressionList<>(valList.stream().filter(Objects::nonNull)
                    .map(x -> {
                        if (javaClass.equals(Integer.class) || javaClass.equals(Long.class)) {
                            return new LongValue(x.toString());
                        }
                        return new StringValue(x.toString());
                    }).collect(Collectors.toList()));
            conditions.add(new InExpression(MyBatisUtils.buildColumn(table, column.getName()), itemsList));
        }
        return conditions;
    }

    private static String getMethodName(String mappedStatementId) {
        return mappedStatementId.substring(mappedStatementId.lastIndexOf(".") + 1);
    }


}
