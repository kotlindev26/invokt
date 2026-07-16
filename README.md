# invokt

**P/Invoke for Kotlin Multiplatform.** Call native C functions from common Kotlin code — with one API that works identically on the JVM and Kotlin/Native.

```kotlin
@Import(library = "libz.dylib")
fun zlibVersion(): Ptr = imported()

fun main() {
    println("zlib ${zlibVersion().readCString()}")
}
```

Kotlin has two FFI worlds that don't talk to each other: the JVM has JNA and the FFM API, Kotlin/Native has cinterop — and nothing works on both. invokt closes that gap, modelled after C#'s P/Invoke: declare a native function's signature, call it like a normal Kotlin function.

## Targets & status

| Target | Backend | Status |
|---|---|---|
| JVM (JDK 22+) | FFM API (`java.lang.foreign`, Project Panama) | ✅ tested |
| macOS arm64 | `dlopen`/`dlsym` + system libffi | ✅ tested |
| Linux x64 | `dlopen`/`dlsym` + vendored libffi | ⚠️ compiles & links, tests pending CI |
| Windows x64 (mingw) | `LoadLibraryW`/`GetProcAddress` + vendored libffi | ⚠️ compiles & links, tests pending CI |

invokt targets 64-bit ABIs only.

**JVM requirement:** the FFM API uses restricted methods, so the JVM must run with

```
--enable-native-access=ALL-UNNAMED
```

## Two ways to bind a function

### 1. Static imports (compiler plugin) — the P/Invoke experience

Annotate a function with `@Import` and give it the `imported()` marker body. The invokt compiler plugin replaces the body at compile time:

```kotlin
@Import                       // no library -> the process's own symbols (libc)
fun getpid(): Int = imported()

@Import(symbol = "abs")       // Kotlin name and C symbol may differ
fun cAbs(value: Int): Int = imported()

@Import(library = "libz.dylib")
fun zlibVersion(): Ptr = imported()
```

What gets generated:

- **Kotlin/Native:** a cached `CPointer<CFunction<...>>`, resolved once via `dlsym`. The call site compiles to a **direct, statically-typed C call** — no libffi, no boxing, no dispatch. (Verified in disassembly: the call trampoline is a bare `br x1`.)
- **JVM:** a cached FFM downcall handle, bound once instead of per call.

Why `= imported()` instead of `external fun`? Kotlin's `external` keyword has fixed compiler semantics (JNI on the JVM, `@SymbolName` on Native) and is not allowed in common code. The marker-body pattern is the established alternative (used by atomicfu and Compose) and works in every source set, including `commonMain`. If the plugin is not applied, calling the function throws a descriptive error instead of doing something undefined.

### 2. Dynamic binding — runtime flexibility

When signatures aren't known at compile time (tooling, plugins, generic bridges):

```kotlin
val libc = processLibrary()

val getpid = libc.function("getpid", CType.I32)
println("PID: ${getpid()}")

openLibrary("libz.dylib").use { zlib ->
    val version = zlib.function("zlibVersion", CType.Pointer)
    println(version().readCString())
}
```

On the JVM this builds an FFM `MethodHandle` at runtime. On Kotlin/Native — where call signatures cannot be constructed at runtime — the call goes through **libffi**: the signature is described as data (`ffi_cif`) and libffi assembles the ABI-correct call.

## Type system

Signatures use explicit-width types instead of C names, because C's `int`/`long` are platform-dependent:

| `CType` | Kotlin | C equivalents |
|---|---|---|
| `I8` / `I16` / `I32` / `I64` | `Byte` / `Short` / `Int` / `Long` | `int8_t` … `int64_t`, `char`, `short`, `int`, `long long`, `long` (LP64) |
| `U8` / `U16` / `U32` / `U64` | `UByte` / `UShort` / `UInt` / `ULong` | `uint8_t` … `uint64_t`, `size_t` (64-bit) |
| `F32` / `F64` | `Float` / `Double` | `float`, `double` |
| `Bool` | `Boolean` | `bool` / `_Bool` (1 byte) |
| `Pointer` | `Ptr` | any `T*`, `void*` |
| `Str` | `String` | `const char*` (see below) |
| `Void` | `Unit` | `void` (return only) |

Signatures are *trusted* — exactly like P/Invoke, a mismatch between the declared and the actual C signature is undefined behavior.

### Strings

`CType.Str` marshals `String` ⇄ `const char*`:

- **Arguments** are copied into a temporary NUL-terminated UTF-8 buffer that lives exactly as long as the call.
- **Returns** are read as UTF-8; a returned `NULL` throws. Use `Pointer` when the callee keeps the pointer, or when `NULL` is a valid result.

```kotlin
val atoi = libc.function("atoi", CType.I32, CType.Str)
atoi("42")  // 42
```

## Memory

Native memory is managed through arenas — everything allocated in an arena is freed together when it closes (mirroring FFM's `Arena` and Native's `memScoped`):

```kotlin
withArena { arena ->
    val buf = arena.allocate(64)                    // zeroed, 8-byte aligned
    val msg = arena.allocateCString("hello")        // NUL-terminated UTF-8

    buf.writeInt(0xCAFE)
    buf.writeDouble(3.5, offset = 8)
    val x = buf.readInt()
    val s = msg.readCString()
}
```

`Ptr` is a value class around a raw 64-bit address — deliberately as unsafe as a C pointer. No bounds checks, no lifetime tracking: reading from a freed arena is undefined behavior.

## Structs

`CStruct` computes C layout rules (natural alignment, padding, trailing padding) so you don't have to:

