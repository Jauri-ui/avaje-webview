# Implementing custom URL scheme handlers on Linux (WebKitGTK)

> **Status: implemented** (2026-10-29) in `linux/GtkSchemeHandlers.java` + `WebKit6.java`, verified
> against WebKitGTK 6.0 (`libwebkitgtk-6.0.so.4.16`) by Jauri's `AssetProtocolUiTest`. Differences
> from the plan below: schemes are registered **once per process** on the default context with one
> global upcall stub, and requests are routed to the issuing view via
> `webkit_uri_scheme_request_get_web_view` (so several windows, or a closed one, are safe); schemes
> are also registered as **secure and CORS-enabled** (without that, `fetch()` from the page fails);
> response headers go through libsoup (option 1 below), not dropped. Request headers/body are not
> forwarded, matching macOS. The rest of this document is the original plan.

You (a Claude sister on Linux) are picking up an in-flight feature. macOS is already implemented and merged on this branch; your job is the WebKitGTK backend. This doc gives you everything you need — API surface, code sites to edit, WebKitGTK calls to make, verification steps, gotchas.

## What already exists

Branch: `feat/scheme-handlers` (against `avaje/avaje-webview`).

Public API (all three files complete, do not change without a very good reason — the macOS impl locks them in):

- `avaje-webview/src/main/java/io/avaje/webview/SchemeHandler.java` — functional interface, `SchemeResponse handle(SchemeRequest request) throws Exception`.
- `avaje-webview/src/main/java/io/avaje/webview/SchemeRequest.java` — record `(String url, String method, Map<String,String> headers, byte[] body)`.
- `avaje-webview/src/main/java/io/avaje/webview/SchemeResponse.java` — final class with `Builder`; `status`/`contentType`/`headers`/`body`. `Content-Type` and `Content-Length` synthesised by the backend, never by the caller.
- `Webview.Builder.registerSchemeHandler(String scheme, SchemeHandler handler)` — wired to `WebviewBuilder`. Reserved schemes (`http/https/file/about/data/javascript/ws/wss/ftp`) rejected at build time. Pattern `[a-z][a-z0-9+.-]*`.

Wiring already present in `WebviewBuilder.createForPlatform`: `GtkWebView`'s new 10-arg constructor takes `Map<String, SchemeHandler> schemeHandlers`. Today it throws `UnsupportedOperationException` when the map is non-empty. **Your job is to replace that throw with a real implementation.**

The macOS backend is in `CocoaWebView.java`. Read it end-to-end — the shape (synthesised handler + `SchemeRequest`/`SchemeResponse` marshalling + status/headers/body + terminal finish call) is the model to follow on Linux, though the WebKitGTK API is simpler than WKWebView's Obj-C protocol.

## Setup

- **Linux with WebKitGTK 6.0** (Ubuntu 24.04 / Fedora 40+ / Arch). Verify:

    ```bash
    pkg-config --modversion webkitgtk-6.0
    ```

    Should print `2.4x.x` or newer. If missing: `sudo apt-get install libwebkitgtk-6.0-dev` (Ubuntu) or `sudo dnf install webkitgtk6.0-devel` (Fedora).

- **Java 25** (Temurin is fine).
- **Maven 3.9+**.
- Clone `avaje/avaje-webview`, check out branch `feat/scheme-handlers`.

Verify baseline before touching anything:

```bash
mvn -DskipTests install
```

If this fails on Linux, first check `docs/upstream-avaje-webview-asks.md` for known platform quirks. Don't debug scheme handlers on a broken build.

## The WebKitGTK API you need

WebKitGTK exposes a **first-class URI scheme handler API** — no vtable synthesis, no COM callbacks. Just a C callback registered against a `WebKitWebContext`. The two functions you'll use:

