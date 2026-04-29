package com.navneet.executespec.readymade.repository;

import com.navneet.executespec.readymade.entity.ReadymadeFavourite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReadymadeFavouriteRepository
        extends JpaRepository<ReadymadeFavourite, ReadymadeFavourite.Id> {

    List<ReadymadeFavourite> findByUserId(String userId);

    boolean existsByUserIdAndSolutionId(String userId, UUID solutionId);

    void deleteByUserIdAndSolutionId(String userId, UUID solutionId);

    long countBySolutionId(UUID solutionId);
}
