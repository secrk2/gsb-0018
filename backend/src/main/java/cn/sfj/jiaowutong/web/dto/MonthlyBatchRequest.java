package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.YearMonth;
import java.util.List;

/**
 * 月度报到批量完成请求。month 形如 "2026-09"（yyyy-MM）；offenderIds 为本次勾选的对象 id 列表。
 */
public record MonthlyBatchRequest(
        @NotNull YearMonth month,
        @NotNull List<Long> offenderIds,
        @Size(max = 256) String note) {
}
