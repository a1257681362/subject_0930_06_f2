package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.MissionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 飞行任务。起飞瞬间锁定 routeVersionId：航线新版本只重绑未起飞（PENDING）任务，
 * 已起飞/已完成任务继续按原版本执行，历史影像因此始终引用原航线版本。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_flight_mission")
public class FlightMission extends BaseEntity {
    private String missionNo;
    private Long routeId;
    private Long routeVersionId;
    private Long turbineId;
    private MissionStatus status;
    private LocalDateTime scheduledAt;
    private LocalDateTime departedAt;
    private LocalDateTime finishedAt;
    /** 最近一次发布前/起飞前校验结果 */
    private Long lastCheckId;
}
