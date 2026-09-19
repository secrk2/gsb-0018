package cn.sfj.jiaowutong.web.dto;

import jakarta.validation.constraints.Size;

/** 销假备注（可空）。 */
public record LeaveReturnRequest(@Size(max = 256) String note) {
}
