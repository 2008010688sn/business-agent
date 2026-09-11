package com.sn68.agent.framework.commons.geodesy;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.sn68.agent.framework.commons.geodesy.DistanceUnit.METERS;
import static com.sn68.agent.framework.commons.geodesy.DistanceUnit.MILES;

/**
 * @author Levin
 */
public class GeoUtils {

    /**
     * 地球平均半径(米)
     * 如果有更精细化的需求可以采用 6371.0087714
     */
    private static final double EARTH_RADIUS_KM = 6371.0;

    // 圆周率π
    public static double PI = 3.1415926535897932384626;
    //π的扩展值，用于经度/纬度与弧度的转换（3000.0 / 180.0 是弧度与度的比例因子）
    public static double X_PI = 3.14159265358979324 * 3000.0 / 180.0;

    // 克拉索夫斯基椭球的长半轴（单位：米）
    public static double A = 6378245.0;
    //椭球的第一偏心率平方
    public static double EE = 0.00669342162296594323;

    /**
     * 核心 Haversine 算法（私有方法，避免重复代码）
     *
     * @param loc1 地址1
     * @param loc2 地址2
     * @return 计算结果
     */
    private static double calculateHaversine(Location loc1, Location loc2) {
        double lat1 = Math.toRadians(loc1.latitude());
        double lon1 = Math.toRadians(loc1.longitude());
        double lat2 = Math.toRadians(loc2.latitude());
        double lon2 = Math.toRadians(loc2.longitude());

        double dLat = lat2 - lat1;
        double dLon = lon2 - lon1;

        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        // 默认千米单位
        return EARTH_RADIUS_KM * c;
    }

    /**
     * @param loc1 地址1
     * @param loc2 地址2
     * @param unit 单位
     * @return 计算结果
     */
    public static double calculate(Location loc1, Location loc2, DistanceUnit unit) {
        double km = calculateHaversine(loc1, loc2);
        return switch (unit) {
            case KILOMETERS -> km;
            case METERS -> km * METERS.getFactor();
            case MILES -> km * MILES.getFactor();
        };
    }

    /**
     * @param loc1 地址1
     * @param loc2 地址2
     * @return 计算结果
     */
    public static double calculate(Location loc1, Location loc2) {
        return calculateHaversine(loc1, loc2);
    }


    /**
     * 快速粗筛是否在一定范围内，只用于大致筛选<br/>
     * <p>
     * degree：1 <br/>
     * longitude:经度差1度，距离相差85km左右 <br/>
     * latitude：纬度相差1度，距离相差111km左右 <br/>
     * degree：0.1 <br/>
     * longitude:经度差0.1度，距离相差8.5km左右 <br/>
     * latitude：纬度相差0.1度，距离相差11.1km左右 <br/>
     * <p>
     * degree精度只在0.1~1
     * 粗筛
     *
     * @param l1     坐标1
     * @param l2     坐标2
     * @param deviation 指定精度（1度 ≈ 111km// 纬度相差1度 ≈ 111km，经度相差1度 ≈ 85km（赤道附近）
     *
     * @return
     */
    public static boolean sifting(Location l1, Location l2, Deviation deviation) {
        return sifting(l1.longitude(), l1.latitude(), l2.longitude(), l2.latitude(), deviation);
    }

    public static boolean sifting(Location l1, Location l2) {
        return sifting(l1, l2, Deviation.BASE);
    }

    public static boolean sifting(double lng1, double lat1, double lng2, double lat2) {
        return sifting(lng1, lat1, lng2, lat2, Deviation.BASE);
    }

    public static boolean sifting(double lng1, double lat1, double lng2, double lat2, Deviation deviation) {
        if (deviation == null) {
            throw new IllegalArgumentException("偏差配置不能为空");
        }
        lng1 = (lng1 % 360 + 360) % 360;
        lng2 = (lng2 % 360 + 360) % 360;
        // 处理国际日期变更线情况
        double lngDiff = Math.min(Math.abs(lng1 - lng2), 360 - Math.abs(lng1 - lng2));
        double latDiff = Math.abs(lat1 - lat2);
        return lngDiff <= deviation.getDegree() && latDiff <= deviation.getDegree();
    }


    /**
     * 判断目标点是否在指定中心点的地理围栏半径范围内
     *
     * @param center 中心点坐标（地理围栏的圆心）
     * @param point  待检测的目标点坐标
     * @param radius 地理围栏的半径阈值（需与 unit 单位一致）
     * @param unit   距离单位（如米、千米、英里）
     * @return true 表示目标点在半径范围内，false 表示超出范围
     * @throws IllegalArgumentException 如果 radius 为负数
     * @throws NullPointerException     如果 center 或 point 为 null
     *
     *                                  <p>示例：</p>
     *                                  <pre>{@code
     *                                  Location cafe = new Location(31.2304, 121.4737);
     *                                  Location user = new Location(31.2310, 121.4725);
     *                                  boolean isNearby = isWithinRadius(cafe, user, 500, DistanceUnit.METERS);
     *                                  // 判断用户是否在咖啡馆 500 米范围内
     *                                  }</pre>
     */
    public static boolean isWithinRadius(Location center, Location point, double radius, DistanceUnit unit) {
        // 参数校验
        if (radius < 0) {
            throw new IllegalArgumentException("半径不能为负数: " + radius);
        }
        Objects.requireNonNull(center, "中心点坐标不能为 null");
        Objects.requireNonNull(point, "目标点坐标不能为 null");
        return sifting(center, point) && calculate(center, point, unit) < radius;
    }

