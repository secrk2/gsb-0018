package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.*;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.web.dto.TrackBatchRequest;
import cn.sfj.jiaowutong.web.vo.ActivityCheckInView;
import cn.sfj.jiaowutong.web.vo.LeaveView;
import cn.sfj.jiaowutong.web.vo.MonthlyReportResultView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 请销假两级审批 + 定位联动、公益活动报名/打卡校验、月度报到批量逐条结果的集成测试。
 * 用全新构造的对象隔离种子数据干扰；时间全部用 UTC Instant。
 */
@SpringBootTest
class LeaveActivityIntegrationTest {

    @Autowired private LeaveService leaveService;
    @Autowired private ActivityService activityService;
    @Autowired private MonthlyReportService monthlyService;
    @Autowired private TrackService trackService;
    @Autowired private CorrectionObjectRepository objectRepository;
    @Autowired private JudicialOfficeRepository officeRepository;
    @Autowired private ViolationEventRepository violationRepository;
    @Autowired private LeaveRequestRepository leaveRepository;
    @Autowired private LeaveRequestLogRepository leaveLogRepository;
    @Autowired private PublicActivityRepository activityRepository;
    @Autowired private ActivitySignupRepository signupRepository;
    @Autowired private ObjectService objectService;

    private JudicialOffice qingshan;
    private JudicialOffice chengguan;

    private LoginUser offender(CorrectionObject o) {
        return new LoginUser(9000 + o.getId(), "t" + o.getId(), o.getFullName(),
                Role.OFFENDER, o.getOffice().getId(), o.getId());
    }

    private LoginUser staff(JudicialOffice o, String name) {
        return new LoginUser(9100L + o.getId(), "s" + o.getId(), name,
                Role.STAFF, o.getId(), null);
    }

    private final LoginUser bureau = new LoginUser(1L, "jiandu", "陈督导", Role.SUPERVISOR, null, null);

    private JudicialOffice office(String code) {
        return officeRepository.findAll().stream()
                .filter(o -> o.getCode().equals(code)).findFirst().orElseThrow();
    }

    private CorrectionObject newObject(JudicialOffice office, CorrectionStatus status) {
        CorrectionObject o = new CorrectionObject();
        o.setCorrectionNo("T" + UUID.randomUUID().toString().substring(0, 8));
        o.setFullName("测试对象");
        o.setMaskedName("T-" + o.getCorrectionNo());
        o.setOffice(office);
        o.setStatus(status);
        o.setReportDay("MONDAY");
        o.setStartDate(LocalDate.now().minusMonths(1));
        o.setEndDate(LocalDate.now().plusMonths(6));
        return objectRepository.save(o);
    }

    private Instant inMinutes(long min) {
        return Instant.now().plusSeconds(min * 60);
    }

    private TrackBatchRequest onePoint(double lat, double lng) {
        var p = new TrackBatchRequest.PointDto(UUID.randomUUID().toString(), Instant.now(),
                lat, lng, false, 90, 4, true);
        return new TrackBatchRequest(List.of(p));
    }

    private long openBreachCount(Long offenderId) {
        return violationRepository.findByOffender_IdAndReadFlagFalse(offenderId).stream()
                .filter(v -> "GEOFENCE_BREACH".equals(v.getType())).count();
    }

    // ---------------- 请销假 + 定位联动 ----------------

