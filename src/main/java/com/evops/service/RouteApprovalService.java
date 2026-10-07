package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.dto.ApprovalDecisionRequest;
import com.evops.entity.RouteApproval;
import com.evops.enums.ApprovalStatus;
import com.evops.enums.ApprovalType;
import com.evops.mapper.RouteApprovalMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 航线两级审批。
 *
 * 一级（现场/编制审核）：PENDING → PENDING_HALF（通过）或 REJECTED（驳回）；
 * 终审（批准放行）：PENDING_HALF → APPROVED（通过，激活版本）或 REJECTED（驳回）。
 * 只完成一半时审批单停留 PENDING_HALF 待处理，航线不生效。
 */
@Service
public class RouteApprovalService {

    private final RouteApprovalMapper approvalMapper;
    private final FlightRouteService flightRouteService;

    public RouteApprovalService(RouteApprovalMapper approvalMapper,
                                @Lazy FlightRouteService flightRouteService) {
        this.approvalMapper = approvalMapper;
        this.flightRouteService = flightRouteService;
    }

    /** 一级审批 */
    @Transactional
    public RouteApproval firstApprove(Long approvalId, ApprovalDecisionRequest req) {
        RouteApproval approval = mustGet(approvalId);
        if (approval.getStatus() != ApprovalStatus.PENDING) {
            throw new IllegalArgumentException("审批单当前状态不允许一级审批: " + approval.getStatus());
        }
        approval.setFirstApprover(req.getApprover());
        approval.setFirstApprovedAt(LocalDateTime.now());
        approval.setFirstRemark(req.getRemark());
        if (Boolean.TRUE.equals(req.getPass())) {
            // 只完成一半：停留待处理，等待终审
            approval.setStatus(ApprovalStatus.PENDING_HALF);
        } else {
            approval.setStatus(ApprovalStatus.REJECTED);
        }
        approvalMapper.updateById(approval);
        return approval;
    }

    /** 终审：两级全部通过才激活航线版本 */
    @Transactional
    public RouteApproval finalApprove(Long approvalId, ApprovalDecisionRequest req) {
        RouteApproval approval = mustGet(approvalId);
        if (approval.getStatus() != ApprovalStatus.PENDING_HALF) {
            throw new IllegalArgumentException("审批单未停留待处理状态，不能终审: " + approval.getStatus());
        }
        approval.setFinalApprover(req.getApprover());
        approval.setFinalApprovedAt(LocalDateTime.now());
        approval.setFinalRemark(req.getRemark());
        if (Boolean.TRUE.equals(req.getPass())) {
            approval.setStatus(ApprovalStatus.APPROVED);
            approvalMapper.updateById(approval);
            // 两级审批完成：发布/改线版本生效，仅重绑未起飞任务
            flightRouteService.activateVersion(approval);
        } else {
            approval.setStatus(ApprovalStatus.REJECTED);
            approvalMapper.updateById(approval);
        }
        return approval;
    }

    /** 待处理审批单：未开始（PENDING）与只完成一半（PENDING_HALF） */
    public List<RouteApproval> listPending() {
        return approvalMapper.selectList(new QueryWrapper<RouteApproval>()
                .in("status", ApprovalStatus.PENDING, ApprovalStatus.PENDING_HALF)
                .orderByAsc("id"));
    }

    public List<RouteApproval> listByRoute(Long routeId) {
        return approvalMapper.selectList(new QueryWrapper<RouteApproval>()
                .eq("route_id", routeId)
                .orderByDesc("id"));
    }

    /** 查版本对应的临时改线审批单（已通过），供影像追溯改线依据 */
    public Long findApprovedChangeApplyId(Long versionId) {
        List<RouteApproval> list = approvalMapper.selectList(new QueryWrapper<RouteApproval>()
                .eq("version_id", versionId)
                .eq("approval_type", ApprovalType.TEMPORARY_CHANGE)
                .eq("status", ApprovalStatus.APPROVED)
                .orderByDesc("id")
                .last("LIMIT 1"));
        return list.isEmpty() ? null : list.get(0).getId();
    }

    public RouteApproval mustGet(Long id) {
        RouteApproval approval = approvalMapper.selectById(id);
        if (approval == null) {
            throw new IllegalArgumentException("航线审批单不存在: " + id);
        }
        return approval;
    }
}
