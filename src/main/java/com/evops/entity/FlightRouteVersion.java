package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.RouteChangeType;
import com.evops.enums.RouteVersionStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 航线版本：发布/临时改线均生成不可变快照。
 * 历史影像引用具体版本，版本内容永远可还原（waypointSnapshot + 阈值列）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_flight_route_version")
public class FlightRouteVersion extends BaseEntity {
    private Long routeId;
    private String versionNo;
    /** 改版基线版本；首发为 null */
    private Long baseVersionId;
    private RouteChangeType changeType;
    /** 航点配置快照 JSON */
    private String waypointSnapshot;
    private String snapshotHash;
    private Double maxWindSpeed;
    private Integer reserveBatteryPct;
    private Integer estimatedPowerPct;
    private RouteVersionStatus status;
    private LocalDateTime publishedAt;
}
