package io.avaje.webview.windows;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

import io.avaje.webview.Dialogs;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Native file and message dialogs on Windows.
 *
 * <p>Files go through the shell {@code IFileOpenDialog} / {@code IFileSaveDialog} (the Vista-era
 * common item dialog, which is what every modern Windows app shows), and messages through {@code
 * TaskDialogIndirect}, the only Win32 message box that accepts custom button labels.
 *
 * <p>Every call here is modal and runs its own message loop, so the caller blocks until the user
 * is done. They must therefore be invoked on the UI thread; {@code Win32WebView} marshals.
 */
final class Win32Dialogs {

  private Win32Dialogs() {}

  // Shell CLSIDs and IIDs, from shobjidl_core.h.
  private static final String CLSID_FILE_OPEN_DIALOG = "DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7";
  private static final String IID_IFILE_OPEN_DIALOG = "D57C7288-D4AD-4768-BE02-9D969532D960";
  private static final String CLSID_FILE_SAVE_DIALOG = "C0B4E2F3-BA21-4773-8DBA-335EC946EB8B";
  private static final String IID_IFILE_SAVE_DIALOG = "84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB";
  private static final String IID_ISHELL_ITEM = "43826D1E-E718-42EE-BC55-A1E261C37BFE";

  // _FILEOPENDIALOGOPTIONS
  private static final int FOS_OVERWRITEPROMPT = 0x2;
  private static final int FOS_NOCHANGEDIR = 0x8;
  private static final int FOS_PICKFOLDERS = 0x20;
  private static final int FOS_FORCEFILESYSTEM = 0x40;
  private static final int FOS_ALLOWMULTISELECT = 0x200;
  private static final int FOS_PATHMUSTEXIST = 0x800;
  private static final int FOS_FILEMUSTEXIST = 0x1000;

  /** {@code SIGDN_FILESYSPATH} — the display name form that is an actual filesystem path. */
  private static final int SIGDN_FILESYSPATH = 0x80058000;

  // IFileDialog vtable (IModalWindow first), from shobjidl_core.h.
  private static final int SHOW = 3;
  private static final int SET_FILE_TYPES = 4;
  private static final int SET_OPTIONS = 9;
  private static final int SET_FOLDER = 12;
  private static final int SET_FILE_NAME = 15;
  private static final int SET_TITLE = 17;
  private static final int GET_RESULT = 20;
  private static final int SET_DEFAULT_EXTENSION = 22;
  private static final int GET_RESULTS = 27; // IFileOpenDialog only

  // IShellItem / IShellItemArray
  private static final int GET_DISPLAY_NAME = 5;
  private static final int ARRAY_GET_COUNT = 7;
  private static final int ARRAY_GET_ITEM_AT = 8;

  /** Pointer and int layouts with no alignment, for the byte-packed TASKDIALOGCONFIG. */
  private static final ValueLayout.OfInt PACKED_INT = JAVA_INT.withByteAlignment(1);

  private static final ValueLayout.OfLong PACKED_PTR =
      ValueLayout.JAVA_LONG.withByteAlignment(1);

  /**
   * Shows an open dialog.
   *
   * @return the chosen paths, empty when the user cancelled
   */
  static List<Path> open(MemorySegment owner, Dialogs.FileDialogOptions options) {
    try (var a = Arena.ofConfined()) {
      final var dialog =
          createDialog(a, CLSID_FILE_OPEN_DIALOG, IID_IFILE_OPEN_DIALOG);
      if (dialog.address() == 0) {
        return List.of();
      }
      try {
        var flags = FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST | FOS_NOCHANGEDIR;
        if (options.directory()) {
          flags |= FOS_PICKFOLDERS;
        } else {
          flags |= FOS_FILEMUSTEXIST;
        }
        if (options.multiple()) {
          flags |= FOS_ALLOWMULTISELECT;
        }
        configure(a, dialog, options, flags);
        if (call(dialog, SHOW, ptr(), dialog, owner) != 0) {
          return List.of(); // cancelled, or the dialog could not be shown
        }
        return options.multiple() ? results(a, dialog) : single(a, dialog);
      } finally {
        Win32.comRelease(dialog);
      }
    }
  }

