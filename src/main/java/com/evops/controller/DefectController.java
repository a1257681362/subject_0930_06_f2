package com.evops.controller;

import com.evops.common.ApiResponse;
import com.evops.dto.AcceptRequest;
import com.evops.dto.RepairRequest;
import com.evops.entity.Defect;
import com.evops.service.InspectionService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/defects")
public class DefectController {

    private final InspectionService inspectionService;

    public DefectController(InspectionService inspectionService) {
        this.inspectionService = inspectionService;
    }

    /** 维修：回填维修结果 */
    @PostMapping("/{id}/repair")
    public ApiResponse<Defect> repair(@PathVariable Long id,
                                      @Validated @RequestBody RepairRequest req) {
        return ApiResponse.ok(inspectionService.repair(id, req));
    }

    /** 验收：通过闭环，驳回退回待维修 */
    @PostMapping("/{id}/accept")
    public ApiResponse<Defect> accept(@PathVariable Long id,
                                      @Validated @RequestBody AcceptRequest req) {
        return ApiResponse.ok(inspectionService.accept(id, req));
    }
}
