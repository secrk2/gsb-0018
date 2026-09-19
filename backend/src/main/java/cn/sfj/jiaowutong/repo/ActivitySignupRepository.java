package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.ActivitySignup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ActivitySignupRepository extends JpaRepository<ActivitySignup, Long> {

    Optional<ActivitySignup> findByActivity_IdAndOffender_Id(Long activityId, Long offenderId);

    boolean existsByActivity_IdAndOffender_Id(Long activityId, Long offenderId);

    long countByActivity_Id(Long activityId);

    List<ActivitySignup> findByActivity_IdOrderBySignedAtAscIdAsc(Long activityId);

    List<ActivitySignup> findByOffender_IdOrderBySignedAtDescIdDesc(Long offenderId);
}
