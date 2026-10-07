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
}
