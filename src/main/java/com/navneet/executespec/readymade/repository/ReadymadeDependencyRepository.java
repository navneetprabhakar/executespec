package com.navneet.executespec.readymade.repository;

import com.navneet.executespec.readymade.entity.ReadymadeDependency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReadymadeDependencyRepository
        extends JpaRepository<ReadymadeDependency, ReadymadeDependency.Id> {

    /** Outbound edges: "which solutions does {@code solutionId} depend on?" */
    List<ReadymadeDependency> findBySolutionId(UUID solutionId);

    /** Inbound edges: "which solutions depend on {@code dependsOnId}?"
     *  Useful for delete-safety checks (don't archive a primitive
     *  while a STACK still references it). */
    List<ReadymadeDependency> findByDependsOnId(UUID dependsOnId);

    void deleteBySolutionId(UUID solutionId);
}
