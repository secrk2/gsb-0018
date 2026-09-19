package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.domain.CheckIn;
import cn.sfj.jiaowutong.domain.CorrectionObject;
import cn.sfj.jiaowutong.domain.CorrectionStatus;
import cn.sfj.jiaowutong.domain.Role;
import cn.sfj.jiaowutong.repo.CheckInRepository;
import cn.sfj.jiaowutong.repo.CorrectionObjectRepository;
import cn.sfj.jiaowutong.security.LoginUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * 矫正对象日常报到。报到定位与轨迹上报同样禁止旧位置。
 * “今天/星期几”按对象所属司法所时区计算，跨时区对象不会因服务器时区而记错报到日。
 */
@Service
public class CheckInService {

    private static final long REALTIME_SKEW_MIN = 5;

    private final CheckInRepository checkInRepository;
    private final CorrectionObjectRepository objectRepository;
    private final FenceService fenceService;

    public CheckInService(CheckInRepository checkInRepository,
                          CorrectionObjectRepository objectRepository,
                          FenceService fenceService) {
        this.checkInRepository = checkInRepository;
        this.objectRepository = objectRepository;
        this.fenceService = fenceService;
    }

    @Transactional
    public Map<String, Object> checkIn(Instant fixTime, Double lat, Double lng, LoginUser user) {
        if (user.role() != Role.OFFENDER || user.offenderId() == null) {
            throw ApiException.forbidden("仅矫正对象本人账号可报到");
        }
        CorrectionObject obj = objectRepository.findById(user.offenderId())
                .orElseThrow(() -> ApiException.notFound("本人档案不存在"));
        if (obj.getStatus() == CorrectionStatus.RELEASED
                || obj.getStatus() == CorrectionStatus.REIMPRISONED) {
            throw ApiException.badRequest("INVALID_STATUS",
                    "当前状态为「" + obj.getStatus().getLabel() + "」，无需日常报到");
        }

        Instant now = Instant.now();
        if (fixTime.isAfter(now.plusSeconds(120))) {
            throw ApiException.badRequest("STALE_LOCATION",
                    "报到定位时间晚于当前时间，疑似伪造定位");
        }
        if (fixTime.isBefore(now.minusSeconds(REALTIME_SKEW_MIN * 60))) {
            throw new ApiException("STALE_LOCATION",
                    "报到定位采集于 " + fixTime + "，已超过 " + REALTIME_SKEW_MIN
                            + " 分钟时效。乡村断网时请在信号恢复后重新获取当前定位，不能用缓存旧位置报到");
        }

        ZoneId zone = FenceService.safeZone(obj.getOffice().getTimezone());
        FenceService.OfficeFences fences = fenceService.load(obj.getOffice());
        boolean inside = fences.insideRange(lat, lng);

        // 报到日按对象所在司法所时区确定，跨时区对象不会把 UTC 的“昨天/今天”记错
        LocalDate localDate = fixTime.atZone(zone).toLocalDate();
        boolean firstToday = !checkInRepository.existsByOffender_IdAndCheckDate(obj.getId(), localDate);
        checkInRepository.save(new CheckIn(obj, localDate, now, "APP", lat, lng, inside));

        // 同步更新最新位置（报到点视为一个有效实时定位）
        obj.setLastLocationAt(now);
        obj.setLastLat(lat);
        obj.setLastLng(lng);
        obj.setLastInsideFence(inside);
        obj.setLastForbidden(fences.forbiddenAt(lat, lng, fixTime) != null);
        objectRepository.save(obj);

        return Map.of(
                "checkedAt", now,
                "insideFence", inside,
                "firstToday", firstToday,
                "message", inside ? "报到成功，定位在规定活动范围内" : "报到成功，但当前定位在电子围栏外，已提示司法所关注"
        );
    }
}
