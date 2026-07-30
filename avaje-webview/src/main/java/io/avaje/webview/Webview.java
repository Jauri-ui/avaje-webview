/**
 * MIT LICENSE
 *
 * <p>Copyright (c) 2024 Alex Bowles @ Casterlabs
 *
 * <p>Permission is hereby granted, free of charge, to any person obtaining a copy of this software
 * and associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * <p>The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * <p>THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
 * BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package io.avaje.webview;

import module java.base;

import module org.jspecify;

/**
 * A native webview window.
 *
 * <p>Renders HTML or a URL, runs JavaScript in the page, and binds Java callbacks into the page's
 * JavaScript environment.
 */
public interface Webview extends Closeable, Runnable {

  /**
   * Creates a new builder to configure and instantiate a {@code Webview}.
   *
   * @return a new Builder instance
   */
  static Builder builder() {
    return new WebviewBuilder();
  }

  /**
   * Returns the native window handle/pointer.
   *
   * <p>Direct access to native memory. Only worth reaching for when integrating with another
   * native library, and only with the platform's threading rules in mind.
   *
   * @return the {@link MemorySegment} pointing to the native window
   */
  MemorySegment nativeWindowPointer();

  /**
   * Sets the HTML content of the webview directly.
   *
   * @param html the HTML string to render, or {@code null} to clear
   */
  void setHTML(@Nullable String html);

  /**
   * Navigates the webview to the specified URL.
   *
   * @param url the URL to load, such as "https://google.com", or {@code null}
   */
  void navigate(@Nullable String url);

  /**
   * Sets the title of the native window.
   *
   * @param title the window title
   */
  void setTitle(@NonNull String title);

  /**
   * Sets the minimum window dimensions.
   *
   * @param width the minimum width in pixels
   * @param height the minimum height in pixels
   */
  void setMinSize(int width, int height);

  /**
   * Sets the maximum window dimensions.
   *
   * <p>On Linux (GTK4) there is no geometry-hint API to hand the limit to the window manager, so it
   * is enforced after the fact: a window that exceeds the maximum is resized back down, which the
   * user sees as a snap-back rather than a resize that refuses to go further. The limit is not
   * applied while the window is maximized or fullscreen.
   *
   * @param width the maximum width in pixels
   * @param height the maximum height in pixels
   */
  void setMaxSize(int width, int height);

  /**
   * Resizes the window to the specified dimensions.
   *
   * @param width the width in pixels
   * @param height the height in pixels
   */
  void setSize(int width, int height);

  /**
   * Sets the window size and prevents the user from manually resizing it.
   *
   * @param width the width in pixels
   * @param height the height in pixels
   */
  void setFixedSize(int width, int height);

  /**
   * Registers a script to run automatically on every page load. Iframes are excluded by default.
   *
   * @param script the JavaScript source code to run
   * @implNote The script is executed immediately after the {@code window.load} event.
   * @see #setInitScript(String, boolean)
   */
  void setInitScript(@NonNull String script);

  /**
   * Registers a script to run automatically on every page load.
   *
   * @param script the JavaScript source code to run
   * @param allowNestedAccess {@code true} to inject the script into nested iframes as well
   * @implNote The script is executed immediately after the {@code window.load} event.
   */
  void setInitScript(@NonNull String script, boolean allowNestedAccess);

  /**
   * Evaluates the provided JavaScript string immediately in the current context.
   *
   * @param script the JavaScript source code to execute
   */
  void eval(@NonNull String script);

  /**
   * Binds a Java callback to a global JavaScript function.
   *
   * <p>Calling the function from JavaScript returns a {@code Promise} that settles once the Java
   * handler finishes, so the browser UI thread keeps running while Java works.
   *
   * @param name the name of the function on the JavaScript {@code window} object, such as
   *     "submitData"
   * @param handler the callback logic to execute when the function is invoked
   * @implNote Binds persist across page navigations. Callbacks are registered after {@code
   *     window.load}.
   */
  void bind(@NonNull String name, @NonNull WebviewBinding handler);

  /**
   * Removes a previously bound JavaScript function.
   *
   * @param name the name of the function to unbind
   */
  void unbind(@NonNull String name);

  /**
   * Schedules a task to be executed on the webview's internal event thread.
   *
   * <p>The safe way to touch webview state from a background thread.
   *
   * @param handler the task to run on the event thread
   */
  void dispatch(@NonNull Runnable handler);

  /**
   * Starts the webview event loop.
   *
   * <p>Blocks until the window closes or {@link #close()} is called, unless the webview is
   * configured in asynchronous mode.
   *
   * @see #close()
   */
  @Override
  void run();