  /**
   * Shows a save dialog.
   *
   * @return the chosen path, or {@code null} when the user cancelled
   */
  static Path save(MemorySegment owner, Dialogs.FileDialogOptions options) {
    try (var a = Arena.ofConfined()) {
      final var dialog = createDialog(a, CLSID_FILE_SAVE_DIALOG, IID_IFILE_SAVE_DIALOG);
      if (dialog.address() == 0) {
        return null;
      }
      try {
        configure(
            a,
            dialog,
            options,
            FOS_FORCEFILESYSTEM | FOS_OVERWRITEPROMPT | FOS_NOCHANGEDIR);
        if (call(dialog, SHOW, ptr(), dialog, owner) != 0) {
          return null;
        }
        final var paths = single(a, dialog);
        return paths.isEmpty() ? null : paths.get(0);
      } finally {
        Win32.comRelease(dialog);
      }
    }
  }

  /**
   * Shows a message dialog.
   *
   * @return the zero-based index of the button pressed; the last button for a dialog dismissed
   *     with Escape or the title-bar close, matching the GTK backend
   */
  static int message(MemorySegment owner, Dialogs.MessageDialogOptions options) {
    final var labels =
        options.buttons() == null || options.buttons().isEmpty()
            ? List.of("OK")
            : options.buttons();
    try (var a = Arena.ofConfined()) {
      // TASKDIALOG_BUTTON is {int nButtonID; PCWSTR pszButtonText;}, byte-packed like the config.
      final var buttons = a.allocate(12L * labels.size(), 1);
      for (var i = 0; i < labels.size(); i++) {
        buttons.set(PACKED_INT, i * 12L, FIRST_BUTTON_ID + i);
        buttons.set(PACKED_PTR, i * 12L + 4, wide(a, labels.get(i)).address());
      }

      final var config = a.allocate(TASKDIALOGCONFIG_SIZE, 1);
      config.fill((byte) 0);
      config.set(PACKED_INT, 0, (int) TASKDIALOGCONFIG_SIZE); // cbSize
      config.set(PACKED_PTR, 4, owner.address()); // hwndParent
      config.set(PACKED_INT, 20, TDF_ALLOW_DIALOG_CANCELLATION | TDF_POSITION_RELATIVE_TO_WINDOW);
      config.set(PACKED_PTR, 28, wide(a, title(options)).address()); // pszWindowTitle
      config.set(PACKED_PTR, 36, icon(options.kind())); // pszMainIcon, a MAKEINTRESOURCE value
      config.set(PACKED_PTR, 52, wide(a, text(options)).address()); // pszContent
      config.set(PACKED_INT, 60, labels.size()); // cButtons
      config.set(PACKED_PTR, 64, buttons.address()); // pButtons
      config.set(PACKED_INT, 72, FIRST_BUTTON_ID); // nDefaultButton: the first, per the contract

      final var pressed = a.allocate(JAVA_INT);
      final int hr;
      try {
        hr =
            (int)
                Win32.TaskDialogIndirect.invokeExact(
                    config, pressed, MemorySegment.NULL, MemorySegment.NULL);
      } catch (final Throwable t) {
        throw new RuntimeException(t);
      }
      if (hr != 0) {
        return labels.size() - 1;
      }
      final var index = pressed.get(JAVA_INT, 0) - FIRST_BUTTON_ID;
      // IDCANCEL (2) comes back when the dialog is cancelled rather than a button pressed.
      return index >= 0 && index < labels.size() ? index : labels.size() - 1;
    }
  }

  /**
   * Size of {@code TASKDIALOGCONFIG}. The struct is declared inside {@code pshpack1.h}, so every
   * field sits at its natural running offset with no padding — 160 bytes on x64, not the 176 a
   * default-aligned layout would give.
   */
  private static final long TASKDIALOGCONFIG_SIZE = 160;

  private static final int TDF_ALLOW_DIALOG_CANCELLATION = 0x0008;
  private static final int TDF_POSITION_RELATIVE_TO_WINDOW = 0x1000;

  /** Button IDs start past the standard ones (IDOK=1 … IDCONTINUE=11) so they cannot collide. */
  private static final int FIRST_BUTTON_ID = 1000;

