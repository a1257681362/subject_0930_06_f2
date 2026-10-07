package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;

/** 航线创建入参：按风机配置，航点表达叶片方位与拍摄距离 */
@Data
public class RouteCreateRequest {
    @NotBlank(message = "航线编号不能为空")
    private String routeNo;
    @NotBlank(message = "航线名称不能为空")
    private String routeName;
    @NotNull(message = "风机不能为空")
    private Long turbineId;
    /** 允许作业最大风速 m/s，不填默认 10 */
    private Double maxWindSpeed;
    /** 返航保留电量余量百分比，不填默认 30 */
    private Integer reserveBatteryPct;
    /** 航线预估耗电百分比，不填默认 60 */
    private Integer estimatedPowerPct;
    private String remark;

    @Valid
    @NotEmpty(message = "航点不能为空")
    private List<WaypointRequest> waypoints;
}
