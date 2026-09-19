package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 公益活动报名记录。对象手机端报名，幂等：同一活动同一对象仅一条（唯一约束）。
 */
@Entity
@Table(name = "activity_signup", uniqueConstraints = {
        @UniqueConstraint(name = "uk_signup_activity_offender",
                columnNames = {"activity_id", "offender_id"})
}, indexes = {
        @Index(name = "idx_signup_activity", columnList = "activity_id"),
        @Index(name = "idx_signup_offender", columnList = "offender_id")
})
public class ActivitySignup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id")
    private PublicActivity activity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offender_id")
    private CorrectionObject offender;

    @Column(nullable = false)
    private Instant signedAt;

    /** 报名备注（对象可填，可为空） */
    @Column(length = 256)
    private String note;

    public ActivitySignup() {
    }

    public ActivitySignup(PublicActivity activity, CorrectionObject offender, String note) {
        this.activity = activity;
        this.offender = offender;
        this.note = note;
        this.signedAt = Instant.now();
    }

    public Long getId() { return id; }
    public PublicActivity getActivity() { return activity; }
    public CorrectionObject getOffender() { return offender; }
    public Instant getSignedAt() { return signedAt; }
    public String getNote() { return note; }
}
