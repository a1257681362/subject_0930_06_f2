package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.dto.RouteConfigUpdateRequest;
import com.evops.dto.RouteCreateRequest;
import com.evops.dto.RoutePublishSubmitRequest;
import com.evops.dto.TemporaryChangeRequest;
import com.evops.dto.WaypointRequest;
import com.evops.entity.FlightRoute;
import com.evops.entity.FlightRouteVersion;
import com.evops.entity.RouteApproval;
import com.evops.entity.RouteVersionDiff;
import com.evops.entity.RouteWaypoint;
import com.evops.enums.ApprovalStatus;
import com.evops.enums.ApprovalType;
import com.evops.enums.BladePosition;
import com.evops.enums.DiffChangeKind;
import com.evops.enums.RouteChangeType;
import com.evops.enums.RouteVersionStatus;
import com.evops.mapper.FlightRouteMapper;
import com.evops.mapper.FlightRouteVersionMapper;
import com.evops.mapper.RouteApprovalMapper;
import com.evops.mapper.RouteVersionDiffMapper;
import com.evops.mapper.RouteWaypointMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 无人机叶片巡检航线。
 *
 * 配置与版本分离：t_route_waypoint 是“待发布配置”，发布/临时改线时冻结成不可变版本快照；
 * 新版本只在两级审批全部通过后激活，并仅重绑未起飞任务。
 */
@Service
public class FlightRouteService extends ServiceImpl<FlightRouteMapper, FlightRoute> {

    static final double DEFAULT_MAX_WIND = 10.0;
    static final int DEFAULT_RESERVE = 30;
    static final int DEFAULT_ESTIMATED_POWER = 60;

    private final TurbineService turbineService;
    private final FlightRouteVersionMapper versionMapper;
    private final RouteWaypointMapper waypointMapper;
    private final RouteVersionDiffMapper diffMapper;
    private final RouteApprovalMapper approvalMapper;
    private final WaypointSnapshotCodec codec;
    private final RouteValidationService validationService;
    private final FlightMissionService missionService;
    private final TransactionTemplate transactionTemplate;

