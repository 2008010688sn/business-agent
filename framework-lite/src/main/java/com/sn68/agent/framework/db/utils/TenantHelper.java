package com.sn68.agent.framework.db.utils;

import cn.hutool.extra.spring.SpringUtil;
import com.baomidou.mybatisplus.core.plugins.IgnoreStrategy;
import com.baomidou.mybatisplus.core.plugins.InterceptorIgnoreHelper;
import com.sn68.agent.framework.commons.security.AuthenticationContext;
import com.sn68.agent.framework.db.properties.DatabaseProperties;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.function.Supplier;

/**
 * 租户工具类（列级隔离；不切换动态数据源）。
 *
 * @author Levin
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class TenantHelper {

    public static boolean isSuperTenant() {
        DatabaseProperties properties = SpringUtil.getBean(DatabaseProperties.class);
        AuthenticationContext context = SpringUtil.getBean(AuthenticationContext.class);
        String tenantCode = context.tenantCode();
        return StringUtils.isNotBlank(tenantCode) && StringUtils.equals(tenantCode, properties.getMultiTenant().getSuperTenantCode());
    }

    /**
     * 使用主数据源执行。lite 模块不切换数据源，直接执行。
     *
     * @param supplier 待执行的动作
     */
    public static <T> T executeWithMaster(Supplier<T> supplier) {
        return supplier.get();
    }

    /**
     * 使用指定租户上下文执行。lite 模块不切换数据源，直接执行。
     *
     * @param supplier 待执行的动作
     */
    public static <T> T executeWithTenantDb(String tenantCode, Supplier<T> supplier) {
        return supplier.get();
    }

    public static boolean isDynamicSource() {
        return false;
    }

    /**
     * 使用隔离类型执行
     *
     * @param dbSupplier     数据源函数
     * @param columnSupplier 字段隔离函数
     * @param <T>            返回类型
     * @return 查询结果
     */
    public static <T> T executeWithIsolationType(Supplier<T> dbSupplier, Supplier<T> columnSupplier) {
        return isDynamicSource() ? dbSupplier.get() : columnSupplier.get();
    }

    public static <T> T withIgnoreStrategy(Supplier<T> block) {
        return withIgnoreStrategy(IgnoreStrategy.builder().tenantLine(true).build(), block);
    }

    public static <T> T withIgnoreStrategy(IgnoreStrategy strategy, Supplier<T> block) {
        try {
            InterceptorIgnoreHelper.handle(strategy);
            return block.get();
        } finally {
            InterceptorIgnoreHelper.clearIgnoreStrategy();
        }
    }
}
