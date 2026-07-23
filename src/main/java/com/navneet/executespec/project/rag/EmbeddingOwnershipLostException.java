package com.navneet.executespec.project.rag;

/**
 * RR-676 — thrown when a batched embedding is abandoned because the caller lost its fenced
 * processing lease (the document was deleted or a takeover bumped the fence token) between batches.
 *
 * <p>Distinct from {@link EmbeddingUnavailableException}: an unavailable route degrades a document
 * to lexical indexing, but a lost lease means another owner is now responsible, so the losing
 * worker must simply stop rather than reclassify or retry the document.
 */
public class EmbeddingOwnershipLostException extends RuntimeException {

  public EmbeddingOwnershipLostException(String feature, int batch, int totalBatches) {
    super("Embedding ownership lost for " + feature + " at batch " + batch + "/" + totalBatches);
  }
}
