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

package com.sn68.agent.framework.db.configuration;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.DataPermissionInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.sn68.agent.framework.commons.entity.enums.UserType;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.mybatisplus.audit.AuditInterceptor;
import com.sn68.agent.framework.db.mybatisplus.datascope.handler.DataScopePermissionHandler;
import com.sn68.agent.framework.db.mybatisplus.encrypt.utils.FieldEncryptKeyHelper;
import com.sn68.agent.framework.db.mybatisplus.encrypt.interceptor.FieldEncryptInterceptor;
import com.sn68.agent.framework.db.mybatisplus.handler.MyBatisMetaObjectHandler;
import com.sn68.agent.framework.db.mybatisplus.handler.type.StringToListTypeHandler;
import com.sn68.agent.framework.db.properties.DatabaseProperties;
import com.sn68.agent.framework.db.properties.MultiTenantType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Objects;

/**
 * Mybatis 常用重用拦截器
 * <p>
 * 拦截器执行一定是：
 * WriteInterceptor > DataScopeInterceptor > PaginationInterceptor
 *
 * @author Levin
 * @since 2018/10/24
 */
@Slf4j
@RequiredArgsConstructor
@Configuration
@EnableConfigurationProperties({DatabaseProperties.class})
public class BaseMybatisConfiguration {

    private final DatabaseProperties properties;
    private final AuthenticationContext context;

    @Bean
    @ConditionalOnMissingBean
    public FieldEncryptKeyHelper fieldEncryptKeyHelper() {
        FieldEncryptKeyHelper fieldEncryptKeyHelper = new FieldEncryptKeyHelper();
        fieldEncryptKeyHelper.setEncryptProperties(properties.getEncryptField());
        return fieldEncryptKeyHelper;
    }

    /**
     * 新的分页插件,一缓和二缓遵循mybatis的规则,
     * 需要设置 MybatisConfiguration#useDeprecatedExecutor = false
     * 避免缓存出现问题(该属性会在旧插件移除后一同移除)
     */
    @Bean
    @Order(5)
    @ConditionalOnMissingBean
    public MybatisPlusInterceptor mybatisPlusInterceptor(FieldEncryptKeyHelper fieldEncryptKeyHelper) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        final DatabaseProperties.MultiTenant multiTenant = properties.getMultiTenant();
        if (MultiTenantType.NONE != multiTenant.getType()) {
            // 新增多租户拦截器
            interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(new TenantLineHandler() {

                @Override
                public Expression getTenantId() {
                    // 租户ID
                    log.debug("当前租户ID - {}", context.tenantId());
                    return context.tenantId() == null ? null : new StringValue(context.tenantId());
                }

                @Override
                public boolean ignoreTable(String tableName) {
                    final List<String> tables = multiTenant.getIncludeTables();
                    // 判断哪些表不需要进行多租户判断,返回false表示都需要进行多租户判断
                    return context.anonymous() || !tables.contains(tableName) || Objects.equals(UserType.PLATFORM_ADMIN, context.userType());
                }

                @Override
                public String getTenantIdColumn() {
                    return multiTenant.getTenantIdColumn();
                }

            }));
        }
        // 加载其它插件
        loadInnerInterceptor(interceptor, fieldEncryptKeyHelper);
        return interceptor;
    }

    /**
     * mybatis-plus 分页插件
     *
     * @param pagination 参数配置
     * @return 插件
     */
    public PaginationInnerInterceptor paginationInnerInterceptor(final DatabaseProperties.Pagination pagination) {
        // 新增MYSQL分页拦截器,一定要先设置租户判断后才进行分页拦截设置
        PaginationInnerInterceptor paginationInnerInterceptor = new PaginationInnerInterceptor(pagination.getDbType());
        paginationInnerInterceptor.setOverflow(pagination.isOverflow());
        paginationInnerInterceptor.setDialect(pagination.getDialect());
        return paginationInnerInterceptor;
    }

    protected void loadInnerInterceptor(MybatisPlusInterceptor interceptor, FieldEncryptKeyHelper fieldEncryptKeyHelper) {
        final DatabaseProperties.Intercept intercept = properties.getIntercept();
        if (properties.getDataPermission().isEnabled()) {
            // 分页拦截器之前的插件 => 数据权限插件
            interceptor.addInnerInterceptor(new DataPermissionInterceptor(new DataScopePermissionHandler(context)));
        }
        if (intercept.isBlockAttack()) {
            // 防止全表更新与删除插件: BlockAttackInnerInterceptor
            interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        }
        if (properties.getAudit().isEnabled()) {
            interceptor.addInnerInterceptor(new AuditInterceptor(properties.getAudit()));
        }
        if (properties.getEncryptField().isEnabled()) {
            // 字段加密插件
            interceptor.addInnerInterceptor(new FieldEncryptInterceptor(fieldEncryptKeyHelper));
        }
        // 分页插件
        interceptor.addInnerInterceptor(paginationInnerInterceptor(intercept.getPagination()));
    }

    @Bean
    @ConditionalOnMissingBean
    public MetaObjectHandler metaObjectHandler() {
        return new MyBatisMetaObjectHandler(context);
    }

    /**
     * 注册默认的 List TypeHandler
     * 确保 List 类型字段默认使用 StringToListTypeHandler（逗号分隔字符串）
     * 如需 JSON 格式，请在字段上显式指定 @TableField(typeHandler = JsonListMapTypeHandler.class)
     */
    @Bean
    @Order(1)
    public ConfigurationCustomizer listTypeHandlerCustomizer() {
        return configuration -> {
            var registry = configuration.getTypeHandlerRegistry();
            // 强制注册，覆盖任何已有的 List TypeHandler
            registry.register(List.class, new StringToListTypeHandler());
            // 验证注册结果
            var handler = registry.getTypeHandler(List.class);
            log.info("List 类型 TypeHandler 注册完成: {}", handler.getClass().getSimpleName());
        };
    }
}
