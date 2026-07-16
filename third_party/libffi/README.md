# Vendored libffi

Headers and static libraries for targets whose Kotlin/Native sysroot does not
provide libffi (macOS gets it from the system SDK instead). See `LICENSE`
(MIT-style) in this directory.

| Directory | Version | Source package |
|---|---|---|
| `linux-x64/` | 3.2.1 | JetBrains `libffi-3.2.1-2-linux-x86-64.tar.gz` (download.jetbrains.com/kotlin/native/) — built against the exact glibc 2.19 sysroot K/N links with; newer distro builds reference glibc symbols (`memfd_create`, `__isoc23_*`) the sysroot lacks |
| `mingw-x64/` | 3.5.2 | msys2 `mingw-w64-x86_64-libffi-3.5.2-1` (mingw-w64 build, matches the K/N MinGW toolchain) |

The static libraries are embedded into the cinterop klib (`-staticLibrary`
in the build script), so consumers of invokt need no libffi at runtime on
these targets.

To update: fetch the new packages, copy `ffi.h` + `ffitarget.h` into
`<target>/include/ffi/` and `libffi.a` into `<target>/lib/`.
