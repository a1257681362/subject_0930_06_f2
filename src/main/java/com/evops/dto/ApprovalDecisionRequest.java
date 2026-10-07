package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 审批决定：两级审批通用；pass=false 即驳回，审批单终结为 REJECTED */
@Data
public class ApprovalDecisionRequest {
    @NotBlank(message = "审批人不能为空")
    private String approver;
    private String remark;
    /** 不填视为通过；false 为驳回 */
    @NotNull
    private Boolean pass;
}
