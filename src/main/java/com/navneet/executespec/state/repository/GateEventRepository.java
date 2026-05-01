package com.navneet.executespec.state.repository;

import java.util.List;

import com.navneet.executespec.state.entity.GateEvent;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GateEventRepository extends JpaRepository<GateEvent, String> {

  List<GateEvent> findByRunIdOrderByOpenedAtAsc(String runId);
}
