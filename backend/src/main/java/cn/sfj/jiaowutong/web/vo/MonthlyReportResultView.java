package cn.sfj.jiaowutong.web.vo;

import java.util.List;

/**
 * 月度报到批量处理结果。
 * results 逐对象给出 outcome：SUCCESS 成功 / SKIPPED 跳过（已完成等）/ FAILED 失败，
 * 并在 message 中逐条说清“谁成了、谁卡在哪、为什么”。
 */
public record MonthlyReportResultView(
        String monthFrom, String monthTo, int requested,
        int successCount, int skippedCount, int failedCount,
        List<Item> results) {

    public record Item(Long offenderId, String correctionNo, String maskedName,
                       String outcome, String message) {
    }
}
