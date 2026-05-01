package com.navneet.executespec.router;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ModelTaskMetricRepository extends JpaRepository<ModelTaskMetric, Long> {

  @Query("SELECT m FROM ModelTaskMetric m ORDER BY m.createdAt DESC")
  List<ModelTaskMetric> findTopNOrderByCreatedAtDesc(Pageable pageable);

  default List<ModelTaskMetric> findTopNOrderByCreatedAtDesc(int n) {
    return findTopNOrderByCreatedAtDesc(Pageable.ofSize(n));
  }
}
