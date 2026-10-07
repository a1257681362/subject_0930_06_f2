package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.time.LocalDateTime;

/** 飞行任务创建：默认绑定航线当前发布版本 */
@Data
public class MissionCreateRequest {
    private String missionNo;
    @NotNull(message = "航线不能为空")
    private Long routeId;
    private LocalDateTime scheduledAt;
}
