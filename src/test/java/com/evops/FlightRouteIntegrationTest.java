package com.evops;

import com.evops.dto.ApprovalDecisionRequest;
import com.evops.dto.InspectSubmitRequest;
import com.evops.dto.MissionCreateRequest;
import com.evops.dto.MissionDepartRequest;
import com.evops.dto.NoFlyZoneRequest;
import com.evops.dto.OrderCreateRequest;
import com.evops.dto.RouteConfigUpdateRequest;
import com.evops.dto.RouteCreateRequest;
import com.evops.dto.RoutePublishSubmitRequest;
import com.evops.dto.TemporaryChangeRequest;
import com.evops.dto.WaypointRequest;
import com.evops.entity.FlightMission;
import com.evops.entity.FlightRoute;
import com.evops.entity.FlightRouteVersion;
import com.evops.entity.InspectionOrder;
import com.evops.entity.NoFlyZone;
import com.evops.entity.RouteApproval;
import com.evops.entity.RoutePublishCheck;
import com.evops.entity.RouteVersionDiff;
import com.evops.entity.Turbine;
import com.evops.enums.ApprovalStatus;
import com.evops.enums.ApprovalType;
import com.evops.enums.BladePosition;
import com.evops.enums.MissionStatus;
import com.evops.enums.NoFlyZoneType;
import com.evops.enums.RouteChangeType;
import com.evops.enums.RouteVersionStatus;
import com.evops.exception.RouteValidationException;
import com.evops.service.FlightMissionService;
import com.evops.service.FlightRouteService;
import com.evops.service.InspectionService;
import com.evops.service.NoFlyZoneService;
import com.evops.service.RouteApprovalService;
import com.evops.service.RouteValidationService;
import com.evops.service.TurbineService;
import com.evops.dto.TurbineCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2 无人机叶片巡检航线：
 * 航线按风机/叶片方位/拍摄距离配置，发布前校验禁飞区（坐标+原因）、风速、电量；
 * 两级审批完成一半停留待处理；新版本只作用未起飞任务，历史影像引用原航线；
 * 临时改线必须审批并留版本差异；航线规划与禁飞区全程可追溯。
 */
@SpringBootTest
@Transactional
class FlightRouteIntegrationTest {

    /** 风机机位附近坐标 */
    private static final double BASE_LON = 114.7045;
    private static final double BASE_LAT = 41.0523;

    @Autowired
    private TurbineService turbineService;
    @Autowired
    private FlightRouteService routeService;
    @Autowired
    private RouteApprovalService approvalService;
    @Autowired
    private NoFlyZoneService noFlyZoneService;
    @Autowired
    private FlightMissionService missionService;
    @Autowired
    private RouteValidationService validationService;
    @Autowired
    private InspectionService inspectionService;

    // -------------------- 辅助构造 --------------------

    private Turbine createTurbine(String no) {
        TurbineCreateRequest req = new TurbineCreateRequest();
        req.setTurbineNo(no);
        req.setSite("张北坝上风电场");
        req.setModel("WT2000-D110");
        req.setGridConnectedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
        return turbineService.create(req);
    }

    private WaypointRequest wp(int seq, BladePosition pos, double dist, double lon, double lat) {
        WaypointRequest w = new WaypointRequest();
        w.setSeq(seq);
        w.setBladePosition(pos);
        w.setShootDistanceM(dist);
        w.setLongitude(lon);
        w.setLatitude(lat);
        w.setAltitudeM(80.0);
        return w;
    }

    private List<WaypointRequest> standardWaypoints() {
        return new ArrayList<>(Arrays.asList(
                wp(1, BladePosition.A, 5.0, BASE_LON, BASE_LAT),
                wp(2, BladePosition.B, 6.0, BASE_LON + 0.001, BASE_LAT),
                wp(3, BladePosition.C, 5.0, BASE_LON - 0.001, BASE_LAT)));
    }

    private FlightRoute createRoute(String no, List<WaypointRequest> waypoints) {
        RouteCreateRequest req = new RouteCreateRequest();
        req.setRouteNo(no);
        req.setRouteName("叶片巡检航线-" + no);
        req.setTurbineId(createTurbine("T-" + no).getId());
        req.setMaxWindSpeed(10.0);
        req.setReserveBatteryPct(30);
        req.setEstimatedPowerPct(40);
        req.setWaypoints(waypoints);
        return routeService.createRoute(req);
    }

