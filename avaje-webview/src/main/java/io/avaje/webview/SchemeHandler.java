package io.avaje.webview;

import module java.base;

/**
 * Handles a custom URL scheme request from the webview.
 *
 * <p>Once a {@code SchemeHandler} is registered on a scheme via {@link
 * Webview.Builder#registerSchemeHandler(String, SchemeHandler)}, every navigation, {@code <img>},
 * {@code fetch()}, XHR, or subresource load matching {@code <scheme>://…} inside the webview is
 * intercepted, dispatched to the handler, and served from Java rather than the network.
 *
 * <p>Typical uses:
 *
 * <ul>
 *   <li>Serving app assets from a jar / classpath instead of the filesystem
 *   <li>Bridging {@code convertFileSrc(path)}-style URLs to file-system paths
 *   <li>Implementing a local API without listening on a TCP port
 * </ul>
 *
 * <p><b>Threading.</b> The handler is invoked on the webview's UI thread. Long-running I/O should
 * complete quickly (single-digit milliseconds); if the work is expensive, offload it to a worker
 * thread <em>before</em> registering the handler and cache the result — blocking here freezes the
 * webview.
 *
 * <p><b>Registration timing.</b> Scheme handlers must be registered on the {@link Webview.Builder}
 * before {@link Webview.Builder#build()}. Registering after the webview exists has no effect on
 * every backend (WKWebView, WebView2, WebKitGTK all require pre-instantiation registration).
 */
@FunctionalInterface
public interface SchemeHandler {

  /**
   * Handles a single request. Return a {@link SchemeResponse} to serve the request or throw to
   * fail it (a 500 status is returned to the webview).
   *
   * @param request the intercepted request
   * @return the response to return to the webview
   * @throws Exception if the request cannot be served — surfaces to the webview as a load failure
   */
  SchemeResponse handle(SchemeRequest request) throws Exception;
}