- `webkit_web_context_register_uri_scheme(WebKitWebContext* ctx, const char* scheme, WebKitURISchemeRequestCallback callback, gpointer user_data, GDestroyNotify user_data_destroy_func)` — registers `callback` to fire for every request under `scheme://…`. Callback signature: `void(*)(WebKitURISchemeRequest*, gpointer)`.
- `webkit_uri_scheme_request_finish(WebKitURISchemeRequest*, GInputStream* stream, gint64 stream_length, const char* content_type)` — completes the request with a body stream, its byte count, and a MIME type. For a byte buffer, wrap in `g_memory_input_stream_new_from_data(bytes, len, GDestroyNotify_or_NULL)`.

For richer responses (custom status code + headers) use:

- `webkit_uri_scheme_request_finish_with_response(WebKitURISchemeRequest*, WebKitURISchemeResponse*)` where `WebKitURISchemeResponse` is built via `webkit_uri_scheme_response_new(GInputStream*, gint64)` + `webkit_uri_scheme_response_set_status(response, status_code, reason_phrase)` + `webkit_uri_scheme_response_set_content_type(response, mime)` + `webkit_uri_scheme_response_set_http_headers(response, SoupMessageHeaders*)`.

Use `finish_with_response` — it maps 1:1 to the `SchemeResponse` shape.

On failure: `webkit_uri_scheme_request_finish_error(WebKitURISchemeRequest*, GError*)`.

**Threading:** WebKitGTK is not thread-safe. All calls must happen on the GTK main thread. Your scheme callback fires on that thread already, and your handler is synchronous, so you can call `finish_with_response` inline.

## Code sites to edit

All under `avaje-webview/src/main/java/io/avaje/webview/linux/`.

1. **`GtkWebView.java`** — the constructor throw at ~line 118 goes away, replaced by "store the map, register schemes on the web context." Registration must happen **before** any `webkit_web_view_load_uri` — do it right after the web view is created, inside `initWindowAndWebView`.
2. **`WebKit6.java`** — add downcall bindings for the WebKitGTK functions listed above. Grep for `webkit_user_content_manager_register_script_message_handler` to see the existing pattern; yours will look identical.
3. **`GLib.java`** — add downcall bindings for:
    - `g_memory_input_stream_new_from_data(const void* data, gssize len, GDestroyNotify destroy)` (from `libgio-2.0`).
    - `g_object_unref` (already exists — reuse).
    - `g_free` (already exists — reuse).

## Detailed implementation plan

### Step 1: store the handlers, don't throw

```java
// GtkWebView.java, at top of the 10-arg constructor
super(...);
this.schemeHandlers = schemeHandlers == null ? java.util.Map.of() : schemeHandlers;
// ... rest unchanged
```

Remove the `UnsupportedOperationException`. Add `private final java.util.Map<String, io.avaje.webview.SchemeHandler> schemeHandlers;`.

### Step 2: register schemes at web-view creation time

In `initWindowAndWebView` (grep the file), right after `webView = WebKit6.webViewNew()`, get the context and register each scheme:

```java
final var webContext = WebKit6.webViewGetContext(webView); // borrowed ref
for (final var entry : schemeHandlers.entrySet()) {
  final var scheme = entry.getKey();
  final var handler = entry.getValue();
  final var callbackStub = buildSchemeCallbackStub(handler);
  WebKit6.webContextRegisterUriScheme(
      webContext,
      Arena.global().allocateFrom(scheme),
      callbackStub,
      MemorySegment.NULL,   // user_data - unused, we bind Java-side
      MemorySegment.NULL);  // destroy notify
}
```

WebKitGTK 6.0 renamed `webkit_web_view_get_context` → `webkit_web_view_get_network_session` in some builds. Check `pkg-config --variable=girfile webkit-web-process-extension-6.0` output or just try both symbol names. The scheme-registration API also moved: newer WebKitGTK 6.x wants the scheme registered on `WebKitWebContext` obtained via `webkit_web_view_get_context`, older wants it on the default context. If `webkit_web_view_get_context` doesn't exist, fall back to `webkit_web_context_get_default()`.

### Step 3: the callback

Bind one Java method to `this` per registered scheme (or share one across all schemes by parsing the scheme out of the URI at dispatch time, like the macOS backend does):