    private RoutePublishSubmitRequest goodConditions() {
        RoutePublishSubmitRequest req = new RoutePublishSubmitRequest();
        req.setWindSpeed(6.0);
        req.setBatteryPct(100);
        req.setEffectiveTime(LocalDateTime.of(2026, 10, 7, 10, 0));
        return req;
    }

    private ApprovalDecisionRequest decision(String approver, boolean pass) {
        ApprovalDecisionRequest d = new ApprovalDecisionRequest();
        d.setApprover(approver);
        d.setPass(pass);
        d.setRemark(pass ? "同意" : "驳回");
        return d;
    }

    /** 完整发布：提交 + 两级审批通过，返回已发布版本 */
    private FlightRouteVersion publishFully(FlightRoute route) {
        RouteApproval approval = routeService.submitPublish(route.getId(), goodConditions());
        approvalService.firstApprove(approval.getId(), decision("现场主管", true));
        approvalService.finalApprove(approval.getId(), decision("安全总监", true));
        FlightRoute reloaded = routeService.mustGetRoute(route.getId());
        return routeService.mustGetVersion(reloaded.getCurrentVersionId());
    }

    // -------------------- 航线配置 --------------------

    @Test
    void 航线按风机叶片方位拍摄距离建档() {
        FlightRoute route = createRoute("R01", standardWaypoints());
        assertNotNull(route.getId());
        assertEquals(3, routeService.listWaypoints(route.getId()).size());
        assertNull(route.getCurrentVersionId(), "建档时尚无发布版本");

        // 方位/拍摄距离落到航点
        assertEquals(BladePosition.A, routeService.listWaypoints(route.getId()).get(0).getBladePosition());
        assertEquals(5.0, routeService.listWaypoints(route.getId()).get(0).getShootDistanceM());
    }

    @Test
    void 航点顺序号重复或距离非法时拒绝建档() {
        List<WaypointRequest> bad = Arrays.asList(
                wp(1, BladePosition.A, 5.0, BASE_LON, BASE_LAT),
                wp(1, BladePosition.B, 6.0, BASE_LON + 0.001, BASE_LAT));
        assertThrows(IllegalArgumentException.class, () -> createRoute("R02", new ArrayList<>(bad)));

        List<WaypointRequest> badDist = Arrays.asList(
                wp(1, BladePosition.A, 0.0, BASE_LON, BASE_LAT));
        assertThrows(IllegalArgumentException.class, () -> createRoute("R03", new ArrayList<>(badDist)));
    }

    // -------------------- 发布前三校验 --------------------

    @Test
    void 风速超限_发布校验不通过_校验记录留痕() {
        FlightRoute route = createRoute("R10", standardWaypoints());
        RoutePublishSubmitRequest req = goodConditions();
        req.setWindSpeed(12.0); // 超过 10.0

        RouteValidationException ex = assertThrows(RouteValidationException.class,
                () -> routeService.submitPublish(route.getId(), req));
        RoutePublishCheck check = ex.getCheck();
        assertFalse(check.getPassed());
        assertTrue(ex.getMessage().contains("风速"));
        // 审批未创建
        assertTrue(approvalService.listByRoute(route.getId()).isEmpty());
        // 校验记录可追溯
        RoutePublishCheck latest = validationService.latestCheck(check.getVersionId());
        assertNotNull(latest);
        assertFalse(latest.getPassed());
    }

    @Test
    void 电量余量不足_发布校验不通过() {
        FlightRoute route = createRoute("R11", standardWaypoints());
        RoutePublishSubmitRequest req = goodConditions();
        // 100 - 预估40 = 60 ≥ 30 通过；改成 60 - 40 = 20 < 30 不通过
        req.setBatteryPct(60);
        assertThrows(RouteValidationException.class,
                () -> routeService.submitPublish(route.getId(), req));
    }

