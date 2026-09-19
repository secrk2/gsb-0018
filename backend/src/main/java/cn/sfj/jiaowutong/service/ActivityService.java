package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.*;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.web.vo.ActivityCheckInView;
import cn.sfj.jiaowutong.web.vo.ActivityView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 公益活动：司法所发布 → 本所对象手机端报名 → 活动现场打卡。
 * 打卡位置由服务端用活动点中心+半径重算：半径内记 NORMAL，半径外记 ABNORMAL 异常（留痕、不采信为到场）；
 * 定位时效与日常报到一致（5 分钟内实时定位），防止拿旧坐标在异地冒打卡。
 */
@Service
public class ActivityService {

    /** 现场打卡相对活动时间的允许窗口：开始前 30 分钟 ~ 活动结束 */
    private static final long CHECKIN_EARLY_SEC = 30 * 60;
    /** 打卡定位时效（分钟），与报到/轨迹一致 */
    private static final long REALTIME_SKEW_MIN = 5;

    private final PublicActivityRepository activityRepository;
    private final ActivitySignupRepository signupRepository;
    private final ActivityCheckInRepository checkInRepository;
    private final CorrectionObjectRepository objectRepository;
    private final JudicialOfficeRepository officeRepository;
    private final AccessControlService accessControl;

    public ActivityService(PublicActivityRepository activityRepository,
                           ActivitySignupRepository signupRepository,
                           ActivityCheckInRepository checkInRepository,
                           CorrectionObjectRepository objectRepository,
                           JudicialOfficeRepository officeRepository,
                           AccessControlService accessControl) {
        this.activityRepository = activityRepository;
        this.signupRepository = signupRepository;
        this.checkInRepository = checkInRepository;
        this.objectRepository = objectRepository;
        this.officeRepository = officeRepository;
        this.accessControl = accessControl;
    }

    // ---------------- 发布与管理 ----------------

    @Transactional
    public ActivityView publish(String title, String description, Long officeId, String locationName,
                                Double lat, Double lng, Integer radiusMeters,
                                Instant startAt, Instant endAt, Instant signupDeadline,
                                Integer capacity, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        JudicialOffice office;
        if (user.role() == Role.STAFF) {
            office = officeRepository.findById(user.officeId())
                    .orElseThrow(() -> ApiException.notFound("司法所不存在"));
        } else {
            if (officeId == null) {
                throw ApiException.badRequest("OFFICE_REQUIRED", "区局发布活动须指定主办司法所");
            }
            office = officeRepository.findById(officeId)
                    .orElseThrow(() -> ApiException.notFound("指定的司法所不存在"));
        }
        validateActivity(title, locationName, lat, lng, radiusMeters, startAt, endAt, signupDeadline);

        PublicActivity a = new PublicActivity();
        a.setTitle(title.trim());
        a.setDescription(description);
        a.setOffice(office);
        a.setLocationName(locationName.trim());
        a.setLat(lat);
        a.setLng(lng);
        a.setRadiusMeters(radiusMeters);
        a.setStartAt(startAt);
        a.setEndAt(endAt);
        a.setSignupDeadline(signupDeadline);
        a.setCapacity(capacity);
        a.setEnabled(true);
        activityRepository.save(a);
        return view(a, false, null, 0L, null);
    }

