package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.NoFlyZoneRequest;
import com.evops.entity.NoFlyZone;
import com.evops.service.NoFlyZoneService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 临时禁飞区档案：建档、撤销、按时刻查询生效区域（禁飞区可追溯） */
@RestController
@RequestMapping("/api/no-fly-zones")
public class NoFlyZoneController {

    private final NoFlyZoneService noFlyZoneService;

    public NoFlyZoneController(NoFlyZoneService noFlyZoneService) {
        this.noFlyZoneService = noFlyZoneService;
    }

    @PostMapping
    public ApiResponse<NoFlyZone> create(@Validated @RequestBody NoFlyZoneRequest req) {
        return ApiResponse.ok(noFlyZoneService.create(req));
    }

    /** 撤销：档案保留可追溯 */
    @PostMapping("/{id}/revoke")
    public ApiResponse<NoFlyZone> revoke(@PathVariable Long id) {
        return ApiResponse.ok(noFlyZoneService.revoke(id));
    }

    @GetMapping("/{id}")
    public ApiResponse<NoFlyZone> detail(@PathVariable Long id) {
        return ApiResponse.ok(noFlyZoneService.mustGet(id));
    }

    /** 某时刻生效中的禁飞区；不带时间取当前时刻 */
    @GetMapping
    public ApiResponse<List<NoFlyZone>> effective(@RequestParam(required = false)
                                                  @org.springframework.format.annotation.DateTimeFormat(
                                                          iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
                                                          LocalDateTime time) {
        return ApiResponse.ok(noFlyZoneService.listEffectiveAt(time != null ? time : LocalDateTime.now()));
    }
}