    @Test
    void 临时禁飞区冲突_返回坐标与原因_校验记录落库() {
        FlightRoute route = createRoute("R12", standardWaypoints());
        LocalDateTime t = LocalDateTime.of(2026, 10, 7, 10, 0);

        // 以 1 号航点为圆心设 100m 临时禁飞区
        NoFlyZoneRequest zoneReq = new NoFlyZoneRequest();
        zoneReq.setZoneNo("NFZ-12");
        zoneReq.setZoneName("军事演训临时管控");
        zoneReq.setZoneType(NoFlyZoneType.TEMPORARY);
        zoneReq.setLongitude(BASE_LON);
        zoneReq.setLatitude(BASE_LAT);
        zoneReq.setRadiusM(100.0);
        zoneReq.setStartTime(t.minusHours(1));
        zoneReq.setEndTime(t.plusHours(3));
        zoneReq.setReason("附近空域临时管制");
        NoFlyZone zone = noFlyZoneService.create(zoneReq);

        RoutePublishSubmitRequest req = goodConditions();
        RouteValidationException ex = assertThrows(RouteValidationException.class,
                () -> routeService.submitPublish(route.getId(), req));
        RoutePublishCheck check = ex.getCheck();
        assertFalse(check.getPassed());
        // 冲突明细：坐标 + 原因
        String detail = check.getConflictDetail();
        assertTrue(detail.contains("NFZ-12"));
        assertTrue(detail.contains(String.valueOf(BASE_LON)));
        assertTrue(detail.contains(String.valueOf(BASE_LAT)));
        assertTrue(detail.contains("临时管制"));
        assertTrue(ex.getMessage().contains("禁飞区冲突"));

        // 窗口外（左闭右开）自动失效：endTime 之后校验通过进入审批
        req.setEffectiveTime(t.plusHours(4));
        RouteApproval approval = routeService.submitPublish(route.getId(), req);
        assertNotNull(approval.getId());
        // 禁飞区档案可追溯
        assertEquals(zone.getZoneNo(), noFlyZoneService.mustGet(zone.getId()).getZoneNo());
    }

    // -------------------- 两级审批：半程停留 --------------------

    @Test
    void 两级审批_只完成一半时停留待处理_航线不生效() {
        FlightRoute route = createRoute("R20", standardWaypoints());
        RouteApproval approval = routeService.submitPublish(route.getId(), goodConditions());

        // 一级通过 → 停留 PENDING_HALF
        approval = approvalService.firstApprove(approval.getId(), decision("现场主管", true));
        assertEquals(ApprovalStatus.PENDING_HALF, approval.getStatus());
        assertTrue(approvalService.listPending().size() >= 1);

        // 版本仍为 DRAFT，航线无当前版本，不能创建飞行任务
        FlightRouteVersion draft = routeService.mustGetVersion(approval.getVersionId());
        assertEquals(RouteVersionStatus.DRAFT, draft.getStatus());
        assertNull(routeService.mustGetRoute(route.getId()).getCurrentVersionId());

        MissionCreateRequest missionReq = new MissionCreateRequest();
        missionReq.setRouteId(route.getId());
        assertThrows(IllegalArgumentException.class, () -> missionService.createMission(missionReq));

        // 半程期间不能重复提交发布
        assertThrows(IllegalArgumentException.class,
                () -> routeService.submitPublish(route.getId(), goodConditions()));

        // 终审通过后才生效
        approvalService.finalApprove(approval.getId(), decision("安全总监", true));
        FlightRoute reloaded = routeService.mustGetRoute(route.getId());
        assertNotNull(reloaded.getCurrentVersionId());
        FlightRouteVersion published = routeService.mustGetVersion(reloaded.getCurrentVersionId());
        assertEquals(RouteVersionStatus.PUBLISHED, published.getStatus());
    }

    @Test
    void 一级驳回_审批终结_版本不生效() {
        FlightRoute route = createRoute("R21", standardWaypoints());
        RouteApproval submitted = routeService.submitPublish(route.getId(), goodConditions());
        RouteApproval approval = approvalService.firstApprove(submitted.getId(),
                decision("现场主管", false));
        assertEquals(ApprovalStatus.REJECTED, approval.getStatus());
        assertNull(routeService.mustGetRoute(route.getId()).getCurrentVersionId());

        // 终审不能对驳回单操作
        assertThrows(IllegalArgumentException.class,
                () -> approvalService.finalApprove(approval.getId(), decision("安全总监", true)));
    }

