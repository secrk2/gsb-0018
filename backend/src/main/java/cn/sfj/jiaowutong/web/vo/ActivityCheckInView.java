package cn.sfj.jiaowutong.web.vo;

import java.time.Instant;

/**
 * 公益活动现场打卡结果视图。服务端测算距离并给出结论：
 * result=NORMAL 打卡点在活动点半径内；ABNORMAL 不在范围内（异常，留痕不采信）。
 * alreadyChecked=true 表示本次为重复打卡，返回的是首次打卡记录（不能重复洗白）。
 */
public record ActivityCheckInView(
        Long id, Long activityId,
        String result, String resultLabel,
        Instant fixTime, Double lat, Double lng,
        Double distanceMeters, Integer radiusMeters, boolean insideRange,
        boolean alreadyChecked) {

    public ActivityCheckInView withAlreadyChecked(boolean already) {
        return new ActivityCheckInView(id, activityId, result, resultLabel, fixTime, lat, lng,
                distanceMeters, radiusMeters, insideRange, already);
    }
}
