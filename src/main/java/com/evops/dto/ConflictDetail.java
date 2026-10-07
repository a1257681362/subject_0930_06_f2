package com.evops.dto;

import lombok.Data;

/**
 * 禁飞区冲突明细：给出冲突区域坐标（圆心+半径）、航点坐标与禁飞原因。
 * 序列化进 t_route_publish_check.conflict_detail，航线规划可追溯。
 */
@Data
public class ConflictDetail {
    private String zoneNo;
    private String zoneName;
    /** 冲突禁飞区圆心坐标 */
    private Double zoneLongitude;
    private Double zoneLatitude;
    private Double radiusM;
    /** 发生冲突的航点 */
    private Integer waypointSeq;
    private String bladePosition;
    private Double waypointLongitude;
    private Double waypointLatitude;
    /** 航点到圆心距离（米） */
    private Double distanceM;
    /** 冲突原因（含禁飞区设立原因与生效窗口说明） */
    private String reason;
}
