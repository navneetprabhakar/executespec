package com.navneet.executespec.billing.repository;

import com.navneet.executespec.billing.entity.SellerConfig;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SellerConfigRepository extends JpaRepository<SellerConfig, String> {

  default SellerConfig getDefault() {
    return findById("DEFAULT")
        .orElseThrow(
            () -> new IllegalStateException("Seller config not found — run V28 migration"));
  }
}
