package com.evops.enums;

/** 版本差异类型：新增航点 / 删除航点 / 修改航点参数 / 阈值修改（风机、阈值等非航点字段） */
public enum DiffChangeKind {
    WAYPOINT_ADDED, WAYPOINT_REMOVED, WAYPOINT_CHANGED, THRESHOLD_CHANGED
}
