package com.navneet.executespec.router;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ModelTaskMetricRepository extends JpaRepository<ModelTaskMetric, Long> {

  List<ModelTaskMetric>
      findTop200ByProviderIgnoreCaseAndModelNameIgnoreCaseAndTierIgnoreCaseOrderByCreatedAtDesc(
          String provider, String modelName, String tier);
}
