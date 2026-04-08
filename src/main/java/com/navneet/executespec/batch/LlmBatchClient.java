package com.navneet.executespec.batch;

import java.util.List;

/**
 * Provider-specific batch API client.
 * Each implementation talks to one provider's batch endpoint using raw HTTP
 * (Spring AI 1.1.0 does not expose batch APIs).
 */
public interface LlmBatchClient {

    /**
     * Submit a batch of requests to the provider.
     *
     * @param runId    Pipeline run ID (for logging/tracing)
     * @param requests Provider-agnostic batch requests
     * @param apiKey   API key to use for this call
     * @return Provider batch ID (e.g. "msgbatch_xxx", "batch_xxx", "batches/xxx")
     * @throws BatchSubmissionException if the provider rejects the batch
     */
    String submitBatch(String runId, List<BatchRequest> requests, String apiKey);

    /**
     * Check the status of a previously submitted batch.
     *
     * @param batchId Provider batch ID
     * @param apiKey  API key
     * @return Normalized batch status
     */
    BatchStatus checkStatus(String batchId, String apiKey);

    /**
     * Retrieve results from a completed batch.
     *
     * @param batchId Provider batch ID
     * @param apiKey  API key
     * @return Aggregated results
     * @throws IllegalStateException if the batch is not yet complete
     */
    BatchResults getResults(String batchId, String apiKey);

    /**
     * Cancel a batch that is still in progress.
     *
     * @param batchId Provider batch ID
     * @param apiKey  API key
     */
    void cancelBatch(String batchId, String apiKey);
}
