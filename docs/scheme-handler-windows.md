# Implementing custom URL scheme handlers on Windows (WebView2)

You (a Claude sister on Windows) are picking up an in-flight feature. macOS is already implemented and merged on this branch; your job is the WebView2 backend. This doc gives you everything you need — API surface, code sites to edit, WebView2 calls to make, verification steps, gotchas.

## What already exists

Branch: `feat/scheme-handlers` (against `avaje/avaje-webview`).

Public API (all three files complete, do not change without a very good reason — the macOS impl locks them in):

- `avaje-webview/src/main/java/io/avaje/webview/SchemeHandler.java` — functional interface, `SchemeResponse handle(SchemeRequest request) throws Exception`.
- `avaje-webview/src/main/java/io/avaje/webview/SchemeRequest.java` — record `(String url, String method, Map<String,String> headers, byte[] body)`.
- `avaje-webview/src/main/java/io/avaje/webview/SchemeResponse.java` — final class with `Builder`; `status`/`contentType`/`headers`/`body`. `Content-Type` and `Content-Length` synthesised by the backend, never by the caller.
- `Webview.Builder.registerSchemeHandler(String scheme, SchemeHandler handler)` — wired to `WebviewBuilder`. Reserved schemes (`http/https/file/about/data/javascript/ws/wss/ftp`) rejected at build time. Pattern `[a-z][a-z0-9+.-]*`.

Wiring already present in `WebviewBuilder.createForPlatform`: `Win32WebView`'s new 10-arg constructor takes `Map<String, SchemeHandler> schemeHandlers`. Today it throws `UnsupportedOperationException` when the map is non-empty. **Your job is to replace that throw with a real implementation.**

The macOS backend is in `CocoaWebView.java`. Read it end-to-end — the shape (synthesised class + `onStartURLSchemeTask` upcall + `SchemeRequest`/`SchemeResponse` marshalling + `didReceiveResponse:` / `didReceiveData:` / `didFinish` completion sequence) is the model to follow on Windows.

## Setup

- **Windows 11** (10 works but WebView2 evergreen runtime coverage is worse).
- **Java 25** (Temurin is fine).
- **Maven 3.9+**.
- **WebView2 Runtime** — should already be installed system-wide; if not, `winget install Microsoft.EdgeWebView2Runtime`.
- Clone `avaje/avaje-webview`, add `origin`/`upstream` if you're on a fork, check out branch `feat/scheme-handlers`.

Verify baseline before touching anything:

```powershell
mvn -DskipTests install
```

That should succeed. If it doesn't, stop and fix the environment — don't debug scheme handlers on a broken build.

## The WebView2 API you need

You are going to intercept HTTP-shaped requests for arbitrary schemes via WebView2's **web-resource-requested** event, which is the API Chromium/Edge/WebView2 exposes for this. The two relevant COM interfaces:

- `ICoreWebView2::add_WebResourceRequested(handler, out token)` — subscribes an event handler.
- `ICoreWebView2::AddWebResourceRequestedFilter(uri, resourceContext)` — restricts which URIs fire the event. Pass `"<scheme>://*"` and `COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL` (= `0`) to catch every request under a scheme.

The event handler is a COM callback with signature `HRESULT(ICoreWebView2*, ICoreWebView2WebResourceRequestedEventArgs*)`. From the args object you:

1. Call `get_Request()` → `ICoreWebView2WebResourceRequest`, then `get_Uri()`/`get_Method()`/`get_Headers()`/`get_Content()`. `get_Content()` returns an `IStream*` for POST bodies (may be null for GET).
2. Build a response via `ICoreWebView2Environment::CreateWebResourceResponse(content, statusCode, reasonPhrase, headers)` where `content` is an `IStream*` (use `SHCreateMemStream` from `shlwapi.dll` — it copies).
3. Set it on the args: `put_Response(response)`.

If you don't set a response, WebView2 tries to resolve the URL itself and (for a custom scheme) fails with `ERR_UNKNOWN_URL_SCHEME`.

**Threading:** all WebView2 calls must happen on the thread that owns the WebView2 controller (the Win32 message-pump thread — the thread that created the window). Your event fires on that thread already, and your handler is synchronous, so you can call `put_Response` inline. If you ever go async, call `GetDeferral()` first.

## Code sites to edit

All under `avaje-webview/src/main/java/io/avaje/webview/windows/`.

