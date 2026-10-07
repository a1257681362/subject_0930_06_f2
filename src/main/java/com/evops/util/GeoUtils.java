package com.evops.util;

/**
 * 地理计算：航点与圆形禁飞区的距离判定。
 * Haversine 公式，地球取平均半径 6371000 米，风电场内公里级精度足够。
 */
public final class GeoUtils {

    private static final double EARTH_RADIUS_M = 6371000.0;

    private GeoUtils() {
    }

    /** 两经纬度坐标间的球面距离（米） */
    public static double distanceM(double lon1, double lat1, double lon2, double lat2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_M * c;
    }

    /**
     * 点是否落入（含边界，安全优先）圆形禁飞区。
     */
    public static boolean insideCircle(double lon, double lat,
                                       double centerLon, double centerLat, double radiusM) {
        return distanceM(lon, lat, centerLon, centerLat) <= radiusM;
    }
}
