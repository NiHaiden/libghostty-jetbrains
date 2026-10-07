//! Key, mouse and focus encoding.

use super::terminal::Terminal;
use super::{Error, Result, check, sized};
use crate::sys;
use std::ffi::c_void;
use std::ptr::{self, NonNull};

/// macOS Option key handling (`GhosttyOptionAsAlt`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum OptionAsAlt {
    #[default]
    No = 0,
    Yes = 1,
    Left = 2,
    Right = 3,
}

/// A key event. `key` is a `GhosttyKey`, `mods`/`consumed_mods` are
/// `GhosttyMods` bits, `text` is what the key types in the current layout
/// without Ctrl/Alt applied (no control characters).
#[derive(Debug, Clone, Copy)]
pub struct KeyInput<'a> {
    /// 0 release, 1 press, 2 repeat.
    pub action: i32,
    pub key: i32,
    pub mods: u16,
    pub consumed_mods: u16,
    pub unshifted: u32,
    pub text: Option<&'a str>,
}

#[derive(Debug)]
pub struct KeyEncoder {
    encoder: NonNull<sys::GhosttyKeyEncoderImpl>,
    event: NonNull<sys::GhosttyKeyEventImpl>,
}

// SAFETY: no thread affinity; used through `&mut self` only.
unsafe impl Send for KeyEncoder {}

impl Drop for KeyEncoder {
    fn drop(&mut self) {
        // SAFETY: we own both handles.
        unsafe {
            sys::ghostty_key_event_free(self.event.as_ptr());
            sys::ghostty_key_encoder_free(self.encoder.as_ptr());
        }
    }
}

fn new_handle<T>(f: impl FnOnce(*mut *mut T) -> sys::GhosttyResult) -> Result<NonNull<T>> {
    let mut raw: *mut T = ptr::null_mut();
    check(f(&mut raw))?;
    NonNull::new(raw).ok_or(Error::OUT_OF_MEMORY)
}

impl KeyEncoder {
    pub fn new() -> Result<KeyEncoder> {
        // SAFETY: plain constructors.
        let encoder = new_handle(|out| unsafe { sys::ghostty_key_encoder_new(ptr::null(), out) })?;
        let event = match new_handle(|out| unsafe { sys::ghostty_key_event_new(ptr::null(), out) }) {
            Ok(e) => e,
            Err(e) => {
                // SAFETY: freeing what we just created.
                unsafe { sys::ghostty_key_encoder_free(encoder.as_ptr()) };
                return Err(e);
            }
        };
        Ok(KeyEncoder { encoder, event })
    }

    /// Encodes a key for the terminal's current modes (cursor keys, Kitty
    /// keyboard flags, ...). Returns the bytes for the pty (possibly empty).
    pub fn encode(&mut self, term: &Terminal, input: &KeyInput<'_>, option_as_alt: OptionAsAlt) -> Result<Vec<u8>> {
        let (enc, ev) = (self.encoder.as_ptr(), self.event.as_ptr());
        let opt = option_as_alt as sys::GhosttyOptionAsAlt;
        let text = input.text.unwrap_or("");
        // Values come from Java: never hand Zig an out-of-range enum.
        let action = match input.action {
            0 => sys::GhosttyKeyAction_GHOSTTY_KEY_ACTION_RELEASE,
            2 => sys::GhosttyKeyAction_GHOSTTY_KEY_ACTION_REPEAT,
            _ => sys::GhosttyKeyAction_GHOSTTY_KEY_ACTION_PRESS,
        };
        let key = u32::try_from(input.key)
            .ok()
            .filter(|k| *k <= sys::GhosttyKey_GHOSTTY_KEY_PASTE)
            .unwrap_or(sys::GhosttyKey_GHOSTTY_KEY_UNIDENTIFIED);
        // SAFETY: valid handles; `text` outlives the encode call and is
        // detached from the event again before returning.
        unsafe {
            sys::ghostty_key_encoder_setopt_from_terminal(enc, term.raw_handle());
            sys::ghostty_key_encoder_setopt(
                enc,
                sys::GhosttyKeyEncoderOption_GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT,
                &opt as *const _ as *const c_void,
            );
            sys::ghostty_key_event_set_action(ev, action);
            sys::ghostty_key_event_set_key(ev, key);
            sys::ghostty_key_event_set_mods(ev, input.mods);
            sys::ghostty_key_event_set_consumed_mods(ev, input.consumed_mods);
            sys::ghostty_key_event_set_unshifted_codepoint(ev, input.unshifted);
            sys::ghostty_key_event_set_composing(ev, false);
            sys::ghostty_key_event_set_utf8(ev, text.as_ptr().cast(), text.len());
        }
        let mut out = vec![0u8; 128];
        let mut len = 0usize;
        // SAFETY: `out` is writable for its full length.
        let mut r = unsafe { sys::ghostty_key_encoder_encode(enc, ev, out.as_mut_ptr().cast(), out.len(), &mut len) };
        if r == sys::GhosttyResult_GHOSTTY_OUT_OF_SPACE {
            out.resize(len.max(out.len() * 2), 0);
            // SAFETY: as above, with a larger buffer.
            r = unsafe { sys::ghostty_key_encoder_encode(enc, ev, out.as_mut_ptr().cast(), out.len(), &mut len) };
        }
        // SAFETY: drop the borrowed text pointer from the event.
        unsafe { sys::ghostty_key_event_set_utf8(ev, ptr::null(), 0) };
        check(r)?;
        out.truncate(len);
        Ok(out)
    }
}

/// Surface geometry for pixel-based mouse reports.
#[derive(Debug, Clone, Copy, Default)]
pub struct MouseGeometry {
    pub cols: u16,
    pub rows: u16,
    pub cell_width: u32,
    pub cell_height: u32,
    pub pad_left: u32,
    pub pad_top: u32,
}

