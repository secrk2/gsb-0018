package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.MonthlyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MonthlyReportRepository extends JpaRepository<MonthlyReport, Long> {

    Optional<MonthlyReport> findByOffender_IdAndReportMonth(Long offenderId, LocalDate reportMonth);

    boolean existsByOffender_IdAndReportMonth(Long offenderId, LocalDate reportMonth);

    List<MonthlyReport> findByReportMonthAndOfficeIdOrderByCompletedAtAscIdAsc(LocalDate reportMonth, Long officeId);

    List<MonthlyReport> findByReportMonthOrderByCompletedAtAscIdAsc(LocalDate reportMonth);
}
