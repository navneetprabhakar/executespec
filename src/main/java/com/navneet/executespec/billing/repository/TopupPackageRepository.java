package com.navneet.executespec.billing.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.billing.entity.TopupPackage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TopupPackageRepository extends JpaRepository<TopupPackage, UUID> {

  List<TopupPackage> findByActiveTrueOrderBySortOrder();

  List<TopupPackage> findAllByOrderBySortOrder();
}