    @Test
    void twoLevelApprove_suppressesFenceAlertsInLeave_andRestoresAfterOverdue() {
        qingshan = office("JGS-QS");
        CorrectionObject o = newObject(qingshan, CorrectionStatus.SERVING);
        LoginUser me = offender(o);
        LoginUser staffQs = staff(qingshan, "罗建军");

        // 批准前先有一条未处置越界红点（模拟审批期间报警挂着）
        violationRepository.save(new ViolationEvent(o, "GEOFENCE_BREACH", "审批前越界红点", Instant.now()));
        assertEquals(1, openBreachCount(o.getId()));

        // 对象申请：假期 30 分钟前已开始（申请时刻在 1 小时宽限内），120 分钟后结束
        LeaveView applied = leaveService.apply("县医院", "家属手术需赴县医院陪护四天",
                inMinutes(-30), inMinutes(120), me);
        assertEquals("PENDING_OFFICE", applied.status());

        // 区局不能越级初审；司法所不能直接复核
        assertThrows(ApiException.class, () -> leaveService.officeReview(applied.id(), true, null, bureau));
        assertThrows(ApiException.class, () -> leaveService.bureauReview(applied.id(), true, null, staffQs));

        // 司法所初审通过
        LeaveView atBureau = leaveService.officeReview(applied.id(), true, "情况属实", staffQs);
        assertEquals("PENDING_BUREAU", atBureau.status());
        // 重复初审被拦
        assertThrows(ApiException.class, () -> leaveService.officeReview(applied.id(), true, null, staffQs));

        // 区局复核通过 → 档案转请假外出，旧越界红点被联动核销（不能一边批假一边报警）
        LeaveView approved = leaveService.bureauReview(applied.id(), true, "同意", bureau);
        assertEquals("APPROVED", approved.status());
        assertEquals(CorrectionStatus.LEAVE, objectRepository.findById(o.getId()).orElseThrow().getStatus());
        assertEquals(0, openBreachCount(o.getId()), "批假应联动核销未处置越界红点");

        // 假期窗口内越过围栏：点照常入库留痕，但不产生越界红点
        // 青山活动范围外坐标（与种子 breach 点一致）
        trackService.ingest(onePoint(30.3380, 114.4720), me);
        assertEquals(0, openBreachCount(o.getId()), "已批准假期内越界不应报警");

        // 假期届满未销假：自动处置 → OVERDUE、档案训诫、违规红点
        int n = leaveService.processOverdue(inMinutes(200));
        assertEquals(1, n);
        LeaveRequest lr = leaveRepository.findById(applied.id()).orElseThrow();
        assertEquals(LeaveStatus.OVERDUE, lr.getStatus());
        assertNotNull(lr.getOverdueAt());
        assertEquals(CorrectionStatus.ADMONISHED, objectRepository.findById(o.getId()).orElseThrow().getStatus());
        assertTrue(violationRepository.findByOffender_IdAndReadFlagFalse(o.getId()).stream()
                .anyMatch(v -> "LEAVE_OVERDUE".equals(v.getType())));

        // 幂等：再次扫描不重复处理
        assertEquals(0, leaveService.processOverdue(inMinutes(300)));

        // 逾期（假期结束）后越界报警恢复
        trackService.ingest(onePoint(30.3380, 114.4720), me);
        assertEquals(1, openBreachCount(o.getId()), "逾假后越界报警应恢复");
    }

    @Test
    void returnAtEachLevel_resubmitKeepsRevisionAndTrail_thenApproveAndReturn() {
        qingshan = office("JGS-QS");
        CorrectionObject o = newObject(qingshan, CorrectionStatus.SERVING);
        LoginUser me = offender(o);
        LoginUser staffQs = staff(qingshan, "罗建军");

        LeaveView v1 = leaveService.apply("邻县", "初版事由想去邻县办点事情", inMinutes(60), inMinutes(180), me);

        // 司法所退回必须填意见
        assertThrows(ApiException.class, () -> leaveService.officeReview(v1.id(), false, "短", staffQs));
        leaveService.officeReview(v1.id(), false, "事由不充分请补材料", staffQs);
        assertEquals("OFFICE_RETURNED", leaveRepository.findById(v1.id()).orElseThrow().getStatus().name());

        // 非退回状态不可重提；退回后可改内容重提，revision+1
        LeaveView v2 = leaveService.resubmit(v1.id(), "邻县医院", "补亲属关系证明赴邻县陪护",
                inMinutes(70), inMinutes(200), me);
        assertEquals(2, v2.revision());
        assertEquals("PENDING_OFFICE", v2.status());

        leaveService.officeReview(v1.id(), true, "材料齐", staffQs);
        // 区局再退回
        leaveService.bureauReview(v1.id(), false, "时间与教育学习冲突请改期", bureau);
        assertEquals("BUREAU_RETURNED", leaveRepository.findById(v1.id()).orElseThrow().getStatus().name());

        // 第三次提交：两级重新走
        LeaveView v3 = leaveService.resubmit(v1.id(), "邻县医院", "改至下周赴邻县陪护复查",
                inMinutes(60 * 24 * 8), inMinutes(60 * 24 * 9), me);
        assertEquals(3, v3.revision());
        leaveService.officeReview(v1.id(), true, null, staffQs);
        leaveService.bureauReview(v1.id(), true, null, bureau);
        assertEquals("APPROVED", leaveRepository.findById(v1.id()).orElseThrow().getStatus().name());
        assertEquals(CorrectionStatus.LEAVE, objectRepository.findById(o.getId()).orElseThrow().getStatus());

        // 留痕动作顺序完整（提交/所退/重提/所过/局退/重提/所过/局过 至少 8 条）
        var logs = leaveLogRepository.findByLeaveIdOrderByIdAsc(v1.id());
        assertTrue(logs.size() >= 8);
        assertEquals("SUBMIT", logs.get(0).getAction());
        assertTrue(logs.stream().anyMatch(l -> "RESUBMIT".equals(l.getAction()) && l.getRevision() == 2));
        assertTrue(logs.stream().anyMatch(l -> "RESUBMIT".equals(l.getAction()) && l.getRevision() == 3));

        // 销假 → 回到在矫，单据 COMPLETED
        LeaveView done = leaveService.returnFromLeave(v1.id(), "已按期返所", me);
        assertEquals("COMPLETED", done.status());
        assertEquals(CorrectionStatus.SERVING, objectRepository.findById(o.getId()).orElseThrow().getStatus());
    }

