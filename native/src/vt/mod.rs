//! Safe wrappers around libghostty-vt.
//!
//! This module (and `sys`) is the only place in the crate allowed to use
//! `unsafe`; everything else is `#![deny(unsafe_code)]`. Each wrapper owns
//! its libghostty handle and frees it on drop, and the API is shaped so that
//! libghostty's rules hold by construction:
//!
//! - every mutating call takes `&mut self`, so the borrow checker gives us
//!   the "no concurrent access" requirement for free;
//! - grid references (which are only valid until the next mutating call)
//!   never leave this module: methods take viewport coordinates instead;
//! - callbacks don't dereference user-data pointers; they talk to the
//!   terminal's [`CallbackCtx`] through a thread-local that is only set
//!   while a call into libghostty is in progress.
#![allow(unsafe_code)]

mod input;
mod render;
mod search;
mod terminal;

pub use input::{KeyEncoder, KeyInput, MouseEncoder, MouseGeometry, MouseInput, OptionAsAlt, encode_focus};
pub use render::{Cell, CellStyle, Colors, Cursor, Dirty, RenderState, Row, Rows, StyleColor};
pub use search::{Search, SearchStatus, Span};
pub use terminal::{
    CallbackCtx, CursorStyle, Event, GestureInput, GestureKind, GestureResult, PasteOutcome, ProgressState, ScrollTo,
    Scrollbar, Terminal,
};

use crate::sys;
use std::fmt;

/// A libghostty error code (a negative `GhosttyResult`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Error(pub i32);

impl Error {
    pub const OUT_OF_MEMORY: Error = Error(sys::GhosttyResult_GHOSTTY_OUT_OF_MEMORY);
    pub const INVALID_VALUE: Error = Error(sys::GhosttyResult_GHOSTTY_INVALID_VALUE);
    pub const OUT_OF_SPACE: Error = Error(sys::GhosttyResult_GHOSTTY_OUT_OF_SPACE);
    pub const NO_VALUE: Error = Error(sys::GhosttyResult_GHOSTTY_NO_VALUE);
    pub const REJECTED: Error = Error(sys::GhosttyResult_GHOSTTY_REJECTED);
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "libghostty error {}", self.0)
    }
}

impl std::error::Error for Error {}

pub type Result<T> = std::result::Result<T, Error>;

/// Converts a `GhosttyResult` into a `Result`.
pub(crate) fn check(code: sys::GhosttyResult) -> Result<()> {
    if code == sys::GhosttyResult_GHOSTTY_SUCCESS { Ok(()) } else { Err(Error(code)) }
}

/// A 24-bit color.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Rgb {
    pub r: u8,
    pub g: u8,
    pub b: u8,
}

impl Rgb {
    pub const fn from_u24(v: u32) -> Rgb {
        Rgb { r: (v >> 16) as u8, g: (v >> 8) as u8, b: v as u8 }
    }

    pub const fn to_u24(self) -> u32 {
        ((self.r as u32) << 16) | ((self.g as u32) << 8) | self.b as u32
    }

    fn from_sys(c: sys::GhosttyColorRgb) -> Rgb {
        Rgb { r: c.r, g: c.g, b: c.b }
    }

    fn to_sys(self) -> sys::GhosttyColorRgb {
        sys::GhosttyColorRgb { r: self.r, g: self.g, b: self.b }
    }
}

/// A terminal mode (DEC private or ANSI), as `ghostty_mode_new` encodes it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Mode(u16);

impl Mode {
    pub const fn dec(value: u16) -> Mode {
        Mode(value & 0x7fff)
    }

    pub const fn ansi(value: u16) -> Mode {
        Mode((value & 0x7fff) | 0x8000)
    }

