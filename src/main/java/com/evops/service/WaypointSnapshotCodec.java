package com.evops.service;

import com.evops.dto.WaypointRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 航点快照 JSON 编解码。版本以不可变 JSON 快照留存航点配置，
 * 历史影像据此还原飞行时实际航线。
 */
@Component
public class WaypointSnapshotCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    public String toJson(List<WaypointRequest> waypoints) {
        try {
            // 统一按 seq 排序，保证同一配置生成同一快照
            waypoints.sort(Comparator.comparing(WaypointRequest::getSeq));
            return MAPPER.writeValueAsString(waypoints);
        } catch (Exception e) {
            throw new IllegalStateException("航点快照序列化失败", e);
        }
    }

    public List<WaypointRequest> fromJson(String json) {
        try {
            List<WaypointRequest> list = MAPPER.readValue(json, new TypeReference<List<WaypointRequest>>() {
            });
            list.sort(Comparator.comparing(WaypointRequest::getSeq));
            return list;
        } catch (Exception e) {
            throw new IllegalStateException("航点快照反序列化失败", e);
        }
    }

    /** 快照内容哈希，用于快速判别版本配置是否变化 */
    public String sha256(String json) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("快照摘要计算失败", e);
        }
    }
}
