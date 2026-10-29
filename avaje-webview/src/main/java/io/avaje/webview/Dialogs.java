package io.avaje.webview;

import module java.base;

/** Option types for the native dialogs on {@link Webview}. */
public final class Dialogs {

  private Dialogs() {}

  /** A named group of file extensions (without the dot), e.g. {@code ("Image", [png, jpg])}. */
  public record Filter(String name, List<String> extensions) {}

  /**
   * Options for {@link Webview#showOpenDialog} / {@link Webview#showSaveDialog}. Any field may be
   * {@code null} (or empty) to use the platform default.
   *
   * @param title dialog title
   * @param defaultPath initial directory, or for a save dialog a directory or a file whose name
   *     pre-fills the name field
   * @param filters allowed extensions; a file matching none of them cannot be chosen
   * @param multiple open only: allow selecting several items
   * @param directory open only: choose directories instead of files
   * @param canCreateDirectories show a "new folder" control
   */
  public record FileDialogOptions(
      String title,
      Path defaultPath,
      List<Filter> filters,
      boolean multiple,
      boolean directory,
      boolean canCreateDirectories) {}

  /** The severity icon of a message dialog. */
  public enum MessageKind {
    INFO,
    WARNING,
    ERROR
  }

  /**
   * Options for {@link Webview#showMessageDialog}.
   *
   * @param buttons button labels in display order; the first is the default button. Empty means a
   *     single "OK".
   */
  public record MessageDialogOptions(
      String title, String message, MessageKind kind, List<String> buttons) {}
}
