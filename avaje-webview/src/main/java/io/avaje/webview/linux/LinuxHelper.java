package io.avaje.webview.linux;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.avaje.webview.Webview;

/** GTK4-specific window operations helper for {@link GtkWebView}. */
final class LinuxHelper {

  private static final System.Logger log = System.getLogger("io.avaje.webview");

  /**
   * Image extensions GTK's icon theme picks up for "unthemed" icons dropped directly into a search
   * path directory. Anything else (JPEG, ICO, …) has no icon-theme loader and is rejected rather
   * than silently ignored by GTK.
   */
  private static final Set<String> ICON_EXTENSIONS = Set.of("png", "svg", "xpm");

  /** Distinguishes successive icons so GTK never serves a cached lookup for a replaced file. */
  private static final AtomicInteger ICON_SEQ = new AtomicInteger();

  /**
   * Process-wide directory registered once with the icon theme; every {@code setIcon} call drops
   * another file into it. Registering a fresh search path per call would grow the theme's path list
   * without bound.
   */
  private static volatile Path iconDir;

  private LinuxHelper() {}

  /** Requests the window manager to make the window fullscreen. */
  static void fullscreen(Webview webview) {
    Gtk4.gtkWindowFullscreen(webview.nativeWindowPointer());
  }

  /** Asks the window manager to minimize (iconify) the window. */
  static void minimizeWindow(Webview webview) {
    Gtk4.gtkWindowMinimize(webview.nativeWindowPointer());
  }

  /** Requests the window manager to maximize the window. */
  static void maximizeWindow(Webview webview) {
    Gtk4.gtkWindowMaximize(webview.nativeWindowPointer());
  }

  /** Begins a native window-move grab, as if the user had grabbed the title bar. */
  static void startWindowDrag(Webview webview) {
    Gtk4.gtkWindowBeginMoveDrag(webview.nativeWindowPointer());
  }

  /**
   * Sets the window icon from a file on disk.
   *
   * <p>GTK4 has no file-based window icon API — {@code gtk_window_set_icon_from_file} and friends
   * were removed and {@code gtk_window_set_icon_name} (an icon <em>theme</em> lookup) is all that
   * remains. To bridge the two, the file is copied into a private directory that is registered on
   * the display's {@code GtkIconTheme} search path, where GTK's "unthemed icon" scan picks it up
   * under the generated file name.
   *
   * <p>Platform reach: on X11 this ends up as the {@code _NET_WM_ICON} property and is honored by
   * every window manager. On Wayland the icon travels over the {@code xdg-toplevel-icon} protocol,
   * which GTK only speaks from 4.20 onwards — on older GTK or a compositor without that protocol,
   * the icon shown is the one from the application's {@code .desktop} file (matched by app-id) and
   * this call has no visible effect. Nothing fails in that case.
   *
   * @param webview the window to apply the icon to
   * @param path a {@code .png}, {@code .svg}, or {@code .xpm} file — the formats GTK's icon theme
   *     can load
   * @param ext the already-validated lower-case extension, from {@link #iconExtension(Path)}
   */
  static void setIcon(Webview webview, Path path, String ext) {
    final var name = "avaje-webview-icon-" + ICON_SEQ.incrementAndGet();
    try {
      final var staged = iconSearchDir().resolve(name + '.' + ext);
      Files.copy(path, staged, StandardCopyOption.REPLACE_EXISTING);
      // Registered after the directory itself, so it is removed first on shutdown (deleteOnExit
      // runs in reverse registration order) and the directory is empty by the time its turn comes.
      staged.toFile().deleteOnExit();
    } catch (final IOException e) {
      log.log(System.Logger.Level.WARNING, "setIcon failed for {0}: {1}", path, e.getMessage());
      return;
    }
    Gtk4.gtkWindowSetIconName(webview.nativeWindowPointer(), name);
  }

  /**
   * Validates that {@code path} is a format GTK's icon theme can load, and returns its lower-case
   * extension. Called on the caller's thread before the work is dispatched onto the GTK thread, so
   * that a bad argument surfaces as a normal exception rather than being thrown out of an upcall
   * stub in the middle of the GLib main loop.
   *
   * @param path the icon file
   * @return the lower-case extension, one of {@code png}, {@code svg}, {@code xpm}
   * @throws IllegalArgumentException if the extension is anything else
   */
  static String iconExtension(Path path) {
    final var fileName = path.getFileName().toString();
    final var dot = fileName.lastIndexOf('.');
    final var ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    if (!ICON_EXTENSIONS.contains(ext)) {
      throw new IllegalArgumentException(
          "GTK4 setIcon requires a .png, .svg, or .xpm file, got: " + fileName);
    }
    return ext;
  }

  /**
   * Returns the process-wide icon staging directory, creating and registering it with the icon
   * theme on first use.
   */
  private static Path iconSearchDir() throws IOException {
    var dir = iconDir;
    if (dir == null) {
      synchronized (LinuxHelper.class) {
        dir = iconDir;
        if (dir == null) {
          dir = Files.createTempDirectory("avaje-webview-icons-");
          dir.toFile().deleteOnExit();
          Gtk4.gtkRegisterIconSearchPath(dir.toAbsolutePath().toString());
          iconDir = dir;
        }
      }
    }
    return dir;
  }

  /**
   * Applies a dark or light appearance to all GTK windows in this process.
   *
   * @param webview used only to satisfy the method signature; GTK settings are process-global
   * @param shouldBeDark {@code true} for dark theme, {@code false} for light theme
   */
  static void setWindowAppearance(Webview webview, boolean shouldBeDark) {
    try (var arena = Arena.ofConfined()) {
      final var settings = Gtk4.gtkSettingsGetDefault();
      if (settings.address() == 0L) {
        throw new RuntimeException("Failed to get GTK settings");
      }
      final var propertyName =
          arena.allocateFrom("gtk-application-prefer-dark-theme", StandardCharsets.UTF_8);
      GLib.gObjectSet(settings, propertyName, shouldBeDark ? 1 : 0, MemorySegment.NULL);
    }
  }
}
