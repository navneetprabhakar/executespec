package com.navneet.executespec.readymade.repository;

import com.navneet.executespec.readymade.entity.ReadymadeFavourite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface ReadymadeFavouriteRepository
        extends JpaRepository<ReadymadeFavourite, ReadymadeFavourite.Id> {

    List<ReadymadeFavourite> findByUserId(String userId);

    boolean existsByUserIdAndSolutionId(String userId, UUID solutionId);

    /**
     * Spring Data derived-name {@code deleteBy*} queries require an active
     * transaction.  The favourite/unfavourite path is invoked directly from
     * {@code ReadymadeController} (no service layer between), so the method
     * itself carries the {@code @Transactional} boundary.
     */
    @Transactional
    void deleteByUserIdAndSolutionId(String userId, UUID solutionId);

    long countBySolutionId(UUID solutionId);
}