1. **`Win32WebView.java`** — the constructor throw at ~line 218 goes away, replaced by "store the map, register schemes after the controller is ready." The event subscription happens after WebView2 environment + controller + view are constructed (there's an existing `embedWebView2(debug)` call — hook in after that).
2. **`ComWebView2.java`** — add vtable bindings for `add_WebResourceRequested` and `AddWebResourceRequestedFilter`. Grep the file for `add_WebMessageReceived` to see the pattern: `Win32.downcallVtable(comObj, idx, FunctionDescriptor)` + a Java wrapper method. Vtable indices for `ICoreWebView2` (from the WebView2 SDK header): `add_WebResourceRequested` = **20**, `AddWebResourceRequestedFilter` = **22**. Double-check against `WebView2.h` in the WebView2 SDK before shipping — the numbering has shifted between SDK versions.
3. **New file `SchemeHandlerBridge.java`** (recommended, mirroring the shape of `CocoaWebView`'s `createSchemeHandler`) — synthesises the COM callback object. Reuse the existing `qiStub()` / `addRefStub()` / `releaseStub()` in `Win32WebView.java` (see line ~1412) as the model. Your callback has one non-IUnknown vtable slot: `Invoke(sender, args)`.
4. **`Win32.java`** — add `SHCreateMemStream` downcall (from `shlwapi.dll`). Signature: `IStream* SHCreateMemStream(const BYTE* pInit, UINT cbInit)`. Free with `IStream::Release()`.

## Detailed implementation plan

### Step 1: store the handlers, don't throw

```java
// Win32WebView.java, at top of the 10-arg constructor
super(...);
this.schemeHandlers = schemeHandlers == null ? java.util.Map.of() : schemeHandlers;
// ... rest unchanged, including embedWebView2(debug)
if (!this.schemeHandlers.isEmpty()) {
  registerSchemeHandlers();
}
```

Add `private final java.util.Map<String, io.avaje.webview.SchemeHandler> schemeHandlers;` as a field. Remove the `UnsupportedOperationException`.

### Step 2: build the COM callback

Model after `Win32WebView.java`'s existing message-received callback (`buildMessageCallback` around the `qiStub()` / `invokeStub` region — grep for `webMessageReceivedHandler`). Each scheme's handler object is a minimal COM object:

```
vtable[0] = QueryInterface  (reuse cachedQI)
vtable[1] = AddRef           (reuse cachedAddRef)
vtable[2] = Release          (reuse cachedRelease)
vtable[3] = Invoke(sender ICoreWebView2*, args ICoreWebView2WebResourceRequestedEventArgs*) -> HRESULT
```

The `Invoke` upcall stub is a Panama method handle wrapping a Java method like:

```java
int onWebResourceRequested(MemorySegment self, MemorySegment sender, MemorySegment args) {
  // ... your work here ...
  return 0; // S_OK
}
```

`FunctionDescriptor`: `of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS)` — three pointer args + int HRESULT return. The stub lives in an arena that outlives the webview (reuse `arenaStubs`).

Bind one Java method to `this` and reuse it for **all** registered schemes — dispatch to the right `SchemeHandler` at runtime by parsing the URL scheme from the request URI. That way one QI/AddRef/Release triple + one Invoke stub cover every scheme, matching how the macOS backend does it (see `schemeHandlerBySelfAddr` in `CocoaWebView`).

### Step 3: register the handler + filter per scheme

Inside `registerSchemeHandlers()`:

```java
final var callback = buildSchemeHandlerCallback(); // returns MemorySegment (COM ptr)
for (final var scheme : schemeHandlers.keySet()) {
  final var uriGlob = a.allocateFrom(scheme + "://*", java.nio.charset.StandardCharsets.UTF_16LE);
  int hr = comWebView2.addWebResourceRequestedFilter(uriGlob, 0 /* COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL */);
  // check hr, log/throw on failure
}
final var tokenOut = a.allocate(JAVA_LONG); // EventRegistrationToken is a struct of one int64
int hr2 = comWebView2.addWebResourceRequested(callback, tokenOut);
```

WebView2 requires wide strings (UTF-16LE) for all string args. Look at how `Navigate` is called in `Win32WebView` for the encoding pattern (`allocateFrom(str, StandardCharsets.UTF_16LE)`).

### Step 4: on invoke, read the request

```java
int onWebResourceRequested(MemorySegment self, MemorySegment sender, MemorySegment args) {
  try {
    // ICoreWebView2WebResourceRequestedEventArgs::get_Request -> ICoreWebView2WebResourceRequest*
    final var requestOut = a.allocate(ADDRESS);
    invokeVtable(args, 0 /* get_Request */, requestOut); // check WebView2.h for exact index
    final var request = requestOut.get(ADDRESS, 0);

    // request::get_Uri -> LPWSTR (caller frees with CoTaskMemFree)
    final var uriOut = a.allocate(ADDRESS);
    invokeVtable(request, 0 /* get_Uri */, uriOut);
    final var uriPtr = uriOut.get(ADDRESS, 0);
    final var uri = readWideString(uriPtr);
    Win32.coTaskMemFree(uriPtr);

    // Same pattern for get_Method (LPWSTR) and get_Headers -> ICoreWebView2HttpRequestHeaders*.
    // For headers, iterate via ICoreWebView2HttpRequestHeaders::GetIterator - non-trivial COM;
    // if you want to defer this, pass an empty header map to SchemeRequest for the MVP and file a
    // follow-up. Tauri v2 doesn't rely on request headers for convertFileSrc.

    // Body: get_Content -> IStream*. May be null (GET). If not null, read fully into a byte[].
    // ...

    final var handler = schemeHandlers.get(schemeOf(uri));
    if (handler == null) return 0; // Let WebView2 fall through - shouldn't happen with our filter.

    final SchemeRequest req = new SchemeRequest(uri, method, headers, body);
    final SchemeResponse resp = handler.handle(req);

    // Build the response IStream via SHCreateMemStream (copies the buffer).
    final var contentStream = Win32.shCreateMemStream(resp.body());

    // Build the "Content-Type: xxx\nHeader: value\n..." string. Yes really - it's newline-joined.
    final var headersStr = buildResponseHeaders(resp);
    final var reasonPhrase = a.allocateFrom(reasonFor(resp.status()), UTF_16LE);
    final var headersW = a.allocateFrom(headersStr, UTF_16LE);

    final var responseOut = a.allocate(ADDRESS);
    // ICoreWebView2Environment::CreateWebResourceResponse - vtable index in WebView2.h
    environment.createWebResourceResponse(contentStream, resp.status(), reasonPhrase, headersW, responseOut);
    final var response = responseOut.get(ADDRESS, 0);
    args.putResponse(response);

    // Release the response COM ptr (put_Response AddRefs).
    release(response);
    if (contentStream.address() != 0) release(contentStream);
    return 0; // S_OK
  } catch (final Throwable t) {
    return 0x80004005; // E_FAIL - never let a Java exception unwind into COM
  }
}
```

Reason phrases: `200 → "OK"`, `404 → "Not Found"`, `500 → "Internal Server Error"`, etc. Use `java.net.http.HttpResponse.BodySubscribers` for a stock list, or just hardcode the top ~10 you actually see. WebView2 does not care much; it uses `statusCode` for logic and only surfaces `reasonPhrase` in debug tools.

### Step 5: cleanup

- Release the stub arena in `close()` (already happens for existing stubs via `arenaStubs.close()`).
- WebView2 releases the callback when the webview is destroyed; no explicit `remove_WebResourceRequested` needed unless you support hot-swapping schemes (we don't).

## Verification

1. `mvn -DskipTests install` at the repo root — must succeed on Windows.
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

3. Confirm the `UnsupportedOperationException` no longer fires for a non-empty map:

    ```java
    Webview.builder().registerSchemeHandler("test", r -> SchemeResponse.builder().build()).build();
    ```

    Should not throw.

## Gotchas

- **Vtable indices drift.** The WebView2 SDK has evolved (`ICoreWebView2`, `ICoreWebView2_2`, `ICoreWebView2_3`, …). Always check the actual `WebView2.h` you're building against, or use `QueryInterface` for a specific `IID` to get a versioned view. The indices for `add_WebResourceRequested` and `AddWebResourceRequestedFilter` are in the base `ICoreWebView2` interface and have been stable since SDK 1.0.
- **UTF-16LE, always.** WebView2's string args are `LPCWSTR`. `allocateFrom(str)` defaults to platform charset — use `allocateFrom(str, StandardCharsets.UTF_16LE)` explicitly.
- **`get_Uri` returns caller-owned memory.** Must be freed with `CoTaskMemFree` (from `ole32.dll`) — otherwise you leak per request. Same for `get_Method` and every other `LPWSTR` out-arg.
- **The response IStream is consumed once.** WebView2 rewinds it internally; you don't need to seek. But don't reuse the same stream for multiple responses.
- **`Content-Length` must match the stream length.** `SHCreateMemStream` records the byte count internally, but if you set a `Content-Length` header in the header string, it must match. Easiest: don't set `Content-Length` explicitly; WebView2 infers it from the stream.
- **COM refcounts.** `put_Response` AddRefs the response; you own the initial ref from `CreateWebResourceResponse`, so `Release` after handing it off. Same for the stream.
- **Never throw across a COM boundary.** Java exceptions propagating into the WebView2 event pump will crash the process. Wrap everything in `try { … } catch (Throwable t) { return E_FAIL; }` — the macOS backend does the same.
- **Filter globs are literal.** `test://*` matches every path under `test://`. If you accidentally register `test:*` (missing slash) you'll match nothing and silently do nothing.
- **Post-navigate registration is too late.** WebView2 caches the first navigation's resource resolution; register schemes before the initial `Navigate` call. In practice this means: register scheme handlers in `Win32WebView` constructor **after** `embedWebView2` (which creates the environment + controller + view) but **before** any user-triggered navigate. `WebviewBuilder.build()` calls `navigate(...)` after `createForPlatform()`, so as long as scheme registration is inside the `Win32WebView` constructor you're safe.

## When you finish

- Run `mvn -DskipTests install` — passes.
- Run the smoke test above — the iframe renders text from the handler.
- Update `Win32WebView.java`'s constructor doc so it no longer says "not yet implemented" (the message inside the removed `throw new UnsupportedOperationException(...)` was the only place claiming that).
- Push a commit onto `feat/scheme-handlers`. Do NOT open a separate PR — the macOS work is the umbrella PR; your commit lands on top of it.

Ping the macOS Claude in the PR description with the WebView2 SDK version you built against.
