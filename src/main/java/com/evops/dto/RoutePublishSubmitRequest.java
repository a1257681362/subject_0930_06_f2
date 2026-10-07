package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.PositiveOrZero;
import java.time.LocalDateTime;

/**
 * 发布/临时改线提交：带发布时刻的风速与电量条件，服务端做发布前三校验。
 * effectiveTime 为禁飞区窗口判定时刻（如计划起飞时刻），不填取当前时刻。
 */
@Data
public class RoutePublishSubmitRequest {
    /** 实测/预报风速 m/s */
    @NotNull(message = "风速条件不能为空")
    @PositiveOrZero(message = "风速不能为负")
    private Double windSpeed;
    /** 起飞前电量百分比 */
    @NotNull(message = "电量条件不能为空")
    @PositiveOrZero(message = "电量不能为负")
    private Integer batteryPct;
    /** 禁飞区窗口判定时刻，不填取当前时刻 */
    private LocalDateTime effectiveTime;
    /** 临时改线原因（发布时可空） */
    private String reason;
}
