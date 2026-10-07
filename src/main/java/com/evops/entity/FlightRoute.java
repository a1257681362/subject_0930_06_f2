package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 无人机叶片巡检航线：一条风机一条。
 * 航点（t_route_waypoint）按叶片方位 + 拍摄距离表达具体巡拍位置。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_flight_route")
public class FlightRoute extends BaseEntity {
    private String routeNo;
    private String routeName;
    private Long turbineId;
    /** 允许作业最大风速 m/s */
    private Double maxWindSpeed;
    /** 返航必须保留的电量余量（百分比） */
    private Integer reserveBatteryPct;
    /** 完成航线预估耗电（百分比） */
    private Integer estimatedPowerPct;
    /** 当前已发布版本；无发布版本时为 null */
    private Long currentVersionId;
    private String remark;
}
