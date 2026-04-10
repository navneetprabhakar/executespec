package com.navneet.executespec.harness.repository;

import com.navneet.executespec.harness.entity.GateRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GateRepository extends JpaRepository<GateRecord, String> {
}
