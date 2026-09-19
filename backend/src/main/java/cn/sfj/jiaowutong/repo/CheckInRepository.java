package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.CheckIn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface CheckInRepository extends JpaRepository<CheckIn, Long> {

    boolean existsByOffender_IdAndCheckDate(Long offenderId, LocalDate checkDate);

    List<CheckIn> findByOffender_IdAndCheckDate(Long offenderId, LocalDate checkDate);

    List<CheckIn> findByOffender_IdOrderByCheckDateAscIdAsc(Long offenderId);
}