    pub const FOCUS_EVENT: Mode = Mode::dec(1004);
    pub const ALT_SCROLL: Mode = Mode::dec(1007);
    pub const BRACKETED_PASTE: Mode = Mode::dec(2004);
    pub const SYNC_OUTPUT: Mode = Mode::dec(2026);
    pub const GRAPHEME_CLUSTER: Mode = Mode::dec(2027);
    pub const COLOR_SCHEME_REPORT: Mode = Mode::dec(2031);
    pub const REVERSE_COLORS: Mode = Mode::dec(5);
}

/// Initializes one of libghostty's "sized" structs (first field `size`).
macro_rules! sized {
    ($($p:ident)::+) => {
        $($p)::+ { size: ::core::mem::size_of::<$($p)::+>(), ..Default::default() }
    };
}
pub(crate) use sized;

/// Copies a borrowed libghostty byte string.
///
/// # Safety
/// `ptr` must be null or point to `len` readable bytes.
pub(crate) unsafe fn copy_bytes(ptr: *const u8, len: usize) -> Vec<u8> {
    if ptr.is_null() || len == 0 {
        return Vec::new();
    }
    // SAFETY: guaranteed by the caller.
    unsafe { std::slice::from_raw_parts(ptr, len) }.to_vec()
}

/// Copies a `GhosttyString`.
///
/// # Safety
/// The string must be valid (libghostty documents when it is).
pub(crate) unsafe fn copy_string(s: sys::GhosttyString) -> Vec<u8> {
    // SAFETY: guaranteed by the caller.
    unsafe { copy_bytes(s.ptr, s.len) }
}

/// libghostty's built-in 256-color palette.
pub fn default_palette() -> [Rgb; 256] {
    let mut out = [sys::GhosttyColorRgb::default(); 256];
    // SAFETY: writes exactly 256 entries.
    unsafe { sys::ghostty_color_palette_default(out.as_mut_ptr()) };
    out.map(Rgb::from_sys)
}

/// Derives the 6x6x6 cube and grayscale ramp (16..=255) from the first 16
/// colors of `base` and the background/foreground, like Ghostty's
/// `palette-generate`, so 256-color programs match the theme.
pub fn generate_palette(base: &[Rgb; 256], background: Rgb, foreground: Rgb) -> [Rgb; 256] {
    let base = base.map(Rgb::to_sys);
    let skip = sys::GhosttyColorPaletteMask::default();
    let (bg, fg) = (background.to_sys(), foreground.to_sys());
    let mut out = [sys::GhosttyColorRgb::default(); 256];
    // SAFETY: all pointers are valid; `base`/`out` hold 256 entries each.
    unsafe { sys::ghostty_color_palette_generate(base.as_ptr(), &skip, &bg, &fg, false, out.as_mut_ptr()) };
    out.map(Rgb::from_sys)
}

/// A few `GhosttyKey` values (the plugin uses the full generated table).
pub mod key {
    use crate::sys;
    pub const A: i32 = sys::GhosttyKey_GHOSTTY_KEY_A as i32;
    pub const C: i32 = sys::GhosttyKey_GHOSTTY_KEY_C as i32;
    pub const ENTER: i32 = sys::GhosttyKey_GHOSTTY_KEY_ENTER as i32;
    pub const ESCAPE: i32 = sys::GhosttyKey_GHOSTTY_KEY_ESCAPE as i32;
    pub const BACKSPACE: i32 = sys::GhosttyKey_GHOSTTY_KEY_BACKSPACE as i32;
    pub const ARROW_UP: i32 = sys::GhosttyKey_GHOSTTY_KEY_ARROW_UP as i32;
    pub const ARROW_RIGHT: i32 = sys::GhosttyKey_GHOSTTY_KEY_ARROW_RIGHT as i32;
}

/// `GhosttyMods` bits.
pub mod mods {
    use crate::sys;
    pub const SHIFT: u16 = sys::GHOSTTY_MODS_SHIFT as u16;
    pub const CTRL: u16 = sys::GHOSTTY_MODS_CTRL as u16;
    pub const ALT: u16 = sys::GHOSTTY_MODS_ALT as u16;
    pub const SUPER: u16 = sys::GHOSTTY_MODS_SUPER as u16;
}
