package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 两级审批意见。approve=false（退回）时 comment 必填且不少于 4 字，服务端二次强校验。
 */
public record LeaveReviewRequest(
        @NotNull Boolean approve,
        @Size(max = 256) String comment) {
}
