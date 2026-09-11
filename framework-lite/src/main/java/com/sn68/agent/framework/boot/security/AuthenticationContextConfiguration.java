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

package com.sn68.agent.framework.boot.security;

import cn.hutool.core.collection.CollUtil;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.commons.security.DataPermission;
import com.sn68.agent.framework.commons.security.DataPermissionResolver;
import com.sn68.agent.framework.commons.threadlocal.ThreadLocalHolder;
import com.sn68.agent.framework.db.mybatisplus.datascope.handler.DataPermissionRule;
import com.sn68.agent.framework.db.mybatisplus.datascope.service.DataScopeService;
import com.sn68.agent.framework.db.mybatisplus.datascope.util.DataPermissionHelper;
import com.sn68.agent.framework.security.domain.UserInfoDetails;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Authentication context backed only by {@link ThreadLocalHolder}.
 * Backend filters (for example OpenAccessFilter) inject {@code USER_INFO_KEY}.
 *
 * @author Levin
 */
@Slf4j
@Configuration
public class AuthenticationContextConfiguration {

    static final String USER_INFO = "USER_INFO_KEY";
    private static final String ANONYMOUS = "USER_ANONYMOUS_KEY";
    private static final String MERGED_DATA_PERMISSION = "MERGED_DATA_PERMISSION_KEY";

    private static List<String> normalizeTeamIds(List<String> teamIds) {
        if (CollUtil.isEmpty(teamIds)) {
            return List.of();
        }
        return teamIds.stream()
                .filter(teamId -> teamId != null && !teamId.isBlank())
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> toList(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(values);
    }

    @Bean
    public AuthenticationContext authenticationContext(ObjectProvider<DataScopeService> dataScopeServiceProvider,
                                                       ObjectProvider<DataPermissionResolver> dataPermissionResolverProvider) {
        return new AuthenticationContext() {
            private final ThreadLocal<Boolean> resolving = ThreadLocal.withInitial(() -> false);

            private UserInfoDetails loadDataPermissionIfNecessary() {
                UserInfoDetails info = getContext();
                if (info == null || Boolean.TRUE.equals(info.getDataPermissionLoaded())) {
                    return info;
                }
                DataScopeService dataScopeService = dataScopeServiceProvider.getIfAvailable();
                if (dataScopeService == null) {
                    return info;
                }
                DataPermission dataPermission = DataPermissionHelper.withStrategy(
                        DataPermissionRule.builder().ignore(true).build(),
                        () -> dataScopeService.getDataScopeById(info.getUserId()));
                info.setDataPermission(dataPermission);
                info.setDataPermissionLoaded(Boolean.TRUE);
                ThreadLocalHolder.set(USER_INFO, info);
                return info;
            }

            @Override
            public UserInfoDetails getContext() {
                Object value = ThreadLocalHolder.get(USER_INFO);
                if (value instanceof UserInfoDetails details) {
                    return details;
                }
                UserInfoDetails demo = UserInfoDetails.builder()
                    .userId("1")
                    .username("sn68")
                    .nickName("Demo User")
                    .tenantId("default")
                    .tenantCode("default")
                    .type(UserType.PLATFORM_ADMIN)
                    .enabled(Boolean.TRUE)
                    .build();
                ThreadLocalHolder.set(USER_INFO, demo);
                return demo;
            }

            @Override
            public String clientId() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getClientId).orElse(null);
            }

            @Override
            public String clientSecret() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getClientSecret).orElse(null);
            }

            @Override
            public String tenantId() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getTenantId).orElse(null);
            }

            @Override
            public String tenantName() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getTenantName).orElse(null);
            }

            @Override
            public String tenantCountry() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getTenantCountry).orElse(null);
            }

            @Override
            public String tenantCode() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getTenantCode).orElse(null);
            }

            @Override
            public String userId() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getUserId).orElse(null);
            }

            @Override
            public UserType userType() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getType).orElse(null);
            }

            @Override
            public String nickName() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getNickName).orElse(null);
            }

            @Override
            public String mobile() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getMobile).orElse(null);
            }

            @Override
            public List<String> funcPermissionList() {
                return Optional.ofNullable(getContext())
                        .map(UserInfoDetails::getFuncPermissions)
                        .map(AuthenticationContextConfiguration::toList)
                        .orElse(List.of());
            }

            @Override
            public List<String> rolePermissionList() {
                return Optional.ofNullable(getContext())
                        .map(UserInfoDetails::getRoles)
                        .map(AuthenticationContextConfiguration::toList)
                        .orElse(List.of());
            }

            @Override
            public DataPermission dataPermission() {
                return (DataPermission) ThreadLocalHolder.get(MERGED_DATA_PERMISSION, () -> {
                    DataPermission userPermission = Optional.ofNullable(loadDataPermissionIfNecessary())
                            .map(UserInfoDetails::getDataPermission)
                            .orElseGet(DataPermission::new);

                    if (resolving.get()) {
                        return userPermission;
                    }

                    DataPermissionResolver dataPermissionResolver = dataPermissionResolverProvider.getIfAvailable();
                    if (dataPermissionResolver == null) {
                        return userPermission;
                    }
                    resolving.set(true);
                    try {
                        return Optional.ofNullable(dataPermissionResolver.resolve(userPermission, normalizeTeamIds(teamIds())))
                                .orElse(userPermission);
                    } finally {
                        resolving.remove();
                    }
                });
            }

            @Override
            public List<String> teamIds() {
                return Optional.ofNullable(getContext()).map(UserInfoDetails::getTeamIds).orElse(List.of());
            }

            @Override
            public boolean anonymous() {
                return (boolean) ThreadLocalHolder.get(ANONYMOUS, () -> getContext() == null, Boolean.FALSE::equals);
            }
        };
    }

}
