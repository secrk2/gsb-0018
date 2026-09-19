package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.ActivityCheckIn;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ActivityCheckInRepository extends JpaRepository<ActivityCheckIn, Long> {

    Optional<ActivityCheckIn> findByActivity_IdAndOffender_Id(Long activityId, Long offenderId);

    List<ActivityCheckIn> findByActivity_IdOrderByCheckedAtAscIdAsc(Long activityId);

    List<ActivityCheckIn> findByOffender_IdOrderByCheckedAtDescIdDesc(Long offenderId);
}
