# Changelog

## 0.2.0

**Native core rewritten in Rust.** The C facade over libghostty-vt is replaced by the
`ghostty-jb` Rust crate (`native/`), and the plugin talks to it over JNI instead of JNA.

- `unsafe` is confined to one module (`native/src/vt/`, RAII wrappers over the C API);
  the terminal facade and the JNI layer contain none.
- Java never holds native pointers: terminals are opaque ids, so a closed or bogus
  handle throws `IllegalStateException` instead of touching freed memory. Rust panics
  become Java exceptions.
- Values coming from Java are validated before they reach libghostty.

**Ghostty in the built-in Terminal tool window.**

- *Ghostty* appears in the Terminal tool window's new-tab dropdown (+ ▾) and opens a
  Ghostty tab right there.
- New setting *Open new tabs in*: the Ghostty tool window (default) or the Terminal
  tool window.
- The Terminal plugin is an optional dependency; without it the Ghostty tool window
  works as before. Ghostty can't replace the built-in terminal's engine (the IDE has
  no extension point for that).

**Other changes**

- OSC 52 clipboard writes follow the *Allow programs to set the clipboard* setting
  inside the native layer; programs are answered immediately.
- Native libraries: Linux x64/arm64 (glibc ≥ 2.28), macOS x64/arm64 (≥ 11),
  Windows x64/arm64. Native library files are now named `libghostty_jb.so`,
  `libghostty_jb.dylib` and `ghostty_jb.dll`.
- CI tests every shipped library on its platform (macOS x64 excepted: no runner).

## 0.1.0

First version: a libghostty-vt terminal in its own *Ghostty* tool window, with a C
facade bound through JNA.
