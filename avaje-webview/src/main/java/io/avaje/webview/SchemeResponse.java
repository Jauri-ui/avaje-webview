package io.avaje.webview;

import module java.base;

/**
 * Response returned by a {@link SchemeHandler}.
 *
 * <p>Modelled on an HTTP response — an HTTP status code, a content type, response headers, and a
 * body. All three native backends (WKWebView, WebView2, WebKitGTK) synthesise an HTTP-shaped
 * response from these fields.
 *
 * <p>Build via {@link #builder()}:
 *
 * <pre>{@code
 * SchemeResponse.builder()
 *     .status(200)
 *     .contentType("text/html; charset=utf-8")
 *     .header("Cache-Control", "no-store")
 *     .body("<h1>hi</h1>".getBytes(StandardCharsets.UTF_8))
 *     .build();
 * }</pre>
 */
public final class SchemeResponse {

  private final int status;
  private final String contentType;
  private final Map<String, String> headers;
  private final byte[] body;

  private SchemeResponse(int status, String contentType, Map<String, String> headers, byte[] body) {
    this.status = status;
    this.contentType = contentType;
    this.headers = headers;
    this.body = body;
  }

  /** HTTP status code, e.g. {@code 200}, {@code 404}. */
  public int status() {
    return status;
  }

  /**
   * The MIME type, e.g. {@code "text/html; charset=utf-8"}. May be {@code null}, in which case the
   * webview will infer the type from the URL extension (best-effort).
   */
  public String contentType() {
    return contentType;
  }

  /**
   * Additional response headers, keyed by header name. {@code Content-Type} and {@code
   * Content-Length} are set automatically from {@link #contentType()} and {@link #body()} and must
   * not be duplicated here.
   */
  public Map<String, String> headers() {
    return headers;
  }

  /** Body bytes. Empty array = no body. Never {@code null}. */
  public byte[] body() {
    return body;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Fluent builder for {@link SchemeResponse}. */
  public static final class Builder {
    private int status = 200;
    private String contentType;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];

    private Builder() {}

    /** Sets the HTTP status code. Defaults to {@code 200}. */
    public Builder status(int status) {
      this.status = status;
      return this;
    }

    /** Sets the {@code Content-Type} header, e.g. {@code "text/html; charset=utf-8"}. */
    public Builder contentType(String contentType) {
      this.contentType = contentType;
      return this;
    }

    /** Adds a response header. Case-preserving; overwrites any prior value for the same name. */
    public Builder header(String name, String value) {
      headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
      return this;
    }

    /** Sets the response body bytes. */
    public Builder body(byte[] body) {
      this.body = body == null ? new byte[0] : body;
      return this;
    }

    /** Sets the response body from a UTF-8 string. */
    public Builder body(String body) {
      this.body = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
      return this;
    }

    public SchemeResponse build() {
      return new SchemeResponse(status, contentType, Map.copyOf(headers), body);
    }
  }
}