    @Transactional(readOnly = true)
    public List<ActivityView> list(LoginUser user) {
        Instant now = Instant.now();
        List<PublicActivity> all;
        if (user.role() == Role.SUPERVISOR) {
            all = activityRepository.findAllByOrderByStartAtAscIdAsc();
        } else if (user.role() == Role.STAFF) {
            all = activityRepository.findByOffice_IdAndEnabledTrueOrderByStartAtAscIdAsc(user.officeId());
        } else {
            CorrectionObject me = objectRepository.findById(user.offenderId())
                    .orElseThrow(() -> ApiException.notFound("本人档案不存在"));
            all = activityRepository.findByOffice_IdAndEnabledTrueOrderByStartAtAscIdAsc(me.getOffice().getId());
        }
        List<ActivityView> result = new ArrayList<>();
        for (PublicActivity a : all) {
            long signed = signupRepository.countByActivity_Id(a.getId());
            if (user.role() == Role.OFFENDER) {
                ActivitySignup mine = signupRepository
                        .findByActivity_IdAndOffender_Id(a.getId(), user.offenderId()).orElse(null);
                ActivityCheckIn ci = checkInRepository
                        .findByActivity_IdAndOffender_Id(a.getId(), user.offenderId()).orElse(null);
                result.add(view(a, mine != null, ci == null ? null : ci.getResult().name(),
                        signed, ci == null ? null : checkinView(ci)));
            } else {
                result.add(view(a, false, null, signed, null));
            }
        }
        result.sort(Comparator.comparing(ActivityView::startAt));
        return result;
    }

    /** 详情：干警/监管员返回报名与打卡花名册；对象返回本人报名/打卡状态。 */
    @Transactional(readOnly = true)
    public java.util.Map<String, Object> detail(Long id, LoginUser user) {
        PublicActivity a = activityRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("活动不存在或已下架"));
        if (!a.getEnabled() && user.role() == Role.OFFENDER) {
            throw ApiException.notFound("活动不存在或已下架");
        }
        if (user.role() == Role.STAFF && !user.officeId().equals(a.getOffice().getId())) {
            throw ApiException.forbidden("该活动由「" + a.getOffice().getName() + "」发布，不在您的管辖范围");
        }

