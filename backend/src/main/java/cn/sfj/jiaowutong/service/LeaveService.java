package cn.sfj.jiaowutong.service;

import cn.sfj.jiaowutong.common.ApiException;
import cn.sfj.jiaowutong.domain.*;
import cn.sfj.jiaowutong.repo.*;
import cn.sfj.jiaowutong.security.LoginUser;
import cn.sfj.jiaowutong.web.vo.LeaveView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请销假两级审批服务：
 * <pre>
 * 对象申请 → 司法所初审 → 区局复核 → 批准（档案转请假外出）→ 对象销假（回到在矫）
 *                  └ 任一级退回 → 对象修改后重提（同一单据 revision+1，逐轮留痕）
 * 批准假期届满仍未销假 → 定时任务自动转训诫并生成违规红点（OVERDUE）。
 * </pre>
 * 与定位联动：批准假期内的定位越界/禁区红点一律不产生；批准时把该对象尚未处置的
 * 越界/禁区红点核销——不能一边批着假一边还报着警。
 */
@Service
public class LeaveService {

    private static final Logger log = LoggerFactory.getLogger(LeaveService.class);

    /** 单次请假最长天数（合理性校验） */
    private static final long MAX_LEAVE_DAYS = 90;

    private final LeaveRequestRepository leaveRepository;
    private final LeaveRequestLogRepository logRepository;
    private final CorrectionObjectRepository objectRepository;
    private final StatusTransitionRepository transitionRepository;
    private final ViolationEventRepository violationRepository;
    private final AccessControlService accessControl;
    private final ObjectMapper objectMapper;

    public LeaveService(LeaveRequestRepository leaveRepository,
                        LeaveRequestLogRepository logRepository,
                        CorrectionObjectRepository objectRepository,
                        StatusTransitionRepository transitionRepository,
                        ViolationEventRepository violationRepository,
                        AccessControlService accessControl,
                        ObjectMapper objectMapper) {
        this.leaveRepository = leaveRepository;
        this.logRepository = logRepository;
        this.objectRepository = objectRepository;
        this.transitionRepository = transitionRepository;
        this.violationRepository = violationRepository;
        this.accessControl = accessControl;
        this.objectMapper = objectMapper;
    }

    // ---------------- 对象端：申请 / 重提 / 销假 ----------------

    @Transactional
    public LeaveView apply(String destination, String reason, Instant startAt, Instant endAt, LoginUser user) {
        CorrectionObject obj = requireSelfOffender(user);
        validateContent(obj, destination, reason, startAt, endAt);

        List<LeaveStatus> open = List.of(LeaveStatus.PENDING_OFFICE, LeaveStatus.PENDING_BUREAU,
                LeaveStatus.OFFICE_RETURNED, LeaveStatus.BUREAU_RETURNED, LeaveStatus.APPROVED);
        if (leaveRepository.existsByOffender_IdAndStatusIn(obj.getId(), open)) {
            throw ApiException.badRequest("LEAVE_ALREADY_OPEN",
                    "您已有一张在审批中或假期中的请假单，请等待审批结果、销假或按退回意见修改后重提，不能重复申请");
        }
        if (obj.getStatus() != CorrectionStatus.SERVING) {
            throw ApiException.badRequest("INVALID_STATUS",
                    "当前状态为「" + obj.getStatus().getLabel() + "」，仅「在矫」对象可以提交请假申请");
        }

        LeaveRequest lr = new LeaveRequest();
        lr.setOffender(obj);
        lr.setDestination(destination.trim());
        lr.setReason(reason.trim());
        lr.setStartAt(startAt);
        lr.setEndAt(endAt);
        lr.setStatus(LeaveStatus.PENDING_OFFICE);
        lr.setRevision(1);
        lr.setSubmittedAt(Instant.now());
        leaveRepository.save(lr);

        appendLog(lr, "SUBMIT", user.userId(), user.realName(), "对象提交请假申请", snapshot(lr));
        return view(lr);
    }

