package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.ApprovalDecisionRequest;
import com.evops.dto.RouteConfigUpdateRequest;
import com.evops.dto.RouteCreateRequest;
import com.evops.dto.RoutePublishSubmitRequest;
import com.evops.dto.TemporaryChangeRequest;
import com.evops.entity.FlightRoute;
import com.evops.entity.FlightRouteVersion;
import com.evops.entity.RouteApproval;
import com.evops.entity.RouteVersionDiff;
import com.evops.entity.RouteWaypoint;
import com.evops.service.FlightRouteService;
import com.evops.service.RouteApprovalService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 无人机叶片巡检航线：配置、发布/临时改线两级审批、版本与差异追溯。
 */
@RestController
@RequestMapping("/api/flight-routes")
public class FlightRouteController {

    private final FlightRouteService routeService;
    private final RouteApprovalService approvalService;

    public FlightRouteController(FlightRouteService routeService, RouteApprovalService approvalService) {
        this.routeService = routeService;
        this.approvalService = approvalService;
    }

    /** 航线建档（按风机 + 航点的叶片方位/拍摄距离配置） */
    @PostMapping
    public ApiResponse<FlightRoute> create(@Validated @RequestBody RouteCreateRequest req) {
        return ApiResponse.ok(routeService.createRoute(req));
    }

    /** 调整待发布配置（不影响已发布版本） */
    @PutMapping("/{id}/config")
    public ApiResponse<FlightRoute> updateConfig(@PathVariable Long id,
                                                 @Validated @RequestBody RouteConfigUpdateRequest req) {
        return ApiResponse.ok(routeService.updateConfig(id, req));
    }

    /** 航线详情：基本信息 + 待发布航点 + 当前版本 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        Map<String, Object> result = new HashMap<>();
        FlightRoute route = routeService.mustGetRoute(id);
        result.put("route", route);
        result.put("waypoints", routeService.listWaypoints(id));
        if (route.getCurrentVersionId() != null) {
            result.put("currentVersion", routeService.mustGetVersion(route.getCurrentVersionId()));
        }
        return ApiResponse.ok(result);
    }

    /** 提交发布：先过禁飞区/风速/电量校验，再进入两级审批 */
    @PostMapping("/{id}/publish-submit")
    public ApiResponse<RouteApproval> submitPublish(@PathVariable Long id,
                                                    @Validated @RequestBody RoutePublishSubmitRequest req) {
        return ApiResponse.ok(routeService.submitPublish(id, req));
    }

    /** 临时改线申请：校验 + 两级审批，留版本差异 */
    @PostMapping("/{id}/temporary-change")
    public ApiResponse<RouteApproval> temporaryChange(@PathVariable Long id,
                                                      @Validated @RequestBody TemporaryChangeRequest req) {
        return ApiResponse.ok(routeService.submitTemporaryChange(id, req));
    }

    /** 一级审批：通过则停留 PENDING_HALF 待处理 */
    @PostMapping("/approvals/{approvalId}/first-approve")
    public ApiResponse<RouteApproval> firstApprove(@PathVariable Long approvalId,
                                                   @Validated @RequestBody ApprovalDecisionRequest req) {
        return ApiResponse.ok(approvalService.firstApprove(approvalId, req));
    }

    /** 终审：两级通过后版本激活，只重绑未起飞任务 */
    @PostMapping("/approvals/{approvalId}/final-approve")
    public ApiResponse<RouteApproval> finalApprove(@PathVariable Long approvalId,
                                                   @Validated @RequestBody ApprovalDecisionRequest req) {
        return ApiResponse.ok(approvalService.finalApprove(approvalId, req));
    }

    /** 待处理审批单（PENDING / PENDING_HALF）——审批完成一半时在此停留可见 */
    @GetMapping("/approvals/pending")
    public ApiResponse<List<RouteApproval>> pendingApprovals() {
        return ApiResponse.ok(approvalService.listPending());
    }

    /** 版本列表（航线规划可追溯） */
    @GetMapping("/{id}/versions")
    public ApiResponse<List<FlightRouteVersion>> versions(@PathVariable Long id) {
        return ApiResponse.ok(routeService.listVersions(id));
    }

    /** 版本详情：版本快照 + 差异行 */
    @GetMapping("/versions/{versionId}")
    public ApiResponse<Map<String, Object>> versionDetail(@PathVariable Long versionId) {
        FlightRouteVersion version = routeService.mustGetVersion(versionId);
        Map<String, Object> result = new HashMap<>();
        result.put("version", version);
        result.put("waypoints", routeService.snapshotWaypoints(versionId));
        result.put("diffs", routeService.listDiffs(versionId));
        return ApiResponse.ok(result);
    }

    /** 航线下的审批记录 */
    @GetMapping("/{id}/approvals")
    public ApiResponse<List<RouteApproval>> approvals(@PathVariable Long id) {
        return ApiResponse.ok(approvalService.listByRoute(id));
    }
}
