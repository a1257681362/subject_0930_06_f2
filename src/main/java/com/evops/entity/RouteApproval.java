package com.evops.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;
import com.evops.enums.ApprovalStatus;
import com.evops.enums.ApprovalType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 航线审批单：发布 / 临时改线均需两级审批。
 * 只完成一半（一级通过、终审未决）时 status=PENDING_HALF，系统停留待处理。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_route_approval")
public class RouteApproval extends BaseEntity {
    private String approvalNo;
    private Long routeId;
    private Long versionId;
    private ApprovalType approvalType;
    /** 临时改线的基线版本；发布审批可空 */
    private Long baseVersionId;
    private String reason;
    private ApprovalStatus status;
    private String firstApprover;
    private LocalDateTime firstApprovedAt;
    private String firstRemark;
    private String finalApprover;
    private LocalDateTime finalApprovedAt;
    private String finalRemark;
}