    @Test
    void cannotEnterLeaveViaGenericTransition() {
        qingshan = office("JGS-QS");
        CorrectionObject o = newObject(qingshan, CorrectionStatus.SERVING);
        LoginUser staffQs = staff(qingshan, "罗建军");
        // 通用状态流转不得绕过两级审批直接进入请假外出
        ApiException ex = assertThrows(ApiException.class,
                () -> objectService.transition(o.getId(), CorrectionStatus.LEAVE, "手工请假", staffQs));
        assertEquals("LEAVE_FLOW_REQUIRED", ex.getCode());
        assertEquals(CorrectionStatus.SERVING, objectRepository.findById(o.getId()).orElseThrow().getStatus());
    }

    // ---------------- 公益活动 ----------------

    @Test
    void signup_isScopedAndIdempotent_checkInValidatesRangeAndIsImmutable() {
        qingshan = office("JGS-QS");
        chengguan = office("JGS-CG");
        LoginUser staffQs = staff(qingshan, "罗建军");

        CorrectionObject qs1 = newObject(qingshan, CorrectionStatus.SERVING);
        CorrectionObject qs2 = newObject(qingshan, CorrectionStatus.SERVING);
        CorrectionObject qs3 = newObject(qingshan, CorrectionStatus.SERVING);
        CorrectionObject cg1 = newObject(chengguan, CorrectionStatus.SERVING);

        Instant now = Instant.now();

        // 活动一：未开始（报名通道开放），测报名范围隔离与幂等
        var futureView = activityService.publish("测试法治宣传", "desc", null,
                "青山乡文化广场", qingshan.getCenterLat(), qingshan.getCenterLng(), 200,
                now.plusSeconds(7200), now.plusSeconds(7200 + 3 * 3600), now.plusSeconds(3600), 10, staffQs);
        Long futureAid = futureView.id();
        assertThrows(ApiException.class, () -> activityService.signup(futureAid, null, offender(cg1)));
        activityService.signup(futureAid, null, offender(qs1));
        activityService.signup(futureAid, null, offender(qs1));
        activityService.signup(futureAid, "参加", offender(qs2));
        assertEquals(2, signupRepository.findByActivity_IdOrderBySignedAtAscIdAsc(futureAid).size());

        // 活动二：进行中（开始 10 分钟前开放报名，现已开始），直接造报名以测现场打卡
        var view = activityService.publish("测试敬老院慰问", "desc", null,
                "青山乡敬老院", qingshan.getCenterLat(), qingshan.getCenterLng(), 200,
                now.minusSeconds(600), now.plusSeconds(7200), now.minusSeconds(900), 10, staffQs);
        Long aid = view.id();
        signupRepository.save(new ActivitySignup(activityRepository.findById(aid).orElseThrow(), qs1, null));
        signupRepository.save(new ActivitySignup(activityRepository.findById(aid).orElseThrow(), qs2, null));
        signupRepository.save(new ActivitySignup(activityRepository.findById(aid).orElseThrow(), qs3, null));

        // 未报名不能打卡
        ApiException notSigned = assertThrows(ApiException.class,
                () -> activityService.checkIn(aid, Instant.now(),
                        qingshan.getCenterLat(), qingshan.getCenterLng(), offender(cg1)));
        assertEquals("NOT_SIGNED_UP", notSigned.getCode());

        // qs1 在半径内（偏移约 50 米）→ 正常
        double d = 0.00045;
        ActivityCheckInView ok = activityService.checkIn(aid, Instant.now(),
                qingshan.getCenterLat() + d, qingshan.getCenterLng(), offender(qs1));
        assertTrue(ok.insideRange());
        assertEquals("NORMAL", ok.result());

        // qs2 在半径外（偏移约 1.1km）→ 异常留痕
        ActivityCheckInView bad = activityService.checkIn(aid, Instant.now(),
                qingshan.getCenterLat() + 0.010, qingshan.getCenterLng(), offender(qs2));
        assertFalse(bad.insideRange());
        assertEquals("ABNORMAL", bad.result());
        assertTrue(bad.distanceMeters() > 200);

        // 异常后跑到现场重打：返回首次异常记录，不能洗白
        ActivityCheckInView retry = activityService.checkIn(aid, Instant.now(),
                qingshan.getCenterLat(), qingshan.getCenterLng(), offender(qs2));
        assertTrue(retry.alreadyChecked());
        assertEquals("ABNORMAL", retry.result());

        // qs3 已报名（见上），用 10 分钟前旧定位打卡 → 拒绝
        ApiException stale = assertThrows(ApiException.class,
                () -> activityService.checkIn(aid, Instant.now().minusSeconds(10 * 60),
                        qingshan.getCenterLat(), qingshan.getCenterLng(), offender(qs3)));
        assertEquals("STALE_LOCATION", stale.getCode());
    }

