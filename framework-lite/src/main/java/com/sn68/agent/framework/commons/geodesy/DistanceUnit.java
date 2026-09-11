package com.sn68.agent.framework.commons.geodesy;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;

/**
 * @author Levin
 */
@Getter
@RequiredArgsConstructor
public enum DistanceUnit {

    METERS(1000.0, "米"),

    KILOMETERS(1.0, "千米"),

    MILES(0.621371, "英里");

    /**
     * 相对于千米的转换因子
     */
    private final double factor;
    private final String unitName;

    /**
     * 转换成千米
     *
     * @param distance 千米
     * @return 转换结果
     */
    public double toKm(BigDecimal distance) {
        if (distance == null) {
            return 0D;
        }
        // 对于米，需要除以1000转换为千米
        if (this == METERS) {
            return distance.doubleValue() / factor;
        }
        // 其他单位使用factor转换
        return distance.doubleValue() * factor;
    }

    /**
     * 转换成千米
     *
     * @param distance 千米
     * @return 转换结果
     */
    public double toKm(double distance) {
        // 对于米，需要除以1000转换为千米
        if (this == METERS) {
            return distance / factor;
        }
        // 其他单位使用factor转换
        return distance * factor;
    }
}
