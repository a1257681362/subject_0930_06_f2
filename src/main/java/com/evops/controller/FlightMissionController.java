package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.MissionCreateRequest;
import com.evops.dto.MissionDepartRequest;
import com.evops.entity.FlightMission;
import com.evops.service.FlightMissionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 飞行任务：创建绑定当前发布版本；起飞冻结版本并再过三校验；
 * 新版本只重绑未起飞任务。
 */
@RestController
@RequestMapping("/api/flight-missions")
public class FlightMissionController {

    private final FlightMissionService missionService;

    public FlightMissionController(FlightMissionService missionService) {
        this.missionService = missionService;
    }

    @PostMapping
    public ApiResponse<FlightMission> create(@Validated @RequestBody MissionCreateRequest req) {
        return ApiResponse.ok(missionService.createMission(req));
    }

    /** 起飞：按绑定版本再校验禁飞区/风速/电量，通过后版本冻结 */
    @PostMapping("/{id}/depart")
    public ApiResponse<FlightMission> depart(@PathVariable Long id,
                                             @Validated @RequestBody MissionDepartRequest req) {
        return ApiResponse.ok(missionService.depart(id, req));
    }

    @PostMapping("/{id}/finish")
    public ApiResponse<FlightMission> finish(@PathVariable Long id) {
        return ApiResponse.ok(missionService.finish(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<FlightMission> cancel(@PathVariable Long id) {
        return ApiResponse.ok(missionService.cancel(id));
    }

    @GetMapping("/{id}")
    public ApiResponse<FlightMission> detail(@PathVariable Long id) {
        return ApiResponse.ok(missionService.mustGet(id));
    }

    @GetMapping
    public ApiResponse<List<FlightMission>> listByRoute(@org.springframework.web.bind.annotation.RequestParam
                                                        Long routeId) {
        return ApiResponse.ok(missionService.listByRoute(routeId));
    }
}
