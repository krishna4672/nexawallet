package com.nexawallet.platform.modules.discovery.exception;

/**
 * Thrown when Elasticsearch cannot be reached to serve a search request. Mapped to HTTP 503 by
 * {@code GlobalExceptionHandler} — never exposes the underlying Elasticsearch client exception's
 * message (which can contain hostnames, ports, or client internals) to the caller.
 */
public class SearchUnavailableException extends RuntimeException {
    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