    public FlightRouteService(TurbineService turbineService,
                              FlightRouteVersionMapper versionMapper,
                              RouteWaypointMapper waypointMapper,
                              RouteVersionDiffMapper diffMapper,
                              RouteApprovalMapper approvalMapper,
                              WaypointSnapshotCodec codec,
                              RouteValidationService validationService,
                              @Lazy FlightMissionService missionService,
                              PlatformTransactionManager txManager) {
        this.turbineService = turbineService;
        this.versionMapper = versionMapper;
        this.waypointMapper = waypointMapper;
        this.diffMapper = diffMapper;
        this.approvalMapper = approvalMapper;
        this.codec = codec;
        this.validationService = validationService;
        this.missionService = missionService;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    // ==================== 航线与待发布配置 ====================

    /** 航线建档：按风机配置航点（叶片方位 + 拍摄距离），此时尚无已发布版本 */
    @Transactional
    public FlightRoute createRoute(RouteCreateRequest req) {
        turbineService.mustGet(req.getTurbineId());
        Long dup = count(new QueryWrapper<FlightRoute>().eq("route_no", req.getRouteNo()));
        if (dup > 0) {
            throw new IllegalArgumentException("航线编号已存在: " + req.getRouteNo());
        }
        validateWaypoints(req.getWaypoints());

        FlightRoute route = new FlightRoute();
        route.setRouteNo(req.getRouteNo());
        route.setRouteName(req.getRouteName());
        route.setTurbineId(req.getTurbineId());
        route.setMaxWindSpeed(req.getMaxWindSpeed() != null ? req.getMaxWindSpeed() : DEFAULT_MAX_WIND);
        route.setReserveBatteryPct(req.getReserveBatteryPct() != null
                ? req.getReserveBatteryPct() : DEFAULT_RESERVE);
        route.setEstimatedPowerPct(req.getEstimatedPowerPct() != null
                ? req.getEstimatedPowerPct() : DEFAULT_ESTIMATED_POWER);
        route.setRemark(req.getRemark());
        save(route);

        insertWaypoints(route.getId(), req.getWaypoints());
        return route;
    }

    /** 调整待发布配置（航点/阈值）。已发布版本不可变，调整在下次发布时形成新版本 */
    @Transactional
    public FlightRoute updateConfig(Long routeId, RouteConfigUpdateRequest req) {
        FlightRoute route = mustGetRoute(routeId);
        assertNoPendingApproval(routeId);
        validateWaypoints(req.getWaypoints());

        if (req.getMaxWindSpeed() != null) {
            route.setMaxWindSpeed(req.getMaxWindSpeed());
        }
        if (req.getReserveBatteryPct() != null) {
            route.setReserveBatteryPct(req.getReserveBatteryPct());
        }
        if (req.getEstimatedPowerPct() != null) {
            route.setEstimatedPowerPct(req.getEstimatedPowerPct());
        }
        updateById(route);

        waypointMapper.delete(new QueryWrapper<RouteWaypoint>().eq("route_id", routeId));
        insertWaypoints(routeId, req.getWaypoints());
        return route;
    }

    public List<RouteWaypoint> listWaypoints(Long routeId) {
        return waypointMapper.selectList(new QueryWrapper<RouteWaypoint>()
                .eq("route_id", routeId)
                .orderByAsc("seq"));
    }

    // ==================== 发布申请 ====================

    /**
     * 提交发布：发布前三校验通过后冻结版本快照、发起两级审批。
     * 审批未全部通过前版本为 DRAFT，不生效。
     * DRAFT 版本先独立落库：校验不通过时，版本与校验记录都保留，航线规划可追溯。
     */
    public RouteApproval submitPublish(Long routeId, RoutePublishSubmitRequest req) {
        FlightRoute route = mustGetRoute(routeId);
        assertNoPendingApproval(routeId);
        List<WaypointRequest> waypoints = toWaypointRequests(listWaypoints(routeId));
        if (waypoints.isEmpty()) {
            throw new IllegalArgumentException("航线尚未配置航点，无法发布");
        }

        Long baseVersionId = route.getCurrentVersionId();
        FlightRouteVersion version = transactionTemplate.execute(status ->
                newVersion(route,
                        baseVersionId == null ? RouteChangeType.INITIAL : RouteChangeType.REVISION,
                        baseVersionId, waypoints,
                        route.getMaxWindSpeed(), route.getReserveBatteryPct(),
                        route.getEstimatedPowerPct()));

        // 发布前校验（事务外）：临时禁飞区 / 风速 / 电量；不通过直接抛出，
        // DRAFT 版本与校验记录已落库，冲突坐标与原因可追溯
        validationService.check(version, route.getMaxWindSpeed(), route.getReserveBatteryPct(),
                req.getWindSpeed(), req.getBatteryPct(), req.getEffectiveTime(), true);

        return transactionTemplate.execute(status -> {
            if (baseVersionId != null) {
                recordDiffs(version, versionMapper.selectById(baseVersionId));
            }
            return createApproval(route, version, ApprovalType.PUBLISH, baseVersionId, req.getReason());
        });
    }

    /**
     * 临时改线：必须审批。提交即过发布前三校验，生成 TEMPORARY_CHANGE 版本，
     * 并逐行保留与基线版本的差异，供两级审批与后续追溯。
     */
    public RouteApproval submitTemporaryChange(Long routeId, TemporaryChangeRequest req) {
        FlightRoute route = mustGetRoute(routeId);
        assertNoPendingApproval(routeId);
        final FlightRouteVersion base = resolveBaseVersion(route, req.getBaseVersionId());

        List<WaypointRequest> newWaypoints = req.getWaypoints() != null
                ? toWaypointRequests(req.getWaypoints())
                : codec.fromJson(base.getWaypointSnapshot());
        validateWaypoints(newWaypoints);
        final double maxWind = req.getMaxWindSpeed() != null ? req.getMaxWindSpeed() : base.getMaxWindSpeed();
        final int reserve = req.getReserveBatteryPct() != null
                ? req.getReserveBatteryPct() : base.getReserveBatteryPct();
        final int estimated = req.getEstimatedPowerPct() != null
                ? req.getEstimatedPowerPct() : base.getEstimatedPowerPct();

        FlightRouteVersion version = transactionTemplate.execute(status ->
                newVersion(route, RouteChangeType.TEMPORARY_CHANGE,
                        base.getId(), newWaypoints, maxWind, reserve, estimated));

        // 改线同样必须过临时禁飞区/风速/电量校验，不通过则版本与校验记录留痕后驳回提交
        validationService.check(version, maxWind, reserve,
                req.getWindSpeed(), req.getBatteryPct(), req.getEffectiveTime(), true);

        final FlightRouteVersion draft = version;
        return transactionTemplate.execute(status -> {
            recordDiffs(draft, base);
            return createApproval(route, draft, ApprovalType.TEMPORARY_CHANGE,
                    base.getId(), req.getReason());
        });
    }

    // ==================== 审批通过后的激活 ====================

    /**
     * 两级审批全部通过后激活版本：旧版本归档、航线指向新版本，
     * 仅重绑未起飞任务；已起飞任务与历史影像继续引用原版本。
     */
    @Transactional
    public void activateVersion(RouteApproval approval) {
        FlightRouteVersion version = versionMapper.selectById(approval.getVersionId());
        if (version.getStatus() != RouteVersionStatus.DRAFT) {
            throw new IllegalStateException("版本不可重复激活: " + version.getVersionNo());
        }
        FlightRoute route = mustGetRoute(approval.getRouteId());

        if (route.getCurrentVersionId() != null
                && !route.getCurrentVersionId().equals(version.getBaseVersionId())) {
            // 审批期间基线已变（正常流程被前序校验拦住），保守拒绝
            throw new IllegalStateException("航线当前版本与改线基线不一致，需重新发起审批");
        }
        if (route.getCurrentVersionId() != null) {
            FlightRouteVersion old = versionMapper.selectById(route.getCurrentVersionId());
            old.setStatus(RouteVersionStatus.ARCHIVED);
            versionMapper.updateById(old);
        }

        version.setStatus(RouteVersionStatus.PUBLISHED);
        version.setPublishedAt(LocalDateTime.now());
        versionMapper.updateById(version);

        route.setCurrentVersionId(version.getId());
        updateById(route);

        // 新版本只作用于未起飞任务；FLYING/FINISHED/CANCELLED 不动
        missionService.rebindPendingMissions(route.getId(), version.getId());
    }

    // ==================== 版本/审批查询（可追溯） ====================

    public List<FlightRouteVersion> listVersions(Long routeId) {
        mustGetRoute(routeId);
        return versionMapper.selectList(new QueryWrapper<FlightRouteVersion>()
                .eq("route_id", routeId)
                .orderByAsc("id"));
    }

    public FlightRouteVersion mustGetVersion(Long versionId) {
        FlightRouteVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("航线版本不存在: " + versionId);
        }
        return version;
    }

