package com.evops.dto;

import com.evops.enums.DefectLevel;
import lombok.Data;

import javax.validation.constraints.NotNull;

/** 缺陷登记入参 */
@Data
public class DefectRegisterRequest {
    @NotNull(message = "叶片不能为空")
    private Long bladeId;
    @NotNull(message = "缺陷等级不能为空")
    private DefectLevel defectLevel;
    private String description;
}
