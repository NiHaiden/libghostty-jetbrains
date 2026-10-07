//! JNI bridge between the Ghostty JetBrains plugin and libghostty-vt.
//!
//! Layers, bottom up:
//! - `sys`: raw bindgen declarations (generated, never used directly);
//! - [`vt`]: safe RAII wrappers, the only module allowed to use `unsafe`;
//! - [`term`]: the terminal facade the plugin needs, in safe Rust;
//! - `jni_api`: the JVM entry points (no `unsafe` of its own).
#![deny(unsafe_code)]

pub mod frame;
mod jni_api;
mod sys;
pub mod term;
pub mod vt;
