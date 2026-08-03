## Summary

Adds a custom URL scheme handler API to `avaje-webview` with a working macOS backend, plus stubs and detailed implementation plans for the Windows and Linux backends. Callers can intercept `<scheme>://…` loads before the webview would resolve them and answer with an HTTP-shaped response:

```java
Webview.builder()
    .registerSchemeHandler("assets", request ->
        SchemeResponse.builder()
            .status(200)
            .contentType("image/png")
            .body(Files.readAllBytes(Path.of("out", request.url().substring("assets://".length()))))
            .build())
    .html("<img src='assets://logo.png'/>")
    .build()
    .run();
```

## Motivation

I'm building [Jauri](https://github.com/priand/Tauri4J), a Java-native alternative to Tauri v2 on top of `avaje-webview`. Tauri v2's JS API exposes `convertFileSrc(path, protocol?)` — the standard way frontends load local files without going through `file://` (which many browser engines lock down for security reasons). Under the hood Tauri registers custom URL scheme handlers on each platform's webview and serves those requests from the app process. Jauri can't match that contract without a primitive for it in the underlying webview, and today `avaje-webview` has no such primitive — so `convertFileSrc` doesn't work in Jauri.

More broadly, custom scheme handlers are the standard mechanism for serving app-bundled assets from a webview (Electron uses them too via `protocol.registerBufferProtocol`), so this makes `avaje-webview` a viable target for asset-heavy hybrid apps.

## Public API

Three small types in `io.avaje.webview`:

- `SchemeHandler` — functional interface: `SchemeResponse handle(SchemeRequest request) throws Exception`.
- `SchemeRequest` — record `(String url, String method, Map<String,String> headers, byte[] body)` with normalisation (empty method → `"GET"`, uppercase, defensive copy of headers/body).
- `SchemeResponse` — final class with a `Builder`; `status` (default 200), `contentType`, `headers`, `body`. `Content-Type` and `Content-Length` are synthesised by the backend, never by the caller.

Wiring on `Webview.Builder`:

```java
Builder registerSchemeHandler(String scheme, SchemeHandler handler);
```

- Scheme is normalised to lowercase and validated against `[a-z][a-z0-9+.-]*` (RFC 3986 scheme syntax minus the trailing colon).
- Reserved schemes rejected at build time: `http`, `https`, `file`, `about`, `data`, `javascript`, `ws`, `wss`, `ftp` — none of the three native backends allow these to be intercepted anyway, so failing loudly is better than silently ignoring.
- Multiple schemes can be registered on the same builder; each maps to one `SchemeHandler`.

## Backend notes

**macOS (`CocoaWebView`)** — synthesises one Obj-C class per registered scheme via `objc_allocateClassPair` + `class_addMethod`, implementing `WKURLSchemeHandler`'s `webView:startURLSchemeTask:` (and a no-op `stopURLSchemeTask:`). Registration hooks into `initWindowAndWebView` between `WKWebViewConfiguration` alloc/init and `WKWebView` alloc — this ordering is required because `WKWebView` copies its configuration at init time, and `setURLSchemeHandler:forURLScheme:` on the live copy has no effect. Response goes back through `NSHTTPURLResponse initWithURL:statusCode:HTTPVersion:headerFields:` + `NSData dataWithBytes:length:` + `didReceiveResponse:` / `didReceiveData:` / `didFinish`. A shared upcall stub dispatches to the right Java handler by looking up the synthesised instance's Obj-C address in a `ConcurrentHashMap<Long, SchemeHandler>`. Any Java exception is caught before it can unwind into Obj-C — failed handlers surface as `didFailWithError:`.

**Windows (`Win32WebView`)** — accepts the handler map through a new 10-arg constructor and throws `UnsupportedOperationException` when it's non-empty. Full implementation plan (WebView2 `ICoreWebView2::add_WebResourceRequested` + `AddWebResourceRequestedFilter("<scheme>://*", ALL)` + `ICoreWebView2Environment::CreateWebResourceResponse` with an `IStream` body built via `SHCreateMemStream`) is in `docs/scheme-handler-windows.md`.

**Linux (`GtkWebView`)** — same pattern: 10-arg constructor, `UnsupportedOperationException` when non-empty. Full implementation plan (WebKitGTK `webkit_web_context_register_uri_scheme` + `webkit_uri_scheme_request_finish_with_response` with a `WebKitURISchemeResponse`) is in `docs/scheme-handler-linux.md`.

Both docs are written as standalone briefs — a fresh contributor with a Windows or Linux box should be able to pick up the branch and implement the missing backend from them.

## Compatibility

- Existing 9-arg backend constructors are preserved and delegate to the new 10-arg overload with `Map.of()`, so any caller that builds `CocoaWebView` / `Win32WebView` / `GtkWebView` directly keeps working.
- `WebviewBuilder` passes an immutable `Map.copyOf(schemeHandlers)` to each backend, so the field is safe to store as-is.
- No API removed or renamed; the new `Builder` method is additive.

## Testing

- Compiled cleanly on macOS with `mvn -DskipTests install`.
- Manual smoke: registering a `test://` handler that returns an HTML page and loading `<iframe src='test://index.html'>` renders the returned body.
- Windows and Linux backends compile with the new constructor param; automated tests come with the per-platform implementations landing on this branch.

## Files touched

- **New**: `SchemeHandler.java`, `SchemeRequest.java`, `SchemeResponse.java`, `docs/scheme-handler-windows.md`, `docs/scheme-handler-linux.md`.
- **Modified**: `Webview.java` (Builder method + docs), `WebviewBuilder.java` (validation, dedup, threading through), `CocoaWebView.java` (registration + synthesised class + response marshalling), `Win32WebView.java` / `GtkWebView.java` (10-arg constructor stubs).