    @Test
    void 终审驳回_审批终结() {
        FlightRoute route = createRoute("R22", standardWaypoints());
        RouteApproval approval = routeService.submitPublish(route.getId(), goodConditions());
        approvalService.firstApprove(approval.getId(), decision("现场主管", true));
        approval = approvalService.finalApprove(approval.getId(), decision("安全总监", false));
        assertEquals(ApprovalStatus.REJECTED, approval.getStatus());
        assertNull(routeService.mustGetRoute(route.getId()).getCurrentVersionId());
    }

    // -------------------- 新版本只作用未起飞任务 + 历史影像引用原航线 --------------------

    @Test
    void 新版本只重绑未起飞任务_已起飞任务与历史影像引用原版本() {
        FlightRoute route = createRoute("R30", standardWaypoints());
        FlightRouteVersion v1 = publishFully(route);

        // 两个未起飞任务
        FlightMission m1 = missionService.createMission(missionReq("FM-30-1", route.getId()));
        FlightMission m2 = missionService.createMission(missionReq("FM-30-2", route.getId()));
        assertEquals(v1.getId(), m1.getRouteVersionId());

        // m1 起飞（满足条件）并完成，随后提交影像
        MissionDepartRequest depart = new MissionDepartRequest();
        depart.setWindSpeed(7.0);
        depart.setBatteryPct(100);
        depart.setEffectiveTime(LocalDateTime.of(2026, 10, 7, 12, 0));
        missionService.depart(m1.getId(), depart);
        missionService.finish(m1.getId());

        OrderCreateRequest orderReq = new OrderCreateRequest();
        orderReq.setOrderNo("IO-30-1");
        orderReq.setTurbineId(route.getTurbineId());
        orderReq.setMissionId(m1.getId());
        InspectionOrder order = inspectionService.createOrder(orderReq);
        assertEquals(v1.getId(), order.getRouteVersionId());

        InspectSubmitRequest inspect = new InspectSubmitRequest();
        inspect.setDroneImageUrl("oss://evops/drone/R30/v1/a1.jpg");
        inspect.setInspectTime(LocalDateTime.of(2026, 10, 7, 12, 30));
        inspectionService.submitInspection(order.getId(), inspect);

        // 调整待发布配置：2 号航点拍摄距离 6 → 8，发布 v2
        RouteConfigUpdateRequest update = new RouteConfigUpdateRequest();
        List<WaypointRequest> changed = standardWaypoints();
        changed.get(1).setShootDistanceM(8.0);
        update.setWaypoints(changed);
        routeService.updateConfig(route.getId(), update);

        RouteApproval approval = routeService.submitPublish(route.getId(), goodConditions());
        approvalService.firstApprove(approval.getId(), decision("现场主管", true));
        approvalService.finalApprove(approval.getId(), decision("安全总监", true));

        FlightRoute reloaded = routeService.mustGetRoute(route.getId());
        FlightRouteVersion v2 = routeService.mustGetVersion(reloaded.getCurrentVersionId());
        assertEquals(RouteChangeType.REVISION, v2.getChangeType());

        // v1 归档
        assertEquals(RouteVersionStatus.ARCHIVED,
                routeService.mustGetVersion(v1.getId()).getStatus());

        // 未起飞任务 m2 重绑 v2；已起飞完成的 m1 仍引用 v1
        assertEquals(v2.getId(), missionService.mustGet(m2.getId()).getRouteVersionId());
        assertEquals(v1.getId(), missionService.mustGet(m1.getId()).getRouteVersionId());

        // 历史影像工单继续引用原航线 v1
        InspectionOrder orderReloaded = inspectionService.mustGetOrder(order.getId());
        assertEquals(v1.getId(), orderReloaded.getRouteVersionId());
        // v1 快照仍可还原（历史影像可追溯原航线）
        List<WaypointRequest> v1Waypoints = routeService.snapshotWaypoints(v1.getId());
        assertEquals(6.0, v1Waypoints.get(1).getShootDistanceM());
        List<WaypointRequest> v2Waypoints = routeService.snapshotWaypoints(v2.getId());
        assertEquals(8.0, v2Waypoints.get(1).getShootDistanceM());
    }

