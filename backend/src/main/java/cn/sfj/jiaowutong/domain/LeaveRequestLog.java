package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 请销假单据流转留痕：提交、初审通过/退回、复核通过/退回、重提、销假、逾期自动处置全部追加一条，
 * 只增不改——即使单据被退回重提，前一轮的申请内容与审批意见仍可逐轮回看。
 */
@Entity
@Table(name = "leave_request_log", indexes = {
        @Index(name = "idx_leave_log", columnList = "leave_id,id")
})
public class LeaveRequestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "leave_id", nullable = false)
    private Long leaveId;

    /**
     * 动作类型：
     * SUBMIT 提交 / RESUBMIT 退回重提 / OFFICE_APPROVE 初审通过 /
     * OFFICE_RETURN 司法所退回 / BUREAU_APPROVE 复核通过 / BUREAU_RETURN 区局退回 /
     * RETURN 销假 / OVERDUE 逾假未归自动处置。
     */
    @Column(nullable = false, length = 24)
    private String action;

    /** 动作发生时单据所处状态（动作后的新状态） */
    @Column(name = "result_status", nullable = false, length = 20)
    private String resultStatus;

    /** 第几轮审批（与 LeaveRequest.revision 对齐） */
    @Column(nullable = false)
    private Integer revision;

    @Column(nullable = false)
    private Long operatorId;

    @Column(nullable = false, length = 64)
    private String operatorName;

    /** 审批意见/退回原因/销假备注/申请内容快照说明 */
    @Column(length = 512)
    private String comment;

    /** 本轮申请内容快照（JSON：目的地/事由/起止），重提后仍可还原每轮申请 */
    @Column(name = "content_snapshot", length = 1024)
    private String contentSnapshot;

    @Column(nullable = false)
    private Instant createdAt;

    public LeaveRequestLog() {
    }

    public LeaveRequestLog(Long leaveId, String action, LeaveStatus resultStatus, Integer revision,
                           Long operatorId, String operatorName, String comment, String contentSnapshot) {
        this.leaveId = leaveId;
        this.action = action;
        this.resultStatus = resultStatus.name();
        this.revision = revision;
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.comment = comment;
        this.contentSnapshot = contentSnapshot;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getLeaveId() { return leaveId; }
    public String getAction() { return action; }
    public String getResultStatus() { return resultStatus; }
    public Integer getRevision() { return revision; }
    public Long getOperatorId() { return operatorId; }
    public String getOperatorName() { return operatorName; }
    public String getComment() { return comment; }
    public String getContentSnapshot() { return contentSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
}
