package cn.sfj.jiaowutong.web.vo;

import java.time.Instant;

/** 月度报到花名册单行：对象及其某月月度报到完成情况。 */
public record MonthlyReportItemView(
        Long offenderId, String correctionNo, String maskedName,
        Long officeId, String officeName, String officeTimezone,
        String status, String statusLabel, boolean active,
        boolean completed, Instant completedAt, String operatorName, String note) {
}
