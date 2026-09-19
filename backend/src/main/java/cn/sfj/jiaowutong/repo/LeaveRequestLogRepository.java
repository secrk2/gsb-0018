package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.LeaveRequestLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LeaveRequestLogRepository extends JpaRepository<LeaveRequestLog, Long> {

    List<LeaveRequestLog> findByLeaveIdOrderByIdAsc(Long leaveId);
}
