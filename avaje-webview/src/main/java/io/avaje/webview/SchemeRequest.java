package io.avaje.webview;

import module java.base;

/**
 * A request intercepted by a {@link SchemeHandler}.
 *
 * <p>Represents a single {@code <scheme>://…} load — from a navigation, subresource, {@code
 * fetch()}, or XHR — captured before the webview would otherwise attempt to resolve it.
 *
 * @param url the full request URL as the webview parsed it, including scheme, host, path, and
 *     query string
 * @param method uppercase HTTP method ({@code "GET"}, {@code "POST"}, …). Empty method strings are
 *     normalised to {@code "GET"}
 * @param headers request headers keyed by lower-case name. Values are the raw header value string
 *     (multi-valued headers are joined with a comma per RFC 7230)
 * @param body request body bytes, or empty for a body-less request
 */
public record SchemeRequest(String url, String method, Map<String, String> headers, byte[] body) {

  public SchemeRequest {
    Objects.requireNonNull(url, "url");
    method = method == null || method.isEmpty() ? "GET" : method.toUpperCase(Locale.ROOT);
    headers = headers == null ? Map.of() : Map.copyOf(headers);
    body = body == null ? new byte[0] : body;
  }
}
