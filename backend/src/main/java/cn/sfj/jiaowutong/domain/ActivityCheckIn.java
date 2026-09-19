package cn.sfj.jiaowutong.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * 公益活动现场打卡。
 * 服务端用活动点中心+半径重新计算距离：IN_RANGE 正常；OUT_OF_RANGE 异常（定位不在活动点范围内，
 * 记录实际距离与坐标，不采信为到场，但留痕供司法所核查）。
 * 报名后才能打卡；同一活动同一对象仅首次打卡生效（幂等，重复打卡返回首条记录）。
 */
@Entity
@Table(name = "activity_check_in", indexes = {
        @Index(name = "idx_actcheck_activity", columnList = "activity_id"),
        @Index(name = "idx_actcheck_offender", columnList = "offender_id")
})
public class ActivityCheckIn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "activity_id")
    private PublicActivity activity;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "offender_id")
    private CorrectionObject offender;

    /** NORMAL 正常 / ABNORMAL 位置异常（不在活动点范围） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Result result;

    /** 打卡定位时间（UTC，受时效校验） */
    @Column(name = "fix_time", nullable = false)
    private Instant fixTime;

    @Column(nullable = false)
    private Instant checkedAt;

    @Column(nullable = false)
    private Double lat;

    @Column(nullable = false)
    private Double lng;

    /** 打卡点到活动点中心距离（米），服务端测算 */
    @Column(name = "distance_meters", nullable = false)
    private Double distanceMeters;

    public enum Result { NORMAL, ABNORMAL }

    public ActivityCheckIn() {
    }

    public ActivityCheckIn(PublicActivity activity, CorrectionObject offender, Result result,
                           Instant fixTime, Double lat, Double lng, Double distanceMeters) {
        this.activity = activity;
        this.offender = offender;
        this.result = result;
        this.fixTime = fixTime;
        this.lat = lat;
        this.lng = lng;
        this.distanceMeters = distanceMeters;
        this.checkedAt = Instant.now();
    }

    public Long getId() { return id; }
    public PublicActivity getActivity() { return activity; }
    public CorrectionObject getOffender() { return offender; }
    public Result getResult() { return result; }
    public Instant getFixTime() { return fixTime; }
    public Instant getCheckedAt() { return checkedAt; }
    public Double getLat() { return lat; }
    public Double getLng() { return lng; }
    public Double getDistanceMeters() { return distanceMeters; }
}
