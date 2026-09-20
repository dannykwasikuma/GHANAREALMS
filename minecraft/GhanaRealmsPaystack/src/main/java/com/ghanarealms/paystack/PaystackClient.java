package com.ghanarealms.paystack;

import com.ghanarealms.paystack.util.MinimalJson;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Talks to Paystack's REST API directly (https://api.paystack.co) using only
 * the JDK's built-in HttpClient - no extra HTTP library dependency.
 *
 * Docs: https://paystack.com/docs/api/transaction/
 */
public class PaystackClient {

    private static final String BASE_URL = "https://api.paystack.co";

    private final String secretKey;
    private final HttpClient http;
    private final Logger logger;

    public PaystackClient(String secretKey, Logger logger) {
        this.secretKey = secretKey;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public record InitResult(boolean ok, String reference, String authorizationUrl, String message) {}

    /**
     * Paystack amounts are in the currency's smallest unit (pesewas for GHS,
     * i.e. amountGhs * 100). We generate our own reference client-side so we
     * can tie it to a pending PurchaseStore row before the player has even
     * opened the checkout page.
     */
    public InitResult initializeTransaction(String email, double amountGhs, String reference, String currency, String callbackUrl) {
        long amountSubunit = Math.round(amountGhs * 100);
        String body = """
                {"email":"%s","amount":%d,"currency":"%s","reference":"%s","callback_url":"%s"}
                """.formatted(
                MinimalJson.escape(email),
                amountSubunit,
                currency,
                MinimalJson.escape(reference),
                MinimalJson.escape(callbackUrl)
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/transaction/initialize"))
                    .header("Authorization", "Bearer " + secretKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> json = MinimalJson.parseObject(response.body());
            boolean status = Boolean.TRUE.equals(json.get("status"));
            String message = String.valueOf(json.get("message"));

            if (!status) {
                return new InitResult(false, null, null, message);
            }

            String authUrl = String.valueOf(MinimalJson.getPath(json, "data.authorization_url"));
            String ref = String.valueOf(MinimalJson.getPath(json, "data.reference"));
            return new InitResult(true, ref, authUrl, message);
        } catch (IOException | InterruptedException e) {
            logger.severe("Paystack initializeTransaction failed: " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new InitResult(false, null, null, "Network error contacting Paystack");
        }
    }

    public record VerifyResult(boolean ok, String status, String reference, double amountGhs, String email, String message) {}

    /**
     * Server-to-server verification of a transaction reference. Call this from
     * the webhook handler (or an admin /paystack verify) rather than trusting
     * the webhook payload's amount/status alone - this is the authoritative check.
     */
    public VerifyResult verifyTransaction(String reference) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/transaction/verify/" + reference))
                    .header("Authorization", "Bearer " + secretKey)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, Object> json = MinimalJson.parseObject(response.body());
            boolean status = Boolean.TRUE.equals(json.get("status"));
            String message = String.valueOf(json.get("message"));

            if (!status) {
                return new VerifyResult(false, null, reference, 0, null, message);
            }

            String txStatus = String.valueOf(MinimalJson.getPath(json, "data.status"));
            Object amountObj = MinimalJson.getPath(json, "data.amount");
            double amountGhs = amountObj == null ? 0 : (((Number) amountObj).longValue()) / 100.0;
            String email = String.valueOf(MinimalJson.getPath(json, "data.customer.email"));

            return new VerifyResult(true, txStatus, reference, amountGhs, email, message);
        } catch (IOException | InterruptedException e) {
            logger.severe("Paystack verifyTransaction failed: " + e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new VerifyResult(false, null, reference, 0, null, "Network error contacting Paystack");
        }
    }
}
