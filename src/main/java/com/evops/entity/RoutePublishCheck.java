package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 发布前校验结果：临时禁飞区冲突、风速条件、电量余量。
 * conflictDetail 以 JSON 记录冲突禁飞区坐标、航点坐标、距离与原因，保证航线规划可追溯。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_route_publish_check")
public class RoutePublishCheck extends BaseEntity {
    private Long routeId;
    private Long versionId;
    private Boolean passed;
    private LocalDateTime checkedAt;
    /** 校验时刻实测/预报风速 m/s */
    private Double windSpeed;
    private Double maxWindSpeed;
    /** 起飞前电量（百分比） */
    private Integer batteryPct;
    private Integer reserveBatteryPct;
    /** 禁飞区冲突明细 JSON */
    private String conflictDetail;
    /** 全部不通过原因 JSON（禁飞区/风速/电量） */
    private String failReasons;
}
