package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import java.time.LocalDateTime;

/** 起飞前条件：风速、电量与禁飞区窗口判定时刻，按任务绑定版本再校验一次 */
@Data
public class MissionDepartRequest {
    @NotNull(message = "风速条件不能为空")
    @PositiveOrZero(message = "风速不能为负")
    private Double windSpeed;
    @NotNull(message = "电量条件不能为空")
    @PositiveOrZero(message = "电量不能为负")
    private Integer batteryPct;
    private LocalDateTime effectiveTime;
}