    private long signupCount(Long aid) {
        return signupRepository.findByActivity_IdOrderBySignedAtAscIdAsc(aid).size();
    }

    // ---------------- 月度报到批量 ----------------

    @Test
    void monthlyBatch_reportsPerItemSuccessSkipFailure() {
        qingshan = office("JGS-QS");
        chengguan = office("JGS-CG");
        LoginUser staffQs = staff(qingshan, "罗建军");

        CorrectionObject a = newObject(qingshan, CorrectionStatus.SERVING);
        CorrectionObject b = newObject(qingshan, CorrectionStatus.SERVING);
        CorrectionObject jailed = newObject(qingshan, CorrectionStatus.REIMPRISONED);
        CorrectionObject otherOffice = newObject(chengguan, CorrectionStatus.SERVING);

        YearMonth month = YearMonth.now(java.time.ZoneId.of("Asia/Shanghai"));

        // 第一次批量：a、b 成功；收监对象失败；跨所对象失败
        MonthlyReportResultView r1 = monthlyService.batchComplete(month,
                List.of(a.getId(), b.getId(), jailed.getId(), otherOffice.getId()), "集中点验", staffQs);
        assertEquals(4, r1.requested());
        assertEquals(2, r1.successCount());
        assertEquals(0, r1.skippedCount());
        assertEquals(2, r1.failedCount());
        assertOutcome(r1, a.getId(), "SUCCESS");
        assertOutcome(r1, jailed.getId(), "FAILED");
        assertOutcome(r1, otherOffice.getId(), "FAILED");
        // 逐条失败原因非空，说明卡在哪
        assertTrue(r1.results().stream().filter(x -> x.outcome().equals("FAILED"))
                .allMatch(x -> x.message() != null && !x.message().isBlank()));

        // 第二次批量：a 已完成→跳过，b 已完成→跳过；本批无新对象
        MonthlyReportResultView r2 = monthlyService.batchComplete(month,
                List.of(a.getId(), b.getId()), null, staffQs);
        assertEquals(2, r2.requested());
        assertEquals(0, r2.successCount());
        assertEquals(2, r2.skippedCount());
        assertOutcome(r2, a.getId(), "SKIPPED");

        // 空批次拒绝
        assertThrows(ApiException.class,
                () -> monthlyService.batchComplete(month, List.of(), null, staffQs));
    }

    private void assertOutcome(MonthlyReportResultView r, Long offenderId, String outcome) {
        MonthlyReportResultView.Item item = r.results().stream()
                .filter(x -> x.offenderId().equals(offenderId)).findFirst().orElseThrow();
        assertEquals(outcome, item.outcome(),
                "对象 " + offenderId + " 应为 " + outcome + "，实际：" + item.message());
    }
}
