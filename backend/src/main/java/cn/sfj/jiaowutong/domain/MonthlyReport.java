package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 月度报到记录（干警当面/批量登记，区别于对象 APP 日常报到 {@link CheckIn}）。
 * 每月每对象至多一条有效记录（唯一约束），月度报到支持一次勾选多个对象批量完成，
 * 重复登记同对象同月由服务端识别为“已完成”并在批量结果中逐条说明。
 */
@Entity
@Table(name = "monthly_report", uniqueConstraints = {
        @UniqueConstraint(name = "uk_monthly_offender_month", columnNames = {"offender_id", "report_month"})
}, indexes = {
        @Index(name = "idx_monthly_month_office", columnList = "report_month,office_id")
})
public class MonthlyReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offender_id")
    private CorrectionObject offender;

    /** 归属月份（该月 1 日）；按对象所属司法所时区取当前月 */
    @Column(name = "report_month", nullable = false)
    private LocalDate reportMonth;

    @Column(name = "office_id", nullable = false)
    private Long officeId;

    @Column(nullable = false)
    private Instant completedAt;

    @Column(nullable = false)
    private Long operatorId;

    @Column(nullable = false, length = 64)
    private String operatorName;

    /** 登记方式：BATCH 批量勾选 / SINGLE 单个登记 */
    @Column(nullable = false, length = 16)
    private String method;

    @Column(length = 256)
    private String note;

    public MonthlyReport() {
    }

    public MonthlyReport(CorrectionObject offender, LocalDate reportMonth, Long operatorId,
                         String operatorName, String method, String note) {
        this.offender = offender;
        this.reportMonth = reportMonth;
        this.officeId = offender.getOffice().getId();
        this.operatorId = operatorId;
        this.operatorName = operatorName;
        this.method = method;
        this.note = note;
        this.completedAt = Instant.now();
    }

    public Long getId() { return id; }
    public CorrectionObject getOffender() { return offender; }
    public LocalDate getReportMonth() { return reportMonth; }
    public Long getOfficeId() { return officeId; }
    public Instant getCompletedAt() { return completedAt; }
    public Long getOperatorId() { return operatorId; }
    public String getOperatorName() { return operatorName; }
    public String getMethod() { return method; }
    public String getNote() { return note; }
}
