package io.avaje.webview.windows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Icon file preparation for {@link Win32WebView#setIcon(Path)}.
 *
 * <p>{@code LoadImageW} with {@code LR_LOADFROMFILE} only reads {@code .ico} containers, but the
 * macOS and Linux backends both accept a plain {@code .png} and callers reasonably ship one icon for
 * all three platforms. Rather than reject PNG here, this class wraps the PNG bytes - untouched - in
 * a single-entry {@code ICO} container. Windows Vista and later store PNG-compressed images inside
 * {@code .ico} natively, so no decoding, and therefore no {@code java.desktop} dependency, is
 * needed: the wrapper is a 22-byte header followed by the original file.
 */
final class WindowsIcon {

  /** The 8-byte PNG signature every PNG file starts with. */
  private static final byte[] PNG_SIGNATURE = {
    (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
  };

  /** {@code ICONDIR} (6 bytes) + one {@code ICONDIRENTRY} (16 bytes). */
  private static final int ICO_HEADER_SIZE = 22;

  private WindowsIcon() {}

  /**
   * Returns a path {@code LoadImageW} can read: {@code path} itself when it is already an {@code
   * .ico}, or a temp {@code .ico} wrapping it when it is a PNG.
   *
   * @param path the caller-supplied icon file
   * @throws IllegalArgumentException if the file is neither a {@code .ico} nor a PNG
   * @throws IOException if the file cannot be read, or the temp wrapper cannot be written
   */
  static Path toIcoFile(Path path) throws IOException {
    final var name = path.getFileName().toString();
    if (name.toLowerCase(Locale.ROOT).endsWith(".ico")) {
      return path;
    }
    final var bytes = Files.readAllBytes(path);
    if (!isPng(bytes)) {
      throw new IllegalArgumentException(
          "Win32 setIcon requires a .ico or .png file, got: " + name);
    }
    final var ico = Files.createTempFile("avaje-webview-icon-", ".ico");
    ico.toFile().deleteOnExit();
    Files.write(ico, wrapPngInIco(bytes));
    return ico;
  }

  /** Tests the 8-byte PNG signature; content is checked rather than trusting the file extension. */
  private static boolean isPng(byte[] bytes) {
    if (bytes.length < PNG_SIGNATURE.length) {
      return false;
    }
    for (var i = 0; i < PNG_SIGNATURE.length; i++) {
      if (bytes[i] != PNG_SIGNATURE[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Builds a one-entry {@code ICO} whose image payload is {@code png} verbatim.
   *
   * <p>Both {@code ICONDIR} and {@code ICONDIRENTRY} are little-endian. The width and height fields
   * are a single byte each, so a dimension of 256 or more is written as {@code 0} - the documented
   * encoding for "256". That covers the common 512x512 app icon: the declared size is only a hint
   * for entry selection, and since there is exactly one entry, {@code LoadImageW} picks it and
   * scales the decoded image to the size actually requested.
   */
  private static byte[] wrapPngInIco(byte[] png) {
    final var dim = pngDimensions(png);
    final var buf =
        ByteBuffer.allocate(ICO_HEADER_SIZE + png.length).order(ByteOrder.LITTLE_ENDIAN);
    // ICONDIR
    buf.putShort((short) 0); // idReserved, must be 0
    buf.putShort((short) 1); // idType, 1 = icon
    buf.putShort((short) 1); // idCount, one image
    // ICONDIRENTRY
    buf.put(byteDimension(dim[0])); // bWidth
    buf.put(byteDimension(dim[1])); // bHeight
    buf.put((byte) 0); // bColorCount, 0 for true colour
    buf.put((byte) 0); // bReserved, must be 0
    buf.putShort((short) 1); // wPlanes
    buf.putShort((short) 32); // wBitCount
    buf.putInt(png.length); // dwBytesInRes
    buf.putInt(ICO_HEADER_SIZE); // dwImageOffset
    buf.put(png);
    return buf.array();
  }

  /** Encodes an image dimension into the single {@code ICONDIRENTRY} byte; 256+ becomes {@code 0}. */
  private static byte byteDimension(int value) {
    return value >= 256 ? 0 : (byte) value;
  }

  /**
   * Reads width and height from the PNG's {@code IHDR} chunk, which the spec requires to be the
   * first chunk: 8-byte signature, 4-byte length, 4-byte type, then two big-endian 4-byte
   * dimensions. Returns {@code {256, 256}} if the file is truncated before that point - the entry
   * dimensions are only a selection hint, so a wrong guess costs nothing.
   */
  private static int[] pngDimensions(byte[] png) {
    if (png.length < 24) {
      return new int[] {256, 256};
    }
    final var buf = ByteBuffer.wrap(png).order(ByteOrder.BIG_ENDIAN);
    return new int[] {buf.getInt(16), buf.getInt(20)};
  }
}
