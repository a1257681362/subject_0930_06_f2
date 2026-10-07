package com.evops.dto;

import com.evops.enums.BladePosition;
import lombok.Data;

/**
 * 航点配置：风机下某叶片方位（A/B/C）在给定拍摄距离上的巡拍点。
 * 同时用于航线入参与版本快照序列化（快照字段不可再随意增减）。
 */
@Data
public class WaypointRequest {
    /** 顺序号，同一航线内唯一 */
    private Integer seq;
    private BladePosition bladePosition;
    /** 拍摄距离（米） */
    private Double shootDistanceM;
    private Double longitude;
    private Double latitude;
    /** 飞行高度（米），不填取 80m 默认 */
    private Double altitudeM;
    private Double gimbalPitchDeg;
}
