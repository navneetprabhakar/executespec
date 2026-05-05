package com.navneet.executespec.prreview.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.prreview.entity.PrReviewComment;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PrReviewCommentRepository extends JpaRepository<PrReviewComment, UUID> {

  List<PrReviewComment> findByPrReviewIdOrderByFilePathAscLineNumberAsc(UUID prReviewId);

  long countByPrReviewId(UUID prReviewId);
}
