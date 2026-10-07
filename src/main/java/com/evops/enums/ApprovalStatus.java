package com.evops.enums;

/**
 * 审批状态。两级审批：
 * PENDING —— 申请已提交、审批未开始（0/2）；
 * PENDING_HALF —— 只完成一半（一级通过、终审未决），系统停留待处理，不可发布/改线生效；
 * APPROVED —— 两级均通过；REJECTED —— 任一级驳回。
 */
public enum ApprovalStatus {
    PENDING, PENDING_HALF, APPROVED, REJECTED
}