#[derive(Debug, Clone, Copy)]
pub struct MouseInput {
    /// 0 press, 1 release, 2 motion.
    pub action: i32,
    /// `GhosttyMouseButton`; 0 for none.
    pub button: i32,
    pub mods: u16,
    pub x: f32,
    pub y: f32,
    pub any_button_pressed: bool,
}

#[derive(Debug)]
pub struct MouseEncoder {
    encoder: NonNull<sys::GhosttyMouseEncoderImpl>,
    event: NonNull<sys::GhosttyMouseEventImpl>,
}

// SAFETY: no thread affinity; used through `&mut self` only.
unsafe impl Send for MouseEncoder {}

impl Drop for MouseEncoder {
    fn drop(&mut self) {
        // SAFETY: we own both handles.
        unsafe {
            sys::ghostty_mouse_event_free(self.event.as_ptr());
            sys::ghostty_mouse_encoder_free(self.encoder.as_ptr());
        }
    }
}

impl MouseEncoder {
    pub fn new() -> Result<MouseEncoder> {
        // SAFETY: plain constructors.
        let encoder = new_handle(|out| unsafe { sys::ghostty_mouse_encoder_new(ptr::null(), out) })?;
        let event = match new_handle(|out| unsafe { sys::ghostty_mouse_event_new(ptr::null(), out) }) {
            Ok(e) => e,
            Err(e) => {
                // SAFETY: freeing what we just created.
                unsafe { sys::ghostty_mouse_encoder_free(encoder.as_ptr()) };
                return Err(e);
            }
        };
        Ok(MouseEncoder { encoder, event })
    }

    /// Encodes a mouse event for the program's tracking mode. Empty when the
    /// program isn't tracking the mouse (or doesn't want this event).
    pub fn encode(&mut self, term: &Terminal, input: &MouseInput, geo: &MouseGeometry) -> Result<Vec<u8>> {
        if !term.mouse_tracking() {
            return Ok(Vec::new());
        }
        let (enc, ev) = (self.encoder.as_ptr(), self.event.as_ptr());
        let size = sys::GhosttyMouseEncoderSize {
            cell_width: geo.cell_width.max(1),
            cell_height: geo.cell_height.max(1),
            padding_left: geo.pad_left,
            padding_top: geo.pad_top,
            screen_width: geo.pad_left + geo.cols as u32 * geo.cell_width.max(1),
            screen_height: geo.pad_top + geo.rows as u32 * geo.cell_height.max(1),
            ..sized!(sys::GhosttyMouseEncoderSize)
        };
        let pressed = input.any_button_pressed;
        let track_last_cell = true;
        // Values come from Java: never hand Zig an out-of-range enum.
        let action = match input.action {
            1 => sys::GhosttyMouseAction_GHOSTTY_MOUSE_ACTION_RELEASE,
            2 => sys::GhosttyMouseAction_GHOSTTY_MOUSE_ACTION_MOTION,
            _ => sys::GhosttyMouseAction_GHOSTTY_MOUSE_ACTION_PRESS,
        };
        let button = u32::try_from(input.button)
            .ok()
            .filter(|b| (1..=sys::GhosttyMouseButton_GHOSTTY_MOUSE_BUTTON_ELEVEN).contains(b));
        // SAFETY: valid handles; option values have the documented types.
        unsafe {
            sys::ghostty_mouse_encoder_setopt_from_terminal(enc, term.raw_handle());
            sys::ghostty_mouse_encoder_setopt(
                enc,
                sys::GhosttyMouseEncoderOption_GHOSTTY_MOUSE_ENCODER_OPT_SIZE,
                &size as *const _ as *const c_void,
            );
            sys::ghostty_mouse_encoder_setopt(
                enc,
                sys::GhosttyMouseEncoderOption_GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED,
                &pressed as *const _ as *const c_void,
            );
            sys::ghostty_mouse_encoder_setopt(
                enc,
                sys::GhosttyMouseEncoderOption_GHOSTTY_MOUSE_ENCODER_OPT_TRACK_LAST_CELL,
                &track_last_cell as *const _ as *const c_void,
            );
            sys::ghostty_mouse_event_set_action(ev, action);
            match button {
                Some(b) => sys::ghostty_mouse_event_set_button(ev, b),
                None => sys::ghostty_mouse_event_clear_button(ev),
            }
            sys::ghostty_mouse_event_set_mods(ev, input.mods);
            sys::ghostty_mouse_event_set_position(ev, sys::GhosttyMousePosition { x: input.x, y: input.y });
        }
        let mut out = vec![0u8; 64];
        let mut len = 0usize;
        // SAFETY: `out` is writable for its full length.
        check(unsafe { sys::ghostty_mouse_encoder_encode(enc, ev, out.as_mut_ptr().cast(), out.len(), &mut len) })?;
        out.truncate(len);
        Ok(out)
    }
}

/// Encodes a focus change (only meaningful when mode 1004 is on).
pub fn encode_focus(gained: bool) -> Vec<u8> {
    let event =
        if gained { sys::GhosttyFocusEvent_GHOSTTY_FOCUS_GAINED } else { sys::GhosttyFocusEvent_GHOSTTY_FOCUS_LOST };
    let mut buf = [0u8; 16];
    let mut n = 0usize;
    // SAFETY: `buf` is writable for its full length.
    let r = unsafe { sys::ghostty_focus_encode(event, buf.as_mut_ptr().cast(), buf.len(), &mut n) };
    if r == sys::GhosttyResult_GHOSTTY_SUCCESS { buf[..n.min(buf.len())].to_vec() } else { Vec::new() }
}
