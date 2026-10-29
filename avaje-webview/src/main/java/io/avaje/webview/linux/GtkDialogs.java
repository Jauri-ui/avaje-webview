package io.avaje.webview.linux;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import io.avaje.webview.Dialogs;

/**
 * Native dialogs on GTK4: {@code GtkFileDialog} for open/save and {@code GtkAlertDialog} for
 * messages, both GTK 4.10+.
 *
 * <p>Both are asynchronous: each {@code start*} method shows the dialog and completes a future from
 * its {@code GAsyncReadyCallback}. Blocking the caller until then is {@link GtkWebView}'s job. All
 * methods run on the GTK thread.
 *
 * <p>Kept apart from {@link Gtk4} so that a GTK older than 4.10 fails only when a dialog is opened
 * ({@code UnsatisfiedLinkError} from this class's initialiser), not at startup.
 */
final class GtkDialogs {

  /** {@code GtkDialogError}: {@code GTK_DIALOG_ERROR_FAILED}; the others mean cancelled. */
  private static final int GTK_DIALOG_ERROR_FAILED = 0;

  private static final System.Logger LOG = System.getLogger(GtkDialogs.class.getName());

  private static final Linker LINKER = Linker.nativeLinker();

  private static final SymbolLookup LOOKUP =
      SymbolLookup.libraryLookup("libgtk-4.so.1", Arena.global())
          .or(SymbolLookup.libraryLookup("libgio-2.0.so.0", Arena.global()))
          .or(SymbolLookup.libraryLookup("libgobject-2.0.so.0", Arena.global()))
          .or(SymbolLookup.libraryLookup("libglib-2.0.so.0", Arena.global()));

