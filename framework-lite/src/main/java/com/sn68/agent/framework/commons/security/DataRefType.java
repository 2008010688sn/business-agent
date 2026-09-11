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

package com.sn68.agent.framework.commons.security;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * <p>
 * 数据关联类型
 * </p>
 *
 * @author Levin
 */
@Getter
@JsonFormat
@AllArgsConstructor
public enum DataRefType {

    ALL("all", "指当提类型下的全部数据"),
    SITE_RELATED("site-related", "指所选客户的所有关联网点"),
    PROJECT_RELATED("project-related", "指所选客户的所有关联二级项目"),
    /**
     * 用户（比如你可以根据 地区、机构、公司、网点等维度控制权限，只需要指定数据资源类型即可）
     */
    USER("user", "用户维度"),
    ROLE("role", "角色维度"),
    ORG("org", "机构维度"),
    //    TENANT("tenant", "租户维度"),
    COMPANY("company", "公司维度"),
    SITE("site", "网点维度"),
    INVESTOR("investor", "投资方"),
    OPERATION_SITE("operationSite", "操作网点"),
//    FROM_SITE("fromSite", "提货网点"),
//    TO_SITE("toSite", "送达网点"),
    TWO_PROJECT("twoProject", "二级项目"),
//    AREA("area", "地区维度"),
    ;

    @EnumValue
    @JsonValue
    private final String type;
    private final String desc;

    @JsonCreator
    public static DataRefType of(String type) {
        if (type == null) {
            return null;
        }
        for (DataRefType info : values()) {
            if (info.type.equals(type)) {
                return info;
            }
        }
        return null;
    }
}
