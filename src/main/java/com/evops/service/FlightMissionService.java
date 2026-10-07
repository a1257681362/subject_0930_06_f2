package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.dto.MissionCreateRequest;
import com.evops.dto.MissionDepartRequest;
import com.evops.entity.FlightMission;
import com.evops.entity.FlightRoute;
import com.evops.entity.FlightRouteVersion;
import com.evops.entity.RoutePublishCheck;
import com.evops.enums.MissionStatus;
import com.evops.enums.RouteVersionStatus;
import com.evops.mapper.FlightMissionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 飞行任务。
 *
 * 版本绑定口径：创建时绑定航线当前发布版本；航线新版本发布后，未起飞（PENDING）任务自动重绑，
 * 已起飞（FLYING）/已完成（FINISHED）任务不动。起飞瞬间再次过禁飞区/风速/电量校验。
 * 历史影像通过工单上的 route_version_id 继续引用飞行时原航线版本。
 */
@Service
public class FlightMissionService extends ServiceImpl<FlightMissionMapper, FlightMission> {

    private final FlightRouteService flightRouteService;
    private final RouteValidationService validationService;

    public FlightMissionService(FlightRouteService flightRouteService,
                                RouteValidationService validationService) {
        this.flightRouteService = flightRouteService;
        this.validationService = validationService;
    }

    /** 创建未起飞任务，绑定当前发布版本 */
    public FlightMission createMission(MissionCreateRequest req) {
        FlightRoute route = flightRouteService.mustGetRoute(req.getRouteId());
        if (route.getCurrentVersionId() == null) {
            throw new IllegalArgumentException("航线尚无已发布版本，不能创建飞行任务");
        }
        FlightRouteVersion version = flightRouteService.mustGetVersion(route.getCurrentVersionId());
        if (version.getStatus() != RouteVersionStatus.PUBLISHED) {
            throw new IllegalStateException("航线当前版本未处于已发布状态");
        }

        String missionNo = StringUtils.hasText(req.getMissionNo())
                ? req.getMissionNo()
                : "FM" + System.currentTimeMillis();
        Long dup = count(new QueryWrapper<FlightMission>().eq("mission_no", missionNo));
        if (dup > 0) {
            throw new IllegalArgumentException("飞行任务编号已存在: " + missionNo);
        }

        FlightMission mission = new FlightMission();
        mission.setMissionNo(missionNo);
        mission.setRouteId(route.getId());
        mission.setRouteVersionId(version.getId());
        mission.setTurbineId(route.getTurbineId());
        mission.setStatus(MissionStatus.PENDING);
        mission.setScheduledAt(req.getScheduledAt());
        save(mission);
        return mission;
    }

    /**
     * 起飞：仅未起飞任务可起飞；按当前绑定版本再做一次禁飞区/风速/电量校验。
     * 起飞后 routeVersionId 冻结，航线再发新版本不影响本任务与历史影像。
     */
    @Transactional
    public FlightMission depart(Long missionId, MissionDepartRequest req) {
        FlightMission mission = mustGet(missionId);
        if (mission.getStatus() != MissionStatus.PENDING) {
            throw new IllegalArgumentException("任务已起飞或已结束，不能重复起飞: " + mission.getStatus());
        }
        FlightRouteVersion version = flightRouteService.mustGetVersion(mission.getRouteVersionId());
        RoutePublishCheck check = validationService.check(version,
                version.getMaxWindSpeed(), version.getReserveBatteryPct(),
                req.getWindSpeed(), req.getBatteryPct(), req.getEffectiveTime(), true);

        mission.setStatus(MissionStatus.FLYING);
        mission.setDepartedAt(LocalDateTime.now());
        mission.setLastCheckId(check.getId());
        updateById(mission);
        return mission;
    }

    /** 完成飞行 */
    public FlightMission finish(Long missionId) {
        FlightMission mission = mustGet(missionId);
        if (mission.getStatus() != MissionStatus.FLYING) {
            throw new IllegalArgumentException("任务未在飞行中，不能完成: " + mission.getStatus());
        }
        mission.setStatus(MissionStatus.FINISHED);
        mission.setFinishedAt(LocalDateTime.now());
        updateById(mission);
        return mission;
    }

    /** 取消未起飞任务 */
    public FlightMission cancel(Long missionId) {
        FlightMission mission = mustGet(missionId);
        if (mission.getStatus() != MissionStatus.PENDING) {
            throw new IllegalArgumentException("只有未起飞任务可以取消: " + mission.getStatus());
        }
        mission.setStatus(MissionStatus.CANCELLED);
        updateById(mission);
        return mission;
    }

    /**
     * 新版本发布后重绑未起飞任务（PENDING 才动）。
     * FLYING/FINISHED/CANCELLED 继续引用原版本——历史影像因此引用原航线。
     */
    @Transactional
    public int rebindPendingMissions(Long routeId, Long newVersionId) {
        List<FlightMission> pending = list(new QueryWrapper<FlightMission>()
                .eq("route_id", routeId)
                .eq("status", MissionStatus.PENDING));
        for (FlightMission mission : pending) {
            mission.setRouteVersionId(newVersionId);
            mission.setLastCheckId(null);
            updateById(mission);
        }
        return pending.size();
    }

    public List<FlightMission> listByRoute(Long routeId) {
        return list(new QueryWrapper<FlightMission>()
                .eq("route_id", routeId)
                .orderByAsc("id"));
    }

    public FlightMission mustGet(Long id) {
        FlightMission mission = getById(id);
        if (mission == null) {
            throw new IllegalArgumentException("飞行任务不存在: " + id);
        }
        return mission;
    }
}
