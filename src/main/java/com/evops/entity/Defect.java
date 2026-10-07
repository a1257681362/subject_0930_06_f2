package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.DefectLevel;
import com.evops.enums.DefectStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 缺陷登记：缺陷等级、维修结果、验收结论。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_defect")
public class Defect extends BaseEntity {
    private Long orderId;
    private Long bladeId;
    /** 缺陷等级 */
    private DefectLevel defectLevel;
    private String description;
    private DefectStatus status;
    /** 维修结果 */
    private String repairResult;
    private LocalDateTime repairedAt;
    private LocalDateTime acceptedAt;
    /** 验收备注（含驳回原因） */
    private String acceptRemark;
}
