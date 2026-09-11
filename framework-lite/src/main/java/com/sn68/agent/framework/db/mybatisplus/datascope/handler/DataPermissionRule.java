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

package com.sn68.agent.framework.db.mybatisplus.datascope.handler;

import com.sn68.agent.framework.commons.entity.Entity;
import com.sn68.agent.framework.commons.security.DataRefType;
import com.sn68.agent.framework.commons.security.DataScopeType;
import lombok.*;

import java.util.List;

/**
 * 数据权限的规则抽象类
 *
 * @author Levin
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataPermissionRule {

    /**
     * 是否忽略数据权限拦截
     */
    private boolean ignore;

    /**
     * 规则字段
     */
    private List<Column> columns;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Column {

        /**
         * 表别名
         */
        private String alias;

        /**
         * 字段名称
         */
        @Builder.Default
        private String name = Entity.CREATE_USER_COLUMN;

        @Builder.Default
        private Class<?> javaClass = String.class;

        /**
         * 权限资源范围(默认创建人)
         */
        @Builder.Default
        private DataRefType dataType = DataRefType.USER;

        /**
         * 权限范围（默认跟随系统，如果指定了就跟着指定走）
         */
        @Builder.Default
        private DataScopeType scopeType = DataScopeType.IGNORE;

    }

}