  /**
   * The {@code TD_*_ICON} values are {@code MAKEINTRESOURCEW(-n)}: a small integer sitting in the
   * pointer-sized icon field rather than an address. Returned as a raw value for that reason.
   */
  private static long icon(Dialogs.MessageKind kind) {
    return switch (kind == null ? Dialogs.MessageKind.INFO : kind) {
      case ERROR -> 0xFFFFL & -2; // TD_ERROR_ICON
      case WARNING -> 0xFFFFL & -1; // TD_WARNING_ICON
      case INFO -> 0xFFFFL & -3; // TD_INFORMATION_ICON
    };
  }

  private static String title(Dialogs.MessageDialogOptions options) {
    return options.title() == null || options.title().isEmpty() ? " " : options.title();
  }

  private static String text(Dialogs.MessageDialogOptions options) {
    return options.message() == null ? "" : options.message();
  }

  /** Creates the dialog COM object, or a null segment if the shell refuses. */
  private static MemorySegment createDialog(Arena a, String clsid, String iid) {
    final var out = a.allocate(ADDRESS);
    final int hr;
    try {
      hr =
          (int)
              Win32.CoCreateInstance.invokeExact(
                  guid(a, clsid),
                  MemorySegment.NULL,
                  Win32.CLSCTX_INPROC_SERVER,
                  guid(a, iid),
                  out);
    } catch (final Throwable t) {
      throw new RuntimeException(t);
    }
    return hr == 0 ? out.get(ADDRESS, 0) : MemorySegment.NULL;
  }

  /** Applies title, filters, start folder, pre-filled name and option flags. */
  private static void configure(
      Arena a, MemorySegment dialog, Dialogs.FileDialogOptions options, int flags) {
    call(dialog, SET_OPTIONS, FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT), dialog, flags);

    if (options.title() != null && !options.title().isEmpty()) {
      call(dialog, SET_TITLE, ptr(), dialog, wide(a, options.title()));
    }
    if (!options.directory()) {
      applyFilters(a, dialog, options.filters());
    }