        ActivityView base;
        List<java.util.Map<String, Object>> roster = new ArrayList<>();
        if (user.role() == Role.OFFENDER) {
            CorrectionObject me = objectRepository.findById(user.offenderId())
                    .orElseThrow(() -> ApiException.notFound("本人档案不存在"));
            if (!me.getOffice().getId().equals(a.getOffice().getId())) {
                throw ApiException.forbidden("对象只能查看本所发布的公益活动");
            }
            ActivitySignup mine = signupRepository
                    .findByActivity_IdAndOffender_Id(a.getId(), user.offenderId()).orElse(null);
            ActivityCheckIn ci = checkInRepository
                    .findByActivity_IdAndOffender_Id(a.getId(), user.offenderId()).orElse(null);
            base = view(a, mine != null, ci == null ? null : ci.getResult().name(),
                    signupRepository.countByActivity_Id(a.getId()),
                    ci == null ? null : checkinView(ci));
        } else {
            base = view(a, false, null, signupRepository.countByActivity_Id(a.getId()), null);
            List<ActivitySignup> signups = signupRepository.findByActivity_IdOrderBySignedAtAscIdAsc(a.getId());
            for (ActivitySignup s : signups) {
                CorrectionObject o = s.getOffender();
                ActivityCheckIn ci = checkInRepository
                        .findByActivity_IdAndOffender_Id(a.getId(), o.getId()).orElse(null);
                roster.add(java.util.Map.of(
                        "offenderId", o.getId(),
                        "correctionNo", o.getCorrectionNo(),
                        "maskedName", o.getMaskedName(),
                        "signedAt", s.getSignedAt(),
                        "signupNote", s.getNote() == null ? "" : s.getNote(),
                        "checkedIn", ci != null,
                        "checkIn", ci == null ? "" : checkinView(ci)));
            }
        }
        return java.util.Map.of("activity", base, "roster", roster);
    }

    // ---------------- 对象端：报名 / 现场打卡 ----------------

    @Transactional
    public ActivityView signup(Long activityId, String note, LoginUser user) {
        if (user.role() != Role.OFFENDER || user.offenderId() == null) {
            throw ApiException.forbidden("仅矫正对象本人账号可报名公益活动");
        }
        PublicActivity a = loadEnabled(activityId);
        CorrectionObject me = objectRepository.findById(user.offenderId())
                .orElseThrow(() -> ApiException.notFound("本人档案不存在"));
        if (!me.getOffice().getId().equals(a.getOffice().getId())) {
            throw ApiException.forbidden("该活动由「" + a.getOffice().getName() + "」发布，仅面向该所对象");
        }
        if (me.getStatus() == CorrectionStatus.REIMPRISONED || me.getStatus() == CorrectionStatus.RELEASED) {
            throw ApiException.badRequest("INVALID_STATUS",
                    "当前状态为「" + me.getStatus().getLabel() + "」，不能报名公益活动");
        }
        Instant now = Instant.now();
        if (now.isAfter(a.getSignupDeadline())) {
            throw ApiException.badRequest("SIGNUP_CLOSED", "报名已于 " + a.getSignupDeadline() + " 截止");
        }
        if (a.getStartAt().isBefore(now)) {
            throw ApiException.badRequest("SIGNUP_CLOSED", "活动已开始，不再接受报名");
        }
        ActivitySignup existing = signupRepository
                .findByActivity_IdAndOffender_Id(activityId, user.offenderId()).orElse(null);
        if (existing != null) {
            // 幂等：重复报名不报错，返回已报名状态
            return view(a, true, null, signupRepository.countByActivity_Id(a.getId()), null);
        }
        if (a.getCapacity() != null) {
            long signed = signupRepository.countByActivity_Id(a.getId());
            if (signed >= a.getCapacity()) {
                throw ApiException.badRequest("ACTIVITY_FULL", "活动名额已满（上限 " + a.getCapacity() + " 人）");
            }
        }
        signupRepository.save(new ActivitySignup(a, me, note));
        return view(a, true, null, signupRepository.countByActivity_Id(a.getId()), null);
    }

    @Transactional
    public ActivityCheckInView checkIn(Long activityId, Instant fixTime, Double lat, Double lng, LoginUser user) {
        if (user.role() != Role.OFFENDER || user.offenderId() == null) {
            throw ApiException.forbidden("仅矫正对象本人账号可现场打卡");
        }
        PublicActivity a = loadEnabled(activityId);
        CorrectionObject me = objectRepository.findById(user.offenderId())
                .orElseThrow(() -> ApiException.notFound("本人档案不存在"));
        if (!signupRepository.existsByActivity_IdAndOffender_Id(activityId, user.offenderId())) {
            throw ApiException.badRequest("NOT_SIGNED_UP", "请先在手机端报名本活动，再到现场打卡");
        }

        Instant now = Instant.now();
        if (fixTime == null) {
            throw ApiException.badRequest("STALE_LOCATION", "缺少定位时间，无法核验现场打卡");
        }
        if (fixTime.isAfter(now.plusSeconds(120))) {
            throw ApiException.badRequest("STALE_LOCATION", "打卡定位时间晚于当前时间，疑似伪造定位");
        }
        if (fixTime.isBefore(now.minusSeconds(REALTIME_SKEW_MIN * 60))) {
            throw new ApiException("STALE_LOCATION",
                    "打卡定位采集于 " + fixTime + "，已超过 " + REALTIME_SKEW_MIN
                            + " 分钟时效，现场打卡必须使用当前位置，不能用缓存旧坐标");
        }
        if (now.isBefore(a.getStartAt().minusSeconds(CHECKIN_EARLY_SEC))) {
            throw ApiException.badRequest("CHECKIN_NOT_OPEN",
                    "活动开始前 30 分钟才开放现场打卡，请到达活动点后再打卡");
        }
        if (now.isAfter(a.getEndAt())) {
            throw ApiException.badRequest("CHECKIN_CLOSED", "活动已结束，现场打卡通道已关闭");
        }

        // 幂等：已打卡直接返回首条结果（含可能的异常记录），不允许“异常后跑到现场洗白”
        ActivityCheckIn existing = checkInRepository
                .findByActivity_IdAndOffender_Id(activityId, user.offenderId()).orElse(null);
        if (existing != null) {
            ActivityCheckInView v = checkinView(existing);
            return new ActivityCheckInView(v.id(), v.activityId(), v.result(), v.resultLabel(),
                    v.fixTime(), v.lat(), v.lng(), v.distanceMeters(), v.radiusMeters(), v.insideRange(),
                    true);
        }

        // 服务端距离重算（不轻信客户端“我在现场”）：半径外即异常，照常留痕
        double dist = GeoUtil.distanceMeters(lat, lng, a.getLat(), a.getLng());
        boolean inside = dist <= a.getRadiusMeters();
        ActivityCheckIn ci = new ActivityCheckIn(a, me,
                inside ? ActivityCheckIn.Result.NORMAL : ActivityCheckIn.Result.ABNORMAL,
                fixTime, lat, lng, dist);
        checkInRepository.save(ci);
        return checkinView(ci).withAlreadyChecked(false);
    }

    // ---------------- 私有 ----------------

    private PublicActivity loadEnabled(Long id) {
        PublicActivity a = activityRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("活动不存在或已下架"));
        if (!Boolean.TRUE.equals(a.getEnabled())) {
            throw ApiException.notFound("活动不存在或已下架");
        }
        return a;
    }

    private void validateActivity(String title, String locationName, Double lat, Double lng,
                                  Integer radiusMeters, Instant startAt, Instant endAt,
                                  Instant signupDeadline) {
        if (title == null || title.trim().length() < 2) {
            throw ApiException.badRequest("INVALID_ACTIVITY", "活动名称至少 2 个字");
        }
        if (locationName == null || locationName.trim().length() < 2) {
            throw ApiException.badRequest("INVALID_ACTIVITY", "活动地点至少 2 个字");
        }
        if (lat == null || lng == null || Math.abs(lat) > 90 || Math.abs(lng) > 180) {
            throw ApiException.badRequest("INVALID_ACTIVITY", "活动点坐标不合法");
        }
        if (radiusMeters == null || radiusMeters < 20 || radiusMeters > 5000) {
            throw ApiException.badRequest("INVALID_ACTIVITY", "打卡有效半径须在 20~5000 米之间");
        }
        if (startAt == null || endAt == null || signupDeadline == null) {
            throw ApiException.badRequest("INVALID_ACTIVITY", "缺少活动时间或报名截止时间");
        }
        if (!endAt.isAfter(startAt)) {
            throw ApiException.badRequest("INVALID_ACTIVITY", "活动结束时间必须晚于开始时间");
        }
        if (signupDeadline.isAfter(startAt)) {
            throw ApiException.badRequest("INVALID_ACTIVITY", "报名截止时间不能晚于活动开始时间");
        }
    }

    private ActivityView view(PublicActivity a, boolean signedUp, String myCheckInResult,
                              long signupCount, ActivityCheckInView myCheckIn) {
        return new ActivityView(a.getId(), a.getTitle(), a.getDescription(),
                a.getOffice().getId(), a.getOffice().getName(), a.getOffice().getTimezone(),
                a.getLocationName(), a.getLat(), a.getLng(), a.getRadiusMeters(),
                a.getStartAt(), a.getEndAt(), a.getSignupDeadline(), a.getCapacity(),
                a.getEnabled(), signupCount, signedUp, myCheckInResult, myCheckIn);
    }

    private ActivityCheckInView checkinView(ActivityCheckIn ci) {
        PublicActivity a = ci.getActivity();
        boolean inside = ci.getResult() == ActivityCheckIn.Result.NORMAL;
        return new ActivityCheckInView(ci.getId(), a.getId(), ci.getResult().name(),
                inside ? "正常" : "位置异常",
                ci.getFixTime(), ci.getLat(), ci.getLng(),
                Math.round(ci.getDistanceMeters() * 10) / 10.0, a.getRadiusMeters(), inside, false);
    }
}
