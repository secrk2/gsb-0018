package cn.sfj.jiaowutong.domain;

/**
 * 请销假单状态（两级审批 + 退回重提）。
 * <pre>
 * 对象提交            PENDING_OFFICE（司法所待初审）
 * 司法所初审通过      PENDING_BUREAU（区局待复核）
 * 司法所退回          OFFICE_RETURNED ──对象修改重提──▶ PENDING_OFFICE
 * 区局复核通过        APPROVED ──对象销假──▶ COMPLETED
 * 区局退回            BUREAU_RETURNED ──对象修改重提──▶ PENDING_OFFICE（两级重新走）
 * 假期届满未销假      APPROVED ──定时扫描──▶ OVERDUE（同步转训诫+违规红点）
 * </pre>
 */
public enum LeaveStatus {
    PENDING_OFFICE("司法所待初审"),
    PENDING_BUREAU("区局待复核"),
    OFFICE_RETURNED("司法所退回"),
    BUREAU_RETURNED("区局退回"),
    APPROVED("已批准·假期中"),
    COMPLETED("已销假"),
    OVERDUE("逾假未归");

    private final String label;

    LeaveStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
