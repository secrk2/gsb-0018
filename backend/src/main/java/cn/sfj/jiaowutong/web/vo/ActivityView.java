package cn.sfj.jiaowutong.web.vo;

import java.time.Instant;

/**
 * 公益活动视图。
 * signupCount 已报名人数；对象视角下 signedUp/myCheckInResult/myCheckIn 为本人状态。
 */
public record ActivityView(
        Long id, String title, String description,
        Long officeId, String officeName, String officeTimezone,
        String locationName, Double lat, Double lng, Integer radiusMeters,
        Instant startAt, Instant endAt, Instant signupDeadline, Integer capacity,
        Boolean enabled, long signupCount,
        boolean signedUp, String myCheckInResult, ActivityCheckInView myCheckIn) {
}
