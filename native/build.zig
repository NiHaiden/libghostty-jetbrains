const std = @import("std");

// Builds libghostty-vt as static archives for the Rust crate in this
// directory (see build.rs, which runs this). Installs into <prefix>/lib:
//
//   libghostty-vt-static.a   (or ghostty-vt-static.lib on Windows)
//   + every static library it links (simdutf, highway, ...), which Zig
//     does not merge into the main archive.
//
//   zig build -Doptimize=ReleaseFast -Dtarget=x86_64-linux-gnu --prefix out
pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const ghostty = b.lazyDependency("ghostty", .{
        .target = target,
        .optimize = optimize,
    }) orelse return;
    const vt = ghostty.artifact("ghostty-vt-static");
    b.installArtifact(vt);
    installLinkedStatics(b, vt);
}

fn installLinkedStatics(b: *std.Build, artifact: *std.Build.Step.Compile) void {
    for (artifact.root_module.link_objects.items) |obj| switch (obj) {
        .other_step => |dep| if (dep.kind == .lib and dep.linkage == .static) {
            b.installArtifact(dep);
            installLinkedStatics(b, dep);
        },
        else => {},
    };
}
