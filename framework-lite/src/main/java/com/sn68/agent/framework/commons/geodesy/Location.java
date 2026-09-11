package com.sn68.agent.framework.commons.geodesy;

import lombok.Builder;

/**
 * @param longitude 经度
 * @param latitude  纬度
 * @author Levin
 */
@Builder
public record Location(double longitude, double latitude) {
    public static Location of(double longitude, double latitude) {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("纬度范围 [-90, 90]");
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("经度范围 [-180, 180]");
        }
        return new Location(longitude, latitude);
    }
}