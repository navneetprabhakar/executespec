package com.navneet.executespec.billing.exception;

public class RazorpayApiException extends RuntimeException {
    private final int statusCode;
    private final String responseBody;

    public RazorpayApiException(int statusCode, String responseBody) {
        super("Razorpay API error: HTTP " + statusCode + " - " + responseBody);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }
}