    public List<RouteVersionDiff> listDiffs(Long versionId) {
        return diffMapper.selectList(new QueryWrapper<RouteVersionDiff>()
                .eq("version_id", versionId)
                .orderByAsc("id"));
    }

    /** 还原某版本冻结时的航点配置（历史影像据此追溯原航线） */
    public List<WaypointRequest> snapshotWaypoints(Long versionId) {
        return codec.fromJson(mustGetVersion(versionId).getWaypointSnapshot());
    }

    public RouteApproval mustGetApproval(Long approvalId) {
        RouteApproval approval = approvalMapper.selectById(approvalId);
        if (approval == null) {
            throw new IllegalArgumentException("航线审批单不存在: " + approvalId);
        }
        return approval;
    }

    public List<RouteApproval> listApprovals(Long routeId) {
        return approvalMapper.selectList(new QueryWrapper<RouteApproval>()
                .eq(routeId != null, "route_id", routeId)
                .orderByDesc("id"));
    }

    public FlightRoute mustGetRoute(Long id) {
        FlightRoute route = getById(id);
        if (route == null) {
            throw new IllegalArgumentException("航线不存在: " + id);
        }
        return route;
    }

    // ==================== 内部方法 ====================

    private FlightRouteVersion newVersion(FlightRoute route, RouteChangeType changeType,
                                          Long baseVersionId, List<WaypointRequest> waypoints,
                                          double maxWind, int reserve, int estimated) {
        String json = codec.toJson(waypoints);
        FlightRouteVersion version = new FlightRouteVersion();
        version.setRouteId(route.getId());
        version.setVersionNo(nextVersionNo(route.getId()));
        version.setBaseVersionId(baseVersionId);
        version.setChangeType(changeType);
        version.setWaypointSnapshot(json);
        version.setSnapshotHash(codec.sha256(json));
        version.setMaxWindSpeed(maxWind);
        version.setReserveBatteryPct(reserve);
        version.setEstimatedPowerPct(estimated);
        version.setStatus(RouteVersionStatus.DRAFT);
        versionMapper.insert(version);
        return version;
    }