    public static List<Location> gps2Gaode(List<Location> coordinateList) {
        if (coordinateList.isEmpty()) {
            return Collections.emptyList();
        }
        return coordinateList.stream().map(e -> gps2Gaode(e.longitude(), e.latitude())).toList();
    }

    /**
     * 转换 WGS-84 坐标系（国际标准）到 GCJ-02 坐标系（中国国测局加密坐标系，俗称火星坐标系）
     */
    public static Location gps2Gaode(Double lng, Double lat) {
        assert lng != null && lat != null;
        if (outOfChina(lat, lng)) {
            return Location.of(lng, lat);
        }
        double dLat = transformLat(lng - 105.0, lat - 35.0);
        double dLng = transformLng(lng - 105.0, lat - 35.0);
        double radLat = lat / 180.0 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLng = (dLng * 180.0) / (A / sqrtMagic * Math.cos(radLat) * PI);
        double mgLat = lat + dLat;
        double mgLng = lng + dLng;
        return Location.of(mgLng, mgLat);
    }

    private static double transformLat(double x, double y) {
        double ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(y * PI) + 40.0 * Math.sin(y / 3.0 * PI)) * 2.0 / 3.0;
        ret += (160.0 * Math.sin(y / 12.0 * PI) + 320 * Math.sin(y * PI / 30.0)) * 2.0 / 3.0;
        return ret;
    }

    private static double transformLng(double x, double y) {
        double ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1
                                                                       * Math.sqrt(Math.abs(x));
        ret += (20.0 * Math.sin(6.0 * x * PI) + 20.0 * Math.sin(2.0 * x * PI)) * 2.0 / 3.0;
        ret += (20.0 * Math.sin(x * PI) + 40.0 * Math.sin(x / 3.0 * PI)) * 2.0 / 3.0;
        ret += (150.0 * Math.sin(x / 12.0 * PI) + 300.0 * Math.sin(x / 30.0 * PI)) * 2.0 / 3.0;
        return ret;
    }

    /**
     * 参数	值	地理意义
     * 经度下限	72.004	中国最西端（帕米尔高原附近）
     * 经度上限	137.8347 中国最东端（黑龙江与乌苏里江交汇处）
     * 纬度下限	0.8293	中国最南端（曾母暗沙附近，实际应为3°N附近）
     * 纬度上限	55.8271	中国最北端（漠河以北）
     *
     * 实际境外坐标可能被误判为境内（如越南部分区域），但境内坐标绝不会被误判为境外，确保GCJ-02加密不漏坐标。
     */
    public static boolean outOfChina(double lng, double lat) {
        if (lng < 72.004 || lng > 137.8347) {
            return true;
        }
        if (lat < 0.8293 || lat > 55.8271) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否在中国
     * 中国地理位置四至点：最东端东经135度2分30秒黑龙江和乌苏里江交汇处最西端东经73度40分帕米尔高原乌兹别里山口（乌恰县）
     * 最南端北纬3度52分南沙群岛曾母暗沙最北端北纬53度33分漠河以北黑龙江主航道（漠河县）
     * 链接：<a href="https://wenku.baidu.com/view/10a7ed85ed3a87c24028915f804d2b160b4e86fa.html">...</a>
     * 来源：百度文库
     */

    public static boolean judgeInChina(double lng, double lat) {
        return (lng >= 72.004 && lng <= 137.8347)
               && (lat >= 3.0 && lat <= 55.8271)
               && !judgeInJapan(lat, lng);
    }

    /**
     * 判断是否在日本Update 20210-11-26 update
     * 分2个块状
     * lon: 125 - 145
     * lat: 30  - 45
     */
    public static boolean judgeInJapan(double lat, double lon) {
        return (lon >= 128 && lon <= 145 && lat >= 30 && lat <= 39)
               || (lon >= 138 && lon <= 145 && lat >= 39 && lat <= 45)
               || (lon >= 125 && lon <= 130 && lat >= 25 && lat <= 30);
    }


    /**
     * GCJ02 to BD09
     */
    public static Location gaode2Baidu(double lng, double lat) {
        double x = lng, y = lat;
        double z = Math.sqrt(x * x + y * y) + 0.00002 * Math.sin(y * X_PI);
        double theta = Math.atan2(y, x) + 0.000003 * Math.cos(x * X_PI);
        double tempLng = z * Math.cos(theta) + 0.0065;
        double tempLat = z * Math.sin(theta) + 0.006;
        return Location.of(tempLng, tempLat);
    }

    /**
     * GPS84 to BD09
     */
    public static Location gps2Baidu(double lat, double lon) {
        Location gcj02 = gps2Gaode(lat, lon);
        if (gcj02 == null) {
            return gcj02;
        }
        return gaode2Baidu(gcj02.longitude(), gcj02.latitude());
    }

}
