## Summary

Fixes `META-INF/native-image/io.avaje/avaje-webview/reachability-metadata.json` so that GraalVM 25's Panama-foreign parser can consume it. Two entries encoded struct-by-value shapes (macOS `NSRect`, four consecutive doubles) as inline JSON arrays where the parser expects a single type-descriptor *string*. Every downstream `native-image` build that puts `avaje-webview` on the classpath fails at `[1/8] Initializing` before any user code is analyzed.

## The bug

`avaje-webview` uses Panama FFM to call `-[NSWindow frame]` on macOS, which returns `NSRect` by value. On the arm64/x86_64 Obj-C ABI a small struct like that is passed in floating-point registers — four consecutive `double`s — and the corresponding `MethodHandle` is described with a `StructLayout`:

```java
// avaje-webview/src/main/java/io/avaje/webview/macos/ObjC.java
static final StructLayout NS_RECT_LAYOUT =
    MemoryLayout.structLayout(
        JAVA_DOUBLE.withName("x"),
        JAVA_DOUBLE.withName("y"),
        JAVA_DOUBLE.withName("w"),
        JAVA_DOUBLE.withName("h"));

static final MethodHandle MSG_SEND_GET_FRAME =
    LINKER.downcallHandle(MSG_SEND_ADDR,
        FunctionDescriptor.of(NS_RECT_LAYOUT, ADDRESS, ADDRESS));
```

The shipped reachability metadata attempts to describe that with an inline value-layout array:

```json
{
  "returnType": ["jdouble", "jdouble", "jdouble", "jdouble"],
  "parameterTypes": ["void*", "void*"]
}
```

But GraalVM 25's schema (`lib/svm/schemas/reachability-metadata-schema.json`) declares `returnType` and every element of `parameterTypes` as `"type": "string"`. And its parser (`com.oracle.svm.hosted.foreign.MemoryLayoutParser`) only accepts strings — either a canonical value-layout name (`jdouble`, `jint`, `void*`, …) or an aggregate layout descriptor: `struct(...)`, `union(...)`, `sequence(...)`, `padding(...)`, `align(...)`.

So `native-image` bails during initialization:

```
Error parsing panama foreign configuration in
jar:.../avaje-webview-0.28.jar!/META-INF/native-image/io.avaje/avaje-webview/reachability-metadata.json:
Invalid string value "[jdouble, jdouble, jdouble, jdouble]" for element 'returnType'
```

Failure happens before the app entrypoint is scanned, so no downstream project can work around it — the metadata must be fixed upstream.

## The fix

Two locations — one downcall `returnType` (`-[NSWindow frame] → NSRect`) and one upcall `parameterTypes` tail — both now use the canonical descriptor string that matches `NS_RECT_LAYOUT`:

```diff
-          ["jdouble", "jdouble", "jdouble", "jdouble"]
+          "struct(jdouble,jdouble,jdouble,jdouble)"
```

```diff
-        "returnType": ["jdouble", "jdouble", "jdouble", "jdouble"],
+        "returnType": "struct(jdouble,jdouble,jdouble,jdouble)",
```

No other entries change. All other value-layout entries in the file were already string-encoded correctly.

## Verification

macOS 15 (arm64), GraalVM 25.0.4+7.1:

```
$ GRAALVM_JDK=$GRAALVM_25 native-image \
    -cp ...:avaje-webview-0.28.jar ... -o hello-world <MainClass>

[1/8] Initializing...                            (5.0s @ 0.17GB)
[2/8] Performing analysis...  [*****]            (9.0s @ 1.26GB)
   12,970 types,  17,425 fields, and  63,734 methods found reachable
   54 downcalls and 12 upcalls registered for foreign access
[3/8] Building universe...                       (1.7s @ 1.50GB)
...
Finished generating 'hello-world' in 30.0s.
```

The parse-time error is gone. `54 downcalls and 12 upcalls registered for foreign access` matches the metadata, so both `NSRect`-shaped entries were consumed correctly. The resulting binary boots, opens the WKWebView, and drives the Panama FFM path (WKWebView init, `-[NSWindow frame]`, dispatch) without runtime errors.

Tested against a downstream Java-native Tauri clone that pulls avaje-webview onto its classpath — the same class of app that trips over this today.

## Scope

- One-file change, two-line diff.
- macOS-only surface (the two entries describe Obj-C ABI shapes).
- No API surface, no runtime code, no behavior change on the JVM. Only affects `native-image` builds.

## References

- GraalVM Panama-foreign metadata schema — `graalvm-jdk-25.x/Contents/Home/lib/svm/schemas/reachability-metadata-schema.json`
- GraalVM `MemoryLayoutParser` accepted syntax — `struct(...)`, `union(...)`, `sequence(...)`, `padding(...)`, `align(...)` plus canonical value layouts (`jdouble`, `jint`, `jlong`, `jbyte`, `jshort`, `void*`).
- macOS `-[NSWindow frame]` signature — see `ObjC.MSG_SEND_GET_FRAME` and `ObjC.NS_RECT_LAYOUT` in this repo.
