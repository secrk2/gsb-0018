package cn.sfj.jiaowutong.web.vo;

import java.time.Instant;

/**
 * 请销假单视图。时间均为 UTC ISO，前端按 officeTimezone 换算展示。
 */
public record LeaveView(
        Long id, Long offenderId, String correctionNo, String maskedName,
        Long officeId, String officeName, String officeTimezone,
        String destination, String reason,
        Instant startAt, Instant endAt,
        String status, String statusLabel, Integer revision,
        Instant submittedAt, Instant officeApprovedAt, Instant bureauApprovedAt,
        Instant returnedAt, String returnNote, Instant overdueAt, Instant createdAt) {
}
