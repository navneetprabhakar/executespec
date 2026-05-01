package com.navneet.executespec.integration;

/** Thrown by InboundAdapter.verifySignature() when the webhook signature is invalid. */
public class WebhookSignatureException extends RuntimeException {

  public WebhookSignatureException(String message) {
    super(message);
  }
}
