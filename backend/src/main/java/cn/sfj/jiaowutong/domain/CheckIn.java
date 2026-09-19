package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.Instant;

/**
 * 日常报到记录。
 */
@Entity
@Table(name = "check_in", indexes = {
        @Index(name = "idx_checkin_offender_day", columnList = "offender_id,check_date")
})
public class CheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offender_id")
    private CorrectionObject offender;

    @Column(nullable = false)
    private LocalDate checkDate;

    @Column(nullable = false)
    private Instant checkedAt;

    /** 报到方式：APP / 当面 */
    @Column(nullable = false, length = 16)
    private String method;

    private Double lat;
    private Double lng;

    /** 报到时是否在围栏内 */
    @Column(nullable = false)
    private Boolean insideFence;

    public CheckIn() {
    }

    public CheckIn(CorrectionObject offender, LocalDate checkDate, Instant checkedAt,
                   String method, Double lat, Double lng, Boolean insideFence) {
        this.offender = offender;
        this.checkDate = checkDate;
        this.checkedAt = checkedAt;
        this.method = method;
        this.lat = lat;
        this.lng = lng;
        this.insideFence = insideFence;
    }

    public Long getId() { return id; }
    public CorrectionObject getOffender() { return offender; }
    public LocalDate getCheckDate() { return checkDate; }
    public Instant getCheckedAt() { return checkedAt; }
    public String getMethod() { return method; }
    public Double getLat() { return lat; }
    public Double getLng() { return lng; }
    public Boolean getInsideFence() { return insideFence; }
}
