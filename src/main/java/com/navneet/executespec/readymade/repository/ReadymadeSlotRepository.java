package com.navneet.executespec.readymade.repository;

import com.navneet.executespec.readymade.entity.ReadymadeSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReadymadeSlotRepository extends JpaRepository<ReadymadeSlot, UUID> {

    /** Returns slots for a solution in {@code display_order} ASC so the
     *  builder UI renders them in the author's intended sequence. */
    List<ReadymadeSlot> findBySolutionIdOrderByDisplayOrderAsc(UUID solutionId);

    void deleteBySolutionId(UUID solutionId);
}
