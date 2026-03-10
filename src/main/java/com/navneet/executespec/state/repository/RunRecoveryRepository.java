package com.navneet.executespec.state.repository;

import com.navneet.executespec.recovery.StuckClassification;
import com.navneet.executespec.state.entity.RunRecovery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RunRecoveryRepository extends JpaRepository<RunRecovery, String> {

    List<RunRecovery> findByOriginalRunIdOrRestartRunId(String originalRunId, String restartRunId);

    List<RunRecovery> findByOriginalRunId(String originalRunId);

    @Query("SELECT r FROM RunRecovery r WHERE r.classification IN :classifications " +
           "AND r.originalRunId IN :runIds")
    List<RunRecovery> findByClassificationIn(
        @Param("classifications") List<StuckClassification> classifications,
        @Param("runIds") List<String> runIds
    );
}