```kotlin
// struct { int32_t a; char b; double c; }  ->  offsets 0, 4, 8; size 16
val layout = CStruct(CType.I32, CType.I8, CType.F64)

withArena { arena ->
    val p = arena.allocate(layout)
    p.writeInt(7, layout.offset(0))
    p.writeDouble(2.5, layout.offset(2))
}
```

Works for structs returned by native code too:

```kotlin
// struct tm: nine leading ints on every libc we target
val tm = CStruct(CType.I32, CType.I32, CType.I32, CType.I32, CType.I32,
                 CType.I32, CType.I32, CType.I32, CType.I32)
val tmPtr = libc.function("localtime", CType.Pointer, CType.Pointer)(timePtr)
val year = tmPtr.readInt(tm.offset(5)) + 1900
```

Structs are accessed through pointers; passing structs **by value** is not supported yet. Model nested structs by inlining their fields.

## Callbacks (upcalls)

Expose a Kotlin function as a C function pointer:

```kotlin
// C: void qsort(void*, size_t, size_t, int (*compar)(const void*, const void*));
nativeCallback(CType.I32, CType.Pointer, CType.Pointer) { (a, b) ->
    (a as Ptr).readInt().compareTo((b as Ptr).readInt())
}.use { comparator ->
    val qsort = libc.function("qsort", CType.Void,
        CType.Pointer, CType.U64, CType.U64, CType.Pointer)
    qsort(buffer, count, 4uL, comparator.ptr)
}
```

Implemented with FFM `upcallStub` on the JVM and libffi closures on Kotlin/Native. The pointer is valid until `close()` — closing while native code can still call it is undefined behavior.

The inverse also exists: `functionAt(ptr, returns, params...)` binds a raw function pointer (from a C API, a vtable, or `Callback.ptr`) as a callable `NativeFunction`.

## Variadic functions

Varargs must be bound per concrete instantiation, with the number of fixed parameters — this is not optional bookkeeping: some ABIs (macOS arm64!) pass varargs on the stack instead of in registers.

```kotlin
// C: int snprintf(char*, size_t, const char*, ...);
val snprintf = libc.function("snprintf", CType.I32,
    CType.Pointer, CType.U64, CType.Str, CType.I32, CType.Str,
    fixedArgs = 3)
snprintf(buf, 64uL, "%d-%s", 7, "invokt")   // "7-invokt"
```

Supported in the dynamic API (FFM `firstVariadicArg` / libffi `ffi_prep_cif_var`); not available for `@Import`.

## How it works

```
                 commonMain: one API
                        │
     ┌──────────────────┼──────────────────────┐
   jvmMain          posixMain               mingwMain
  FFM/Panama      dlopen + dlsym       LoadLibraryW + GetProcAddress
                        │                      │
                        └───────┬──────────────┘
                            nativeMain
                     libffi call engine, arenas,
                     memory access, closures
```

- **JVM** — pure FFM: `SymbolLookup`/`Linker.downcallHandle` for calls, `MemorySegment` for memory, `upcallStub` for callbacks. No JNI, no native glue of our own.
- **Kotlin/Native** — symbol resolution is platform code (POSIX `dlopen`, Windows `LoadLibraryW` + a psapi-based process-symbol search); the calls go through libffi, bound statically via cinterop. libffi's own API is fixed — that's why *it* can be bound at compile time, and then performs arbitrary-signature calls at runtime.
- **libffi sourcing** (`third_party/libffi/README.md`): macOS uses the system libffi (header from the SDK). Linux and Windows embed a vendored static `libffi.a` directly into the klib — consumers need no libffi installed. The builds must match the Kotlin/Native sysroots (glibc 2.19 for Linux!), which is why they are pinned and documented.
- **Compiler plugin** (`invokt-compiler/`) — an IR transformation registered on every JVM and Native compilation via the `io.invokt.compiler` Gradle plugin. For each `@Import` function it adds a private cached binding property to the file and rewrites the marker body into a call through it. `Ptr` and `String` values travel through native signatures as raw `Long` addresses and are converted at the boundary.

## Building & testing

```bash
./gradlew build                 # all targets: compile, link, run host tests
./gradlew jvmTest               # 42 acceptance tests on the JVM
./gradlew macosArm64Test        # the same 42 tests on Kotlin/Native
```

The acceptance tests live in `src/commonTest` and run **unchanged** on every platform — they are the contract every backend has to fulfill. Linux/Windows test binaries are cross-compiled and linked on any host; running them requires a matching host (CI planned).

## Current limitations

- Struct passing **by value** is not supported (pointer-based access only).
- Varargs are dynamic-API only.
- `errno`/`GetLastError` capture is not built in yet.
- The static JVM path still boxes arguments (cached handle, but `invokeWithArguments`); the static Native path is already boxing-free.
- Callback signatures are checked at runtime, not compile time.
- 64-bit targets only.

## Project structure

```
src/commonMain        public API (Ptr, CType, NativeLibrary, Arena, CStruct, Callback, @Import)
src/jvmMain           FFM backend
src/nativeMain        libffi call engine + closures (all native targets)
src/posixMain         dlopen loader (macOS, Linux)
src/mingwMain         LoadLibrary loader (Windows)
src/commonTest        cross-platform acceptance tests
invokt-compiler/      compiler plugin + Gradle plugin (included build)
third_party/libffi/   vendored libffi for Linux/Windows (see its README)
```

## License

Not decided yet. Note that `third_party/libffi` is distributed under libffi's own MIT-style license (`third_party/libffi/LICENSE`).
