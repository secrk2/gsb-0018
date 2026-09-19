package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 公益活动（司法所发布，本所对象手机端可见、可报名、现场打卡）。
 * 现场打卡以活动点中心 + 半径做服务端距离校验：半径内为正常，半径外打卡标 ABNORMAL 异常（不删除，留痕）。
 */
@Entity
@Table(name = "public_activity", indexes = {
        @Index(name = "idx_activity_office", columnList = "office_id,start_at")
})
public class PublicActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String title;

    @Column(length = 512)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "office_id")
    private JudicialOffice office;

    /** 活动地点名称 */
    @Column(nullable = false, length = 128)
    private String locationName;

    /** 活动点中心（打卡校验圆心） */
    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    /** 允许打卡半径（米），超出即异常打卡 */
    @Column(name = "radius_meters", nullable = false)
    private Integer radiusMeters;

    /** 活动开始/结束时刻（UTC） */
    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    /** 报名截止（UTC），逾期不可再报名 */
    @Column(name = "signup_deadline", nullable = false)
    private Instant signupDeadline;

    /** 容量上限；null 表示不限 */
    private Integer capacity;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public PublicActivity() {
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public JudicialOffice getOffice() { return office; }
    public String getLocationName() { return locationName; }
    public Double getLat() { return lat; }
    public Double getLng() { return lng; }
    public Integer getRadiusMeters() { return radiusMeters; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public Instant getSignupDeadline() { return signupDeadline; }
    public Integer getCapacity() { return capacity; }
    public Boolean getEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }

    public void setTitle(String title) { this.title = title; }
    public void setDescription(String description) { this.description = description; }
    public void setOffice(JudicialOffice office) { this.office = office; }
    public void setLocationName(String locationName) { this.locationName = locationName; }
    public void setLat(Double lat) { this.lat = lat; }
    public void setLng(Double lng) { this.lng = lng; }
    public void setRadiusMeters(Integer radiusMeters) { this.radiusMeters = radiusMeters; }
    public void setStartAt(Instant startAt) { this.startAt = startAt; }
    public void setEndAt(Instant endAt) { this.endAt = endAt; }
    public void setSignupDeadline(Instant signupDeadline) { this.signupDeadline = signupDeadline; }
    public void setCapacity(Integer capacity) { this.capacity = capacity; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
}
