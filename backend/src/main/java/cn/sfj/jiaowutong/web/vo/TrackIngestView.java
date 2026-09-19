package cn.sfj.jiaowutong.web.vo;

import java.time.Instant;
import java.util.List;

/**
 * 轨迹批量上报结果：
 * - accepted：实际入库的新有效点（参与轨迹）
 * - duplicates：幂等拦截的重复点（同一 clientPointId 重放）
 * - driftDiscarded：超合理速度跳变、判为 GPS 漂移丢弃的点（留痕但不连线、不更新位置、不报警）
 * - rejected：时效等硬校验拒绝的点及原因
 * - outsideFence / forbidden：本批越界 / 禁区内点数
 */
public record TrackIngestView(int total, int accepted, int duplicates, int driftDiscarded, int rejected,
                              List<RejectedPoint> rejectedPoints, int outsideFence, int forbidden,
                              Instant latestLocationAt, Double latestLat, Double latestLng,
                              boolean latestInsideFence, boolean latestForbidden,
                              Integer battery, Integer signal, Boolean worn,
                              boolean newViolationGenerated) {

    public record RejectedPoint(String clientPointId, String reason) {
    }
}
