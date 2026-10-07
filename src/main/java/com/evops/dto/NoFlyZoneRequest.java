package com.evops.dto;

import com.evops.enums.NoFlyZoneType;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.time.LocalDateTime;

/** 临时禁飞区建档入参 */
@Data
public class NoFlyZoneRequest {
    @NotBlank(message = "禁飞区编号不能为空")
    private String zoneNo;
    @NotBlank(message = "禁飞区名称不能为空")
    private String zoneName;
    /** 不填按临时禁飞区处理 */
    private NoFlyZoneType zoneType;
    @NotNull(message = "圆心经度不能为空")
    private Double longitude;
    @NotNull(message = "圆心纬度不能为空")
    private Double latitude;
    @NotNull(message = "半径不能为空")
    @Positive(message = "半径必须大于 0")
    private Double radiusM;
    @NotNull(message = "生效开始时刻不能为空")
    private LocalDateTime startTime;
    /** 失效时刻（不含），不填表示撤销前长期有效 */
    private LocalDateTime endTime;
    private String reason;
}
