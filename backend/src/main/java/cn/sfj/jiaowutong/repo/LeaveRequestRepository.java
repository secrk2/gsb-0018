package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.LeaveRequest;
import cn.sfj.jiaowutong.domain.LeaveStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long> {

    List<LeaveRequest> findByOffender_IdOrderByCreatedAtDescIdDesc(Long offenderId);

    /** 该对象是否存在指定状态集合中的单据（用于拦截重复申请） */
    boolean existsByOffender_IdAndStatusIn(Long offenderId, List<LeaveStatus> statuses);

    /** 该对象处于指定状态的全部单据（定位联动判定假期窗口） */
    List<LeaveRequest> findByOffender_IdAndStatus(Long offenderId, LeaveStatus status);

    /** 审批工作台：按状态列出（监管员看全区，干警由服务层按所过滤） */
    List<LeaveRequest> findByStatusOrderBySubmittedAtAscIdAsc(LeaveStatus status);

    List<LeaveRequest> findByStatusInOrderBySubmittedAtAscIdAsc(List<LeaveStatus> statuses);

    /** 已批准且假期结束仍未销假（overdueAt 为空表示尚未被定时任务处理） */
    List<LeaveRequest> findByStatusAndEndAtBeforeAndOverdueAtIsNull(LeaveStatus status, java.time.Instant at);

    /** 对象最新一条单据 */
    Optional<LeaveRequest> findFirstByOffender_IdOrderByCreatedAtDescIdDesc(Long offenderId);
}