  /**
   * Closes the webview window and releases all associated native resources. This will cause the
   * {@link #run()} loop to exit.
   */
  @Override
  void close();

  /**
   * Requests the window to use a dark theme appearance.
   *
   * @param shouldAppearDark {@code true} for dark mode, {@code false} for light mode
   */
  void setDarkAppearance(boolean shouldAppearDark);

  /**
   * Maximizes the webview window to fill the screen.
   *
   * @return this Webview instance for chaining
   */
  Webview maximizeWindow();

  /**
   * Restores a previously maximized window to its prior size. No-op if the window is not currently
   * maximized.
   *
   * @return this Webview instance for chaining
   */
  Webview unmaximizeWindow();

  /**
   * Switches the webview window to fullscreen mode.
   *
   * @return this Webview instance for chaining
   */
  Webview fullscreen();

  /**
   * Enters or exits fullscreen mode. Idempotent: passing the current fullscreen state is a no-op.
   *
   * @param on {@code true} to enter fullscreen, {@code false} to exit
   * @return this Webview instance for chaining
   */
  Webview setFullscreen(boolean on);

  /**
   * Minimizes the webview window to the taskbar/dock.
   *
   * @return this Webview instance for chaining
   */
  Webview minimizeWindow();

  /**
   * Restores a previously minimized window. No-op if the window is not currently minimized.
   *
   * @return this Webview instance for chaining
   */
  Webview unminimizeWindow();

  /**
   * Begins a native window-move operation, as if the user had grabbed the title bar and started
   * dragging.
   *
   * <p>Call this from a binding invoked on {@code mousedown} over a custom draggable area (e.g. a
   * custom title bar) to make that area drag the window.
   *
   * <pre>{@code
   * webview.bind("startDrag", req -> {
   *   webview.startWindowDrag();
   *   return null;
   * });
   * }</pre>
   */
  void startWindowDrag();

  /**
   * Moves the window so that its top-left corner sits at logical screen coordinates ({@code x},
   * {@code y}), measured from the top-left of the primary screen.
   *
   * <p>On GTK4 (Linux/Wayland) programmatic window positioning is not supported by the window
   * manager and this call is a no-op.
   *
   * @param x new left edge in pixels
   * @param y new top edge in pixels
   * @return this Webview instance for chaining
   */
  Webview setPosition(int x, int y);

  /**
   * Centers the window on the screen it currently occupies.
   *
   * <p>On GTK4 this defers to the window manager and may be a no-op.
   *
   * @return this Webview instance for chaining
   */
  Webview center();

  /**
   * Returns the window's current top-left position on the primary screen, in logical pixels. The
   * returned array is {@code {x, y}}. Must be called from the webview's UI thread; typical usage
   * is inside a {@link #bind(String, WebviewBinding) bind} callback, which is already dispatched
   * on the UI thread.
   *
   * <p>On GTK4 this may return {@code {0, 0}} because Wayland does not expose absolute window
   * coordinates to clients.
   *
   * @return a fresh {@code int[2]} of the current logical top-left {@code {x, y}}
   */
  int[] getPosition();

  /**
   * Makes the window visible if it was previously hidden. No-op if the window is already visible.
   *
   * @return this Webview instance for chaining
   */
  Webview show();

  /**
   * Hides the window without destroying it. The window can later be restored with {@link #show()}
   * or {@link #setFocus()}.
   *
   * @return this Webview instance for chaining
   */
  Webview hide();

  /**
   * Brings the window to the front, makes it the key/active window, and gives it keyboard focus.
   *
   * @return this Webview instance for chaining
   */
  Webview setFocus();

  /**
   * Requests the window to stay above (or return to normal stacking with) all other windows.
   *
   * <p>On GTK4 this maps to {@code gtk_window_set_keep_above}, which is deprecated in GTK4 and may
   * be ignored by some Wayland compositors.
   *
   * @param onTop {@code true} to always keep the window above others; {@code false} to restore
   *     normal stacking
   * @return this Webview instance for chaining
   */
  Webview setAlwaysOnTop(boolean onTop);

  /**
   * Enables or disables user resizing of the window.
   *
   * @param resizable {@code true} to allow the user to drag window edges to resize
   * @return this Webview instance for chaining
   */
  Webview setResizable(boolean resizable);

  /**
   * Shows or hides native window decorations (title bar, borders, minimize/maximize/close
   * buttons).
   *
   * @param decorated {@code true} to show decorations; {@code false} to remove them
   * @return this Webview instance for chaining
   */
  Webview setDecorations(boolean decorated);

  /**
   * Returns whether the window is currently maximized (zoomed to fill the screen but not
   * fullscreen). Must be called from the webview's UI thread.
   *
   * @return {@code true} if maximized
   */
  boolean isMaximized();

