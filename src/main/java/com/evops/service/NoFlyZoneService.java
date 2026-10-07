package com.evops.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.evops.dto.NoFlyZoneRequest;
import com.evops.entity.NoFlyZone;
import com.evops.enums.NoFlyZoneStatus;
import com.evops.enums.NoFlyZoneType;
import com.evops.mapper.NoFlyZoneMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 临时禁飞区档案。生效窗口与叶片归属窗口同一口径：[startTime, endTime) 左闭右开。
 */
@Service
public class NoFlyZoneService extends ServiceImpl<NoFlyZoneMapper, NoFlyZone> {

    /** 建档（编号唯一） */
    public NoFlyZone create(NoFlyZoneRequest req) {
        Long dup = count(new QueryWrapper<NoFlyZone>().eq("zone_no", req.getZoneNo()));
        if (dup > 0) {
            throw new IllegalArgumentException("禁飞区编号已存在: " + req.getZoneNo());
        }
        if (req.getEndTime() != null && !req.getEndTime().isAfter(req.getStartTime())) {
            throw new IllegalArgumentException("禁飞区失效时刻必须晚于生效时刻");
        }

        NoFlyZone zone = new NoFlyZone();
        zone.setZoneNo(req.getZoneNo());
        zone.setZoneName(req.getZoneName());
        zone.setZoneType(req.getZoneType() != null ? req.getZoneType() : NoFlyZoneType.TEMPORARY);
        zone.setLongitude(req.getLongitude());
        zone.setLatitude(req.getLatitude());
        zone.setRadiusM(req.getRadiusM());
        zone.setStartTime(req.getStartTime());
        zone.setEndTime(req.getEndTime());
        zone.setReason(req.getReason());
        zone.setStatus(NoFlyZoneStatus.ACTIVE);
        save(zone);
        return zone;
    }

    /** 撤销禁飞区：保留档案可追溯，仅置为 REVOKED */
    public NoFlyZone revoke(Long id) {
        NoFlyZone zone = mustGet(id);
        zone.setStatus(NoFlyZoneStatus.REVOKED);
        updateById(zone);
        return zone;
    }

    /**
     * 某时刻仍生效的禁飞区：状态 ACTIVE 且 startTime <= t < endTime（左闭右开）。
     * end_time 为空表示撤销前长期有效。
     */
    public List<NoFlyZone> listEffectiveAt(LocalDateTime t) {
        return list(new QueryWrapper<NoFlyZone>()
                .eq("status", NoFlyZoneStatus.ACTIVE)
                .le("start_time", t)
                .and(w -> w.isNull("end_time").or().gt("end_time", t))
                .orderByAsc("id"));
    }

    public NoFlyZone mustGet(Long id) {
        NoFlyZone zone = getById(id);
        if (zone == null) {
            throw new IllegalArgumentException("禁飞区不存在: " + id);
        }
        return zone;
    }
}
