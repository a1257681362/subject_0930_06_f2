package com.evops.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;

/** 巡检工单创建入参 */
@Data
public class OrderCreateRequest {
    /** 工单号；为空时自动生成 */
    private String orderNo;
    @NotNull(message = "风机不能为空")
    private Long turbineId;
    /** 指定单叶片巡检；为空表示整机巡检 */
    private Long bladeId;
    /** 关联飞行任务：工单继承其已冻结的航线版本与临时改线审批，历史影像可追溯原航线 */
    private Long missionId;
}
