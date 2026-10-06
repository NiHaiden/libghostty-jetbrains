const std = @import("std");

// Builds libghostty-jb: a shared library exposing the small ghostty_jb.h
// facade, with libghostty-vt linked in statically. One self-contained
// native file per platform is all the plugin has to ship.
//
//   zig build -Doptimize=ReleaseFast                      host platform
//   zig build -Doptimize=ReleaseFast -Dtarget=aarch64-macos
//   zig build test                                        run the C tests
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const ghostty = b.lazyDependency("ghostty", .{
        .target = target,
        .optimize = optimize,
    }) orelse return;
    const vt = ghostty.artifact("ghostty-vt-static");

    const c_flags: []const []const u8 = &.{
        "-std=c11",
        "-Wall",
        "-Wextra",
        "-Wno-unused-parameter",
        "-DGHOSTTY_STATIC",
        "-fvisibility=hidden",
    };

    const lib_mod = b.createModule(.{
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .pic = true,
        .strip = optimize != .Debug,
    });
    lib_mod.addCSourceFiles(.{
        .root = b.path("src"),
        .files = &.{"ghostty_jb.c"},
        .flags = c_flags,
    });
    lib_mod.addIncludePath(b.path("src"));
    lib_mod.linkLibrary(vt);

    const lib = b.addLibrary(.{
        .name = "ghostty-jb",
        .linkage = .dynamic,
        .root_module = lib_mod,
    });
    b.installArtifact(lib);

    // Tests: a C program that drives the facade the same way the plugin does.
    const test_mod = b.createModule(.{
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    test_mod.addCSourceFiles(.{
        .root = b.path("."),
        .files = &.{ "test/test_main.c", "src/ghostty_jb.c" },
        .flags = c_flags,
    });
    test_mod.addIncludePath(b.path("src"));
    test_mod.linkLibrary(vt);
    const test_exe = b.addExecutable(.{
        .name = "ghostty_jb_test",
        .root_module = test_mod,
    });
    const run_tests = b.addRunArtifact(test_exe);
    const test_step = b.step("test", "Run the native facade tests");
    test_step.dependOn(&run_tests.step);
}
