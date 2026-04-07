package com.navneet.executespec.deployment.preview.vm;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PreviewMetricRepository extends JpaRepository<PreviewMetric, Long> {

    long countByEventType(String eventType);

    long countByEventTypeAndCreatedAtAfter(String eventType, Instant after);

    List<PreviewMetric> findByEventTypeInOrderByCreatedAtDesc(
            List<String> eventTypes, Pageable pageable);
}
