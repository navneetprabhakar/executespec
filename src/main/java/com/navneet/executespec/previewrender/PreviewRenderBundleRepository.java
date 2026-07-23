package com.navneet.executespec.previewrender;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Rendered surfaces (V302). */
@Repository
public interface PreviewRenderBundleRepository extends JpaRepository<PreviewRenderBundle, UUID> {

  List<PreviewRenderBundle> findByJobIdOrderByDisplayOrderAsc(UUID jobId);

  Optional<PreviewRenderBundle> findByJobIdAndSurfaceId(UUID jobId, String surfaceId);
}
