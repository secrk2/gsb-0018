package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 请销假单（两级审批）。
 * 对象手机端发起；司法所初审、区局复核；退回后可修改重提（复用同一条单据，
 * 全程动作写 {@link LeaveRequestLog} 留痕，不覆盖历史）；
 * 批准后档案进入「请假外出」，销假回到「在矫」；假期届满未销假由定时任务自动转「训诫」并报违规。
 *
 * <p>时间一律 UTC 存储；起止按对象所属司法所时区解释为整天/时刻，假期窗口为 [startAt, endAt)。</p>
 */
@Entity
@Table(name = "leave_request", indexes = {
        @Index(name = "idx_leave_offender", columnList = "offender_id,status"),
        @Index(name = "idx_leave_status_end", columnList = "status,end_at")
})
public class LeaveRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offender_id")
    private CorrectionObject offender;

    /** 请假目的地 */
    @Column(nullable = false, length = 128)
    private String destination;

    /** 请假事由 */
    @Column(nullable = false, length = 256)
    private String reason;

    /** 假期开始时刻（UTC，由前端按对象时区把日期时间转换） */
    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    /** 假期结束时刻（UTC；逾期扫描以此时刻为准，未销假且 now &gt; endAt 即逾假） */
    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeaveStatus status = LeaveStatus.PENDING_OFFICE;

    /** 本次审批轮次：提交=1，每次退回重提 +1，用于区分第几次申请内容 */
    @Column(nullable = false)
    private Integer revision = 1;

    /** 申请提交时间（最新一轮） */
    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    /** 初审通过时间 */
    @Column(name = "office_approved_at")
    private Instant officeApprovedAt;

    /** 复核通过时间 */
    @Column(name = "bureau_approved_at")
    private Instant bureauApprovedAt;

    /** 销假时间 */
    @Column(name = "returned_at")
    private Instant returnedAt;

    /** 销假备注 */
    @Column(name = "return_note", length = 256)
    private String returnNote;

    /** 逾期判定时刻（定时任务写入），非空表示已触发过自动违规，避免重复升违规 */
    @Column(name = "overdue_at")
    private Instant overdueAt;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public LeaveRequest() {
    }

    public Long getId() { return id; }
    public CorrectionObject getOffender() { return offender; }
    public String getDestination() { return destination; }
    public String getReason() { return reason; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public LeaveStatus getStatus() { return status; }
    public Integer getRevision() { return revision; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getOfficeApprovedAt() { return officeApprovedAt; }
    public Instant getBureauApprovedAt() { return bureauApprovedAt; }
    public Instant getReturnedAt() { return returnedAt; }
    public String getReturnNote() { return returnNote; }
    public Instant getOverdueAt() { return overdueAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setOffender(CorrectionObject offender) { this.offender = offender; }
    public void setDestination(String destination) { this.destination = destination; }
    public void setReason(String reason) { this.reason = reason; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public void setStatus(LeaveStatus status) { this.status = status; }
    public void setRevision(Integer revision) { this.revision = revision; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public void setOfficeApprovedAt(Instant officeApprovedAt) { this.officeApprovedAt = officeApprovedAt; }
    public void setBureauApprovedAt(Instant bureauApprovedAt) { this.bureauApprovedAt = bureauApprovedAt; }
    public void setReturnedAt(Instant returnedAt) { this.returnedAt = returnedAt; }
    public void setReturnNote(String returnNote) { this.returnNote = returnNote; }
    public void setOverdueAt(Instant overdueAt) { this.overdueAt = overdueAt; }
}