    private String nextVersionNo(Long routeId) {
        Long count = versionMapper.selectCount(
                new QueryWrapper<FlightRouteVersion>().eq("route_id", routeId));
        return "v" + (count + 1);
    }

    private FlightRouteVersion resolveBaseVersion(FlightRoute route, Long baseVersionId) {
        Long id = baseVersionId != null ? baseVersionId : route.getCurrentVersionId();
        if (id == null) {
            throw new IllegalArgumentException("航线尚无已发布版本，不能临时改线，请先发布");
        }
        FlightRouteVersion base = versionMapper.selectById(id);
        if (base == null || !base.getRouteId().equals(route.getId())) {
            throw new IllegalArgumentException("基线版本不存在或不属于该航线");
        }
        if (base.getStatus() != RouteVersionStatus.PUBLISHED) {
            throw new IllegalArgumentException("只能以当前已发布版本为改线基线");
        }
        return base;
    }

    private void assertNoPendingApproval(Long routeId) {
        Long pending = approvalMapper.selectCount(new QueryWrapper<RouteApproval>()
                .eq("route_id", routeId)
                .in("status", ApprovalStatus.PENDING, ApprovalStatus.PENDING_HALF));
        if (pending > 0) {
            throw new IllegalArgumentException("航线存在未完成的审批，请先处理或驳回后再操作");
        }
    }

    private RouteApproval createApproval(FlightRoute route, FlightRouteVersion version,
                                         ApprovalType type, Long baseVersionId, String reason) {
        RouteApproval approval = new RouteApproval();
        approval.setApprovalNo("AP" + System.currentTimeMillis() + "-" + route.getId());
        approval.setRouteId(route.getId());
        approval.setVersionId(version.getId());
        approval.setApprovalType(type);
        approval.setBaseVersionId(baseVersionId);
        approval.setReason(reason);
        // 审批尚未开始，系统停留待处理（一级处理后进入 PENDING_HALF）
        approval.setStatus(ApprovalStatus.PENDING);
        approvalMapper.insert(approval);
        return approval;
    }

