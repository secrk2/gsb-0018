package cn.sfj.jiaowutong.config;

import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.*;
import cn.sfj.jiaowutong.security.PasswordEncoder;
import cn.sfj.jiaowutong.service.PinyinUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 演示种子数据：
 * - 4 个司法所（含跨时区的新疆伊宁所，UTC+6，验证“按对象时区显/判”）；
 * - ALLOW_RANGE 多边形活动范围 + FORBIDDEN 禁区（全天禁行 / 跨午夜 22:00-05:00 / 每晚 20:00-06:00）；
 * - 腕表 5 秒粒度近期轨迹：在线/越界/禁区/离线/未佩戴/低电/漂移丢弃点；
 * - 周/月稀疏历史轨迹；从无轨迹、轨迹已清除两种空态；
 * - 完成度双口径相反的对象（只在非报到日打卡 vs 只踩点报到）。
 * 仅在空库时执行（H2 文件卷重启后不重复播种）。
 */
@Component
@Order(0)
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");

    private final JudicialOfficeRepository officeRepository;
    private final CorrectionObjectRepository objectRepository;
    private final UserAccountRepository userRepository;
    private final TrackPointRepository trackPointRepository;
    private final CheckInRepository checkInRepository;
    private final ViolationEventRepository violationRepository;
    private final StatusTransitionRepository transitionRepository;
    private final GeoFenceRepository fenceRepository;
    private final FenceScheduleRepository scheduleRepository;
    private final MonitorActionRepository monitorActionRepository;
    private final LeaveRequestRepository leaveRepository;
    private final LeaveRequestLogRepository leaveLogRepository;
    private final PublicActivityRepository activityRepository;
    private final ActivitySignupRepository signupRepository;
    private final ActivityCheckInRepository activityCheckInRepository;
    private final MonthlyReportRepository monthlyReportRepository;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;

    public DataInitializer(JudicialOfficeRepository officeRepository,
                           CorrectionObjectRepository objectRepository,
                           UserAccountRepository userRepository,
                           TrackPointRepository trackPointRepository,
                           CheckInRepository checkInRepository,
                           ViolationEventRepository violationRepository,
                           StatusTransitionRepository transitionRepository,
                           GeoFenceRepository fenceRepository,
                           FenceScheduleRepository scheduleRepository,
                           MonitorActionRepository monitorActionRepository,
                           LeaveRequestRepository leaveRepository,
                           LeaveRequestLogRepository leaveLogRepository,
                           PublicActivityRepository activityRepository,
                           ActivitySignupRepository signupRepository,
                           ActivityCheckInRepository activityCheckInRepository,
                           MonthlyReportRepository monthlyReportRepository,
                           PasswordEncoder passwordEncoder,
                           ObjectMapper objectMapper) {
        this.officeRepository = officeRepository;
        this.objectRepository = objectRepository;
        this.userRepository = userRepository;
        this.trackPointRepository = trackPointRepository;
        this.checkInRepository = checkInRepository;
        this.violationRepository = violationRepository;
        this.transitionRepository = transitionRepository;
        this.fenceRepository = fenceRepository;
        this.scheduleRepository = scheduleRepository;
        this.monitorActionRepository = monitorActionRepository;
        this.leaveRepository = leaveRepository;
        this.leaveLogRepository = leaveLogRepository;
        this.activityRepository = activityRepository;
        this.signupRepository = signupRepository;
        this.activityCheckInRepository = activityCheckInRepository;
        this.monthlyReportRepository = monthlyReportRepository;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("检测到已有数据，跳过种子初始化");
            return;
        }

        LocalDate today = LocalDate.now(SH);
        String todayWeek = today.getDayOfWeek().toString();
        Instant now = Instant.now();

        // ---------- 司法所（跨时区：伊宁所 UTC+6，验证按对象时区判/显） ----------
        JudicialOffice chengguan = officeRepository.save(new JudicialOffice(
                "JGS-CG", "城关司法所", "城关街道", "Asia/Shanghai", 30.21230, 114.32456, 1000));
        JudicialOffice qingshan = officeRepository.save(new JudicialOffice(
                "JGS-QS", "青山司法所", "青山乡（丘陵山区）", "Asia/Shanghai", 30.35810, 114.47290, 1000));
        JudicialOffice longhu = officeRepository.save(new JudicialOffice(
                "JGS-LH", "龙湖司法所", "龙湖镇", "Asia/Shanghai", 30.10540, 114.21870, 1000));
        JudicialOffice yining = officeRepository.save(new JudicialOffice(
                "JGS-YN", "伊宁司法所", "伊犁州伊宁市（跨时区协作点）", "Asia/Urumqi", 43.9075, 81.3250, 1500));

        // ---------- 多边形围栏 ----------
        GeoFence cgAllow = allowFence(chengguan, "城关规定活动范围",
                quad(30.2240, 114.3090, 30.2245, 114.3420, 30.2000, 114.3430, 30.1995, 114.3100));
        GeoFence cgForbid = forbidFence(chengguan, "城关火车站广场",
                quad(30.2280, 114.3460, 30.2285, 114.3600, 30.2180, 114.3605, 30.2175, 114.3465));
        scheduleRepository.save(new FenceSchedule(cgForbid, "FRIDAY,SATURDAY",
                LocalTime.of(22, 0), LocalTime.of(5, 0), "周末夜间禁行（周五/周六 22:00-次日 05:00）"));

        GeoFence qsAllow = allowFence(qingshan, "青山乡规定活动范围",
                quad(30.3700, 114.4580, 30.3710, 114.4880, 30.3460, 114.4890, 30.3450, 114.4590));
        GeoFence qsTailings = forbidFence(qingshan, "青山尾矿库（全天禁入）",
                quad(30.3620, 114.4950, 30.3640, 114.5050, 30.3520, 114.5060, 30.3510, 114.4960));
        GeoFence qsForest = forbidFence(qingshan, "青山国有林区",
                quad(30.3800, 114.4700, 30.3830, 114.4900, 30.3740, 114.4920, 30.3720, 114.4720));
        scheduleRepository.save(new FenceSchedule(qsForest, null,
                LocalTime.of(20, 0), LocalTime.of(6, 0), "林区夜间禁行（每日 20:00-次日 06:00）"));

        GeoFence lhAllow = allowFence(longhu, "龙湖镇规定活动范围",
                quad(30.1170, 114.2040, 30.1180, 114.2340, 30.0940, 114.2350, 30.0930, 114.2050));
        GeoFence lhDock = forbidFence(longhu, "龙湖废弃码头（全天禁入）",
                quad(30.1080, 114.2200, 30.1085, 114.2240, 30.1040, 114.2245, 30.1035, 114.2205));

        GeoFence ynAllow = allowFence(yining, "伊宁规定活动范围",
                quad(43.9200, 81.3050, 43.9210, 81.3450, 43.8950, 81.3460, 43.8940, 81.3060));

        // ---------- 对象 ----------
        List<SeedObj> seeds = new ArrayList<>();
        seeds.add(new SeedObj("JWT26001", "张伟国", chengguan, CorrectionStatus.SERVING,
                todayWeek, "危险驾驶罪", today.minusMonths(8), today.plusMonths(4)));
        seeds.add(new SeedObj("JWT26002", "王秀兰", chengguan, CorrectionStatus.LEAVE,
                "FRIDAY", "交通肇事罪", today.minusMonths(5), today.plusMonths(7)));
        seeds.add(new SeedObj("JWT26003", "李志强", chengguan, CorrectionStatus.ADMONISHED,
                "TUESDAY", "故意伤害罪", today.minusMonths(10), today.plusMonths(2)));
        seeds.add(new SeedObj("JWT26004", "赵敏", chengguan, CorrectionStatus.RELEASED,
                "MONDAY", "盗窃罪", today.minusYears(1), today.minusDays(20)));

        seeds.add(new SeedObj("JWT26005", "陈大山", qingshan, CorrectionStatus.SERVING,
                "WEDNESDAY", "滥伐林木罪", today.minusMonths(3), today.plusMonths(9)));
        seeds.add(new SeedObj("JWT26006", "杨春生", qingshan, CorrectionStatus.SERVING,
                todayWeek, "非法捕捞水产品罪", today.minusMonths(6), today.plusMonths(6)));
        seeds.add(new SeedObj("JWT26007", "刘德海", qingshan, CorrectionStatus.INTAKE,
                "THURSDAY", "过失致人重伤罪", today.minusDays(3), today.plusMonths(11)));
        seeds.add(new SeedObj("JWT26008", "黄国庆", qingshan, CorrectionStatus.REIMPRISONED,
                "MONDAY", "寻衅滋事罪", today.minusMonths(9), today.plusMonths(3)));

        seeds.add(new SeedObj("JWT26009", "周文斌", longhu, CorrectionStatus.SERVING,
                "MONDAY", "开设赌场罪", today.minusMonths(4), today.plusMonths(8)));
        seeds.add(new SeedObj("JWT26010", "吴桂芳", longhu, CorrectionStatus.LEAVE,
                todayWeek, "信用卡诈骗罪", today.minusMonths(7), today.plusMonths(5)));
        seeds.add(new SeedObj("JWT26011", "徐建华", longhu, CorrectionStatus.ADMONISHED,
                "SATURDAY", "妨害公务罪", today.minusMonths(2), today.plusMonths(10)));
        seeds.add(new SeedObj("JWT26012", "孙满堂", longhu, CorrectionStatus.SERVING,
                "SUNDAY", "污染环境罪", today.minusMonths(1), today.plusMonths(11)));

        // 跨时区在矫对象
        seeds.add(new SeedObj("JWT26013", "买买提·阿卜拉", yining, CorrectionStatus.SERVING,
                "TUESDAY", "危险驾驶罪", today.minusMonths(2), today.plusMonths(10)));

        List<CorrectionObject> objs = new ArrayList<>();
        for (SeedObj s : seeds) {
            CorrectionObject o = new CorrectionObject();
            o.setCorrectionNo(s.no());
            o.setFullName(s.fullName());
            o.setMaskedName(PinyinUtil.surnameInitial(s.fullName()) + "-" + s.no());
            o.setOffice(s.office());
            o.setStatus(s.status());
            o.setReportDay(s.reportDay());
            o.setCharge(s.charge());
            o.setStartDate(s.start());
            o.setEndDate(s.end());
            o.setPhone("138" + String.format("%08d", Math.floorMod(
                    Integer.parseInt(s.no().substring(6)) * 137, 100000000)));
            o.setIdCardTail("****" + String.format("%04X", Math.floorMod(s.no().hashCode(), 0x10000)));
            objs.add(objectRepository.save(o));
            emitPath(o, s.status());
        }

        CorrectionObject zhang = objs.get(0);
        CorrectionObject chen = objs.get(4);
        CorrectionObject yang = objs.get(5);
        CorrectionObject zhou = objs.get(8);
        CorrectionObject wu = objs.get(9);
        CorrectionObject xu = objs.get(10);
        CorrectionObject sun = objs.get(11);
        CorrectionObject mait = objs.get(12);

        // ---------- 30 天稀疏历史轨迹（周/月视图有线可看） ----------
        emitHistory(chen, qingshan.getCenterLat(), qingshan.getCenterLng(), "seed-chen-hist", 0.0020);
        emitHistory(yang, qingshan.getCenterLat(), qingshan.getCenterLng(), "seed-yang-hist", 0.0020);
        emitHistory(zhang, chengguan.getCenterLat(), chengguan.getCenterLng(), "seed-zhang-hist", 0.0020);
        // 龙湖历史点振幅收窄，避免摆进废弃码头禁区多边形
        emitHistory(zhou, longhu.getCenterLat(), longhu.getCenterLng(), "seed-zhou-hist", 0.0012);
        emitHistory(mait, yining.getCenterLat(), yining.getCenterLng(), "seed-mait-hist", 0.0020);

        // ---------- 近期 5 秒粒度轨迹 ----------
        // 陈大山：正常行走 → 一个漂移跳点（丢弃）→ 末尾越出活动范围；低电、弱网、末三点离线补传
        int chenN = 60;
        for (int i = 0; i < chenN; i++) {
            Instant t = now.minusSeconds((long) (chenN - i) * 5 + 20);
            double[] c = wander(qingshan.getCenterLat(), qingshan.getCenterLng(), i, 0.0011);
            boolean offline = i >= chenN - 3;
            savePoint(chen, "seed-chen-rt-" + i, t, c[0], c[1], offline, false, false,
                    14 + i / 10, i < 10 ? 1 : 2, true, TrackPoint.IngestResult.ACCEPTED);
        }
        // 漂移点：5 秒内跳到数公里外（等效速度远超 45m/s），服务端判 DRIFT_DISCARDED，不连线不报警
        TrackPoint anchor = trackPointRepository.findAll().stream()
                .filter(p -> p.getOffender().getId().equals(chen.getId()))
                .reduce((a, b) -> b).orElse(null);
        savePoint(chen, "seed-chen-drift", anchor.getPointTime().plusSeconds(5),
                qingshan.getCenterLat() + 0.62, qingshan.getCenterLng() + 0.41,
                false, false, false, null, null, null, TrackPoint.IngestResult.DRIFT_DISCARDED);
        // 越界点：漂移后真实位置仍在南边界外（漂移点不移动锚点，故仍能正确判越界）
        Instant breachT = now.minusSeconds(12);
        savePoint(chen, "seed-chen-breach", breachT, 30.3380, 114.4720,
                true, true, false, 12, 1, true, TrackPoint.IngestResult.ACCEPTED);
        setLast(chen, breachT, 30.3380, 114.4720, false, false, 12, 1, true);

        // 杨春生：健康在线
        emitRecentWalk(yang, qingshan, 40, now.minusSeconds(10), "seed-yang-rt", 96, 4, true, 0);
        // 张伟国：12 分钟前最后定位（信号延迟），腕表已脱腕
        emitRecentWalk(zhang, chengguan, 40, now.minusSeconds(12 * 60), "seed-zhang-rt", 80, 4, false, 0);
        // 周文斌：最后一点进入废弃码头禁区（仍在活动范围内，仅触发禁区预警）
        emitRecentWalk(zhou, longhu, 40, now.minusSeconds(15), "seed-zhou-rt", 55, 3, true, 1);
        // 吴桂芳（请假）：25 分钟前信号中断 → 离线
        emitRecentWalk(wu, longhu, 24, now.minusSeconds(25 * 60), "seed-wu-rt", 43, 0, true, 0);
        // 伊宁对象：跨时区在线
        emitRecentWalk(mait, yining, 30, now.minusSeconds(18), "seed-mait-rt", 70, 4, true, 0);

        // 徐建华：先布轨迹再清除并留痕（“轨迹已清除”空态）
        for (int i = 0; i < 20; i++) {
            double[] c = wander(longhu.getCenterLat(), longhu.getCenterLng(), i, 0.0010);
            savePoint(xu, "seed-xu-clear-" + i, now.minusSeconds(2000 - i * 5L),
                    c[0], c[1], false, false, false, 60, 3, true, TrackPoint.IngestResult.ACCEPTED);
        }
        trackPointRepository.flush();
        long xuDeleted = trackPointRepository.deleteByOffender_Id(xu.getId());
        trackPointRepository.flush();
        monitorActionRepository.save(new MonitorAction(xu.getId(), "CLEAR_TRACKS", 0L, "韩雪梅",
                "对象更换配发腕表，设备回收后清除旧设备历史轨迹归档", null, null, (int) xuDeleted,
                "演示数据：共清除 " + xuDeleted + " 点"));
        xu.setLastLocationAt(null);
        xu.setLastLat(null);
        xu.setLastLng(null);
        xu.setLastInsideFence(null);
        xu.setLastForbidden(null);
        objectRepository.save(xu);
        // 孙满堂：从无任何轨迹（“该对象无轨迹”空态）

        // ---------- 报到记录（完成度双口径） ----------
        // 杨春生：近 30 天只在规定报到日（=今天星期）踩点报到 → 关键报到口径高、打卡天数口径低
        for (LocalDate d = today.minusDays(29); !d.isAfter(today); d = d.plusDays(1)) {
            if (d.getDayOfWeek().toString().equals(yang.getReportDay())) {
                checkInRepository.save(new CheckIn(yang, d,
                        d.atTime(9, 15).atZone(SH).toInstant(), "APP",
                        qingshan.getCenterLat() + 0.001, qingshan.getCenterLng(), true));
            }
        }
        // 周文斌：近 30 天在 18 个非周一打卡，却漏掉全部周一报到节点 → 打卡天数口径高、关键报到口径低
        int nonMondays = 0;
        for (LocalDate d = today.minusDays(29); !d.isAfter(today) && nonMondays < 18; d = d.plusDays(1)) {
            if (d.getDayOfWeek().toString().equals("MONDAY")) continue;
            checkInRepository.save(new CheckIn(zhou, d,
                    d.atTime(20, 5).atZone(SH).toInstant(), "APP",
                    longhu.getCenterLat() + 0.001, longhu.getCenterLng(), true));
            nonMondays++;
        }
        // 伊宁对象：按乌鲁木齐时区的“今天”完成一次报到
        ZoneId ynZone = ZoneId.of("Asia/Urumqi");
        LocalDate ynToday = now.atZone(ynZone).toLocalDate();
        checkInRepository.save(new CheckIn(mait, ynToday, now.minusSeconds(3600), "APP",
                yining.getCenterLat() + 0.001, yining.getCenterLng(), true));

        // ---------- 红点事件（时间均为 UTC） ----------
        violationRepository.save(new ViolationEvent(zhang, "ABSENT",
                "对象 Z-JWT26001 今日应到司法所/APP 报到，截至目前未报到", now.minusSeconds(20 * 60)));
        violationRepository.save(new ViolationEvent(chen, "GEOFENCE_BREACH",
                "对象 C-JWT26005 定位越出「青山乡规定活动范围」多边形围栏，最近定位时间（Asia/Shanghai）"
                        + ZonedDateTime.ofInstant(breachT, SH).toLocalDateTime() + "，末三点为离线补传",
                now.minusSeconds(10)));
        violationRepository.save(new ViolationEvent(zhou, "FORBIDDEN_ZONE",
                "对象 Z-JWT26009 定位进入「龙湖废弃码头（全天禁入）」禁区，最近定位时间（Asia/Shanghai）"
                        + ZonedDateTime.ofInstant(now.minusSeconds(15), SH).toLocalDateTime(),
                now.minusSeconds(12)));
        violationRepository.save(new ViolationEvent(objs.get(2), "ADMONISH",
                "对象 L-JWT26003 因本周两次未按规定时间报到，被予以训诫",
                today.minusDays(1).atTime(15, 30).atZone(SH).toInstant()));

        // ---------- 账号（先建账号，便于下方业务种子引用真实操作人 id） ----------
        UserAccount jiandu = createAccount("jiandu", "陈督导", Role.SUPERVISOR, null, null);
        UserAccount gancheng = createAccount("gancheng", "李建国", Role.STAFF, chengguan, null);
        UserAccount ganqingshan = createAccount("ganqingshan", "罗建军", Role.STAFF, qingshan, null);
        UserAccount ganlonghu = createAccount("ganlonghu", "韩雪梅", Role.STAFF, longhu, null);
        UserAccount ganyining = createAccount("ganyining", "古丽娜尔", Role.STAFF, yining, null);

        String[] objUsers = {"obj1", "obj2", "obj3", null, "obj4", "obj5", null, null,
                "obj6", "obj7", "obj8", "obj9", "obj10"};
        for (int i = 0; i < objs.size(); i++) {
            if (objUsers[i] != null) {
                createAccount(objUsers[i], objs.get(i).getFullName(), Role.OFFENDER,
                        objs.get(i).getOffice(), objs.get(i));
            }
        }

        // ---------- 请销假种子（两级审批 / 退回重提 / 逾假未归） ----------
        seedLeaves(objs, now, gancheng, ganqingshan, ganlonghu, ganyining, jiandu);

        // ---------- 公益活动种子（报名 / 正常打卡 / 位置异常打卡） ----------
        seedActivities(objs, now, qingshan, chengguan, longhu);

        // ---------- 月度报到种子（本月 + 上月，批量花名册可显示“已完成/未完成”） ----------
        seedMonthlyReports(objs, today, gancheng, ganqingshan, ganlonghu, ganyining);

        log.info("种子数据完成：4 个司法所（含跨时区伊宁所）、13 名对象、多边形活动范围 4 个、禁区 5 个、5 秒粒度轨迹与双口径样本、请销假/公益活动/月度报到样本");
    }

    /** 近 n 个 5 秒点围绕所中心游走；specialTail=1 时最后一点落入龙湖废弃码头 */
    private void emitRecentWalk(CorrectionObject o, JudicialOffice office, int n, Instant end,
                                String idPrefix, int battery, int signal, boolean wornLast,
                                int specialTail) {
        for (int i = 0; i < n; i++) {
            Instant t = end.minusSeconds((long) (n - 1 - i) * 5);
            double lat;
            double lng;
            boolean forbidden = false;
            if (specialTail == 1 && i == n - 1) {
                lat = 30.1062;
                lng = 114.2222;
                forbidden = true;
            } else {
                double[] c = wander(office.getCenterLat(), office.getCenterLng(), i, 0.0010);
                lat = c[0];
                lng = c[1];
            }
            savePoint(o, idPrefix + "-" + i, t, lat, lng, false, false, forbidden,
                    battery + (i % 3), signal, wornLast, TrackPoint.IngestResult.ACCEPTED);
        }
        double[] ll = specialTail == 1 ? new double[]{30.1062, 114.2222}
                : wander(office.getCenterLat(), office.getCenterLng(), n - 1, 0.0010);
        setLast(o, end, ll[0], ll[1], specialTail != 1, specialTail == 1,
                battery, signal, wornLast);
    }

    /** 近 30 天每 8 小时一个点（确定性抖动，沿时间轴略摆动），保证周/月视图有历史线 */
    private void emitHistory(CorrectionObject o, double baseLat, double baseLng, String prefix, double amp) {
        Instant now = Instant.now();
        for (int k = 1; k <= 90; k++) {
            Instant t = now.minusSeconds(k * 8L * 3600);
            double[] c = wander(baseLat, baseLng, k, amp);
            boolean offline = prefix.contains("chen") && k % 3 == 0;
            savePoint(o, prefix + "-" + k, t, c[0], c[1], offline, false, false,
                    null, null, null, TrackPoint.IngestResult.ACCEPTED);
        }
    }

    /** 确定性小范围游走（不用随机数，重启/重建数据稳定） */
    private double[] wander(double baseLat, double baseLng, int i, double amp) {
        double dLat = Math.sin(i * 1.7) * amp;
        double dLng = Math.cos(i * 1.3) * amp;
        return new double[]{baseLat + dLat, baseLng + dLng};
    }

    private void savePoint(CorrectionObject o, String clientId, Instant t,
                           double lat, double lng, boolean offline, boolean outside, boolean forbidden,
                           Integer battery, Integer signal, Boolean worn, TrackPoint.IngestResult result) {
        trackPointRepository.save(new TrackPoint(o, clientId, t, lat, lng, offline,
                Instant.now(), outside, forbidden, battery, signal, worn, result));
    }

    private void setLast(CorrectionObject o, Instant t, double lat, double lng,
                         boolean inside, boolean forbidden, int battery, int signal, boolean worn) {
        o.setLastLocationAt(t);
        o.setLastLat(lat);
        o.setLastLng(lng);
        o.setLastInsideFence(inside);
        o.setLastForbidden(forbidden);
        o.setLastBattery(battery);
        o.setLastSignal(signal);
        o.setLastWorn(worn);
        objectRepository.save(o);
    }

    private GeoFence allowFence(JudicialOffice office, String name, String polygonJson) {
        return fenceRepository.save(new GeoFence(office, name, GeoFence.FenceKind.ALLOW_RANGE,
                polygonJson, office.getCenterLat(), office.getCenterLng(), office.getFenceRadiusMeters(), true));
    }

    private GeoFence forbidFence(JudicialOffice office, String name, String polygonJson) {
        return fenceRepository.save(new GeoFence(office, name, GeoFence.FenceKind.FORBIDDEN,
                polygonJson, null, null, null, true));
    }

    private String quad(double aLat, double aLng, double bLat, double bLng,
                        double cLat, double cLng, double dLat, double dLng) {
        try {
            double[][] pts = {{aLat, aLng}, {bLat, bLng}, {cLat, cLng}, {dLat, dLng}};
            return objectMapper.writeValueAsString(pts);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void emitPath(CorrectionObject o, CorrectionStatus target) {
        Long op = 0L;
        transitionRepository.save(new StatusTransition(
                o.getId(), null, CorrectionStatus.INTAKE, op, "系统（入矫建档）", "入矫登记建档"));
        if (target == CorrectionStatus.INTAKE) {
            return;
        }
        transitionRepository.save(new StatusTransition(
                o.getId(), CorrectionStatus.INTAKE, CorrectionStatus.SERVING, op, "系统（入矫宣告）", "入矫宣告，纳入在矫管理"));
        switch (target) {
            case LEAVE -> transitionRepository.save(new StatusTransition(
                    o.getId(), CorrectionStatus.SERVING, CorrectionStatus.LEAVE, op, "系统（种子数据）", "请假外出审批通过"));
            case ADMONISHED -> transitionRepository.save(new StatusTransition(
                    o.getId(), CorrectionStatus.SERVING, CorrectionStatus.ADMONISHED, op, "系统（种子数据）", "违反监管规定，予以训诫"));
            case REIMPRISONED -> transitionRepository.save(new StatusTransition(
                    o.getId(), CorrectionStatus.SERVING, CorrectionStatus.REIMPRISONED, op, "系统（种子数据）", "违反监管规定情节严重，撤销缓刑收监执行"));
            case RELEASED -> transitionRepository.save(new StatusTransition(
                    o.getId(), CorrectionStatus.SERVING, CorrectionStatus.RELEASED, op, "系统（种子数据）", "矫正期满，依法解除社区矫正"));
            default -> { /* SERVING */ }
        }
    }

    private UserAccount createAccount(String username, String realName, Role role,
                               JudicialOffice office, CorrectionObject linked) {
        String[] saltHash = passwordEncoder.newSaltAndHash("123456");
        UserAccount u = new UserAccount();
        u.setUsername(username);
        u.setRealName(realName);
        u.setRole(role);
        u.setOffice(office);
        u.setLinkedOffender(linked);
        u.setPasswordSalt(saltHash[0]);
        u.setPasswordHash(saltHash[1]);
        u.setEnabled(true);
        return userRepository.save(u);
    }

    // ---------------- 请销假 / 公益活动 / 月度报到 种子 ----------------

    /**
     * 请销假样本，覆盖两级审批各环节：
     * 待初审、待区局复核、司法所退回、区局退回重提（两轮留痕）、已批准假期中、已销假、逾假未归（自动转训诫）。
     */
    private void seedLeaves(List<CorrectionObject> objs, Instant now,
                            UserAccount gancheng, UserAccount ganqingshan,
                            UserAccount ganlonghu, UserAccount ganyining, UserAccount jiandu) {
        CorrectionObject wang = objs.get(1);    // 城关·王秀兰（原 LEAVE）→ 逾假未归
        CorrectionObject chen = objs.get(4);    // 青山·陈大山 → 待区局复核
        CorrectionObject yang = objs.get(5);    // 青山·杨春生 → 区局退回（第二轮）
        CorrectionObject zhou = objs.get(8);    // 龙湖·周文斌 → 司法所退回
        CorrectionObject wu = objs.get(9);      // 龙湖·吴桂芳（LEAVE）→ 已批准假期中
        CorrectionObject sun = objs.get(11);    // 龙湖·孙满堂 → 已销假历史单
        CorrectionObject mait = objs.get(12);   // 伊宁·买买提 → 待司法所初审

        // 吴桂芳：已批准、假期进行中（昨天开始，两天后结束）
        LeaveRequest wuLeave = persistLeave(wu, "县人民医院", "陪护住院手术的家属，需赴县医院照护",
                now.minusSeconds(86400L / 2), now.plusSeconds(2 * 86400L),
                LeaveStatus.APPROVED, 1, now.minusSeconds(3 * 86400L));
        wuLeave.setOfficeApprovedAt(now.minusSeconds(2 * 86400L));
        wuLeave.setBureauApprovedAt(now.minusSeconds(86400L));
        leaveRepository.save(wuLeave);
        leaveLog(wuLeave, "SUBMIT", wuLeave.getStatus(), wu.getId(), wu.getFullName(), "对象提交请假申请", wuLeave);
        leaveLog(wuLeave, "OFFICE_APPROVE", wuLeave.getStatus(), ganlonghu.getId(), ganlonghu.getRealName(),
                "司法所初审同意，情况属实，报区局复核", null);
        leaveLog(wuLeave, "BUREAU_APPROVE", wuLeave.getStatus(), jiandu.getId(), jiandu.getRealName(),
                "区局复核同意，予以准假；假期内越界不报警，已联动核销红点", null);

        // 王秀兰：假期 3 天前结束仍未销假 → 逾假未归，档案转训诫（与定时任务自动处置同构）
        LeaveRequest wangLeave = persistLeave(wang, "外省老家", "家中长辈丧事需返乡处理",
                now.minusSeconds(6 * 86400L), now.minusSeconds(3 * 86400L),
                LeaveStatus.OVERDUE, 1, now.minusSeconds(8 * 86400L));
        wangLeave.setOfficeApprovedAt(now.minusSeconds(8 * 86400L + 3600));
        wangLeave.setBureauApprovedAt(now.minusSeconds(7 * 86400L));
        wangLeave.setOverdueAt(now.minusSeconds(3 * 86400L));
        leaveRepository.save(wangLeave);
        leaveLog(wangLeave, "SUBMIT", wangLeave.getStatus(), wang.getId(), wang.getFullName(), "对象提交请假申请", wangLeave);
        leaveLog(wangLeave, "OFFICE_APPROVE", wangLeave.getStatus(), gancheng.getId(), gancheng.getRealName(), "司法所初审同意，报区局复核", null);
        leaveLog(wangLeave, "BUREAU_APPROVE", wangLeave.getStatus(), jiandu.getId(), jiandu.getRealName(), "区局复核同意，予以准假", null);
        leaveLog(wangLeave, "OVERDUE", wangLeave.getStatus(), 0L, "系统（定时任务）",
                "假期届满未销假，自动升为违规并转训诫", (LeaveRequest) null);
        wang.setStatus(CorrectionStatus.ADMONISHED);
        objectRepository.save(wang);
        transitionRepository.save(new StatusTransition(wang.getId(),
                CorrectionStatus.LEAVE, CorrectionStatus.ADMONISHED, 0L,
                "系统（逾假未归自动处置）", "假期届满未销假，系统自动转训诫"));
        violationRepository.save(new ViolationEvent(wang, "LEAVE_OVERDUE",
                "对象 W-JWT26002 请假假期已于 " + wangLeave.getEndAt()
                        + " 届满，至今未销假返所，系统自动登记为逾假未归违规并转训诫",
                now.minusSeconds(3 * 86400L)));

        // 陈大山：司法所已初审通过，等待区局复核
        LeaveRequest chenLeave = persistLeave(chen, "县城", "随工队赴县城参加集中技能培训",
                now.plusSeconds(2 * 86400L), now.plusSeconds(3 * 86400L),
                LeaveStatus.PENDING_BUREAU, 1, now.minusSeconds(5 * 3600L));
        chenLeave.setOfficeApprovedAt(now.minusSeconds(2 * 3600L));
        leaveRepository.save(chenLeave);
        leaveLog(chenLeave, "SUBMIT", LeaveStatus.PENDING_OFFICE, chen.getId(), chen.getFullName(),
                "对象提交请假申请", chenLeave);
        leaveLog(chenLeave, "OFFICE_APPROVE", LeaveStatus.PENDING_BUREAU,
                ganqingshan.getId(), ganqingshan.getRealName(),
                "司法所初审同意，培训通知已核验，报区局复核", null);

        // 杨春生：两轮审批——第一轮被司法所退回、重提后区局复核再次退回，等待第二次修改重提
        LeaveRequest yangLeave = persistLeave(yang, "邻县", "拟赴邻县探望务工的配偶（区局复核退回后已补材料）",
                now.plusSeconds(4 * 86400L), now.plusSeconds(5 * 86400L),
                LeaveStatus.BUREAU_RETURNED, 2, now.minusSeconds(6 * 3600L));
        yangLeave.setOfficeApprovedAt(now.minusSeconds(2 * 3600L));
        leaveRepository.save(yangLeave);
        leaveLog(yangLeave, "SUBMIT", LeaveStatus.PENDING_OFFICE, yang.getId(), yang.getFullName(),
                "对象提交请假申请", rawSnapshot("邻县", "想去邻县打工几天",
                        now.plusSeconds(3 * 86400L), now.plusSeconds(6 * 86400L)));
        leaveLog(yangLeave, "OFFICE_RETURN", LeaveStatus.OFFICE_RETURNED,
                ganqingshan.getId(), ganqingshan.getRealName(),
                "司法所退回：事由不充分，请补充外出具体事由与证明材料", rawSnapshot(
                        "邻县", "想去邻县打工几天",
                        now.plusSeconds(3 * 86400L), now.plusSeconds(6 * 86400L)));
        leaveLog(yangLeave, "RESUBMIT", LeaveStatus.PENDING_OFFICE, yang.getId(), yang.getFullName(),
                "对象按退回意见补充亲属关系证明后第 2 次重新提交", yangLeave);
        leaveLog(yangLeave, "OFFICE_APPROVE", LeaveStatus.PENDING_BUREAU,
                ganqingshan.getId(), ganqingshan.getRealName(),
                "司法所初审同意（第 2 轮），材料已补齐，报区局复核", null);
        leaveLog(yangLeave, "BUREAU_RETURN", LeaveStatus.BUREAU_RETURNED,
                jiandu.getId(), jiandu.getRealName(),
                "区局退回：请假时间与在矫教育学习安排冲突，请调整为下周三之后再提交", null);

        // 周文斌：司法所初审直接退回，等待修改重提
        LeaveRequest zhouLeave = persistLeave(zhou, "市区", "想到市区找朋友",
                now.plusSeconds(86400L), now.plusSeconds(2 * 86400L),
                LeaveStatus.OFFICE_RETURNED, 1, now.minusSeconds(26 * 3600L));
        leaveRepository.save(zhouLeave);
        leaveLog(zhouLeave, "SUBMIT", LeaveStatus.PENDING_OFFICE, zhou.getId(), zhou.getFullName(),
                "对象提交请假申请", zhouLeave);
        leaveLog(zhouLeave, "OFFICE_RETURN", LeaveStatus.OFFICE_RETURNED,
                ganlonghu.getId(), ganlonghu.getRealName(),
                "司法所退回：目的地与事由均不明确，须写明具体去处、同行人与返回时间", null);

        // 买买提：刚提交，司法所待初审（跨时区所）
        LeaveRequest maitLeave = persistLeave(mait, "伊宁市开发区", "按司法所安排赴开发区参加集中就业洽谈",
                now.plusSeconds(3 * 86400L), now.plusSeconds(3 * 86400L + 6 * 3600L),
                LeaveStatus.PENDING_OFFICE, 1, now.minusSeconds(40 * 3600L));
        leaveRepository.save(maitLeave);
        leaveLog(maitLeave, "SUBMIT", LeaveStatus.PENDING_OFFICE, mait.getId(), mait.getFullName(),
                "对象提交请假申请", maitLeave);

        // 孙满堂：上个月一次完整的已销假假期
        LeaveRequest sunLeave = persistLeave(sun, "县医院", "复诊取药",
                now.minusSeconds(20 * 86400L), now.minusSeconds(19 * 86400L),
                LeaveStatus.COMPLETED, 1, now.minusSeconds(22 * 86400L));
        sunLeave.setOfficeApprovedAt(now.minusSeconds(21 * 86400L));
        sunLeave.setBureauApprovedAt(now.minusSeconds(20 * 86400L + 3600));
        sunLeave.setReturnedAt(now.minusSeconds(19 * 86400L + 1800));
        sunLeave.setReturnNote("按期返所，复诊病历已交司法所备案");
        leaveRepository.save(sunLeave);
        leaveLog(sunLeave, "SUBMIT", LeaveStatus.PENDING_OFFICE, sun.getId(), sun.getFullName(),
                "对象提交请假申请", sunLeave);
        leaveLog(sunLeave, "OFFICE_APPROVE", LeaveStatus.PENDING_BUREAU,
                ganlonghu.getId(), ganlonghu.getRealName(), "司法所初审同意，报区局复核", null);
        leaveLog(sunLeave, "BUREAU_APPROVE", LeaveStatus.APPROVED,
                jiandu.getId(), jiandu.getRealName(), "区局复核同意，予以准假半天", null);
        leaveLog(sunLeave, "RETURN", LeaveStatus.COMPLETED,
                sun.getId(), sun.getFullName(), "对象按期销假返所：复诊病历已交司法所备案", null);
    }

    /** 城关所干警账号已先行创建 */

    private LeaveRequest persistLeave(CorrectionObject o, String dest, String reason,
                                      Instant startAt, Instant endAt, LeaveStatus status,
                                      int revision, Instant submittedAt) {
        LeaveRequest lr = new LeaveRequest();
        lr.setOffender(o);
        lr.setDestination(dest);
        lr.setReason(reason);
        lr.setStartAt(startAt);
        lr.setEndAt(endAt);
        lr.setStatus(status);
        lr.setRevision(revision);
        lr.setSubmittedAt(submittedAt);
        return leaveRepository.save(lr);
    }

    private String rawSnapshot(String dest, String reason, Instant startAt, Instant endAt) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "destination", dest, "reason", reason,
                    "startAt", startAt.toString(), "endAt", endAt.toString()));
        } catch (Exception e) {
            return null;
        }
    }

    private void leaveLog(LeaveRequest lr, String action, LeaveStatus resultStatus,
                          Long operatorId, String operatorName, String comment, Object snapshotOrSource) {
        String snapshot = null;
        if (snapshotOrSource instanceof LeaveRequest src) {
            snapshot = rawSnapshot(src.getDestination(), src.getReason(), src.getStartAt(), src.getEndAt());
        } else if (snapshotOrSource instanceof String s) {
            snapshot = s;
        }
        leaveLogRepository.save(new LeaveRequestLog(lr.getId(), action, resultStatus,
                lr.getRevision(), operatorId, operatorName, comment, snapshot));
    }

    /** 公益活动样本：未开始（可报名）、进行中（可现场打卡）、已结束（含正常+异常打卡花名册）。 */
    private void seedActivities(List<CorrectionObject> objs, Instant now,
                                JudicialOffice qingshan, JudicialOffice chengguan, JudicialOffice longhu) {
        CorrectionObject chen = objs.get(4);
        CorrectionObject yang = objs.get(5);
        CorrectionObject zhou = objs.get(8);
        CorrectionObject wu = objs.get(9);
        CorrectionObject sun = objs.get(11);

        // 进行中：青山乡敬老院慰问（打卡通道开放），陈大山已在现场正常打卡
        PublicActivity caring = newActivity("青山乡敬老院慰问志愿服务",
                "陪同敬老院老人打扫卫生、表演节目，现场签到打卡",
                qingshan, "青山乡中心敬老院",
                qingshan.getCenterLat() + 0.0010, qingshan.getCenterLng() - 0.0008, 200,
                now.minusSeconds(40 * 60), now.plusSeconds(2 * 3600), now.minusSeconds(86400L), 30);
        signupRepository.save(new ActivitySignup(caring, chen, "准时参加"));
        signupRepository.save(new ActivitySignup(caring, yang, ""));
        activityCheckInRepository.save(new ActivityCheckIn(caring, chen, ActivityCheckIn.Result.NORMAL,
                now.minusSeconds(20 * 60),
                caring.getLat() + 0.00002, caring.getLng() - 0.00001, 6.3));

        // 未开始：青山乡河道清理（手机端可报名）
        newActivity("青山乡河道垃圾清理公益劳动",
                "沿乡河道捡拾垃圾、清理淤积物，请着劳动服装，现场打卡考勤",
                qingshan, "青山乡东河桥集合点",
                qingshan.getCenterLat() + 0.0022, qingshan.getCenterLng() + 0.0015, 200,
                now.plusSeconds(2 * 86400L), now.plusSeconds(2 * 86400L + 3 * 3600L),
                now.plusSeconds(86400L), 20);

        // 未开始：城关社区法治宣传
        newActivity("城关社区法治宣传日",
                "协助司法所发放社区矫正法宣传册，现场答疑维持秩序",
                chengguan, "城关街道文化广场",
                chengguan.getCenterLat() - 0.0010, chengguan.getCenterLng() + 0.0012, 300,
                now.plusSeconds(3 * 86400L), now.plusSeconds(3 * 86400L + 4 * 3600L),
                now.plusSeconds(2 * 86400L), 50);

        // 已结束：龙湖镇公园清扫——周文斌/孙满堂正常打卡，吴桂芳在范围外打卡被标异常
        PublicActivity cleanup = newActivity("龙湖镇滨河公园清扫",
                "清扫滨河公园步道与绿化带",
                longhu, "龙湖镇滨河公园南门",
                longhu.getCenterLat() + 0.0008, longhu.getCenterLng() - 0.0006, 150,
                now.minusSeconds(10 * 86400L), now.minusSeconds(10 * 86400L + 3 * 3600L),
                now.minusSeconds(11 * 86400L), 25);
        signupRepository.save(new ActivitySignup(cleanup, zhou, ""));
        signupRepository.save(new ActivitySignup(cleanup, sun, ""));
        signupRepository.save(new ActivitySignup(cleanup, wu, "身体不适可能晚到"));
        activityCheckInRepository.save(new ActivityCheckIn(cleanup, zhou, ActivityCheckIn.Result.NORMAL,
                now.minusSeconds(10 * 86400L + 5 * 60),
                cleanup.getLat(), cleanup.getLng() + 0.00003, 3.1));
        activityCheckInRepository.save(new ActivityCheckIn(cleanup, sun, ActivityCheckIn.Result.NORMAL,
                now.minusSeconds(10 * 86400L + 8 * 60),
                cleanup.getLat() - 0.00002, cleanup.getLng(), 2.4));
        // 异常：定位距活动点约 2.1km，不在 150m 半径内，标记 ABNORMAL 留痕
        activityCheckInRepository.save(new ActivityCheckIn(cleanup, wu, ActivityCheckIn.Result.ABNORMAL,
                now.minusSeconds(10 * 86400L + 12 * 60),
                cleanup.getLat() + 0.019, cleanup.getLng() + 0.005, 2120.0));
    }

    private PublicActivity newActivity(String title, String desc, JudicialOffice office,
                                       String locationName, double lat, double lng, int radius,
                                       Instant startAt, Instant endAt, Instant deadline, Integer capacity) {
        PublicActivity a = new PublicActivity();
        a.setTitle(title);
        a.setDescription(desc);
        a.setOffice(office);
        a.setLocationName(locationName);
        a.setLat(lat);
        a.setLng(lng);
        a.setRadiusMeters(radius);
        a.setStartAt(startAt);
        a.setEndAt(endAt);
        a.setSignupDeadline(deadline);
        a.setCapacity(capacity);
        a.setEnabled(true);
        return activityRepository.save(a);
    }

    /** 月度报到：本月部分对象已批量登记，上月花名册多人已完成。 */
    private void seedMonthlyReports(List<CorrectionObject> objs, LocalDate today,
                                    UserAccount gancheng, UserAccount ganqingshan,
                                    UserAccount ganlonghu, UserAccount ganyining) {
        java.time.YearMonth thisMonth = java.time.YearMonth.from(today);
        LocalDate m0 = thisMonth.atDay(1);
        LocalDate m1 = thisMonth.minusMonths(1).atDay(1);

        // 本月已完成：陈大山、杨春生（青山所批量登记）、买买提（伊宁所）
        monthlyReportRepository.save(new MonthlyReport(objs.get(4), m0,
                ganqingshan.getId(), ganqingshan.getRealName(), "BATCH", "本月集中点验，按月度报到花名册批量登记"));
        monthlyReportRepository.save(new MonthlyReport(objs.get(5), m0,
                ganqingshan.getId(), ganqingshan.getRealName(), "BATCH", "本月集中点验，按月度报到花名册批量登记"));
        monthlyReportRepository.save(new MonthlyReport(objs.get(12), m0,
                ganyining.getId(), ganyining.getRealName(), "SINGLE", "当面月度报到"));

        // 上月已完成多人
        monthlyReportRepository.save(new MonthlyReport(objs.get(0), m1, gancheng.getId(),
                gancheng.getRealName(), "BATCH", "上月月度点验"));
        monthlyReportRepository.save(new MonthlyReport(objs.get(4), m1, ganqingshan.getId(),
                ganqingshan.getRealName(), "BATCH", "上月月度点验"));
        monthlyReportRepository.save(new MonthlyReport(objs.get(5), m1, ganqingshan.getId(),
                ganqingshan.getRealName(), "BATCH", "上月月度点验"));
        monthlyReportRepository.save(new MonthlyReport(objs.get(8), m1, ganlonghu.getId(),
                ganlonghu.getRealName(), "BATCH", "上月月度点验"));
        monthlyReportRepository.save(new MonthlyReport(objs.get(12), m1, ganyining.getId(),
                ganyining.getRealName(), "SINGLE", "上月当面月度报到"));
    }

    private record SeedObj(String no, String fullName, JudicialOffice office,
                           CorrectionStatus status, String reportDay, String charge,
                           LocalDate start, LocalDate end) {
    }
}
