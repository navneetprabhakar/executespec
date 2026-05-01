package com.navneet.executespec.billing.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.PAYMENT_REQUIRED)
public class BillingException extends RuntimeException {
  public BillingException(String message) {
    super(message);
  }
}
