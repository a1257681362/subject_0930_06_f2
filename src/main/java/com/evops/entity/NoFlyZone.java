package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.NoFlyZoneStatus;
import com.evops.enums.NoFlyZoneType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 临时禁飞区：圆心经纬度 + 半径（米）的圆形区域，生效窗口 [startTime, endTime)。
 * end_time 为空表示长期有效（PERMANENT 或临时区撤销前持续）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_no_fly_zone")
public class NoFlyZone extends BaseEntity {
    private String zoneNo;
    private String zoneName;
    private NoFlyZoneType zoneType;
    private Double longitude;
    private Double latitude;
    private Double radiusM;
    private LocalDateTime startTime;
    /** 失效时刻（不含）；null 表示未设结束时间 */
    private LocalDateTime endTime;
    /** 设立原因，冲突时随坐标一并返回 */
    private String reason;
    private NoFlyZoneStatus status;
}