    /**
     * 版本差异：与基线版本逐航点（按 seq）、逐字段对比，阈值变化同样留痕。
     */
    private void recordDiffs(FlightRouteVersion version, FlightRouteVersion base) {
        Map<Integer, WaypointRequest> oldMap = mapBySeq(codec.fromJson(base.getWaypointSnapshot()));
        Map<Integer, WaypointRequest> newMap = mapBySeq(codec.fromJson(version.getWaypointSnapshot()));
        List<RouteVersionDiff> diffs = new ArrayList<>();

        Set<Integer> seqs = new HashSet<>();
        seqs.addAll(oldMap.keySet());
        seqs.addAll(newMap.keySet());
        for (Integer seq : seqs) {
            WaypointRequest oldWp = oldMap.get(seq);
            WaypointRequest newWp = newMap.get(seq);
            if (oldWp == null) {
                diffs.add(diff(version, DiffChangeKind.WAYPOINT_ADDED, seq, newWp,
                        "waypoint", null, describe(newWp)));
                continue;
            }
            if (newWp == null) {
                diffs.add(diff(version, DiffChangeKind.WAYPOINT_REMOVED, seq, oldWp,
                        "waypoint", describe(oldWp), null));
                continue;
            }
            compareField(diffs, version, seq, newWp, "bladePosition",
                    oldWp.getBladePosition() == null ? null : oldWp.getBladePosition().name(),
                    newWp.getBladePosition() == null ? null : newWp.getBladePosition().name());
            compareField(diffs, version, seq, newWp, "shootDistanceM",
                    str(oldWp.getShootDistanceM()), str(newWp.getShootDistanceM()));
            compareField(diffs, version, seq, newWp, "longitude",
                    str(oldWp.getLongitude()), str(newWp.getLongitude()));
            compareField(diffs, version, seq, newWp, "latitude",
                    str(oldWp.getLatitude()), str(newWp.getLatitude()));
            compareField(diffs, version, seq, newWp, "altitudeM",
                    str(oldWp.getAltitudeM()), str(newWp.getAltitudeM()));
            compareField(diffs, version, seq, newWp, "gimbalPitchDeg",
                    str(oldWp.getGimbalPitchDeg()), str(newWp.getGimbalPitchDeg()));
        }

        compareThreshold(diffs, version, "maxWindSpeed",
                str(base.getMaxWindSpeed()), str(version.getMaxWindSpeed()));
        compareThreshold(diffs, version, "reserveBatteryPct",
                str(base.getReserveBatteryPct()), str(version.getReserveBatteryPct()));
        compareThreshold(diffs, version, "estimatedPowerPct",
                str(base.getEstimatedPowerPct()), str(version.getEstimatedPowerPct()));

        for (RouteVersionDiff diff : diffs) {
            diffMapper.insert(diff);
        }
    }

    private void compareField(List<RouteVersionDiff> diffs, FlightRouteVersion version,
                              Integer seq, WaypointRequest wp, String field, String oldV, String newV) {
        if (!equalsValue(oldV, newV)) {
            RouteVersionDiff diff = diff(version, DiffChangeKind.WAYPOINT_CHANGED, seq, wp, field, oldV, newV);
            diffs.add(diff);
        }
    }

    private void compareThreshold(List<RouteVersionDiff> diffs, FlightRouteVersion version,
                                  String field, String oldV, String newV) {
        if (!equalsValue(oldV, newV)) {
            RouteVersionDiff diff = new RouteVersionDiff();
            diff.setVersionId(version.getId());
            diff.setChangeKind(DiffChangeKind.THRESHOLD_CHANGED);
            diff.setFieldName(field);
            diff.setOldValue(oldV);
            diff.setNewValue(newV);
            diffs.add(diff);
        }
    }