  /**
   * Returns whether the window is currently minimized/iconified. Must be called from the webview's
   * UI thread.
   *
   * @return {@code true} if minimized
   */
  boolean isMinimized();

  /**
   * Returns whether the window is currently in fullscreen mode. Must be called from the webview's
   * UI thread.
   *
   * @return {@code true} if fullscreen
   */
  boolean isFullscreen();

  /**
   * Returns whether the window is visible on screen (not hidden). A minimized window is still
   * considered visible.
   *
   * @return {@code true} if visible
   */
  boolean isVisible();

  /**
   * Returns whether the window currently has keyboard focus.
   *
   * @return {@code true} if focused
   */
  boolean isFocused();

  /**
   * Returns whether the window currently shows native decorations (title bar, borders).
   *
   * @return {@code true} if decorated
   */
  boolean isDecorated();

  /**
   * Returns whether the user is allowed to resize the window by dragging its edges.
   *
   * @return {@code true} if resizable
   */
  boolean isResizable();

  /**
   * Sets the icon for the webview window.
   *
   * <p>The accepted format is platform-specific: Windows requires a {@code .ico}; Linux requires a
   * {@code .png}, {@code .svg}, or {@code .xpm}, the formats GTK's icon theme can load. macOS
   * accepts anything {@code NSImage} can read.
   *
   * @param path to the icon file
   * @throws IllegalArgumentException on Linux, and {@link IllegalStateException} on Windows, if the
   *     file is not in a format that platform can load
   */
  void setIcon(Path path);

  /**
   * Sets the icon for the webview window, for icons held as classpath resources.
   *
   * @param uri location of the icon file
   */
  void setIcon(URI uri);

  /** Interface for configuring and instantiating {@link Webview} instances. */
  public interface Builder {

    /**
     * Sets the title of the webview window.
     *
     * @param title the window title
     * @return this builder
     */
    Builder title(String title);

    /**
     * Enables or disables browser developer tools, reached with right-click then Inspect.
     *
     * @param enableDeveloperTools {@code true} to enable them where the platform supports it
     * @return this builder
     */
    Builder enableDeveloperTools(boolean enableDeveloperTools);

    /**
     * Enables JS console redirection to {@link System.Logger}. Defaults to {@code false}.
     *
     * @param redirectConsole {@code true} to forward console output to the Java logger
     * @return this builder
     */
    Builder redirectConsole(boolean redirectConsole);

    /**
     * Attaches the webview to an existing native window handle.
     *
     * @param windowPointer a {@link MemorySegment} pointing to a native window handle
     * @return this builder
     */
    Builder windowPointer(MemorySegment windowPointer);

    /**
     * Sets the initial width of the window. Defaults to 800.
     *
     * @param width width in pixels
     * @return this builder
     */
    Builder width(int width);

    /**
     * Sets the initial height of the window. Defaults to 600.
     *
     * @param height height in pixels
     * @return this builder
     */
    Builder height(int height);

    /**
     * Sets the initial HTML content to be rendered.
     *
     * @param html raw HTML string
     * @return this builder
     */
    Builder html(String html);

    /**
     * Sets the initial URL for the webview to load.
     *
     * @param url the URL, such as "https://localhost:8080"
     * @return this builder
     */
    Builder navigate(String url);

    /**
     * Creates the window without native OS decorations (title bar, borders, and minimize/maximize/
     * close buttons). Defaults to {@code false}. The window remains resizable via its edges.
     *
     * <p>With no native title bar to grab, pair this with {@link Webview#startWindowDrag()} to
     * build a draggable title bar of your own.
     *
     * @param borderless {@code true} to remove native window decorations
     * @return this builder
     */
    Builder borderless(boolean borderless);

    /**
     * Creates the window without native OS decorations, with optional retention of the native
     * window outline (drop shadow and thin border).
     *
     * <p>With {@code outline} set, only the title bar goes and the surrounding border and drop
     * shadow stay. Platform behaviour:
     *
     * <ul>
     *   <li><b>Windows</b> removes only the title bar area; the left, right, and bottom borders
     *       remain, preserving the DWM drop shadow and 1-px outline.
     *   <li><b>macOS</b> uses a transparent, hidden title bar so the native window shadow and
     *       border are retained while web content covers the full window area.
     *   <li>on <b>Linux</b> this has no effect.
     * </ul>
     *
     * @param borderless {@code true} to remove native window decorations
     * @param outline {@code true} to keep the native window outline when borderless
     * @return this builder
     */
    Builder borderless(boolean borderless, boolean outline);