    @Test
    void 起飞时再次校验_禁飞区不满足则不能起飞() {
        FlightRoute route = createRoute("R31", standardWaypoints());
        publishFully(route);
        FlightMission mission = missionService.createMission(missionReq("FM-31-1", route.getId()));

        // 起飞前出现临时禁飞区
        LocalDateTime t = LocalDateTime.of(2026, 10, 8, 9, 0);
        NoFlyZoneRequest zoneReq = new NoFlyZoneRequest();
        zoneReq.setZoneNo("NFZ-31");
        zoneReq.setZoneName("突发救援空域");
        zoneReq.setLongitude(BASE_LON);
        zoneReq.setLatitude(BASE_LAT);
        zoneReq.setRadiusM(100.0);
        zoneReq.setStartTime(t.minusMinutes(30));
        zoneReq.setEndTime(t.plusHours(2));
        zoneReq.setReason("应急救援");
        noFlyZoneService.create(zoneReq);

        MissionDepartRequest depart = new MissionDepartRequest();
        depart.setWindSpeed(5.0);
        depart.setBatteryPct(100);
        depart.setEffectiveTime(t);
        assertThrows(RouteValidationException.class, () -> missionService.depart(mission.getId(), depart));
        assertEquals(MissionStatus.PENDING, missionService.mustGet(mission.getId()).getStatus());
    }

    // -------------------- 临时改线：审批 + 版本差异 --------------------

    @Test
    void 临时改线_必须两级审批_并逐行保留版本差异() {
        FlightRoute route = createRoute("R40", standardWaypoints());
        FlightRouteVersion v1 = publishFully(route);

        // 改线：2 号航点拍摄距离 6 → 9，新增 4 号航点，删除 3 号航点
        List<WaypointRequest> changed = Arrays.asList(
                wp(1, BladePosition.A, 5.0, BASE_LON, BASE_LAT),
                wp(2, BladePosition.B, 9.0, BASE_LON + 0.001, BASE_LAT),
                wp(4, BladePosition.C, 7.0, BASE_LON + 0.002, BASE_LAT));

        TemporaryChangeRequest changeReq = new TemporaryChangeRequest();
        changeReq.setReason("叶片B侧发现新增吊装作业，临时绕行拉开拍摄距离");
        changeReq.setWaypoints(changed);
        changeReq.setWindSpeed(5.0);
        changeReq.setBatteryPct(100);
        changeReq.setEffectiveTime(LocalDateTime.of(2026, 10, 9, 10, 0));

        RouteApproval approval = routeService.submitTemporaryChange(route.getId(), changeReq);
        assertEquals(ApprovalType.TEMPORARY_CHANGE, approval.getApprovalType());

        // 半程停留，不生效
        approval = approvalService.firstApprove(approval.getId(), decision("现场主管", true));
        assertEquals(ApprovalStatus.PENDING_HALF, approval.getStatus());
        assertEquals(v1.getId(), routeService.mustGetRoute(route.getId()).getCurrentVersionId());

        // 终审通过
        approval = approvalService.finalApprove(approval.getId(), decision("安全总监", true));
        FlightRoute reloaded = routeService.mustGetRoute(route.getId());
        FlightRouteVersion change = routeService.mustGetVersion(reloaded.getCurrentVersionId());
        assertEquals(RouteChangeType.TEMPORARY_CHANGE, change.getChangeType());
        assertEquals(v1.getId(), change.getBaseVersionId());

        // 版本差异逐行留痕：改距离、新增航点、删除航点
        List<RouteVersionDiff> diffs = routeService.listDiffs(change.getId());
        assertTrue(diffs.stream().anyMatch(d -> "shootDistanceM".equals(d.getFieldName())
                && "6.0".equals(d.getOldValue()) && "9.0".equals(d.getNewValue())));
        assertTrue(diffs.stream().anyMatch(d -> d.getChangeKind().name().equals("WAYPOINT_ADDED")
                && d.getSeq() == 4));
        assertTrue(diffs.stream().anyMatch(d -> d.getChangeKind().name().equals("WAYPOINT_REMOVED")
                && d.getSeq() == 3));

        // 未起飞任务重绑到改线版本；关联工单记录改线审批单号
        FlightMission mission = missionService.createMission(missionReq("FM-40-1", route.getId()));
        assertEquals(change.getId(), mission.getRouteVersionId());
    }

