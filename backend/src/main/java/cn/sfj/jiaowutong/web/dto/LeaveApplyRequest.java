package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 对象请假申请 / 退回重提请求体。时间为 UTC ISO（前端按对象时区选择日期时间后换算）。
 */
public record LeaveApplyRequest(
        @NotNull @Size(min = 2, max = 128) String destination,
        @NotNull @Size(min = 4, max = 256) String reason,
        @NotNull Instant startAt,
        @NotNull Instant endAt) {
}
