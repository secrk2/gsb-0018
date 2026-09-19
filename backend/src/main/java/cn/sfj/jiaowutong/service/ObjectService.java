package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.*;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.web.vo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;

@Service
public class ObjectService {

    private final CorrectionObjectRepository objectRepository;
    private final StatusTransitionRepository transitionRepository;
    private final NameViewAuditRepository nameAuditRepository;
    private final ViolationEventRepository violationRepository;
    private final TrackPointRepository trackPointRepository;
    private final CheckInRepository checkInRepository;
    private final LeaveRequestRepository leaveRequestRepository;
    private final LeaveRequestLogRepository leaveLogRepository;
    private final AccessControlService accessControl;

    public ObjectService(CorrectionObjectRepository objectRepository,
                         StatusTransitionRepository transitionRepository,
                         NameViewAuditRepository nameAuditRepository,
                         ViolationEventRepository violationRepository,
                         TrackPointRepository trackPointRepository,
                         CheckInRepository checkInRepository,
                         LeaveRequestRepository leaveRequestRepository,
                         LeaveRequestLogRepository leaveLogRepository,
                         AccessControlService accessControl) {
        this.objectRepository = objectRepository;
        this.transitionRepository = transitionRepository;
        this.nameAuditRepository = nameAuditRepository;
        this.violationRepository = violationRepository;
        this.trackPointRepository = trackPointRepository;
        this.checkInRepository = checkInRepository;
        this.leaveRequestRepository = leaveRequestRepository;
        this.leaveLogRepository = leaveLogRepository;
        this.accessControl = accessControl;
    }

    @Transactional(readOnly = true)
    public List<ObjectView> list(CorrectionStatus status, Long officeId, LoginUser user) {
        List<CorrectionObject> all = objectRepository.findAll();
        return accessControl.filterByScope(all, user).stream()
                .filter(o -> status == null || o.getStatus() == status)
                .filter(o -> officeId == null || officeId.equals(o.getOffice().getId()))
                .sorted(Comparator.comparing(CorrectionObject::getCorrectionNo))
                .map(o -> ObjectView.of(o, isSelfOffender(o, user)))
                .toList();
    }

    private boolean isSelfOffender(CorrectionObject o, LoginUser user) {
        return user.role() == Role.OFFENDER && o.getId().equals(user.offenderId());
    }

    @Transactional(readOnly = true)
    public ObjectDetailView detail(Long id, LoginUser user) {
        CorrectionObject o = accessControl.loadVisible(id, user);
        boolean self = isSelfOffender(o, user);
        ObjectView base = ObjectView.of(o, self);

        List<TransitionView> transitions = transitionRepository
                .findByOffenderIdOrderByOperatedAtDescIdDesc(id).stream()
                .limit(20)
                .map(t -> new TransitionView(
                        t.getFromStatus(),
                        t.getFromStatus() == null ? "—" : labelOf(t.getFromStatus()),
                        t.getToStatus(), labelOf(t.getToStatus()),
                        t.getReason(), t.getOperatorName(), t.getOperatedAt()))
                .toList();

        List<DashboardView.RedDotItem> violations = violationRepository
                .findTop20ByOffender_IdOrderByEventTimeDesc(id).stream()
                .map(v -> toRedDot(v, o)).toList();

        // “今日”按对象所在司法所时区
        ZoneId zone = FenceService.safeZone(o.getOffice().getTimezone());
        LocalDate localToday = Instant.now().atZone(zone).toLocalDate();
        boolean checkedToday = checkInRepository.existsByOffender_IdAndCheckDate(id, localToday);
        long trackCount = trackPointRepository
                .countByOffender_IdAndResult(id, TrackPoint.IngestResult.ACCEPTED);

        return new ObjectDetailView(base, transitions, violations, checkedToday, trackCount);
    }

    /** 二次确认 + 必填理由后返回全名，并落审计留痕 */
    @Transactional
    public String revealFullName(Long id, String reason, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        CorrectionObject o = accessControl.loadVisible(id, user);
        nameAuditRepository.save(new NameViewAudit(id, user.userId(), user.realName(), reason));
        return o.getFullName();
    }

    @Transactional(readOnly = true)
    public List<NameAuditView> nameAudits(Long id, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        accessControl.loadVisible(id, user);
        return nameAuditRepository.findByOffenderIdOrderByViewedAtDescIdDesc(id).stream()
                .map(a -> new NameAuditView(a.getViewerName(), a.getReason(), a.getViewedAt()))
                .toList();
    }