    /** 退回后修改重提：复用同一单据，revision+1，从司法所初审重新走两级。 */
    @Transactional
    public LeaveView resubmit(Long id, String destination, String reason, Instant startAt, Instant endAt,
                              LoginUser user) {
        CorrectionObject obj = requireSelfOffender(user);
        LeaveRequest lr = loadOwned(id, obj.getId());
        if (lr.getStatus() != LeaveStatus.OFFICE_RETURNED && lr.getStatus() != LeaveStatus.BUREAU_RETURNED) {
            throw ApiException.badRequest("LEAVE_NOT_RETURNED",
                    "当前单据状态为「" + lr.getStatus().getLabel() + "」，只有被退回的单据才能修改重提");
        }
        validateContent(obj, destination, reason, startAt, endAt);

        lr.setDestination(destination.trim());
        lr.setReason(reason.trim());
        lr.setStartAt(startAt);
        lr.setEndAt(endAt);
        lr.setRevision(lr.getRevision() + 1);
        lr.setStatus(LeaveStatus.PENDING_OFFICE);
        lr.setSubmittedAt(Instant.now());
        leaveRepository.save(lr);

        appendLog(lr, "RESUBMIT", user.userId(), user.realName(),
                "对象按退回意见修改后第 " + lr.getRevision() + " 次重新提交", snapshot(lr));
        return view(lr);
    }

    /** 销假：对象本人（或本所干警代登记）；批准状态才能销，回到在矫。 */
    @Transactional
    public LeaveView returnFromLeave(Long id, String note, LoginUser user) {
        LeaveRequest lr = leaveRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("请假单不存在"));
        CorrectionObject obj = lr.getOffender();
        if (user.role() == Role.OFFENDER) {
            if (user.offenderId() == null || !user.offenderId().equals(obj.getId())) {
                throw ApiException.forbidden("只能为本人请假单办理销假");
            }
        } else {
            accessControl.assertCanView(obj, user);
        }
        if (lr.getStatus() != LeaveStatus.APPROVED) {
            throw ApiException.badRequest("LEAVE_NOT_APPROVED",
                    "单据状态为「" + lr.getStatus().getLabel() + "」，只有已批准、假期中的单据可以销假");
        }

        Instant now = Instant.now();
        lr.setStatus(LeaveStatus.COMPLETED);
        lr.setReturnedAt(now);
        lr.setReturnNote(note);
        leaveRepository.save(lr);

        // 档案状态联动：请假外出 → 在矫（状态机校验），并写状态流转留痕
        CorrectionStateMachine.assertTransition(obj.getStatus(), CorrectionStatus.SERVING);
        obj.setStatus(CorrectionStatus.SERVING);
        objectRepository.save(obj);
        transitionRepository.save(new StatusTransition(obj.getId(),
                CorrectionStatus.LEAVE, CorrectionStatus.SERVING,
                user.userId(), user.realName(),
                "销假返所" + (note == null || note.isBlank() ? "" : "：" + note)));

