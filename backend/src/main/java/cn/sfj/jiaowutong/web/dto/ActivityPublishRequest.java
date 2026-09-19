package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.constraints.*;

import java.time.Instant;

/**
 * 公益活动发布请求。干警发布时 officeId 可空（默认本所）；监管员发布须指定主办司法所。
 */
public record ActivityPublishRequest(
        @NotBlank @Size(min = 2, max = 64) String title,
        @Size(max = 512) String description,
        Long officeId,
        @NotBlank @Size(min = 2, max = 128) String locationName,
        @NotNull @Min(-90) @Max(90) Double lat,
        @NotNull @Min(-180) @Max(180) Double lng,
        @NotNull @Min(20) @Max(5000) Integer radiusMeters,
        @NotNull Instant startAt,
        @NotNull Instant endAt,
        @NotNull Instant signupDeadline,
        @Min(1) @Max(9999) Integer capacity) {
}
