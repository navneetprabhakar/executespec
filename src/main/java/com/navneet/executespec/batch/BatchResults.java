package com.navneet.executespec.batch;

import java.util.List;

/**
 * Aggregated results from a completed batch.
 *
 * @param batchId  Provider batch ID
 * @param results  Individual task results
 */
public record BatchResults(
        String batchId,
        List<BatchResult> results
) {}
