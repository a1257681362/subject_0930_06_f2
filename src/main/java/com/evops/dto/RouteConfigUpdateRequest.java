package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import java.util.List;

/**
 * 航线配置调整：只改“待发布配置”（航点与阈值）。
 * 已发布版本不可变；调整内容在下次发布/临时改线时形成新版本。
 */
@Data
public class RouteConfigUpdateRequest {
    private Double maxWindSpeed;
    private Integer reserveBatteryPct;
    private Integer estimatedPowerPct;

    @Valid
    @NotEmpty(message = "航点不能为空")
    private List<WaypointRequest> waypoints;
}
