package cn.sfj.jiaowutong.web;

import cn.sfj.jiaowutong.common.ApiResult;
import cn.sfj.jiaowutong.security.CurrentUserHolder;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.service.MonthlyReportService;
import cn.sfj.jiaowutong.web.dto.MonthlyBatchRequest;
import cn.sfj.jiaowutong.web.vo.MonthlyReportItemView;
import cn.sfj.jiaowutong.web.vo.MonthlyReportResultView;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.List;

/**
 * 月度报到（管理端）：
 * - GET  /monthly-reports?month=2026-09            月度花名册（含完成状态）
 * - POST /monthly-reports/batch-complete           一次勾选多个对象批量完成，逐条返回结果
 */
@RestController
@RequestMapping("/api/monthly-reports")
public class MonthlyReportController {

    private final MonthlyReportService service;

    public MonthlyReportController(MonthlyReportService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResult<List<MonthlyReportItemView>> roster(
            @RequestParam(required = false) String month) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(service.roster(parseMonth(month), user));
    }

    @PostMapping("/batch-complete")
    public ApiResult<MonthlyReportResultView> batchComplete(@Valid @RequestBody MonthlyBatchRequest request) {
        LoginUser user = CurrentUserHolder.require();
        return ApiResult.success(service.batchComplete(
                request.month(), request.offenderIds(), request.note(), user));
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"));
        }
        try {
            return YearMonth.parse(month);
        } catch (Exception e) {
            throw cn.sfj.jiaowutong.common.ApiException.badRequest("INVALID_MONTH",
                    "月份格式应为 yyyy-MM，如 2026-09");
        }
    }
}
