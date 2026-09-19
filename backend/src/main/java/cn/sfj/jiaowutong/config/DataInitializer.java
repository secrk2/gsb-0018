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

        // ---------- 账号 ----------
        createAccount("jiandu", "陈督导", Role.SUPERVISOR, null, null);
        createAccount("gancheng", "李建国", Role.STAFF, chengguan, null);
        createAccount("ganqingshan", "罗建军", Role.STAFF, qingshan, null);
        createAccount("ganlonghu", "韩雪梅", Role.STAFF, longhu, null);
        createAccount("ganyining", "古丽娜尔", Role.STAFF, yining, null);

        String[] objUsers = {"obj1", "obj2", "obj3", null, "obj4", "obj5", null, null,
                "obj6", "obj7", "obj8", "obj9", "obj10"};
        for (int i = 0; i < objs.size(); i++) {
            if (objUsers[i] != null) {
                createAccount(objUsers[i], objs.get(i).getFullName(), Role.OFFENDER,
                        objs.get(i).getOffice(), objs.get(i));
            }
        }

        log.info("种子数据完成：4 个司法所（含跨时区伊宁所）、13 名对象、多边形活动范围 4 个、禁区 5 个、5 秒粒度轨迹与双口径样本");
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

    private void createAccount(String username, String realName, Role role,
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
        userRepository.save(u);
    }

    private record SeedObj(String no, String fullName, JudicialOffice office,
                           CorrectionStatus status, String reportDay, String charge,
                           LocalDate start, LocalDate end) {
    }
}
