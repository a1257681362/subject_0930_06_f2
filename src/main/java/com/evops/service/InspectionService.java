package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.dto.AcceptRequest;
import com.evops.dto.DefectRegisterRequest;
import com.evops.dto.InspectSubmitRequest;
import com.evops.dto.OrderCreateRequest;
import com.evops.dto.RepairRequest;
import com.evops.entity.Blade;
import com.evops.entity.Defect;
import com.evops.entity.FlightMission;
import com.evops.entity.InspectionOrder;
import com.evops.enums.DefectStatus;
import com.evops.enums.MissionStatus;
import com.evops.enums.OrderStatus;
import com.evops.mapper.DefectMapper;
import com.evops.mapper.InspectionOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class InspectionService extends ServiceImpl<InspectionOrderMapper, InspectionOrder> {

    private final TurbineService turbineService;
    private final BladeService bladeService;
    private final DefectMapper defectMapper;
    private final FlightMissionService missionService;
    private final RouteApprovalService routeApprovalService;

    public InspectionService(TurbineService turbineService, BladeService bladeService,
                             DefectMapper defectMapper, FlightMissionService missionService,
                             RouteApprovalService routeApprovalService) {
        this.turbineService = turbineService;
        this.bladeService = bladeService;
        this.defectMapper = defectMapper;
        this.missionService = missionService;
        this.routeApprovalService = routeApprovalService;
    }

    /** 创建巡检工单 */
    public InspectionOrder createOrder(OrderCreateRequest req) {
        turbineService.mustGet(req.getTurbineId());
        if (req.getBladeId() != null) {
            Blade blade = bladeService.mustGet(req.getBladeId());
            if (!blade.getTurbineId().equals(req.getTurbineId())) {
                throw new IllegalArgumentException("叶片不属于该风机: " + req.getBladeId());
            }
        }

        FlightMission mission = null;
        if (req.getMissionId() != null) {
            mission = missionService.mustGet(req.getMissionId());
            if (!mission.getTurbineId().equals(req.getTurbineId())) {
                throw new IllegalArgumentException("飞行任务不属于该风机: " + req.getMissionId());
            }
            // 必须已起飞：此时航线版本已冻结，工单影像引用飞行时的原航线版本
            if (mission.getStatus() == MissionStatus.PENDING || mission.getStatus() == MissionStatus.CANCELLED) {
                throw new IllegalArgumentException("飞行任务尚未起飞，不能关联巡检工单: " + mission.getStatus());
            }
        }

        String orderNo = StringUtils.hasText(req.getOrderNo())
                ? req.getOrderNo()
                : "IO" + System.currentTimeMillis();
        Long dup = count(new QueryWrapper<InspectionOrder>().eq("order_no", orderNo));
        if (dup > 0) {
            throw new IllegalArgumentException("工单号已存在: " + orderNo);
        }

        InspectionOrder order = new InspectionOrder();
        order.setOrderNo(orderNo);
        order.setTurbineId(req.getTurbineId());
        order.setBladeId(req.getBladeId());
        if (mission != null) {
            order.setRouteMissionId(mission.getId());
            // 继承起飞时冻结的版本；后续航线再发新版本也不改变，历史影像引用原航线
            order.setRouteVersionId(mission.getRouteVersionId());
            // 若该版本来自已通过的临时改线，留审批单号，影像可追溯改线依据
            order.setRouteChangeApplyId(
                    routeApprovalService.findApprovedChangeApplyId(mission.getRouteVersionId()));
        }
        order.setStatus(OrderStatus.PENDING);
        save(order);
        return order;
    }

    /** 提交巡检结果：登记无人机影像与巡检时刻 */
    public InspectionOrder submitInspection(Long orderId, InspectSubmitRequest req) {
        InspectionOrder order = mustGetOrder(orderId);
        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalArgumentException("工单状态不允许提交巡检结果: " + order.getStatus());
        }
        order.setDroneImageUrl(req.getDroneImageUrl());
        order.setInspectTime(req.getInspectTime());
        order.setStatus(OrderStatus.INSPECTED);
        updateById(order);
        return order;
    }

    /** 缺陷登记：一个工单可登记多条缺陷 */
    @Transactional
    public Defect registerDefect(Long orderId, DefectRegisterRequest req) {
        InspectionOrder order = mustGetOrder(orderId);
        if (order.getStatus() != OrderStatus.INSPECTED
                && order.getStatus() != OrderStatus.DEFECT_REGISTERED) {
            throw new IllegalArgumentException("工单状态不允许登记缺陷: " + order.getStatus());
        }
        Blade blade = bladeService.mustGet(req.getBladeId());
        if (!blade.getTurbineId().equals(order.getTurbineId())) {
            throw new IllegalArgumentException("缺陷叶片不属于工单风机: " + req.getBladeId());
        }

        Defect defect = new Defect();
        defect.setOrderId(orderId);
        defect.setBladeId(req.getBladeId());
        defect.setDefectLevel(req.getDefectLevel());
        defect.setDescription(req.getDescription());
        defect.setStatus(DefectStatus.OPEN);
        defectMapper.insert(defect);

        order.setStatus(OrderStatus.DEFECT_REGISTERED);
        updateById(order);
        return defect;
    }

    /** 维修：回填维修结果 */
    @Transactional
    public Defect repair(Long defectId, RepairRequest req) {
        Defect defect = mustGetDefect(defectId);
        if (defect.getStatus() != DefectStatus.OPEN) {
            throw new IllegalArgumentException("缺陷状态不允许维修: " + defect.getStatus());
        }
        defect.setRepairResult(req.getRepairResult());
        defect.setRepairedAt(LocalDateTime.now());
        defect.setStatus(DefectStatus.REPAIRED);
        defectMapper.updateById(defect);

        InspectionOrder order = mustGetOrder(defect.getOrderId());
        order.setStatus(OrderStatus.REPAIRED);
        updateById(order);
        return defect;
    }

    /** 验收：通过则闭环；驳回则退回待维修。工单下全部缺陷验收通过后工单闭环 */
    @Transactional
    public Defect accept(Long defectId, AcceptRequest req) {
        Defect defect = mustGetDefect(defectId);
        if (defect.getStatus() != DefectStatus.REPAIRED) {
            throw new IllegalArgumentException("缺陷状态不允许验收: " + defect.getStatus());
        }
        InspectionOrder order = mustGetOrder(defect.getOrderId());

        if (Boolean.TRUE.equals(req.getPass())) {
            defect.setStatus(DefectStatus.ACCEPTED);
            defect.setAcceptedAt(LocalDateTime.now());
            defect.setAcceptRemark(req.getRemark());
            defectMapper.updateById(defect);

            Long remaining = defectMapper.selectCount(new QueryWrapper<Defect>()
                    .eq("order_id", order.getId())
                    .ne("status", DefectStatus.ACCEPTED));
            if (remaining == 0) {
                order.setStatus(OrderStatus.ACCEPTED);
                updateById(order);
            }
        } else {
            // 验收驳回：退回待维修
            defect.setStatus(DefectStatus.OPEN);
            defect.setAcceptRemark(req.getRemark());
            defectMapper.updateById(defect);

            order.setStatus(OrderStatus.DEFECT_REGISTERED);
            updateById(order);
        }
        return defect;
    }

    /** 无缺陷闭环 */
    public InspectionOrder close(Long orderId) {
        InspectionOrder order = mustGetOrder(orderId);
        if (order.getStatus() != OrderStatus.INSPECTED) {
            throw new IllegalArgumentException("工单状态不允许闭环: " + order.getStatus());
        }
        order.setStatus(OrderStatus.CLOSED);
        updateById(order);
        return order;
    }

    public List<Defect> listDefects(Long orderId) {
        return defectMapper.selectList(new QueryWrapper<Defect>()
                .eq("order_id", orderId)
                .orderByAsc("id"));
    }

    public InspectionOrder mustGetOrder(Long id) {
        InspectionOrder order = getById(id);
        if (order == null) {
            throw new IllegalArgumentException("巡检工单不存在: " + id);
        }
        return order;
    }

    public Defect mustGetDefect(Long id) {
        Defect defect = defectMapper.selectById(id);
        if (defect == null) {
            throw new IllegalArgumentException("缺陷不存在: " + id);
        }
        return defect;
    }
}
