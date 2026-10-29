package io.avaje.webview.linux;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.avaje.webview.SchemeHandler;
import io.avaje.webview.SchemeRequest;
import io.avaje.webview.SchemeResponse;

/**
 * Custom URL schemes for WebKitGTK.
 *
 * <p>{@code webkit_web_context_register_uri_scheme} is per {@code WebKitWebContext}, every view
 * shares the default one, and a registration cannot be undone. So each scheme is registered once
 * per process against a single upcall stub in the global arena, and each request is routed to the
 * handlers of the view that issued it ({@code webkit_uri_scheme_request_get_web_view}). A view
 * without a handler for that scheme, including one already closed, gets an error, never a call into
 * a freed stub.
 *
 * <p>Everything except {@link #unregister} runs on the GTK thread.
 */
final class GtkSchemeHandlers {

  private static final Map<Long, Map<String, SchemeHandler>> byWebView = new ConcurrentHashMap<>();
  private static final Set<String> registeredSchemes = ConcurrentHashMap.newKeySet();
  private static MemorySegment callbackStub;

  /** Routes {@code handlers}' schemes to {@code webView}. Call before the view's first load. */
  static void register(MemorySegment webView, Map<String, SchemeHandler> handlers) {
    if (handlers.isEmpty()) {
      return;
    }
    byWebView.put(webView.address(), Map.copyOf(handlers));
    final var ctx = WebKit6.webkitWebContextGetDefault();
    for (final var scheme : handlers.keySet()) {
      if (registeredSchemes.add(scheme)) {
        WebKit6.webkitWebContextRegisterUriScheme(ctx, scheme, stub());
      }
    }
  }

  /** Stops routing requests to {@code webView}'s handlers. */
  static void unregister(MemorySegment webView) {
    byWebView.remove(webView.address());
  }

  private static MemorySegment stub() {
    if (callbackStub == null) {
      try {
        final var mh =
            MethodHandles.lookup()
                .findStatic(
                    GtkSchemeHandlers.class,
                    "onRequest",
                    MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class));
        // Global: WebKit keeps the pointer for the life of the context, i.e. the process.
        callbackStub =
            Linker.nativeLinker()
                .upcallStub(mh, WebKit6.URI_SCHEME_REQUEST_CALLBACK_DESC, Arena.global());
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new RuntimeException("Failed to build scheme handler upcall stub", e);
      }
    }
    return callbackStub;
  }

  /**
   * {@code WebKitURISchemeRequestCallback}. Runs the Java handler synchronously and finishes the
   * request inline, as {@code CocoaWebView#onStartURLSchemeTask} does. Nothing may escape into C.
   */
  @SuppressWarnings("unused")
  static void onRequest(MemorySegment request, MemorySegment userData) {
    try {
      final var url = WebKit6.uriSchemeRequestGetUri(request);
      final var webView = WebKit6.uriSchemeRequestGetWebView(request);
      final var handlers = byWebView.get(webView.address());
      final var handler = handlers == null ? null : handlers.get(schemeOf(url));
      if (handler == null) {
        WebKit6.uriSchemeRequestFinishError(request, "No scheme handler for " + url);
        return;
      }
      final var method = WebKit6.uriSchemeRequestGetHttpMethod(request);
      final SchemeResponse resp;
      try {
        resp = handler.handle(new SchemeRequest(url, method, Map.of(), new byte[0]));
      } catch (final Throwable t) {
        WebKit6.uriSchemeRequestFinishError(
            request, t.getMessage() == null ? t.toString() : t.getMessage());
        return;
      }
      if (resp == null) {
        WebKit6.uriSchemeRequestFinishError(request, "SchemeHandler returned null");
        return;
      }
      WebKit6.uriSchemeRequestFinish(
          request, resp.status(), resp.contentType(), resp.headers(), resp.body());
    } catch (final Throwable t) {
      // Never let an exception propagate back into C - it would crash the process.
    }
  }

  private static String schemeOf(String url) {
    final var colon = url == null ? -1 : url.indexOf(':');
    return colon < 0 ? "" : url.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
  }

  private GtkSchemeHandlers() {}
}