    @Test
    void 无已发布版本时不能临时改线() {
        FlightRoute route = createRoute("R41", standardWaypoints());
        TemporaryChangeRequest req = new TemporaryChangeRequest();
        req.setWaypoints(standardWaypoints());
        assertThrows(IllegalArgumentException.class,
                () -> routeService.submitTemporaryChange(route.getId(), req));
    }

    // -------------------- 禁飞区窗口与可追溯 --------------------

    @Test
    void 禁飞区左闭右开窗口_撤销后放行_档案保留() {
        createRoute("R50", standardWaypoints());
        LocalDateTime t = LocalDateTime.of(2026, 10, 7, 10, 0);

        NoFlyZoneRequest zoneReq = new NoFlyZoneRequest();
        zoneReq.setZoneNo("NFZ-50");
        zoneReq.setZoneName("短时管控");
        zoneReq.setLongitude(BASE_LON);
        zoneReq.setLatitude(BASE_LAT);
        zoneReq.setRadiusM(50.0);
        zoneReq.setStartTime(t);
        zoneReq.setEndTime(t.plusHours(1));
        NoFlyZone zone = noFlyZoneService.create(zoneReq);

        // 起点（含）生效
        assertTrue(noFlyZoneService.listEffectiveAt(t).stream()
                .anyMatch(z -> z.getZoneNo().equals("NFZ-50")));
        // 终点（不含）失效
        assertFalse(noFlyZoneService.listEffectiveAt(t.plusHours(1)).stream()
                .anyMatch(z -> z.getZoneNo().equals("NFZ-50")));

        // 撤销后放行，档案保留
        noFlyZoneService.revoke(zone.getId());
        assertFalse(noFlyZoneService.listEffectiveAt(t.plusMinutes(10)).stream()
                .anyMatch(z -> z.getZoneNo().equals("NFZ-50")));
        assertNotNull(noFlyZoneService.mustGet(zone.getId()));
    }

    @Test
    void 未起飞任务不能关联工单_起飞后可以并继承冻结版本() {
        FlightRoute route = createRoute("R60", standardWaypoints());
        FlightRouteVersion v1 = publishFully(route);
        FlightMission mission = missionService.createMission(missionReq("FM-60-1", route.getId()));

        OrderCreateRequest req = new OrderCreateRequest();
        req.setOrderNo("IO-60-1");
        req.setTurbineId(route.getTurbineId());
        req.setMissionId(mission.getId());
        // PENDING 未起飞：拒绝
        assertThrows(IllegalArgumentException.class, () -> inspectionService.createOrder(req));

        // 取消的任务同样拒绝
        missionService.cancel(mission.getId());
        assertThrows(IllegalArgumentException.class, () -> inspectionService.createOrder(req));

        // 新建任务并起飞后允许关联，工单继承冻结版本
        FlightMission mission2 = missionService.createMission(missionReq("FM-60-2", route.getId()));
        MissionDepartRequest depart = new MissionDepartRequest();
        depart.setWindSpeed(6.0);
        depart.setBatteryPct(100);
        depart.setEffectiveTime(LocalDateTime.of(2026, 10, 10, 9, 0));
        missionService.depart(mission2.getId(), depart);

        req.setMissionId(mission2.getId());
        InspectionOrder order = inspectionService.createOrder(req);
        assertEquals(v1.getId(), order.getRouteVersionId());
        assertEquals(mission2.getId(), order.getRouteMissionId());
    }

    private MissionCreateRequest missionReq(String no, Long routeId) {
        MissionCreateRequest req = new MissionCreateRequest();
        req.setMissionNo(no);
        req.setRouteId(routeId);
        return req;
    }
}
