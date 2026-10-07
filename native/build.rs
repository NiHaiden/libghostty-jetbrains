//! Builds libghostty-vt (Zig, from the vendored submodule) as static
//! archives for the Cargo target and links them into this crate.
//!
//! Needs `zig` 0.16 on PATH (or `ZIG=/path/to/zig`). Override the Zig target
//! triple with `GHOSTTY_JB_ZIG_TARGET` if the mapping below doesn't fit.

use std::env;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;

fn zig_target(rust_target: &str) -> Option<&'static str> {
    Some(match rust_target {
        "x86_64-unknown-linux-gnu" => "x86_64-linux-gnu",
        "aarch64-unknown-linux-gnu" => "aarch64-linux-gnu",
        "x86_64-apple-darwin" => "x86_64-macos.11.0",
        "aarch64-apple-darwin" => "aarch64-macos.11.0",
        "x86_64-pc-windows-gnu" | "x86_64-pc-windows-gnullvm" => "x86_64-windows-gnu",
        "aarch64-pc-windows-gnullvm" => "aarch64-windows-gnu",
        _ => return None,
    })
}

fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").unwrap());
    let out = PathBuf::from(env::var("OUT_DIR").unwrap());
    let target = env::var("TARGET").unwrap();
    let profile = env::var("PROFILE").unwrap_or_default();

    for path in
        ["build.zig", "build.zig.zon", "vendor/ghostty/src", "vendor/ghostty/include", "vendor/ghostty/build.zig"]
    {
        println!("cargo:rerun-if-changed={}", manifest.join(path).display());
    }
    println!("cargo:rerun-if-env-changed=ZIG");
    println!("cargo:rerun-if-env-changed=GHOSTTY_JB_ZIG_TARGET");

    let zig_target = env::var("GHOSTTY_JB_ZIG_TARGET")
        .ok()
        .filter(|t| !t.is_empty())
        .or_else(|| zig_target(&target).map(str::to_owned))
        .unwrap_or_else(|| panic!("no Zig target known for {target}; set GHOSTTY_JB_ZIG_TARGET"));
    // Debug builds keep Zig's safety checks, which catch API misuse in tests.
    let optimize = if profile == "release" { "ReleaseFast" } else { "ReleaseSafe" };

    let prefix = out.join("ghostty-vt");
    let zig = env::var("ZIG").unwrap_or_else(|_| "zig".to_owned());
    let status = Command::new(&zig)
        .current_dir(&manifest)
        .arg("build")
        .arg(format!("-Doptimize={optimize}"))
        .arg(format!("-Dtarget={zig_target}"))
        .arg("--prefix")
        .arg(&prefix)
        // cargo-zigbuild exports its own compiler settings; keep them away
        // from the nested Zig build.
        .env_remove("CC")
        .env_remove("CXX")
        .env_remove("CFLAGS")
        .env_remove("CXXFLAGS")
        .status()
        .unwrap_or_else(|e| panic!("failed to run `{zig} build` (is Zig 0.16 installed?): {e}"));
    assert!(status.success(), "`zig build` for libghostty-vt failed ({status})");

    // libghostty-vt first: it references simdutf and highway. These are
    // built without libc++, so only the C library is needed besides them.
    let libs = ["ghostty-vt-static", "simdutf", "highway"];
    let mut lib_dir = prefix.join("lib");
    normalize_archive_names(&lib_dir);
    if target.contains("apple") && env::var("HOST").is_ok_and(|h| h.contains("apple")) {
        lib_dir = rewrite_for_apple_ld(&lib_dir, &out.join("ghostty-vt-apple"), &libs);
    }
    println!("cargo:rustc-link-search=native={}", lib_dir.display());
    for lib in libs {
        println!("cargo:rustc-link-lib=static={lib}");
    }
    if target.contains("apple") {
        // Don't bake the build directory into the dylib's install name.
        println!("cargo:rustc-cdylib-link-arg=-Wl,-install_name,@rpath/libghostty_jb.dylib");
    }
    if target.contains("windows") {
        // The Zig standard library inside libghostty-vt uses NT APIs.
        println!("cargo:rustc-link-lib=dylib=ntdll");
        println!("cargo:rustc-link-lib=dylib=kernel32");
    }
}

/// Zig names Windows static libraries `name.lib`; the GNU-style linkers used
/// for Rust's `*-windows-gnu*` targets look for `libname.a`.
fn normalize_archive_names(dir: &Path) {
    let Ok(entries) = fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.extension().is_some_and(|e| e == "lib") {
            let stem = path.file_stem().unwrap().to_string_lossy();
            let _ = fs::copy(&path, dir.join(format!("lib{stem}.a")));
        }
    }
}

/// Apple's linker rejects the archives Zig writes ("64-bit mach-o member not
/// 8-byte aligned"). Rewrite them with Apple's own tools, the same way
/// Ghostty's build does (vendor/ghostty/src/build/LibtoolStep.zig): ranlib
/// normalizes the layout, libtool writes a clean archive. Only needed when
/// linking with Apple's ld; Zig's linker (cargo-zigbuild) reads them fine.
fn rewrite_for_apple_ld(src: &Path, dst: &Path, libs: &[&str]) -> PathBuf {
    fs::create_dir_all(dst).expect("create archive dir");
    for lib in libs {
        let name = format!("lib{lib}.a");
        let tmp = dst.join(format!("ranlib-{name}"));
        fs::copy(src.join(&name), &tmp).unwrap_or_else(|e| panic!("copy {name}: {e}"));
        run(Command::new("/usr/bin/ranlib").arg(&tmp));
        let _ = fs::remove_file(dst.join(&name));
        run(Command::new("libtool").args(["-static", "-o"]).arg(dst.join(&name)).arg(&tmp));
    }
    dst.to_path_buf()
}

fn run(cmd: &mut Command) {
    let status = cmd.status().unwrap_or_else(|e| panic!("failed to run {cmd:?}: {e}"));
    assert!(status.success(), "{cmd:?} failed ({status})");
}
