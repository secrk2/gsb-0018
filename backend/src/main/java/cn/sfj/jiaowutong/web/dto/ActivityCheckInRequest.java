package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * 公益活动现场打卡请求。与日常报到一致携带定位时间，服务端按时效（5 分钟）与活动点半径双重校验。
 */
public record ActivityCheckInRequest(
        @NotNull(message = "缺少定位时间，无法核验现场打卡") Instant fixTime,
        @NotNull @Min(-90) @Max(90) Double lat,
        @NotNull @Min(-180) @Max(180) Double lng) {
}