```java
void onUriSchemeRequest(MemorySegment request, MemorySegment userData) {
  try {
    // webkit_uri_scheme_request_get_uri(request) -> const char* (owned by request; do not free)
    final var uriPtr = WebKit6.uriSchemeRequestGetUri(request);
    final var uri = readCString(uriPtr);

    // Method is available via webkit_uri_scheme_request_get_http_method(request) - added in
    // WebKitGTK 2.36. Older versions default to GET; that's fine for MVP.
    final var method = "GET";

    final SchemeRequest req = new SchemeRequest(uri, method, java.util.Map.of(), new byte[0]);
    final SchemeHandler handler = schemeHandlers.get(schemeOf(uri));
    if (handler == null) {
      // Shouldn't happen given we filter by scheme at registration. Fail cleanly.
      WebKit6.uriSchemeRequestFinishError(request, gErrorNew("io.avaje.webview", 404, "no handler"));
      return;
    }

    final SchemeResponse resp;
    try {
      resp = handler.handle(req);
    } catch (final Throwable t) {
      WebKit6.uriSchemeRequestFinishError(request, gErrorNew("io.avaje.webview", -1, String.valueOf(t.getMessage())));
      return;
    }

    // Copy the body into a g_malloc'd buffer, wrap in a memory input stream, hand off to WebKit.
    // g_memory_input_stream_new_from_data with a destroy notify of g_free lets GLib free it.
    final var body = resp.body();
    final var bodyBuf = GLib.gMalloc(body.length); // wrap g_malloc(gsize)
    MemorySegment.copy(body, 0, bodyBuf.reinterpret(body.length), ValueLayout.JAVA_BYTE, 0, body.length);
    final var stream = GLib.gMemoryInputStreamNewFromData(bodyBuf, body.length, GLib.G_FREE_ADDR);

    final var response = WebKit6.uriSchemeResponseNew(stream, body.length);
    WebKit6.uriSchemeResponseSetStatus(response, resp.status(), reasonFor(resp.status()));
    if (resp.contentType() != null) {
      WebKit6.uriSchemeResponseSetContentType(response, resp.contentType());
    }
    if (!resp.headers().isEmpty()) {
      final var soupHeaders = Soup.messageHeadersNew(1 /* SOUP_MESSAGE_HEADERS_RESPONSE */);
      for (final var h : resp.headers().entrySet()) {
        Soup.messageHeadersAppend(soupHeaders, h.getKey(), h.getValue());
      }
      WebKit6.uriSchemeResponseSetHttpHeaders(response, soupHeaders);
    }

    WebKit6.uriSchemeRequestFinishWithResponse(request, response);
    GLib.gObjectUnref(response);
    GLib.gObjectUnref(stream);
  } catch (final Throwable t) {
    // Never propagate into C. Log and drop.
  }
}
```

`FunctionDescriptor` for the callback stub: `ofVoid(ADDRESS, ADDRESS)` — request pointer + user_data pointer. Panama `bindTo(this)` to lock the stub to a `GtkWebView` instance, matching the existing `msgStub` in `GtkWebView.java`.

The stub arena needs to outlive the webview — reuse the existing stub arena (grep `Arena.ofShared` in `GtkWebView.java`).

### Step 4: reason phrases

`webkit_uri_scheme_response_set_status(response, code, phrase)`: for `200`, pass `"OK"`; if `phrase` is `NULL`, WebKitGTK synthesises a default based on the code, so passing `NULL` for anything not in a small allowlist is fine.

### Step 5: cleanup

- Nothing extra: the scheme-registration lifetime is tied to the `WebKitWebContext`, which is destroyed when the web view is disposed. WebKitGTK unrefs the callback via the destroy-notify we pass (which we set to `NULL` since our stub lives forever in `arenaStubs`).

## `libsoup` — do you need it?

`WebKitURISchemeResponse::set_http_headers` takes a `SoupMessageHeaders*`. That's from `libsoup-3.0`. Two options:

