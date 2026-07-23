package com.navneet.executespec.specchat;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectChatConfirmationRepository
    extends JpaRepository<ProjectChatConfirmation, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT c FROM ProjectChatConfirmation c WHERE c.id = :id")
  Optional<ProjectChatConfirmation> findByIdForUpdate(@Param("id") UUID id);
}