    /**
     * Marks this window as owned by {@code parent}, making it behave like a modal child/dialog
     * window.
     *
     * <p>The parent stops receiving mouse and keyboard input as soon as this window is built, and
     * gets it back when this window closes.
     *
     * @param parent the {@code Webview} that should be blocked while this window is open
     * @return this builder
     */
    Builder parent(Webview parent);

    /**
     * Marks this window as owned by {@code parent} and optionally locks their positions together.
     *
     * <p>With {@code moveParentWithChild} set, dragging the child moves the parent by the same
     * amount so the two stay visually locked.
     *
     * <p>On Linux (GTK4) this flag has no effect: there is no window-positioning API to read the
     * child's position from or to apply a delta to the parent with. The child is always created as a
     * modal transient of its parent, which leaves placement and stacking to the window manager.
     *
     * @param parent the {@code Webview} that should be blocked while this window is open
     * @param moveParentWithChild {@code true} to synchronise parent position with child
     * @return this builder
     */
    Builder parent(Webview parent, boolean moveParentWithChild);

    /**
     * Maximizes the window immediately after it is shown. Defaults to {@code false}.
     *
     * @param maximize {@code true} to start maximized
     * @return this builder
     */
    Builder maximize(boolean maximize);

    /**
     * Switches the window to fullscreen immediately after it is shown. Defaults to {@code false}.
     * Takes precedence over {@link #maximize(boolean)} when both are set.
     *
     * @param fullscreen {@code true} to start fullscreen
     * @return this builder
     */
    Builder fullscreen(boolean fullscreen);

    /**
     * Controls whether the maximize button is shown on the native title bar. Defaults to {@code
     * true}.
     *
     * <p>On Linux (GTK4) the button cannot be removed from a window-manager-drawn title bar, so
     * maximize is vetoed instead: the window un-maximizes immediately whenever it becomes maximized,
     * whether from the button, a title-bar double-click, or a keyboard shortcut.
     *
     * @param maximizable {@code false} to hide/disable the maximize button
     * @return this builder
     */
    Builder maximizable(boolean maximizable);

    /**
     * Sets the minimum size the user can resize the window to.
     *
     * @param width minimum width in pixels
     * @param height minimum height in pixels
     * @return this builder
     */
    Builder minSize(int width, int height);

    /**
     * Sets the maximum size the user can resize the window to.
     *
     * @param width maximum width in pixels
     * @param height maximum height in pixels
     * @return this builder
     */
    Builder maxSize(int width, int height);

    /**
     * Controls whether the user can resize the window. Defaults to {@code true}.
     *
     * <p>At {@code false} the window opens at the given {@link #width(int)} and {@link
     * #height(int)} and stays there, the same as calling {@link Webview#setFixedSize(int, int)}
     * right after build.
     *
     * @param resizable {@code false} to prevent user resizing
     * @return this builder
     */
    Builder resizable(boolean resizable);

    /**
     * Enables a transparent window background so web content with a transparent or semi-transparent
     * CSS background shows through to the desktop. Defaults to {@code false}.
     *
     * @param transparent {@code true} to enable transparency
     * @return this builder
     */
    Builder transparent(boolean transparent);

    /**
     * Registers a custom URL scheme handler on this webview.
     *
     * <p>Every navigation, {@code <img>} load, {@code fetch()}, XHR, or subresource whose URL
     * starts with {@code <scheme>://} is intercepted and dispatched to {@code handler} instead of
     * being resolved over the network. The handler runs on the webview's UI thread.
     *
     * <p>Handlers must be registered <em>before</em> {@link #build()}. Every native backend
     * (WKWebView, WebView2, WebKitGTK) requires registration prior to webview instantiation;
     * registering after the webview exists is not supported.
     *
     * <p>Reserved schemes ({@code http}, {@code https}, {@code file}, {@code about}, {@code data},
     * {@code javascript}, {@code ws}, {@code wss}, {@code ftp}) cannot be intercepted and will
     * throw {@link IllegalArgumentException} at build time.
     *
     * <p>Scheme names must match {@code [a-z][a-z0-9+.-]*} — lowercase letters, digits, {@code +},
     * {@code .}, {@code -} — starting with a letter. Case is not significant for lookup but the
     * registered value must be lowercase.
     *
     * @param scheme the URL scheme to intercept, e.g. {@code "app"}, {@code "asset"},
     *     {@code "tauri"}
     * @param handler the handler that produces a {@link SchemeResponse} for each intercepted
     *     request
     * @return this builder
     * @throws IllegalArgumentException if {@code scheme} is malformed or reserved
     */
    Builder registerSchemeHandler(String scheme, SchemeHandler handler);

    /**
     * Builds the Webview.
     *
     * @return the configured Webview
     */
    Webview build();
  }
}