    private boolean equalsValue(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private RouteVersionDiff diff(FlightRouteVersion version, DiffChangeKind kind,
                                  Integer seq, WaypointRequest wp, String field,
                                  String oldV, String newV) {
        RouteVersionDiff diff = new RouteVersionDiff();
        diff.setVersionId(version.getId());
        diff.setChangeKind(kind);
        diff.setSeq(seq);
        diff.setBladePosition(wp != null && wp.getBladePosition() != null ? wp.getBladePosition().name() : null);
        diff.setFieldName(field);
        diff.setOldValue(oldV);
        diff.setNewValue(newV);
        return diff;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String describe(WaypointRequest wp) {
        return (wp.getBladePosition() == null ? "?" : wp.getBladePosition().name())
                + "@" + wp.getShootDistanceM() + "m"
                + "(" + wp.getLongitude() + "," + wp.getLatitude() + ")";
    }

    private Map<Integer, WaypointRequest> mapBySeq(List<WaypointRequest> waypoints) {
        Map<Integer, WaypointRequest> map = new HashMap<>();
        for (WaypointRequest wp : waypoints) {
            map.put(wp.getSeq(), wp);
        }
        return map;
    }

    private List<WaypointRequest> toWaypointRequests(List<RouteWaypoint> entities) {
        List<WaypointRequest> list = new ArrayList<>();
        for (RouteWaypoint e : entities) {
            WaypointRequest wp = new WaypointRequest();
            wp.setSeq(e.getSeq());
            wp.setBladePosition(e.getBladePosition());
            wp.setShootDistanceM(e.getShootDistanceM());
            wp.setLongitude(e.getLongitude());
            wp.setLatitude(e.getLatitude());
            wp.setAltitudeM(e.getAltitudeM());
            wp.setGimbalPitchDeg(e.getGimbalPitchDeg());
            list.add(wp);
        }
        return list;
    }

    private List<WaypointRequest> toWaypointRequests(Iterable<WaypointRequest> src) {
        List<WaypointRequest> list = new ArrayList<>();
        src.forEach(list::add);
        return list;
    }

    private void insertWaypoints(Long routeId, List<WaypointRequest> waypoints) {
        for (WaypointRequest req : waypoints) {
            RouteWaypoint wp = new RouteWaypoint();
            wp.setRouteId(routeId);
            wp.setSeq(req.getSeq());
            wp.setBladePosition(req.getBladePosition());
            wp.setShootDistanceM(req.getShootDistanceM());
            wp.setLongitude(req.getLongitude());
            wp.setLatitude(req.getLatitude());
            wp.setAltitudeM(req.getAltitudeM() != null ? req.getAltitudeM() : 80.0);
            wp.setGimbalPitchDeg(req.getGimbalPitchDeg());
            waypointMapper.insert(wp);
        }
    }

    private void validateWaypoints(List<WaypointRequest> waypoints) {
        if (waypoints == null || waypoints.isEmpty()) {
            throw new IllegalArgumentException("航点不能为空");
        }
        Set<Integer> seqs = new HashSet<>();
        for (WaypointRequest wp : waypoints) {
            if (wp.getSeq() == null || wp.getSeq() <= 0) {
                throw new IllegalArgumentException("航点顺序号必须为正整数");
            }
            if (!seqs.add(wp.getSeq())) {
                throw new IllegalArgumentException("航点顺序号重复: " + wp.getSeq());
            }
            if (wp.getBladePosition() == null) {
                throw new IllegalArgumentException("航点 " + wp.getSeq() + " 叶片方位不能为空");
            }
            if (wp.getBladePosition() != BladePosition.A
                    && wp.getBladePosition() != BladePosition.B
                    && wp.getBladePosition() != BladePosition.C) {
                throw new IllegalArgumentException("叶片方位只支持 A/B/C");
            }
            if (wp.getShootDistanceM() == null || wp.getShootDistanceM() <= 0) {
                throw new IllegalArgumentException("航点 " + wp.getSeq() + " 拍摄距离必须大于 0");
            }
            if (wp.getLongitude() == null || wp.getLatitude() == null) {
                throw new IllegalArgumentException("航点 " + wp.getSeq() + " 经纬度不能为空");
            }
            if (wp.getLongitude() < -180 || wp.getLongitude() > 180
                    || wp.getLatitude() < -90 || wp.getLatitude() > 90) {
                throw new IllegalArgumentException("航点 " + wp.getSeq() + " 经纬度超出范围");
            }
            if (wp.getAltitudeM() != null && wp.getAltitudeM() <= 0) {
                throw new IllegalArgumentException("航点 " + wp.getSeq() + " 飞行高度必须大于 0");
            }
        }
    }
}
