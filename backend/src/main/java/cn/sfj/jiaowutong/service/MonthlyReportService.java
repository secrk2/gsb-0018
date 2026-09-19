package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.domain.CorrectionObject;
import cn.sfj.jiaowutong.domain.CorrectionStatus;
import cn.sfj.jiaowutong.domain.MonthlyReport;
import cn.sfj.jiaowutong.domain.Role;
import cn.sfj.jiaowutong.repo.CorrectionObjectRepository;
import cn.sfj.jiaowutong.repo.MonthlyReportRepository;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.web.vo.MonthlyReportItemView;
import cn.sfj.jiaowutong.web.vo.MonthlyReportResultView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 月度报到（干警当面/批量登记）。
 * 支持一次勾选多个对象批量完成：服务端对每个对象独立判定，逐条返回“成功/跳过/失败 + 原因”，
 * 任一对象失败（越权、终态、已完成）不影响其他对象入库——每条登记在独立事务中提交，不做整批回滚。
 * 月份按对象所属司法所时区判定。
 */
@Service
public class MonthlyReportService {

    private final MonthlyReportRepository reportRepository;
    private final CorrectionObjectRepository objectRepository;
    private final AccessControlService accessControl;
    private final TransactionTemplate txNew;

    public MonthlyReportService(MonthlyReportRepository reportRepository,
                                CorrectionObjectRepository objectRepository,
                                AccessControlService accessControl,
                                PlatformTransactionManager txManager) {
        this.reportRepository = reportRepository;
        this.objectRepository = objectRepository;
        this.accessControl = accessControl;
        this.txNew = new TransactionTemplate(txManager);
        this.txNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 月度花名册：监管范围内在册（非收监/解除）对象及其当月月度报到状态。 */
    @Transactional(readOnly = true)
    public List<MonthlyReportItemView> roster(YearMonth month, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        LocalDate monthFirst = month.atDay(1);
        List<CorrectionObject> scoped = accessControl.filterByScope(objectRepository.findAll(), user);
        List<MonthlyReportItemView> items = new ArrayList<>();
        for (CorrectionObject o : scoped) {
            boolean active = o.getStatus() != CorrectionStatus.REIMPRISONED
                    && o.getStatus() != CorrectionStatus.RELEASED;
            MonthlyReport done = reportRepository
                    .findByOffender_IdAndReportMonth(o.getId(), monthFirst).orElse(null);
            items.add(new MonthlyReportItemView(
                    o.getId(), o.getCorrectionNo(), o.getMaskedName(),
                    o.getOffice().getId(), o.getOffice().getName(), o.getOffice().getTimezone(),
                    o.getStatus().name(), o.getStatus().getLabel(), active,
                    done != null, done == null ? null : done.getCompletedAt(),
                    done == null ? null : done.getOperatorName(),
                    done == null ? null : (done.getNote() == null ? "" : done.getNote())));
        }
        items.sort(java.util.Comparator.comparing(MonthlyReportItemView::correctionNo));
        return items;
    }

    /**
     * 批量完成月度报到。逐个对象独立校验，逐条在独立事务中落库并记录结果，不整批回滚。
     * 外层只读事务用于加载对象与读取状态，逐条写入使用 REQUIRES_NEW 独立提交。
     */
    @Transactional(readOnly = true)
    public MonthlyReportResultView batchComplete(YearMonth month, List<Long> offenderIds,
                                                 String note, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        if (offenderIds == null || offenderIds.isEmpty()) {
            throw ApiException.badRequest("EMPTY_BATCH", "请至少勾选一名对象再提交");
        }
        if (offenderIds.size() > 500) {
            throw ApiException.badRequest("BATCH_TOO_LARGE", "单次批量登记不能超过 500 名对象");
        }
        LocalDate monthFirst = month.atDay(1);
        LocalDate monthLast = month.atEndOfMonth();

        int success = 0;
        int skipped = 0;
        int failed = 0;
        List<MonthlyReportResultView.Item> results = new ArrayList<>();
        // 同一次请求内去重，避免同对象在同一批里出现两次
        List<Long> dedupIds = offenderIds.stream().distinct().toList();
        boolean single = dedupIds.size() == 1;

        for (Long oid : dedupIds) {
            CorrectionObject o = objectRepository.findById(oid).orElse(null);
            if (o == null) {
                failed++;
                results.add(item(oid, null, null, "FAILED", "档案不存在，可能已归档"));
                continue;
            }
            final String no = o.getCorrectionNo();
            final String name = o.getMaskedName();
            // 数据范围强校验：干警只能登记本所对象，越权逐条标失败而非整批拒绝
            if (user.role() == Role.STAFF
                    && (user.officeId() == null || !user.officeId().equals(o.getOffice().getId()))) {
                failed++;
                results.add(item(oid, no, name, "FAILED",
                        "越权：该对象归属「" + o.getOffice().getName() + "」，不在您所在司法所管辖范围"));
                continue;
            }
            // 不允许给未到来的月份登记（月份按对象所属司法所时区判定）；历史月份允许补登
            LocalDate todayLocal = java.time.Instant.now()
                    .atZone(FenceService.safeZone(o.getOffice().getTimezone())).toLocalDate();
            if (monthFirst.isAfter(todayLocal.withDayOfMonth(1))) {
                failed++;
                results.add(item(oid, no, name, "FAILED",
                        "不能为尚未到来的月份（" + month + "，按 " + o.getOffice().getTimezone() + "）登记月度报到"));
                continue;
            }
            if (o.getStatus() == CorrectionStatus.REIMPRISONED || o.getStatus() == CorrectionStatus.RELEASED) {
                failed++;
                results.add(item(oid, no, name, "FAILED",
                        "对象当前为「" + o.getStatus().getLabel() + "」终态，不办理月度报到"));
                continue;
            }
            if (reportRepository.existsByOffender_IdAndReportMonth(oid, monthFirst)) {
                skipped++;
                results.add(item(oid, no, name, "SKIPPED",
                        month + " 月度报到已完成，跳过以免重复登记"));
                continue;
            }
            // 独立事务落库：并发唯一约束冲突等只回滚该条，不拖垮整批
            try {
                txNew.executeWithoutResult(status -> {
                    // 内层新事务重新加载实体，避免跨事务使用外层（已挂起）会话的懒加载代理
                    CorrectionObject fresh = objectRepository.findById(oid).orElseThrow();
                    MonthlyReport mr = new MonthlyReport(fresh, monthFirst, user.userId(), user.realName(),
                            single ? "SINGLE" : "BATCH", note);
                    reportRepository.save(mr);
                });
                success++;
                results.add(item(oid, no, name, "SUCCESS", month + " 月度报到登记成功"));
            } catch (Exception e) {
                failed++;
                results.add(item(oid, no, name, "FAILED",
                        "登记失败：" + rootMessage(e)));
            }
        }

        return new MonthlyReportResultView(monthFirst.toString(), monthLast.toString(),
                dedupIds.size(), success, skipped, failed, results);
    }

    private String rootMessage(Throwable e) {
        Throwable c = e;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        String m = c.getMessage();
        return m == null ? c.getClass().getSimpleName() : m;
    }

    private MonthlyReportResultView.Item item(Long offenderId, String correctionNo, String maskedName,
                                              String outcome, String message) {
        return new MonthlyReportResultView.Item(offenderId, correctionNo, maskedName, outcome, message);
    }
}
