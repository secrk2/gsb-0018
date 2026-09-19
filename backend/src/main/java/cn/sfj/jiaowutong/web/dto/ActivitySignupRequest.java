package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.constraints.Size;

/** 公益活动报名请求（备注可空）。 */
public record ActivitySignupRequest(@Size(max = 256) String note) {
}