    /** 状态机流转；非法回退由状态机抛 INVALID_TRANSITION 说明原因 */
    @Transactional
    public TransitionView transition(Long id, CorrectionStatus target, String reason, LoginUser user) {
        accessControl.assertStaffOrSupervisor(user);
        CorrectionObject o = accessControl.loadVisible(id, user);
        CorrectionStatus from = o.getStatus();

        // 进入/离开“请假外出”必须走请销假两级审批流程，不能用通用状态按钮直接切换，
        // 否则会绕过司法所初审 + 区局复核以及定位联动（假期消警/逾期升级）
        if (target == CorrectionStatus.LEAVE) {
            throw ApiException.badRequest("LEAVE_FLOW_REQUIRED",
                    "进入「请假外出」必须通过请销假模块：由对象手机端发起申请，经司法所初审、区局复核通过后自动生效，不能手工直接切换状态");
        }

        CorrectionStateMachine.assertTransition(from, target);

        o.setStatus(target);
        objectRepository.save(o);
        transitionRepository.save(new StatusTransition(
                id, from, target, user.userId(), user.realName(), reason));

        // 手工“销假返所/逾假训诫”时联动关闭仍在假期中的请假单，避免单据与档案状态长期不一致
        if (from == CorrectionStatus.LEAVE) {
            leaveRequestRepository
                    .findByOffender_IdAndStatus(id, cn.sfj.jiaowutong.domain.LeaveStatus.APPROVED)
                    .forEach(lr -> {
                        if (target == CorrectionStatus.SERVING) {
                            lr.setStatus(cn.sfj.jiaowutong.domain.LeaveStatus.COMPLETED);
                            lr.setReturnedAt(java.time.Instant.now());
                            lr.setReturnNote("干警在档案中直接办理销假返所：" + reason);
                            leaveRequestRepository.save(lr);
                            leaveLogRepository.save(new LeaveRequestLog(lr.getId(), "RETURN",
                                    cn.sfj.jiaowutong.domain.LeaveStatus.COMPLETED, lr.getRevision(),
                                    user.userId(), user.realName(), "干警在档案中直接办理销假返所", null));
                        } else if (target == CorrectionStatus.ADMONISHED) {
                            lr.setStatus(cn.sfj.jiaowutong.domain.LeaveStatus.OVERDUE);
                            lr.setOverdueAt(java.time.Instant.now());
                            leaveRequestRepository.save(lr);
                            leaveLogRepository.save(new LeaveRequestLog(lr.getId(), "OVERDUE",
                                    cn.sfj.jiaowutong.domain.LeaveStatus.OVERDUE, lr.getRevision(),
                                    user.userId(), user.realName(),
                                    "干警在档案中直接以逾假未归予以训诫：" + reason, null));
                        }
                    });
        }

        // 训诫本身是处置措施，同步生成一条违规处置红点
        if (target == CorrectionStatus.ADMONISHED) {
            violationRepository.save(new ViolationEvent(o, "ADMONISH",
                    "对象 " + o.getMaskedName() + " 因违规被训诫" + (reason == null || reason.isBlank() ? "" : "：" + reason),
                    Instant.now()));
        }

        return new TransitionView(from.name(), from.getLabel(),
                target.name(), target.getLabel(), reason, user.realName(), Instant.now());
    }

    /** 轨迹回放：仅 ACCEPTED 点；漂移丢弃点不入轨迹，重复补传点本就不入库 */
    @Transactional(readOnly = true)
    public List<TrackView> tracks(Long id, LoginUser user) {
        CorrectionObject o = accessControl.loadVisible(id, user);
        return trackPointRepository
                .findByOffender_IdAndResultOrderByPointTimeAscIdAsc(id, TrackPoint.IngestResult.ACCEPTED)
                .stream()
                .map(t -> new TrackView(t.getClientPointId(), t.getPointTime(), t.getLat(), t.getLng(),
                        t.getOfflineCaptured(), t.getReceivedAt(), t.getOutsideFence(),
                        Boolean.TRUE.equals(t.getForbiddenZone()), t.getResult().name(),
                        t.getBattery(), t.getSignal(), t.getWorn()))
                .toList();
    }

    static String labelOf(String statusName) {
        try {
            return CorrectionStatus.valueOf(statusName).getLabel();
        } catch (Exception e) {
            return statusName;
        }
    }

    static DashboardView.RedDotItem toRedDot(ViolationEvent v, CorrectionObject o) {
        return new DashboardView.RedDotItem(
                v.getId(), o.getId(), o.getCorrectionNo(), o.getMaskedName(),
                o.getOffice().getName(), o.getOffice().getTimezone(),
                v.getType(), typeLabel(v.getType()),
                v.getDetail(), v.getEventTime(), v.getReadFlag());
    }

    static String typeLabel(String type) {
        return switch (type) {
            case "GEOFENCE_BREACH" -> "越界";
            case "FORBIDDEN_ZONE" -> "禁区闯入";
            case "ABSENT" -> "未按日报到";
            case "ADMONISH" -> "训诫";
            case "LEAVE_OVERDUE" -> "逾假未归";
            default -> type;
        };
    }
}