    final var start = options.defaultPath();
    if (start != null) {
      final var folder = Files.isDirectory(start) ? start : start.getParent();
      if (folder != null) {
        final var item = shellItem(a, folder);
        if (item.address() != 0) {
          call(dialog, SET_FOLDER, ptr(), dialog, item);
          Win32.comRelease(item);
        }
      }
      if (!Files.isDirectory(start) && start.getFileName() != null) {
        call(dialog, SET_FILE_NAME, ptr(), dialog, wide(a, start.getFileName().toString()));
      }
    }
  }

  /**
   * Turns the filter list into {@code COMDLG_FILTERSPEC[]}, whose entries are a display name and a
   * {@code *.ext;*.ext} pattern. A filter holding {@code *} becomes "all files".
   */
  private static void applyFilters(Arena a, MemorySegment dialog, List<Dialogs.Filter> filters) {
    if (filters == null || filters.isEmpty()) {
      return;
    }
    // COMDLG_FILTERSPEC is two pointers, naturally aligned.
    final var specs = a.allocate(ADDRESS.byteSize() * 2 * filters.size());
    var i = 0;
    for (final var f : filters) {
      final var exts = f.extensions() == null ? List.<String>of() : f.extensions();
      final var pattern =
          exts.isEmpty() || exts.contains("*")
              ? "*.*"
              : String.join(";", exts.stream().map(e -> "*." + e).toList());
      final var name = f.name() == null || f.name().isEmpty() ? pattern : f.name();
      specs.setAtIndex(ADDRESS, i * 2L, wide(a, name));
      specs.setAtIndex(ADDRESS, i * 2L + 1, wide(a, pattern));
      i++;
    }
    call(
        dialog,
        SET_FILE_TYPES,
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
        dialog,
        filters.size(),
        specs);
    final var first = filters.get(0).extensions();
    if (first != null && !first.isEmpty() && !first.contains("*")) {
      call(dialog, SET_DEFAULT_EXTENSION, ptr(), dialog, wide(a, first.get(0)));
    }
  }

  /** Reads the single selection off a dialog that has returned S_OK. */
  private static List<Path> single(Arena a, MemorySegment dialog) {
    final var out = a.allocate(ADDRESS);
    if (call(dialog, GET_RESULT, ptr(), dialog, out) != 0) {
      return List.of();
    }
    final var item = out.get(ADDRESS, 0);
    try {
      final var path = itemPath(a, item);
      return path == null ? List.of() : List.of(path);
    } finally {
      Win32.comRelease(item);
    }
  }

  /** Reads every selection off an open dialog created with {@code FOS_ALLOWMULTISELECT}. */
  private static List<Path> results(Arena a, MemorySegment dialog) {
    final var out = a.allocate(ADDRESS);
    if (call(dialog, GET_RESULTS, ptr(), dialog, out) != 0) {
      return List.of();
    }
    final var array = out.get(ADDRESS, 0);
    final var paths = new ArrayList<Path>();
    try {
      final var count = a.allocate(JAVA_INT);
      if (call(array, ARRAY_GET_COUNT, ptr(), array, count) != 0) {
        return List.of();
      }
      for (var i = 0; i < count.get(JAVA_INT, 0); i++) {
        final var itemOut = a.allocate(ADDRESS);
        if (call(
                array,
                ARRAY_GET_ITEM_AT,
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
                array,
                i,
                itemOut)
            != 0) {
          continue;
        }
        final var item = itemOut.get(ADDRESS, 0);
        try {
          final var path = itemPath(a, item);
          if (path != null) {
            paths.add(path);
          }
        } finally {
          Win32.comRelease(item);
        }
      }
      return List.copyOf(paths);
    } finally {
      Win32.comRelease(array);
    }
  }

  /** {@code IShellItem::GetDisplayName(SIGDN_FILESYSPATH)}. */
  private static Path itemPath(Arena a, MemorySegment item) {
    if (item.address() == 0) {
      return null;
    }
    final var out = a.allocate(ADDRESS);
    if (call(
            item,
            GET_DISPLAY_NAME,
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS),
            item,
            SIGDN_FILESYSPATH,
            out)
        != 0) {
      return null;
    }
    final var text = Win32.takeWideString(out.get(ADDRESS, 0));
    if (text == null || text.isEmpty()) {
      return null;
    }
    try {
      return Path.of(text);
    } catch (final RuntimeException e) {
      return null; // a shell item that is not a filesystem path after all
    }
  }

  /** {@code SHCreateItemFromParsingName}, for {@code IFileDialog::SetFolder}. */
  private static MemorySegment shellItem(Arena a, Path path) {
    final var out = a.allocate(ADDRESS);
    final int hr;
    try {
      hr =
          (int)
              Win32.SHCreateItemFromParsingName.invokeExact(
                  wide(a, path.toAbsolutePath().toString()),
                  MemorySegment.NULL,
                  guid(a, IID_ISHELL_ITEM),
                  out);
    } catch (final Throwable t) {
      return MemorySegment.NULL;
    }
    return hr == 0 ? out.get(ADDRESS, 0) : MemorySegment.NULL;
  }

  /**
   * Parses a canonical GUID string into the 16-byte {@code GUID} struct COM expects: a
   * little-endian DWORD and two WORDs, then eight bytes in written order.
   */
  private static MemorySegment guid(Arena a, String text) {
    final var parts = text.split("-");
    final var seg = a.allocate(16);
    seg.set(JAVA_INT, 0, (int) Long.parseLong(parts[0], 16));
    seg.set(JAVA_SHORT, 4, (short) Integer.parseInt(parts[1], 16));
    seg.set(JAVA_SHORT, 6, (short) Integer.parseInt(parts[2], 16));
    final var tail = parts[3] + parts[4];
    for (var i = 0; i < 8; i++) {
      seg.set(JAVA_BYTE, 8 + i, (byte) Integer.parseInt(tail.substring(i * 2, i * 2 + 2), 16));
    }
    return seg;
  }

  private static MemorySegment wide(Arena a, String text) {
    return a.allocateFrom(text, StandardCharsets.UTF_16LE);
  }

  /** The common {@code HRESULT(This, void*)} shape. */
  private static FunctionDescriptor ptr() {
    return FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
  }

  private static int call(
      MemorySegment comObj, int vtableIndex, FunctionDescriptor fd, Object... args) {
    if (comObj == null || comObj.address() == 0) {
      return 0x80004005; // E_FAIL
    }
    try {
      return (int) Win32.resolve(comObj, vtableIndex, fd).invokeWithArguments(args);
    } catch (final Throwable t) {
      return 0x80004005;
    }
  }
}