        appendLog(lr, "RETURN", user.userId(), user.realName(),
                "对象按期销假返所" + (note == null || note.isBlank() ? "" : "：" + note), null);
        return view(lr);
    }

    // ---------------- 司法所初审 / 区局复核 ----------------

    @Transactional
    public LeaveView officeReview(Long id, boolean approve, String comment, LoginUser user) {
        if (user.role() != Role.STAFF) {
            throw ApiException.forbidden("请假初审须由对象所属司法所干警办理");
        }
        LeaveRequest lr = leaveRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("请假单不存在"));
        CorrectionObject obj = lr.getOffender();
        if (!user.officeId().equals(obj.getOffice().getId())) {
            throw ApiException.forbidden("该请假单归属「" + obj.getOffice().getName() + "」，不由您所在司法所初审");
        }
        if (lr.getStatus() != LeaveStatus.PENDING_OFFICE) {
            throw ApiException.badRequest("LEAVE_NOT_PENDING_OFFICE",
                    "单据当前为「" + lr.getStatus().getLabel() + "」，不在司法所待初审环节，不能重复审批");
        }

        if (approve) {
            lr.setStatus(LeaveStatus.PENDING_BUREAU);
            lr.setOfficeApprovedAt(Instant.now());
            leaveRepository.save(lr);
            appendLog(lr, "OFFICE_APPROVE", user.userId(), user.realName(),
                    blankToDefault(comment, "司法所初审同意，报区局复核"), null);
        } else {
            requireComment(comment);
            lr.setStatus(LeaveStatus.OFFICE_RETURNED);
            leaveRepository.save(lr);
            appendLog(lr, "OFFICE_RETURN", user.userId(), user.realName(),
                    "司法所退回：" + comment.trim(), null);
        }
        return view(lr);
    }

    @Transactional
    public LeaveView bureauReview(Long id, boolean approve, String comment, LoginUser user) {
        if (user.role() != Role.SUPERVISOR) {
            throw ApiException.forbidden("请假复核由区司法局监管员办理，司法所账号无复核权限");
        }
        LeaveRequest lr = leaveRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("请假单不存在"));
        CorrectionObject obj = lr.getOffender();
        if (lr.getStatus() != LeaveStatus.PENDING_BUREAU) {
            throw ApiException.badRequest("LEAVE_NOT_PENDING_BUREAU",
                    "单据当前为「" + lr.getStatus().getLabel() + "」，不在区局待复核环节，不能重复审批");
        }

        if (approve) {
            // 批准前档案必须仍在“在矫”（审批期间档案发生训诫/收监等变化的不得准假）
            if (obj.getStatus() != CorrectionStatus.SERVING) {
                throw ApiException.badRequest("INVALID_STATUS",
                        "审批期间对象档案已变更为「" + obj.getStatus().getLabel()
                                + "」，不符合准假条件，请退回单据");
            }
            Instant now = Instant.now();
            lr.setStatus(LeaveStatus.APPROVED);
            lr.setBureauApprovedAt(now);
            leaveRepository.save(lr);

            obj.setStatus(CorrectionStatus.LEAVE);
            objectRepository.save(obj);
            transitionRepository.save(new StatusTransition(obj.getId(),
                    CorrectionStatus.SERVING, CorrectionStatus.LEAVE,
                    user.userId(), user.realName(),
                    "请销假两级审批通过（司法所初审+区局复核），假期至 " + lr.getEndAt()));

            // 联动：批假即消警——假期内不再判越界/禁区，先把未处置的同类红点核销，
            // 避免“一边批着假一边报着警”。
            int resolved = resolveOpenFenceAlerts(obj);

            appendLog(lr, "BUREAU_APPROVE", user.userId(), user.realName(),
                    blankToDefault(comment, "区局复核同意，予以准假")
                            + (resolved > 0 ? "；联动核销假期前未处置越界/禁区红点 " + resolved + " 条" : ""), null);
        } else {
            requireComment(comment);
            lr.setStatus(LeaveStatus.BUREAU_RETURNED);
            leaveRepository.save(lr);
            appendLog(lr, "BUREAU_RETURN", user.userId(), user.realName(),
                    "区局退回：" + comment.trim(), null);
        }
        return view(lr);
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<LeaveView> queue(String stage, LoginUser user) {
        List<LeaveRequest> list;
        if ("OFFICE".equals(stage)) {
            list = leaveRepository.findByStatusOrderBySubmittedAtAscIdAsc(LeaveStatus.PENDING_OFFICE);
        } else if ("BUREAU".equals(stage)) {
            list = leaveRepository.findByStatusOrderBySubmittedAtAscIdAsc(LeaveStatus.PENDING_BUREAU);
        } else if ("RETURNED".equals(stage)) {
            list = leaveRepository.findByStatusInOrderBySubmittedAtAscIdAsc(
                    List.of(LeaveStatus.OFFICE_RETURNED, LeaveStatus.BUREAU_RETURNED));
        } else if ("ACTIVE".equals(stage)) {
            list = leaveRepository.findByStatusInOrderBySubmittedAtAscIdAsc(
                    List.of(LeaveStatus.APPROVED, LeaveStatus.OVERDUE));
        } else {
            list = leaveRepository.findAll();
        }
        return list.stream()
                .filter(lr -> canView(lr.getOffender(), user))
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id, LoginUser user) {
        LeaveRequest lr = leaveRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("请假单不存在"));
        CorrectionObject obj = lr.getOffender();
        if (user.role() == Role.OFFENDER) {
            if (user.offenderId() == null || !user.offenderId().equals(obj.getId())) {
                throw ApiException.forbidden("只能查看本人请假单");
            }
        } else {
            accessControl.assertCanView(obj, user);
        }
        List<Map<String, Object>> logs = logRepository.findByLeaveIdOrderByIdAsc(id).stream()
                .map(l -> Map.<String, Object>of(
                        "action", l.getAction(),
                        "actionLabel", actionLabel(l.getAction()),
                        "resultStatus", l.getResultStatus(),
                        "revision", l.getRevision(),
                        "operatorName", l.getOperatorName(),
                        "comment", l.getComment() == null ? "" : l.getComment(),
                        "contentSnapshot", l.getContentSnapshot() == null ? "" : l.getContentSnapshot(),
                        "createdAt", l.getCreatedAt()))
                .toList();
        return Map.of("leave", view(lr), "logs", logs);
    }

    @Transactional(readOnly = true)
    public List<LeaveView> mine(LoginUser user) {
        CorrectionObject obj = requireSelfOffender(user);
        return leaveRepository.findByOffender_IdOrderByCreatedAtDescIdDesc(obj.getId()).stream()
                .map(this::view).toList();
    }

    // ---------------- 逾期自动处置（定时扫描） ----------------

    /**
     * 扫描并处置：已批准且 endAt 已过仍未销假的单据，自动转「训诫」并生成违规红点。
     * 幂等：overdueAt 非空的不再处理；单据置 OVERDUE 后定位越界报警自动恢复。
     * 由 {@link LeaveOverdueScheduler} 每 60 秒触发；测试中可直接调用本方法验证。
     */
    @Transactional
    public int processOverdue(Instant now) {
        List<LeaveRequest> overdue =
                leaveRepository.findByStatusAndEndAtBeforeAndOverdueAtIsNull(LeaveStatus.APPROVED, now);
        for (LeaveRequest lr : overdue) {
            CorrectionObject obj = lr.getOffender();
            lr.setStatus(LeaveStatus.OVERDUE);
            lr.setOverdueAt(now);
            leaveRepository.save(lr);

            if (obj.getStatus() == CorrectionStatus.LEAVE) {
                CorrectionStateMachine.assertTransition(CorrectionStatus.LEAVE, CorrectionStatus.ADMONISHED);
                obj.setStatus(CorrectionStatus.ADMONISHED);
                objectRepository.save(obj);
                transitionRepository.save(new StatusTransition(obj.getId(),
                        CorrectionStatus.LEAVE, CorrectionStatus.ADMONISHED,
                        0L, "系统（逾假未归自动处置）",
                        "假期于 " + lr.getEndAt() + " 届满未销假，系统自动升为训诫"));
            }
            violationRepository.save(new ViolationEvent(obj, "LEAVE_OVERDUE",
                    "对象 " + obj.getMaskedName() + " 请假假期已于 " + lr.getEndAt()
                            + " 届满，至今未销假返所，系统自动登记为逾假未归违规并转训诫", now));
            appendLog(lr, "OVERDUE", 0L, "系统（定时任务）",
                    "假期届满未销假，自动升为违规并转训诫", null);
            log.warn("对象 {} 逾假未归，请假单 #{} 自动转训诫", obj.getMaskedName(), lr.getId());
        }
        return overdue.size();
    }

    // ---------------- 与定位联动的判定 ----------------

    /**
     * 该对象在指定 UTC 时刻是否处于“已批准假期窗口”内（[startAt, endAt)）。
     * TrackService 据此抑制假期内的越界/禁区红点；逾期（OVERDUE）后窗口关闭，报警自动恢复。
     */
    @Transactional(readOnly = true)
    public boolean withinApprovedLeave(Long offenderId, Instant at) {
        if (at == null) {
            return false;
        }
        return leaveRepository.findByOffender_IdAndStatus(offenderId, LeaveStatus.APPROVED).stream()
                .anyMatch(lr -> !at.isBefore(lr.getStartAt()) && at.isBefore(lr.getEndAt()));
    }

    // ---------------- 私有辅助 ----------------

    private int resolveOpenFenceAlerts(CorrectionObject obj) {
        List<ViolationEvent> open = violationRepository
                .findByOffender_IdAndReadFlagFalse(obj.getId()).stream()
                .filter(v -> "GEOFENCE_BREACH".equals(v.getType()) || "FORBIDDEN_ZONE".equals(v.getType()))
                .toList();
        for (ViolationEvent v : open) {
            v.setReadFlag(true);
            violationRepository.save(v);
        }
        return open.size();
    }

    private boolean canView(CorrectionObject obj, LoginUser user) {
        if (user.role() == Role.SUPERVISOR) return true;
        if (user.role() == Role.STAFF) return user.officeId().equals(obj.getOffice().getId());
        return user.offenderId() != null && user.offenderId().equals(obj.getId());
    }

    private CorrectionObject requireSelfOffender(LoginUser user) {
        if (user.role() != Role.OFFENDER || user.offenderId() == null) {
            throw ApiException.forbidden("仅矫正对象本人账号可办理请假/销假");
        }
        return objectRepository.findById(user.offenderId())
                .orElseThrow(() -> ApiException.notFound("本人档案不存在"));
    }

    private LeaveRequest loadOwned(Long id, Long offenderId) {
        LeaveRequest lr = leaveRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("请假单不存在"));
        if (!lr.getOffender().getId().equals(offenderId)) {
            throw ApiException.forbidden("只能操作本人请假单");
        }
        return lr;
    }

    private void validateContent(CorrectionObject obj, String destination, String reason,
                                 Instant startAt, Instant endAt) {
        if (destination == null || destination.trim().length() < 2) {
            throw ApiException.badRequest("INVALID_LEAVE", "请假目的地至少 2 个字");
        }
        if (reason == null || reason.trim().length() < 4) {
            throw ApiException.badRequest("INVALID_LEAVE", "请假事由不少于 4 个字");
        }
        if (startAt == null || endAt == null) {
            throw ApiException.badRequest("INVALID_LEAVE", "缺少假期起止时间");
        }
        if (!endAt.isAfter(startAt)) {
            throw ApiException.badRequest("INVALID_LEAVE", "假期结束时间必须晚于开始时间");
        }
        if (startAt.isBefore(Instant.now().minusSeconds(3600))) {
            throw ApiException.badRequest("INVALID_LEAVE", "假期开始时间不能早于当前时间（请假须事先申请，不得事后补假）");
        }
        if (Instant.now().plusSeconds(MAX_LEAVE_DAYS * 86400L).isBefore(endAt)) {
            throw ApiException.badRequest("INVALID_LEAVE", "单次请假时长不得超过 " + MAX_LEAVE_DAYS + " 天");
        }
    }

    private void requireComment(String comment) {
        if (comment == null || comment.trim().length() < 4) {
            throw ApiException.badRequest("REVIEW_COMMENT_REQUIRED",
                    "退回必须填写不少于 4 个字的退回意见，供对象修改重提时对照");
        }
    }

    private String blankToDefault(String s, String dflt) {
        return s == null || s.trim().isEmpty() ? dflt : s.trim();
    }

    private void appendLog(LeaveRequest lr, String action, Long operatorId,
                           String operatorName, String comment, String snapshot) {
        logRepository.save(new LeaveRequestLog(lr.getId(), action, lr.getStatus(), lr.getRevision(),
                operatorId, operatorName, comment, snapshot));
    }

    private String snapshot(LeaveRequest lr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("destination", lr.getDestination());
        m.put("reason", lr.getReason());
        m.put("startAt", lr.getStartAt());
        m.put("endAt", lr.getEndAt());
        try {
            return objectMapper.writeValueAsString(m);
        } catch (Exception e) {
            return null;
        }
    }

    private LeaveView view(LeaveRequest lr) {
        CorrectionObject o = lr.getOffender();
        return new LeaveView(lr.getId(), o.getId(), o.getCorrectionNo(), o.getMaskedName(),
                o.getOffice().getId(), o.getOffice().getName(), o.getOffice().getTimezone(),
                lr.getDestination(), lr.getReason(), lr.getStartAt(), lr.getEndAt(),
                lr.getStatus().name(), lr.getStatus().getLabel(), lr.getRevision(),
                lr.getSubmittedAt(), lr.getOfficeApprovedAt(), lr.getBureauApprovedAt(),
                lr.getReturnedAt(), lr.getReturnNote(), lr.getOverdueAt(), lr.getCreatedAt());
    }

    public static String actionLabel(String action) {
        return switch (action) {
            case "SUBMIT" -> "提交申请";
            case "RESUBMIT" -> "退回重提";
            case "OFFICE_APPROVE" -> "司法所初审通过";
            case "OFFICE_RETURN" -> "司法所退回";
            case "BUREAU_APPROVE" -> "区局复核通过";
            case "BUREAU_RETURN" -> "区局退回";
            case "RETURN" -> "销假返所";
            case "OVERDUE" -> "逾假未归·自动处置";
            default -> action;
        };
    }
}