  /** {@code GAsyncReadyCallback}: {@code void(*)(GObject* source, GAsyncResult* res, gpointer)}. */
  private static final FunctionDescriptor ASYNC_READY_DESC =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);

  // GtkFileDialog
  private static final MethodHandle FILE_DIALOG_NEW =
      downcall("gtk_file_dialog_new", FunctionDescriptor.of(ADDRESS));
  private static final MethodHandle FILE_DIALOG_SET_TITLE =
      downcall("gtk_file_dialog_set_title", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  private static final MethodHandle FILE_DIALOG_SET_INITIAL_FOLDER =
      downcall("gtk_file_dialog_set_initial_folder", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  private static final MethodHandle FILE_DIALOG_SET_INITIAL_NAME =
      downcall("gtk_file_dialog_set_initial_name", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  private static final MethodHandle FILE_DIALOG_SET_FILTERS =
      downcall("gtk_file_dialog_set_filters", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  /**
   * {@code gtk_file_dialog_{open,open_multiple,select_folder,select_multiple_folders,save}(
   * GtkFileDialog*, GtkWindow* parent, GCancellable*, GAsyncReadyCallback, gpointer)} all share
   * this shape.
   */
  private static final FunctionDescriptor START_DESC =
      FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);

  /** {@code gtk_file_dialog_*_finish(GtkFileDialog*, GAsyncResult*, GError**)}, returning a {@code GFile*} or {@code GListModel*}. */
  private static final FunctionDescriptor FINISH_DESC =
      FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS);

  private static final MethodHandle OPEN = downcall("gtk_file_dialog_open", START_DESC);
  private static final MethodHandle OPEN_FINISH = downcall("gtk_file_dialog_open_finish", FINISH_DESC);
  private static final MethodHandle OPEN_MULTIPLE =
      downcall("gtk_file_dialog_open_multiple", START_DESC);
  private static final MethodHandle OPEN_MULTIPLE_FINISH =
      downcall("gtk_file_dialog_open_multiple_finish", FINISH_DESC);
  private static final MethodHandle SELECT_FOLDER =
      downcall("gtk_file_dialog_select_folder", START_DESC);
  private static final MethodHandle SELECT_FOLDER_FINISH =
      downcall("gtk_file_dialog_select_folder_finish", FINISH_DESC);
  private static final MethodHandle SELECT_MULTIPLE_FOLDERS =
      downcall("gtk_file_dialog_select_multiple_folders", START_DESC);
  private static final MethodHandle SELECT_MULTIPLE_FOLDERS_FINISH =
      downcall("gtk_file_dialog_select_multiple_folders_finish", FINISH_DESC);
  private static final MethodHandle SAVE = downcall("gtk_file_dialog_save", START_DESC);
  private static final MethodHandle SAVE_FINISH = downcall("gtk_file_dialog_save_finish", FINISH_DESC);

  // GtkFileFilter + GListStore
  private static final MethodHandle FILE_FILTER_NEW =
      downcall("gtk_file_filter_new", FunctionDescriptor.of(ADDRESS));
  private static final MethodHandle FILE_FILTER_SET_NAME =
      downcall("gtk_file_filter_set_name", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  private static final MethodHandle FILE_FILTER_ADD_SUFFIX =
      downcall("gtk_file_filter_add_suffix", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  private static final MethodHandle FILE_FILTER_GET_TYPE =
      downcall("gtk_file_filter_get_type", FunctionDescriptor.of(JAVA_LONG));
  private static final MethodHandle LIST_STORE_NEW =
      downcall("g_list_store_new", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
  private static final MethodHandle LIST_STORE_APPEND =
      downcall("g_list_store_append", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

  // GListModel / GFile
  private static final MethodHandle LIST_MODEL_GET_N_ITEMS =
      downcall("g_list_model_get_n_items", FunctionDescriptor.of(JAVA_INT, ADDRESS));
  private static final MethodHandle LIST_MODEL_GET_ITEM =
      downcall("g_list_model_get_item", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
  private static final MethodHandle FILE_NEW_FOR_PATH =
      downcall("g_file_new_for_path", FunctionDescriptor.of(ADDRESS, ADDRESS));
  private static final MethodHandle FILE_GET_PATH =
      downcall("g_file_get_path", FunctionDescriptor.of(ADDRESS, ADDRESS));

  // GtkAlertDialog. gtk_alert_dialog_new is variadic (a printf format), so the dialog is built
  // with the non-variadic g_object_new_with_properties instead.
  private static final MethodHandle ALERT_DIALOG_GET_TYPE =
      downcall("gtk_alert_dialog_get_type", FunctionDescriptor.of(JAVA_LONG));
  private static final MethodHandle OBJECT_NEW_WITH_PROPERTIES =
      downcall(
          "g_object_new_with_properties",
          FunctionDescriptor.of(ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS));
  private static final MethodHandle ALERT_SET_MESSAGE =
      downcall("gtk_alert_dialog_set_message", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  private static final MethodHandle ALERT_SET_DETAIL =
      downcall("gtk_alert_dialog_set_detail", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  private static final MethodHandle ALERT_SET_BUTTONS =
      downcall("gtk_alert_dialog_set_buttons", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
  private static final MethodHandle ALERT_SET_DEFAULT_BUTTON =
      downcall("gtk_alert_dialog_set_default_button", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
  private static final MethodHandle ALERT_SET_CANCEL_BUTTON =
      downcall("gtk_alert_dialog_set_cancel_button", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
  private static final MethodHandle ALERT_CHOOSE = downcall("gtk_alert_dialog_choose", START_DESC);
  private static final MethodHandle ALERT_CHOOSE_FINISH =
      downcall(
          "gtk_alert_dialog_choose_finish", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

  private static final MethodHandle QUARK_FROM_STRING =
      downcall("g_quark_from_string", FunctionDescriptor.of(JAVA_INT, ADDRESS));

  /**
   * Completions waiting for their {@code GAsyncReadyCallback}, keyed by the id passed as {@code
   * user_data}. One shared stub serves every dialog.
   */
  private static final Map<Long, BiConsumer<MemorySegment, MemorySegment>> pending =
      new ConcurrentHashMap<>();

  private static final AtomicLong nextId = new AtomicLong(1);
  private static final MemorySegment READY_STUB = buildReadyStub();

  /** Shows an open (or select-folder) dialog; completes {@code out} with the chosen paths. */
  static void startOpen(
      MemorySegment parent, Dialogs.FileDialogOptions o, CompletableFuture<List<Path>> out) {
    final var dialog = newFileDialog(o, false);
    final MethodHandle start;
    final MethodHandle finish;
    final boolean many = o.multiple();
    if (o.directory()) {
      start = many ? SELECT_MULTIPLE_FOLDERS : SELECT_FOLDER;
      finish = many ? SELECT_MULTIPLE_FOLDERS_FINISH : SELECT_FOLDER_FINISH;
    } else {
      start = many ? OPEN_MULTIPLE : OPEN;
      finish = many ? OPEN_MULTIPLE_FINISH : OPEN_FINISH;
    }
    begin(
        start,
        dialog,
        parent,
        res -> {
          final var obj = finishCall(finish, dialog, res);
          if (obj == null) {
            return List.of();
          }
          try {
            if (many) {
              return pathsOf(obj);
            }
            final var path = pathOf(obj);
            return path == null ? List.<Path>of() : List.of(path);
          } finally {
            GLib.gObjectUnref(obj);
          }
        },
        out);
  }

  /** Shows a save dialog; completes {@code out} with the chosen path, or {@code null}. */
  static void startSave(
      MemorySegment parent, Dialogs.FileDialogOptions o, CompletableFuture<Path> out) {
    final var dialog = newFileDialog(o, true);
    begin(
        SAVE,
        dialog,
        parent,
        res -> {
          final var file = finishCall(SAVE_FINISH, dialog, res);
          if (file == null) {
            return null;
          }
          try {
            return pathOf(file);
          } finally {
            GLib.gObjectUnref(file);
          }
        },
        out);
  }

  /**
   * Shows a message dialog; completes {@code out} with the pressed button's index. Dismissing it
   * (Escape, closing the window) counts as the last button, which for the usual "OK"/"Cancel" or
   * "Yes"/"No" order is the negative answer, and for a lone "OK" is that button.
   *
   * <p>{@code GtkAlertDialog} has no severity icon, so {@code kind} is not shown.
   */
  static void startMessage(
      MemorySegment parent, Dialogs.MessageDialogOptions o, CompletableFuture<Integer> out) {
    final var buttons =
        o.buttons() == null || o.buttons().isEmpty() ? List.of("OK") : o.buttons();
    final MemorySegment dialog;
    try (var a = Arena.ofConfined()) {
      dialog =
          (MemorySegment)
              OBJECT_NEW_WITH_PROPERTIES.invokeExact(
                  (long) ALERT_DIALOG_GET_TYPE.invokeExact(), 0, MemorySegment.NULL, MemorySegment.NULL);
      // GtkAlertDialog's bold heading is "message" and the body is "detail" - the same split as
      // NSAlert's messageText/informativeText that CocoaWebView maps title/message onto.
      final var heading = o.title() != null ? o.title() : o.message();
      ALERT_SET_MESSAGE.invokeExact(dialog, utf8(a, heading == null ? "" : heading));
      if (o.title() != null && o.message() != null) {
        ALERT_SET_DETAIL.invokeExact(dialog, utf8(a, o.message()));
      }
      final var labels = a.allocate(ADDRESS, buttons.size() + 1L); // NULL-terminated
      for (int i = 0; i < buttons.size(); i++) {
        labels.setAtIndex(ADDRESS, i, utf8(a, buttons.get(i)));
      }
      labels.setAtIndex(ADDRESS, buttons.size(), MemorySegment.NULL);
      ALERT_SET_BUTTONS.invokeExact(dialog, labels); // copies the strings
      ALERT_SET_DEFAULT_BUTTON.invokeExact(dialog, 0);
      ALERT_SET_CANCEL_BUTTON.invokeExact(dialog, buttons.size() - 1);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
    final int last = buttons.size() - 1;
    begin(
        ALERT_CHOOSE,
        dialog,
        parent,
        res -> {
          try (var a = Arena.ofConfined()) {
            final var err = a.allocate(ADDRESS);
            err.set(ADDRESS, 0, MemorySegment.NULL);
            final int r = (int) ALERT_CHOOSE_FINISH.invokeExact(dialog, res, err);
            freeError(err.get(ADDRESS, 0));
            return Math.max(0, Math.min(last, r < 0 ? last : r));
          } catch (final Throwable t) {
            throw new RuntimeException(t);
          }
        },
        out);
  }

  // ---------------------------------------------------------------------------------------------

  /** Function from the finished call's {@code GAsyncResult*} to the dialog's value. */
  private interface Finisher<T> {
    T apply(MemorySegment result) throws Throwable;
  }

  /**
   * Starts an async dialog call and completes {@code out} from its callback. The dialog's
   * reference is dropped once the callback has run.
   */
  private static <T> void begin(
      MethodHandle start,
      MemorySegment dialog,
      MemorySegment parent,
      Finisher<T> finisher,
      CompletableFuture<T> out) {
    final long id = nextId.getAndIncrement();
    pending.put(
        id,
        (source, res) -> {
          try {
            out.complete(finisher.apply(res));
          } catch (final Throwable t) {
            out.completeExceptionally(t);
          } finally {
            GLib.gObjectUnref(dialog);
          }
        });
    // A typed local: invokeExact would see a conditional expression as Object.
    final MemorySegment parentWindow = parent == null ? MemorySegment.NULL : parent;
    try {
      start.invokeExact(
          dialog,
          parentWindow,
          MemorySegment.NULL,
          READY_STUB,
          MemorySegment.ofAddress(id));
    } catch (final Throwable t) {
      pending.remove(id);
      GLib.gObjectUnref(dialog);
      throw new RuntimeException(t);
    }
  }

  /** {@code GAsyncReadyCallback} shared by every dialog; never lets an exception reach C. */
  @SuppressWarnings("unused")
  static void onReady(MemorySegment source, MemorySegment result, MemorySegment userData) {
    final var completion = pending.remove(userData.address());
    if (completion != null) {
      try {
        completion.accept(source, result);
      } catch (final Throwable ignored) {
      }
    }
  }

  private static MemorySegment newFileDialog(Dialogs.FileDialogOptions o, boolean isSave) {
    try (var a = Arena.ofConfined()) {
      final var dialog = (MemorySegment) FILE_DIALOG_NEW.invokeExact();
      if (o.title() != null) {
        FILE_DIALOG_SET_TITLE.invokeExact(dialog, utf8(a, o.title()));
      }
      // canCreateDirectories has no GtkFileDialog equivalent: the dialog always offers it.
      applyFilters(a, dialog, o.filters());
      applyDefaultPath(a, dialog, o.defaultPath(), isSave);
      return dialog;
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /** One {@code GtkFileFilter} per filter; a {@code "*"} extension anywhere lifts all filtering. */
  private static void applyFilters(Arena a, MemorySegment dialog, List<Dialogs.Filter> filters)
      throws Throwable {
    if (filters == null || filters.isEmpty()) {
      return;
    }
    for (var f : filters) {
      if (f.extensions().contains("*")) {
        return;
      }
    }
    final var store = (MemorySegment) LIST_STORE_NEW.invokeExact((long) FILE_FILTER_GET_TYPE.invokeExact());
    for (var f : filters) {
      final var filter = (MemorySegment) FILE_FILTER_NEW.invokeExact();
      final var name =
          f.name() != null && !f.name().isEmpty() ? f.name() : String.join(", ", f.extensions());
      FILE_FILTER_SET_NAME.invokeExact(filter, utf8(a, name));
      for (var ext : f.extensions()) {
        FILE_FILTER_ADD_SUFFIX.invokeExact(filter, utf8(a, ext));
      }
      LIST_STORE_APPEND.invokeExact(store, filter); // the store takes its own reference
      GLib.gObjectUnref(filter);
    }
    FILE_DIALOG_SET_FILTERS.invokeExact(dialog, store);
    GLib.gObjectUnref(store);
  }

  /** Same rules as {@code MacOSHelper.applyDefaultPath}. */
  private static void applyDefaultPath(Arena a, MemorySegment dialog, Path p, boolean isSave)
      throws Throwable {
    if (p == null) {
      return;
    }
    final Path folder = Files.isDirectory(p) ? p : p.getParent();
    if (folder != null) {
      final var file =
          (MemorySegment) FILE_NEW_FOR_PATH.invokeExact(utf8(a, folder.toAbsolutePath().toString()));
      FILE_DIALOG_SET_INITIAL_FOLDER.invokeExact(dialog, file);
      GLib.gObjectUnref(file);
    }
    if (isSave && !Files.isDirectory(p) && p.getFileName() != null) {
      FILE_DIALOG_SET_INITIAL_NAME.invokeExact(dialog, utf8(a, p.getFileName().toString()));
    }
  }

  /**
   * Calls a {@code *_finish} function. Returns its object, or {@code null} if the user cancelled;
   * throws if the dialog failed outright.
   */
  private static MemorySegment finishCall(MethodHandle finish, MemorySegment dialog, MemorySegment res)
      throws Throwable {
    try (var a = Arena.ofConfined()) {
      final var err = a.allocate(ADDRESS);
      err.set(ADDRESS, 0, MemorySegment.NULL);
      final var obj = (MemorySegment) finish.invokeExact(dialog, res, err);
      final var e = err.get(ADDRESS, 0);
      if (e.address() != 0L) {
        final var failure = failureMessage(a, e);
        GLib.gErrorFree(e);
        if (failure != null) {
          throw new IllegalStateException("GTK dialog failed: " + failure);
        }
      }
      if (obj.address() == 0L && e.address() == 0L) {
        LOG.log(System.Logger.Level.DEBUG, "GTK dialog finished with no result and no error");
      }
      return obj.address() == 0L ? null : obj;
    }
  }

  /** The message of a {@code GTK_DIALOG_ERROR_FAILED} error; {@code null} for a cancellation. */
  private static String failureMessage(Arena a, MemorySegment error) throws Throwable {
    // struct GError { GQuark domain; gint code; gchar* message; }
    final var e = error.reinterpret(16);
    final int domain = e.get(JAVA_INT, 0);
    final int code = e.get(JAVA_INT, 4);
    final int dialogDomain = (int) QUARK_FROM_STRING.invokeExact(a.allocateFrom("gtk-dialog-error-quark"));
    final var msg = error.reinterpret(16).get(ADDRESS, 8);
    final var text =
        msg.address() == 0L
            ? "unknown error"
            : msg.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
    if (domain != dialogDomain || code != GTK_DIALOG_ERROR_FAILED) {
      LOG.log(System.Logger.Level.DEBUG, "GTK dialog ended without a result: code {0}: {1}", code, text);
      return null;
    }
    return text;
  }

  private static void freeError(MemorySegment error) {
    if (error.address() != 0L) {
      GLib.gErrorFree(error);
    }
  }

  private static Path pathOf(MemorySegment gfile) throws Throwable {
    final var p = (MemorySegment) FILE_GET_PATH.invokeExact(gfile);
    if (p.address() == 0L) {
      return null; // not a local file
    }
    try {
      return Path.of(p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_8));
    } finally {
      GLib.gFree(p);
    }
  }

  private static List<Path> pathsOf(MemorySegment listModel) throws Throwable {
    final int n = (int) LIST_MODEL_GET_N_ITEMS.invokeExact(listModel);
    final var out = new ArrayList<Path>(n);
    for (int i = 0; i < n; i++) {
      final var item = (MemorySegment) LIST_MODEL_GET_ITEM.invokeExact(listModel, i);
      try {
        final var p = pathOf(item);
        if (p != null) {
          out.add(p);
        }
      } finally {
        GLib.gObjectUnref(item); // get_item returns a new reference
      }
    }
    return out;
  }

  private static MemorySegment utf8(Arena a, String s) {
    return a.allocateFrom(s, StandardCharsets.UTF_8);
  }

  private static MemorySegment buildReadyStub() {
    try {
      final var mh =
          MethodHandles.lookup()
              .findStatic(
                  GtkDialogs.class,
                  "onReady",
                  MethodType.methodType(
                      void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
      return LINKER.upcallStub(mh, ASYNC_READY_DESC, Arena.global());
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new RuntimeException("Failed to build dialog upcall stub", e);
    }
  }

  private static MethodHandle downcall(String sym, FunctionDescriptor desc) {
    return LINKER.downcallHandle(
        LOOKUP
            .find(sym)
            .orElseThrow(() -> new UnsatisfiedLinkError("GTK4 dialog symbol not found: " + sym)),
        desc);
  }

  private GtkDialogs() {}
}
