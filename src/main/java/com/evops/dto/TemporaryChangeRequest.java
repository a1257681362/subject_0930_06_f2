package com.evops.dto;

import lombok.Data;

import javax.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 临时改线申请：在当前已发布版本基础上调整航点（必须），阈值可选。
 * 必须走两级审批，通过后生成 TEMPORARY_CHANGE 版本并保留与基线的版本差异；
 * 提交时同样校验临时禁飞区、风速条件、电量余量。
 */
@Data
public class TemporaryChangeRequest {
    /** 改线原因/依据，如临时气象、周边施工 */
    private String reason;
    /** 基线版本，不填取当前发布版本 */
    private Long baseVersionId;

    @Valid
    private List<WaypointRequest> waypoints;
    private Double maxWindSpeed;
    private Integer reserveBatteryPct;
    private Integer estimatedPowerPct;

    /** 发布时刻实测/预报风速 m/s */
    private Double windSpeed;
    /** 起飞前电量百分比 */
    private Integer batteryPct;
    /** 禁飞区窗口判定时刻，不填取当前时刻 */
    private LocalDateTime effectiveTime;
}
