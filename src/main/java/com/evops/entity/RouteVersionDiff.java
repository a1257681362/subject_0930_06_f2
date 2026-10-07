package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.DiffChangeKind;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 版本差异行：临时改线与基线版本逐字段对比留痕 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_route_version_diff")
public class RouteVersionDiff extends BaseEntity {
    private Long versionId;
    private DiffChangeKind changeKind;
    /** 航点顺序号；阈值类差异为 null */
    private Integer seq;
    /** 叶片方位；阈值类差异为 null */
    private String bladePosition;
    private String fieldName;
    private String oldValue;
    private String newValue;
}