1. **Add a small `Soup.java` binding.** Just `soup_message_headers_new(SoupMessageHeadersType)` and `soup_message_headers_append(SoupMessageHeaders*, const char*, const char*)`, plus a `g_boxed_free`-style cleanup. `libsoup-3.0` is a WebKitGTK 6.0 dependency, so it's already installed anywhere WebKitGTK 6.0 works.
2. **Skip custom headers for MVP.** Only `content-type` + status; skip the `.headers()` map on Linux. Log at DEBUG that the extras were dropped. File a follow-up. Tauri v2's `convertFileSrc` doesn't set custom response headers, so this is a defensible MVP.

Pick 2 for the first pass, then upgrade to 1 in a follow-up. Note in the PR description which you chose.

## Verification

1. `mvn -DskipTests install` at the repo root — must succeed on Linux.
2. Add a smoke test (mirror the macOS one at `avaje-webview/src/test/java/io/avaje/webview/SchemeHandlerSmokeTest.java` when it lands). Manual smoke test if the automated one isn't there yet:

    ```java
    public static void main(String[] args) {
      var wv = Webview.builder()
          .title("scheme test")
          .registerSchemeHandler("test", req ->
              SchemeResponse.builder()
                  .status(200)
                  .contentType("text/html; charset=utf-8")
                  .body("<h1>hello from " + req.url() + "</h1>")
                  .build())
          .html("<html><body><iframe src='test://index.html' width=400 height=200></iframe></body></html>")
          .build();
      wv.run();
    }
    ```

    Expected: the iframe renders "hello from test://index.html".

3. Confirm the `UnsupportedOperationException` no longer fires for a non-empty map.

## Gotchas

- **GTK thread rule.** All WebKitGTK calls must happen on the same thread that called `gtk_init` (already enforced by `GtkWebView`). The scheme callback fires on that thread, so you're fine as long as you don't hop threads inside the handler.
- **Callback lifetime.** WebKitGTK holds the callback pointer for the life of the `WebKitWebContext`. If the callback arena is closed early, the process crashes on the next request. Use `Arena.ofShared()` (matches existing stubs) and never close it until the webview closes.
- **`get_uri` returns a borrowed pointer.** Do NOT `g_free` it — the request owns it. `get_http_method` (WebKitGTK ≥ 2.36) is the same.
- **Body stream ownership.** Once handed to `webkit_uri_scheme_response_new`, the response owns the stream reference; you `g_object_unref` the local one after. `finish_with_response` transfers request ownership; do not touch the request pointer after that call.
- **Register before load.** Registration must happen before `webkit_web_view_load_uri` for the first request under a scheme to hit your callback. This is why registration lives inside `initWindowAndWebView` before the first navigate.
- **`register_uri_scheme` is per-`WebKitWebContext`.** If two `GtkWebView` instances share a context, they share the scheme registration. Today Jauri is one-webview-per-process and this doesn't matter, but flag it in a comment.
- **Content-Length inference.** `webkit_uri_scheme_response_new(stream, length)` uses `length` as the byte count. If you pass `-1`, WebKit reads the stream to exhaustion — fine for small bodies, wasteful for big ones. Always pass the actual byte count.
- **UTF-8 everywhere.** Unlike WebView2 (UTF-16LE), WebKitGTK uses UTF-8 for all string args. `Arena.allocateFrom(str)` defaults to UTF-8 on Linux via ISO-8859-1 for ASCII-safe strings — explicitly pass `StandardCharsets.UTF_8` if the string can contain non-ASCII.
- **Never throw across a C boundary.** GLib will happily call a callback that raises a Java exception, and the JVM will crash on the way out. Wrap everything in `try { … } catch (Throwable t) { /* log */ }`.

## When you finish

- Run `mvn -DskipTests install` — passes.
- Run the smoke test above — the iframe renders text from the handler.
- Update `GtkWebView.java`'s constructor doc so it no longer says "not yet implemented" (the message inside the removed `throw new UnsupportedOperationException(...)` was the only place claiming that).
- Push a commit onto `feat/scheme-handlers`. Do NOT open a separate PR — the macOS work is the umbrella PR; your commit lands on top of it.

Ping the macOS Claude in the PR description with the WebKitGTK version you built against.
