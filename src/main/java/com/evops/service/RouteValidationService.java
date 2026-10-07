package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.dto.ConflictDetail;
import com.evops.dto.WaypointRequest;
import com.evops.entity.FlightRouteVersion;
import com.evops.entity.NoFlyZone;
import com.evops.entity.RoutePublishCheck;
import com.evops.exception.RouteValidationException;
import com.evops.mapper.RoutePublishCheckMapper;
import com.evops.util.GeoUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布前/起飞前三校验：临时禁飞区、风速条件、电量余量。
 * 每次校验都落 t_route_publish_check；不通过时冲突明细必须给出坐标与原因。
 */
@Service
public class RouteValidationService {

    private final NoFlyZoneService noFlyZoneService;
    private final RoutePublishCheckMapper checkMapper;
    private final WaypointSnapshotCodec codec;
    private final ObjectMapper objectMapper;

    public RouteValidationService(NoFlyZoneService noFlyZoneService,
                                  RoutePublishCheckMapper checkMapper,
                                  WaypointSnapshotCodec codec,
                                  ObjectMapper objectMapper) {
        this.noFlyZoneService = noFlyZoneService;
        this.checkMapper = checkMapper;
        this.codec = codec;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行校验并持久化结果。
     *
     * @param throwOnFail true 时校验不通过抛 {@link RouteValidationException}（记录已落库）
     * @return 校验记录
     */
    public RoutePublishCheck check(FlightRouteVersion version, double maxWindSpeed, int reserveBatteryPct,
                                   Double windSpeed, Integer batteryPct, LocalDateTime effectiveTime,
                                   boolean throwOnFail) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime t = effectiveTime != null ? effectiveTime : now;
        List<WaypointRequest> waypoints = codec.fromJson(version.getWaypointSnapshot());

        // 1) 临时禁飞区：逐航点判定，给出冲突区域坐标、航点坐标与原因
        List<ConflictDetail> conflicts = detectConflicts(waypoints, t);

        // 2) 风速条件：实测/预报风速不得超过航线允许最大值
        boolean windOk = windSpeed == null || windSpeed <= maxWindSpeed + 1e-9;

        // 3) 电量余量：起飞电量 - 预估耗电 必须保留至少 reserveBatteryPct
        boolean batteryOk = batteryPct == null
                || batteryPct - version.getEstimatedPowerPct() >= reserveBatteryPct;

        List<String> failReasons = new ArrayList<>();
        if (!conflicts.isEmpty()) {
            failReasons.add("航线与 " + conflicts.size() + " 处禁飞区冲突");
        }
        if (!windOk) {
            failReasons.add("风速 " + windSpeed + "m/s 超过允许上限 " + maxWindSpeed + "m/s");
        }
        if (!batteryOk) {
            failReasons.add("电量 " + batteryPct + "% 扣除预估耗电 " + version.getEstimatedPowerPct()
                    + "% 后余量不足 " + reserveBatteryPct + "%");
        }

        boolean passed = conflicts.isEmpty() && windOk && batteryOk;

        RoutePublishCheck check = new RoutePublishCheck();
        check.setRouteId(version.getRouteId());
        check.setVersionId(version.getId());
        check.setPassed(passed);
        check.setCheckedAt(now);
        check.setWindSpeed(windSpeed);
        check.setMaxWindSpeed(maxWindSpeed);
        check.setBatteryPct(batteryPct);
        check.setReserveBatteryPct(reserveBatteryPct);
        check.setConflictDetail(toJson(conflicts));
        check.setFailReasons(toJson(failReasons));
        checkMapper.insert(check);

        if (!passed && throwOnFail) {
            throw new RouteValidationException(String.join("；", failReasons), check);
        }
        return check;
    }

    /**
     * 冲突检测：仅统计 effectiveTime 时刻生效的禁飞区（左闭右开窗口），
     * 航点落入圆形区域（含边界）即冲突。
     */
    public List<ConflictDetail> detectConflicts(List<WaypointRequest> waypoints, LocalDateTime effectiveTime) {
        List<NoFlyZone> zones = noFlyZoneService.listEffectiveAt(effectiveTime);
        List<ConflictDetail> conflicts = new ArrayList<>();
        for (WaypointRequest wp : waypoints) {
            for (NoFlyZone zone : zones) {
                double distance = GeoUtils.distanceM(wp.getLongitude(), wp.getLatitude(),
                        zone.getLongitude(), zone.getLatitude());
                if (distance <= zone.getRadiusM()) {
                    ConflictDetail detail = new ConflictDetail();
                    detail.setZoneNo(zone.getZoneNo());
                    detail.setZoneName(zone.getZoneName());
                    detail.setZoneLongitude(zone.getLongitude());
                    detail.setZoneLatitude(zone.getLatitude());
                    detail.setRadiusM(zone.getRadiusM());
                    detail.setWaypointSeq(wp.getSeq());
                    detail.setBladePosition(wp.getBladePosition() == null ? null : wp.getBladePosition().name());
                    detail.setWaypointLongitude(wp.getLongitude());
                    detail.setWaypointLatitude(wp.getLatitude());
                    detail.setDistanceM(Math.round(distance * 100) / 100.0);
                    detail.setReason(buildReason(zone, distance));
                    conflicts.add(detail);
                }
            }
        }
        return conflicts;
    }

    private String buildReason(NoFlyZone zone, double distance) {
        StringBuilder reason = new StringBuilder();
        reason.append("航点落入禁飞区[").append(zone.getZoneNo()).append("]").append(zone.getZoneName());
        if (zone.getReason() != null) {
            reason.append("，设立原因：").append(zone.getReason());
        }
        reason.append("，距圆心 ").append(Math.round(distance)).append("m ≤ 半径 ")
                .append(Math.round(zone.getRadiusM())).append("m");
        reason.append("，生效窗口 ").append(zone.getStartTime());
        reason.append(" 至 ").append(zone.getEndTime() != null ? zone.getEndTime() : "撤销前长期有效");
        return reason.toString();
    }

    /** 最近一次校验记录（可追溯用） */
    public RoutePublishCheck latestCheck(Long versionId) {
        List<RoutePublishCheck> checks = checkMapper.selectList(new QueryWrapper<RoutePublishCheck>()
                .eq("version_id", versionId)
                .orderByDesc("id")
                .last("LIMIT 1"));
        return checks.isEmpty() ? null : checks.get(0);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new IllegalStateException("校验结果序列化失败", e);
        }
    }
}
