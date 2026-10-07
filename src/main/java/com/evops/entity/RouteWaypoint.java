package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.BladePosition;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 航点：在 seq 顺序上对某叶片方位（A/B/C）按给定拍摄距离巡拍。
 * 经纬度用于与临时禁飞区做冲突校验。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_route_waypoint")
public class RouteWaypoint extends BaseEntity {
    private Long routeId;
    private Integer seq;
    private BladePosition bladePosition;
    /** 拍摄距离（米） */
    private Double shootDistanceM;
    private Double longitude;
    private Double latitude;
    /** 相对起飞点/地面的飞行高度（米） */
    private Double altitudeM;
    /** 云台俯仰角（度），可空 */
    private Double gimbalPitchDeg;
}
