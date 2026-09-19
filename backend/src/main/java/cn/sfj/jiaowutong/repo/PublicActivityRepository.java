package cn.sfj.jiaowutong.repo;

import cn.sfj.jiaowutong.domain.PublicActivity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PublicActivityRepository extends JpaRepository<PublicActivity, Long> {

    List<PublicActivity> findByEnabledTrueOrderByStartAtAscIdAsc();

    List<PublicActivity> findByOffice_IdAndEnabledTrueOrderByStartAtAscIdAsc(Long officeId);

    List<PublicActivity> findAllByOrderByStartAtAscIdAsc();
}
