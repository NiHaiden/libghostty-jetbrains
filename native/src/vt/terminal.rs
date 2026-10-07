//! The libghostty terminal, its callbacks and the operations on it.

use super::render::RenderState;
use super::{Error, Mode, Result, Rgb, check, copy_bytes, copy_string, sized};
use crate::sys;
use std::cell::RefCell;
use std::ffi::c_void;
use std::ptr::{self, NonNull};
use std::time::Instant;

/// Something the terminal reported while processing a call.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    /// Bytes for the pty (query replies, encoded pastes, ...).
    WritePty(Vec<u8>),
    Bell,
    TitleChanged(String),
    PwdChanged(String),
    /// A program set the clipboard (OSC 52 & co). Only reported when allowed.
    ClipboardWrite {
        text: String,
        primary: bool,
    },
    Notification {
        title: String,
        body: String,
    },
    Progress {
        state: ProgressState,
        progress: Option<u8>,
    },
    CommandFinished {
        exit_code: Option<i32>,
    },
}

/// OSC 9;4 progress states.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProgressState {
    Remove = 0,
    Set = 1,
    Error = 2,
    Indeterminate = 3,
    Pause = 4,
}

/// State shared with libghostty's callbacks. It lives inside the terminal
/// and is lent to a thread-local only while a libghostty call runs.
#[derive(Debug)]
pub struct CallbackCtx {
    pub events: Vec<Event>,
    pub cols: u16,
    pub rows: u16,
    pub cell_width: u32,
    pub cell_height: u32,
    pub dark: bool,
    pub allow_clipboard_write: bool,
    /// Synchronized output (mode 2026): a program asked us to stop updating.
    pub held: bool,
    pub held_since: Option<Instant>,
    /// Captured by the render-hold callback, so it lives here too.
    pub render: RenderState,
}

thread_local! {
    /// The context of the terminal whose libghostty call is in progress on
    /// this thread (callbacks always run synchronously inside that call).
    static ACTIVE: RefCell<Option<Box<CallbackCtx>>> = const { RefCell::new(None) };
    /// Data being pasted, read by the paste reader callback.
    static PASTE_SOURCE: RefCell<Vec<u8>> = const { RefCell::new(Vec::new()) };
}

fn with_active(f: impl FnOnce(&mut CallbackCtx)) {
    ACTIVE.with_borrow_mut(|ctx| {
        if let Some(ctx) = ctx.as_mut() {
            f(ctx);
        }
    });
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CursorStyle {
    Bar = 0,
    Block = 1,
    Underline = 2,
    BlockHollow = 3,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScrollTo {
    Top,
    Bottom,
    Delta(isize),
    Row(usize),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Scrollbar {
    pub total: u64,
    pub offset: u64,
    pub len: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PasteOutcome {
    Written,
    /// Could run commands; nothing was written. Retry with `allow_unsafe`.
    Rejected,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GestureKind {
    Press,
    Release,
    Drag,
    AutoscrollTick,
}

/// A pointer event for the selection gesture state machine. Positions are
/// surface pixels; geometry describes the grid on that surface.
#[derive(Debug, Clone, Copy)]
pub struct GestureInput {
    pub kind: GestureKind,
    pub x: f64,
    pub y: f64,
    pub time_ns: u64,
    pub rectangle: bool,
    pub click_interval_ns: u64,
    pub cols: u16,
    pub rows: u16,
    pub cell_width: u32,
    pub cell_height: u32,
    pub pad_left: u32,
    pub pad_top: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct GestureResult {
    pub changed: bool,
    pub has_selection: bool,
    pub autoscroll_up: bool,
    pub autoscroll_down: bool,
}

/// A libghostty-vt terminal.
#[derive(Debug)]
pub struct Terminal {
    raw: NonNull<sys::GhosttyTerminalImpl>,
    ctx: Option<Box<CallbackCtx>>,
    gesture: NonNull<sys::GhosttySelectionGestureImpl>,
    /// Reusable gesture events, indexed by `GestureKind`.
    gesture_events: [NonNull<sys::GhosttySelectionGestureEventImpl>; 4],
}

// SAFETY: libghostty terminals have no thread affinity; they only require
// that calls are serialized, which `&mut self` (and the plugin's per-terminal
// mutex) guarantees. Callback context is moved, not shared, across threads.
unsafe impl Send for Terminal {}

impl Drop for Terminal {
    fn drop(&mut self) {
        // SAFETY: we own these handles; they're freed exactly once, events and
        // the gesture before the terminal they refer to.
        unsafe {
            for ev in self.gesture_events {
                sys::ghostty_selection_gesture_event_free(ev.as_ptr());
            }
            sys::ghostty_selection_gesture_free(self.gesture.as_ptr(), self.raw.as_ptr());
            sys::ghostty_terminal_free(self.raw.as_ptr());
        }
    }
}

/// Creates a libghostty object through an out-parameter constructor.
fn new_handle<T>(f: impl FnOnce(*mut *mut T) -> sys::GhosttyResult) -> Result<NonNull<T>> {
    let mut raw: *mut T = ptr::null_mut();
    check(f(&mut raw))?;
    NonNull::new(raw).ok_or(Error::OUT_OF_MEMORY)
}

impl Terminal {
    pub fn new(cols: u16, rows: u16, ctx: CallbackCtx) -> Result<Terminal> {
        let cols = cols.max(1);
        let rows = rows.max(1);
        // SAFETY: plain constructor; NULL allocator selects the default one.
        let raw = new_handle(|out| unsafe { sys::ghostty_terminal_new(ptr::null(), out, cols, rows) })?;
        // SAFETY: as above.
        let gesture = match new_handle(|out| unsafe { sys::ghostty_selection_gesture_new(ptr::null(), out) }) {
            Ok(g) => g,
            Err(e) => {
                // SAFETY: freeing the terminal we just created.
                unsafe { sys::ghostty_terminal_free(raw.as_ptr()) };
                return Err(e);
            }
        };
        let kinds = [
            sys::GhosttySelectionGestureEventType_GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_PRESS,
            sys::GhosttySelectionGestureEventType_GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_RELEASE,
            sys::GhosttySelectionGestureEventType_GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_DRAG,
            sys::GhosttySelectionGestureEventType_GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_AUTOSCROLL_TICK,
        ];
        let mut events = Vec::with_capacity(4);
        for kind in kinds {
            // SAFETY: plain constructor.
            match new_handle(|out| unsafe { sys::ghostty_selection_gesture_event_new(ptr::null(), out, kind) }) {
                Ok(ev) => events.push(ev),
                Err(e) => {
                    // SAFETY: freeing what we created above, in reverse order.
                    unsafe {
                        for ev in events {
                            sys::ghostty_selection_gesture_event_free(ev.as_ptr());
                        }
                        sys::ghostty_selection_gesture_free(gesture.as_ptr(), raw.as_ptr());
                        sys::ghostty_terminal_free(raw.as_ptr());
                    }
                    return Err(e);
                }
            }
        }
        let gesture_events = [events[0], events[1], events[2], events[3]];

        let mut term = Terminal { raw, ctx: Some(Box::new(ctx)), gesture, gesture_events };
        term.install_callbacks();
        // Advertise xterm-256color: the xterm-ghostty terminfo is rarely installed.
        term.set_string(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_TERMINFO_NAME, b"xterm-256color");
        // Like the Ghostty app: cluster graphemes (emoji ZWJ sequences, flags...).
        term.set_mode_default(Mode::GRAPHEME_CLUSTER, true);
        Ok(term)
    }

    fn raw(&self) -> sys::GhosttyTerminal {
        self.raw.as_ptr()
    }

    fn install_callbacks(&mut self) {
        type O = sys::GhosttyTerminalOption;
        let callbacks: [(O, *const c_void); 12] = [
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_WRITE_PTY, cb_write_pty as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_BELL, cb_bell as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, cb_title as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_PWD_CHANGED, cb_pwd as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, cb_clipboard_write as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION, cb_notification as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_PROGRESS_REPORT, cb_progress as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_SEMANTIC_PROMPT, cb_semantic_prompt as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_RENDER_HOLD, cb_render_hold as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES, cb_device_attributes as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_SIZE, cb_size as *const c_void),
            (sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_COLOR_SCHEME, cb_color_scheme as *const c_void),
        ];
        for (opt, f) in callbacks {
            // SAFETY: each option expects exactly this callback signature
            // (checked against the bindings' typedefs by the cb_* signatures).
            unsafe { sys::ghostty_terminal_set(self.raw(), opt, f) };
        }
    }

    /// Runs a libghostty call with this terminal's callback context lent to
    /// the callbacks. The context is restored even if `f` panics.
    fn call<R>(&mut self, f: impl FnOnce(sys::GhosttyTerminal) -> R) -> R {
        struct Restore<'a>(&'a mut Option<Box<CallbackCtx>>);
        impl Drop for Restore<'_> {
            fn drop(&mut self) {
                *self.0 = ACTIVE.with_borrow_mut(Option::take);
            }
        }
        let raw = self.raw();
        let ctx = self.ctx.take();
        ACTIVE.with_borrow_mut(|active| {
            debug_assert!(active.is_none(), "nested libghostty calls on one thread");
            *active = ctx;
        });
        let _restore = Restore(&mut self.ctx);
        f(raw)
    }

    pub fn ctx(&self) -> &CallbackCtx {
        self.ctx.as_deref().expect("callback context is only lent out during calls")
    }

    pub fn ctx_mut(&mut self) -> &mut CallbackCtx {
        self.ctx.as_deref_mut().expect("callback context is only lent out during calls")
    }

    // ---- Output ------------------------------------------------------------

    /// Feeds bytes from the pty.
    pub fn write(&mut self, data: &[u8]) {
        if data.is_empty() {
            return;
        }
        // SAFETY: data is a valid slice for the duration of the call.
        self.call(|t| unsafe { sys::ghostty_terminal_vt_write(t, data.as_ptr(), data.len()) });
    }

    pub fn reset(&mut self) {
        // SAFETY: plain call on our handle.
        self.call(|t| unsafe { sys::ghostty_terminal_reset(t) });
        self.ctx_mut().held = false;
    }

    pub fn resize(&mut self, cols: u16, rows: u16, cell_width: u32, cell_height: u32) -> Result<()> {
        let (cols, rows) = (cols.max(1), rows.max(1));
        let (cw, ch) = (cell_width.max(1), cell_height.max(1));
        // SAFETY: plain call on our handle.
        let r = self.call(|t| unsafe { sys::ghostty_terminal_resize(t, cols, rows, cw, ch) });
        check(r)?;
        let ctx = self.ctx_mut();
        ctx.cols = cols;
        ctx.rows = rows;
        ctx.cell_width = cw;
        ctx.cell_height = ch;
        Ok(())
    }

    // ---- Options -----------------------------------------------------------

    /// Sets an option whose value is a pointer to `T` (or NULL to reset).
    fn set<T>(&mut self, opt: sys::GhosttyTerminalOption, value: Option<&T>) -> Result<()> {
        let ptr = value.map_or(ptr::null(), |v| v as *const T as *const c_void);
        // SAFETY: callers pass the value type documented for `opt`; libghostty
        // copies what it needs before returning.
        check(self.call(|t| unsafe { sys::ghostty_terminal_set(t, opt, ptr) }))
    }

    fn set_string(&mut self, opt: sys::GhosttyTerminalOption, value: &[u8]) {
        let s = sys::GhosttyString { ptr: value.as_ptr(), len: value.len() };
        let _ = self.set(opt, Some(&s));
    }

    pub fn set_foreground(&mut self, c: Option<Rgb>) {
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, c.map(Rgb::to_sys).as_ref());
    }

    pub fn set_background(&mut self, c: Option<Rgb>) {
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, c.map(Rgb::to_sys).as_ref());
    }

    pub fn set_cursor_color(&mut self, c: Option<Rgb>) {
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, c.map(Rgb::to_sys).as_ref());
    }

    /// Sets the 256-color palette, or resets it to libghostty's default.
    pub fn set_palette(&mut self, palette: Option<&[Rgb; 256]>) {
        let raw = palette.map(|p| p.map(Rgb::to_sys));
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, raw.as_ref());
    }

    pub fn set_scrollback_bytes(&mut self, bytes: Option<usize>) {
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, bytes.as_ref());
    }

    pub fn set_default_cursor_style(&mut self, style: CursorStyle) {
        let v = style as sys::GhosttyTerminalCursorStyle;
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE, Some(&v));
    }

    pub fn set_default_cursor_blink(&mut self, blink: bool) {
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK, Some(&blink));
    }

    pub fn set_resize_pull_scrollback(&mut self, pull: bool) {
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_RESIZE_PULL_SCROLLBACK, Some(&pull));
    }

    pub fn set_title_report(&mut self, enabled: bool) {
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_TITLE_REPORT, Some(&enabled));
    }

    /// Sets the current value of a mode (not its reset default).
    pub fn set_mode(&mut self, mode: Mode, value: bool) {
        let cfg = sys::GhosttyTerminalModeConfig { mode: mode.0, value };
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_MODE, Some(&cfg));
    }

    pub fn set_mode_default(&mut self, mode: Mode, value: bool) {
        let cfg = sys::GhosttyTerminalModeConfig { mode: mode.0, value };
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_MODE_DEFAULT, Some(&cfg));
    }

    // ---- Queries -----------------------------------------------------------

    /// Reads a data value of type `T`.
    fn get<T: Default>(&self, data: sys::GhosttyTerminalData) -> Option<T> {
        let mut out = T::default();
        // SAFETY: callers pick `T` to match the output type documented for
        // `data`; `out` is a valid, initialized destination.
        let r = unsafe { sys::ghostty_terminal_get(self.raw(), data, &mut out as *mut T as *mut c_void) };
        (r == sys::GhosttyResult_GHOSTTY_SUCCESS).then_some(out)
    }

    fn get_string(&self, data: sys::GhosttyTerminalData) -> String {
        let s: Option<sys::GhosttyString> = self.get(data);
        // SAFETY: the borrowed string is valid until the next mutating call;
        // `&self` rules that out while we copy it.
        s.map(|s| String::from_utf8_lossy(&unsafe { copy_string(s) }).into_owned()).unwrap_or_default()
    }

    pub fn mode(&self, mode: Mode) -> bool {
        let mut cfg = sys::GhosttyTerminalModeConfig { mode: mode.0, value: false };
        // SAFETY: DATA_MODE takes an in/out GhosttyTerminalModeConfig.
        let r = unsafe {
            sys::ghostty_terminal_get(
                self.raw(),
                sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_MODE,
                &mut cfg as *mut _ as *mut c_void,
            )
        };
        r == sys::GhosttyResult_GHOSTTY_SUCCESS && cfg.value
    }

    pub fn title(&self) -> String {
        self.get_string(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_TITLE)
    }

    pub fn pwd(&self) -> String {
        self.get_string(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_PWD)
    }

    pub fn mouse_tracking(&self) -> bool {
        self.get(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING).unwrap_or(false)
    }

    pub fn alt_screen(&self) -> bool {
        let s: Option<sys::GhosttyTerminalScreen> =
            self.get(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN);
        s == Some(sys::GhosttyTerminalScreen_GHOSTTY_TERMINAL_SCREEN_ALTERNATE)
    }

    pub fn viewport_at_bottom(&self) -> bool {
        self.get(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE).unwrap_or(false)
    }

    pub fn scrollback_rows(&self) -> usize {
        self.get(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS).unwrap_or(0)
    }

    pub fn cursor_position(&self) -> (u16, u16) {
        let x = self.get(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_CURSOR_X).unwrap_or(0);
        let y = self.get(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_CURSOR_Y).unwrap_or(0);
        (x, y)
    }

    pub fn scrollbar(&self) -> Scrollbar {
        let s: Option<sys::GhosttyTerminalScrollbar> =
            self.get(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_SCROLLBAR);
        s.map(|s| Scrollbar { total: s.total, offset: s.offset, len: s.len }).unwrap_or_default()
    }

    pub fn mouse_shape(&self) -> i32 {
        let shape: sys::GhosttyMouseShape = self
            .get(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_MOUSE_SHAPE)
            .unwrap_or(sys::GhosttyMouseShape_GHOSTTY_MOUSE_SHAPE_TEXT);
        shape as i32
    }

    // ---- Viewport ----------------------------------------------------------

    pub fn scroll(&mut self, to: ScrollTo) {
        let (tag, value) = match to {
            ScrollTo::Top => (sys::GhosttyTerminalScrollViewportTag_GHOSTTY_SCROLL_VIEWPORT_TOP, Default::default()),
            ScrollTo::Bottom => {
                (sys::GhosttyTerminalScrollViewportTag_GHOSTTY_SCROLL_VIEWPORT_BOTTOM, Default::default())
            }
            ScrollTo::Delta(d) => (
                sys::GhosttyTerminalScrollViewportTag_GHOSTTY_SCROLL_VIEWPORT_DELTA,
                sys::GhosttyTerminalScrollViewportValue { delta: d },
            ),
            ScrollTo::Row(r) => (
                sys::GhosttyTerminalScrollViewportTag_GHOSTTY_SCROLL_VIEWPORT_ROW,
                sys::GhosttyTerminalScrollViewportValue { row: r },
            ),
        };
        let sv = sys::GhosttyTerminalScrollViewport { tag, value };
        // SAFETY: plain call; the union arm matches the tag.
        self.call(|t| unsafe { sys::ghostty_terminal_scroll_viewport(t, sv) });
    }

    // ---- Rendering ---------------------------------------------------------

    /// Captures the current screen into the render state.
    pub fn update_render_state(&mut self) -> Result<()> {
        let raw = self.raw();
        self.ctx_mut().render.update(raw)
    }

    pub fn render_state(&mut self) -> &mut RenderState {
        &mut self.ctx_mut().render
    }

    // ---- Selection ---------------------------------------------------------

    /// The grid reference of a viewport cell (clamped into the grid).
    fn grid_ref(&self, col: u16, row: u32) -> Option<sys::GhosttyGridRef> {
        let point = sys::GhosttyPoint {
            tag: sys::GhosttyPointTag_GHOSTTY_POINT_TAG_VIEWPORT,
            value: sys::GhosttyPointValue { coordinate: sys::GhosttyPointCoordinate { x: col, y: row } },
        };
        let mut out = sized!(sys::GhosttyGridRef);
        // SAFETY: valid point and output struct.
        let r = unsafe { sys::ghostty_terminal_grid_ref(self.raw(), point, &mut out) };
        (r == sys::GhosttyResult_GHOSTTY_SUCCESS).then_some(out)
    }

    fn active_selection(&self) -> Option<sys::GhosttySelection> {
        self.get_sized(sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_SELECTION)
    }

    fn get_sized<T: Default + HasSize>(&self, data: sys::GhosttyTerminalData) -> Option<T> {
        let mut out = T::default();
        out.set_size();
        // SAFETY: as in `get`, with the sized struct's `size` initialized.
        let r = unsafe { sys::ghostty_terminal_get(self.raw(), data, &mut out as *mut T as *mut c_void) };
        (r == sys::GhosttyResult_GHOSTTY_SUCCESS).then_some(out)
    }

    pub fn has_selection(&self) -> bool {
        self.active_selection().is_some()
    }

    /// Installs `sel` (built from grid refs taken in this same `&mut` borrow)
    /// as the active selection, or clears it.
    fn apply_selection(&mut self, sel: Option<&sys::GhosttySelection>) {
        let _ = self.set(sys::GhosttyTerminalOption_GHOSTTY_TERMINAL_OPT_SELECTION, sel);
    }

    pub fn clear_selection(&mut self) {
        self.apply_selection(None);
        // SAFETY: both handles are ours.
        unsafe { sys::ghostty_selection_gesture_reset(self.gesture.as_ptr(), self.raw()) };
    }

    pub fn select_all(&mut self) -> bool {
        let mut sel = sized!(sys::GhosttySelection);
        // SAFETY: valid output struct.
        let r = unsafe { sys::ghostty_terminal_select_all(self.raw(), &mut sel) };
        if r != sys::GhosttyResult_GHOSTTY_SUCCESS {
            return false;
        }
        self.apply_selection(Some(&sel));
        true
    }

    /// The selected text (soft wraps joined, trailing blanks trimmed).
    pub fn selection_text(&self) -> Option<String> {
        let opts = sys::GhosttyTerminalSelectionFormatOptions {
            emit: sys::GhosttyFormatterFormat_GHOSTTY_FORMATTER_FORMAT_PLAIN,
            unwrap: true,
            trim: true,
            selection: ptr::null(), // the active selection
            ..sized!(sys::GhosttyTerminalSelectionFormatOptions)
        };
        let mut out: *mut u8 = ptr::null_mut();
        let mut len = 0usize;
        // SAFETY: valid options and out-params; the buffer is freed below.
        let r =
            unsafe { sys::ghostty_terminal_selection_format_alloc(self.raw(), ptr::null(), opts, &mut out, &mut len) };
        if r != sys::GhosttyResult_GHOSTTY_SUCCESS {
            return None;
        }
        Some(take_alloc(out, len))
    }

    /// The whole screen including scrollback, as plain text.
    pub fn screen_text(&self) -> Option<String> {
        let mut opts = sized!(sys::GhosttyFormatterTerminalOptions);
        opts.emit = sys::GhosttyFormatterFormat_GHOSTTY_FORMATTER_FORMAT_PLAIN;
        opts.unwrap = true;
        opts.trim = true;
        opts.extra.size = std::mem::size_of::<sys::GhosttyFormatterTerminalExtra>();
        opts.extra.screen.size = std::mem::size_of::<sys::GhosttyFormatterScreenExtra>();
        // SAFETY: plain constructor reading the terminal (borrowed via &self).
        let fmt = new_handle(|out| unsafe { sys::ghostty_formatter_terminal_new(ptr::null(), out, self.raw(), opts) })
            .ok()?;
        let mut out: *mut u8 = ptr::null_mut();
        let mut len = 0usize;
        // SAFETY: valid formatter and out-params; both freed below.
        let r = unsafe { sys::ghostty_formatter_format_alloc(fmt.as_ptr(), ptr::null(), &mut out, &mut len) };
        // SAFETY: we own the formatter.
        unsafe { sys::ghostty_formatter_free(fmt.as_ptr()) };
        (r == sys::GhosttyResult_GHOSTTY_SUCCESS).then(|| take_alloc(out, len))
    }

    /// The OSC 8 hyperlink of a viewport cell.
    pub fn hyperlink_at(&self, col: u16, row: u16) -> Option<String> {
        let ctx = self.ctx();
        if col >= ctx.cols || row >= ctx.rows {
            return None;
        }
        let r = self.grid_ref(col, row as u32)?;
        let mut len = 0usize;
        // SAFETY: the ref was just taken under the same `&self` borrow; a NULL
        // buffer asks for the required length.
        let res = unsafe { sys::ghostty_grid_ref_hyperlink_uri(&r, ptr::null_mut(), 0, &mut len) };
        if res != sys::GhosttyResult_GHOSTTY_OUT_OF_SPACE || len == 0 {
            return None;
        }
        let mut buf = vec![0u8; len];
        let mut got = 0usize;
        // SAFETY: `buf` has `len` writable bytes.
        let res = unsafe { sys::ghostty_grid_ref_hyperlink_uri(&r, buf.as_mut_ptr(), buf.len(), &mut got) };
        if res != sys::GhosttyResult_GHOSTTY_SUCCESS {
            return None;
        }
        buf.truncate(got);
        Some(String::from_utf8_lossy(&buf).into_owned())
    }

    /// Converts a selection to inclusive viewport coordinates, start first.
    pub(super) fn selection_to_viewport(&self, sel: &sys::GhosttySelection) -> Option<((u16, u32), (u16, u32))> {
        let mut ordered = sized!(sys::GhosttySelection);
        // SAFETY: `sel` comes from libghostty under the caller's borrow.
        let r = unsafe {
            sys::ghostty_terminal_selection_ordered(
                self.raw(),
                sel,
                sys::GhosttySelectionOrder_GHOSTTY_SELECTION_ORDER_FORWARD,
                &mut ordered,
            )
        };
        let s = if r == sys::GhosttyResult_GHOSTTY_SUCCESS { ordered } else { *sel };
        let a = self.point_from_ref(&s.start)?;
        let b = self.point_from_ref(&s.end)?;
        Some((a, b))
    }

    fn point_from_ref(&self, r: &sys::GhosttyGridRef) -> Option<(u16, u32)> {
        let mut out = sys::GhosttyPointCoordinate::default();
        // SAFETY: valid ref (same borrow) and output.
        let res = unsafe {
            sys::ghostty_terminal_point_from_grid_ref(
                self.raw(),
                r,
                sys::GhosttyPointTag_GHOSTTY_POINT_TAG_VIEWPORT,
                &mut out,
            )
        };
        (res == sys::GhosttyResult_GHOSTTY_SUCCESS).then_some((out.x, out.y))
    }

    pub(super) fn raw_handle(&self) -> sys::GhosttyTerminal {
        self.raw()
    }

    /// Feeds a pointer event to the selection gesture state machine
    /// (single/double/triple click, drag, autoscroll) and applies the result.
    pub fn gesture(&mut self, input: GestureInput) -> GestureResult {
        let cols = input.cols.max(1);
        let rows = input.rows.max(1);
        let cw = input.cell_width.max(1) as f64;
        let ch = input.cell_height.max(1) as f64;
        let col = (((input.x - input.pad_left as f64) / cw).floor().max(0.0) as u32).min(cols as u32 - 1) as u16;
        let row = (((input.y - input.pad_top as f64) / ch).floor().max(0.0) as u32).min(rows as u32 - 1);

        let ev = self.gesture_events[input.kind as usize].as_ptr();
        let r = self.grid_ref(col, row);

        // Options are copied into the event by libghostty.
        let set = |opt: sys::GhosttySelectionGestureEventOption, value: *const c_void| {
            // SAFETY: each call passes the value type documented for `opt`.
            unsafe { sys::ghostty_selection_gesture_event_set(ev, opt, value) };
        };
        set(
            sys::GhosttySelectionGestureEventOption_GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF,
            r.as_ref().map_or(ptr::null(), |r| r as *const _ as *const c_void),
        );
        let pos = sys::GhosttySurfacePosition { x: input.x, y: input.y };
        if input.kind != GestureKind::Release {
            set(sys::GhosttySelectionGestureEventOption_GHOSTTY_SELECTION_GESTURE_EVENT_OPT_POSITION, ptr_of(&pos));
        }
        let distance = input.cell_width as f64;
        if input.kind == GestureKind::Press {
            set(
                sys::GhosttySelectionGestureEventOption_GHOSTTY_SELECTION_GESTURE_EVENT_OPT_TIME_NS,
                ptr_of(&input.time_ns),
            );
            set(
                sys::GhosttySelectionGestureEventOption_GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_INTERVAL_NS,
                ptr_of(&input.click_interval_ns),
            );
            set(
                sys::GhosttySelectionGestureEventOption_GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_DISTANCE,
                ptr_of(&distance),
            );
        }
        let geometry = sys::GhosttySelectionGestureGeometry {
            columns: cols as u32,
            cell_width: input.cell_width.max(1),
            padding_left: input.pad_left,
            screen_height: input.pad_top + rows as u32 * input.cell_height.max(1),
        };
        let viewport = sys::GhosttyPointCoordinate { x: col, y: row };
        if matches!(input.kind, GestureKind::Drag | GestureKind::AutoscrollTick) {
            set(
                sys::GhosttySelectionGestureEventOption_GHOSTTY_SELECTION_GESTURE_EVENT_OPT_RECTANGLE,
                ptr_of(&input.rectangle),
            );
            set(
                sys::GhosttySelectionGestureEventOption_GHOSTTY_SELECTION_GESTURE_EVENT_OPT_GEOMETRY,
                ptr_of(&geometry),
            );
        }
        if input.kind == GestureKind::AutoscrollTick {
            set(
                sys::GhosttySelectionGestureEventOption_GHOSTTY_SELECTION_GESTURE_EVENT_OPT_VIEWPORT,
                ptr_of(&viewport),
            );
        }

        let mut result = GestureResult::default();
        let mut sel = sized!(sys::GhosttySelection);
        let out: *mut sys::GhosttySelection =
            if input.kind == GestureKind::Release { ptr::null_mut() } else { &mut sel };
        let gesture = self.gesture.as_ptr();
        // SAFETY: gesture, event and output are valid; the grid ref set on the
        // event was taken under this same `&mut self` borrow.
        let r = self.call(|t| unsafe { sys::ghostty_selection_gesture_event(gesture, t, ev, out) });
        if r == sys::GhosttyResult_GHOSTTY_SUCCESS && input.kind != GestureKind::Release {
            self.apply_selection(Some(&sel));
            result.changed = true;
        } else if r == sys::GhosttyResult_GHOSTTY_NO_VALUE && input.kind == GestureKind::Press && self.has_selection() {
            // A plain single click clears any existing selection.
            self.apply_selection(None);
            result.changed = true;
        }

        let mut autoscroll: sys::GhosttySelectionGestureAutoscroll =
            sys::GhosttySelectionGestureAutoscroll_GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_NONE;
        // SAFETY: AUTOSCROLL outputs a GhosttySelectionGestureAutoscroll.
        unsafe {
            sys::ghostty_selection_gesture_get(
                gesture,
                self.raw(),
                sys::GhosttySelectionGestureData_GHOSTTY_SELECTION_GESTURE_DATA_AUTOSCROLL,
                &mut autoscroll as *mut _ as *mut c_void,
            )
        };
        result.autoscroll_up =
            autoscroll == sys::GhosttySelectionGestureAutoscroll_GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_UP;
        result.autoscroll_down =
            autoscroll == sys::GhosttySelectionGestureAutoscroll_GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_DOWN;
        result.has_selection = self.has_selection();
        result
    }

    // ---- Paste -------------------------------------------------------------

    /// Pastes UTF-8 text; the encoded bytes arrive as [`Event::WritePty`].
    pub fn paste(&mut self, text: &[u8], allow_unsafe: bool) -> Result<PasteOutcome> {
        const TEXT_PLAIN: &[u8] = b"text/plain";
        let mimes = [sys::GhosttyString { ptr: TEXT_PLAIN.as_ptr(), len: TEXT_PLAIN.len() }];
        let paste = sys::GhosttyPaste {
            location: sys::GhosttyClipboardLocation_GHOSTTY_CLIPBOARD_LOCATION_STANDARD,
            // We hand over the text directly; we don't implement the Kitty
            // clipboard read protocol that paste events would need.
            source: sys::GhosttyPasteSource_GHOSTTY_PASTE_SOURCE_TEXT,
            mimes: mimes.as_ptr(),
            mimes_len: mimes.len(),
            reader: sys::GhosttyMimeReader { read: Some(cb_paste_read), userdata: ptr::null_mut() },
            allow_unsafe,
            ..sized!(sys::GhosttyPaste)
        };
        PASTE_SOURCE.with_borrow_mut(|s| {
            s.clear();
            s.extend_from_slice(text);
        });
        let mut written = false;
        // SAFETY: `paste` and `mimes` outlive the call; the reader reads the
        // thread-local source set above.
        let r = self.call(|t| unsafe { sys::ghostty_terminal_paste(t, &paste, &mut written) });
        PASTE_SOURCE.with_borrow_mut(Vec::clear);
        match r {
            sys::GhosttyResult_GHOSTTY_SUCCESS => Ok(PasteOutcome::Written),
            sys::GhosttyResult_GHOSTTY_REJECTED => Ok(PasteOutcome::Rejected),
            e => Err(Error(e)),
        }
    }

    /// Answers a color scheme change to programs that asked (mode 2031).
    pub fn report_color_scheme(&mut self, dark: bool) {
        let scheme = if dark {
            sys::GhosttyColorScheme_GHOSTTY_COLOR_SCHEME_DARK
        } else {
            sys::GhosttyColorScheme_GHOSTTY_COLOR_SCHEME_LIGHT
        };
        let mut buf = [0u8; 32];
        let mut n = 0usize;
        // SAFETY: `buf` is writable for its full length.
        let r = unsafe { sys::ghostty_color_scheme_report_encode(scheme, buf.as_mut_ptr().cast(), buf.len(), &mut n) };
        if r == sys::GhosttyResult_GHOSTTY_SUCCESS {
            self.ctx_mut().events.push(Event::WritePty(buf[..n.min(buf.len())].to_vec()));
        }
    }
}

/// Sized-struct helper for `get_sized`.
trait HasSize {
    fn set_size(&mut self);
}

impl HasSize for sys::GhosttySelection {
    fn set_size(&mut self) {
        self.size = std::mem::size_of::<Self>();
    }
}

fn ptr_of<T>(v: &T) -> *const c_void {
    v as *const T as *const c_void
}

/// Copies and frees a buffer allocated by libghostty's default allocator.
fn take_alloc(ptr: *mut u8, len: usize) -> String {
    // SAFETY: libghostty returned `len` initialized bytes at `ptr`.
    let bytes = unsafe { copy_bytes(ptr, len) };
    if !ptr.is_null() {
        // SAFETY: allocated by the default allocator (NULL) with this length.
        unsafe { sys::ghostty_free(ptr::null(), ptr, len) };
    }
    String::from_utf8_lossy(&bytes).into_owned()
}

// ---- Callbacks ----------------------------------------------------------------
//
// libghostty invokes these synchronously from inside `Terminal::call`, on the
// same thread, so `ACTIVE` holds that terminal's context. They never touch the
// `userdata` pointer.

unsafe extern "C" fn cb_write_pty(_t: sys::GhosttyTerminal, _ud: *mut c_void, data: *const u8, len: usize) {
    // SAFETY: libghostty passes `len` readable bytes valid during the callback.
    let bytes = unsafe { copy_bytes(data, len) };
    with_active(|ctx| ctx.events.push(Event::WritePty(bytes)));
}

unsafe extern "C" fn cb_bell(_t: sys::GhosttyTerminal, _ud: *mut c_void) {
    with_active(|ctx| ctx.events.push(Event::Bell));
}

/// Reads a terminal string property from inside a callback.
fn callback_string(t: sys::GhosttyTerminal, data: sys::GhosttyTerminalData) -> String {
    let mut s = sys::GhosttyString::default();
    // SAFETY: `t` is the live terminal libghostty passed to the callback.
    let r = unsafe { sys::ghostty_terminal_get(t, data, &mut s as *mut _ as *mut c_void) };
    if r != sys::GhosttyResult_GHOSTTY_SUCCESS {
        return String::new();
    }
    // SAFETY: borrowed string, valid until the next mutating call.
    String::from_utf8_lossy(&unsafe { copy_string(s) }).into_owned()
}

unsafe extern "C" fn cb_title(t: sys::GhosttyTerminal, _ud: *mut c_void) {
    let title = callback_string(t, sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_TITLE);
    with_active(|ctx| ctx.events.push(Event::TitleChanged(title)));
}

unsafe extern "C" fn cb_pwd(t: sys::GhosttyTerminal, _ud: *mut c_void) {
    let pwd = callback_string(t, sys::GhosttyTerminalData_GHOSTTY_TERMINAL_DATA_PWD);
    with_active(|ctx| ctx.events.push(Event::PwdChanged(pwd)));
}

unsafe extern "C" fn cb_clipboard_write(
    _t: sys::GhosttyTerminal,
    _ud: *mut c_void,
    write: *const sys::GhosttyClipboardWrite,
) {
    // SAFETY: libghostty passes a valid request for the callback's duration.
    let Some(write) = (unsafe { write.as_ref() }) else { return };
    let contents: &[sys::GhosttyClipboardContent] = if write.contents.is_null() || write.contents_len == 0 {
        &[]
    } else {
        // SAFETY: `contents` points to `contents_len` entries.
        unsafe { std::slice::from_raw_parts(write.contents, write.contents_len) }
    };
    // SAFETY: the MIME strings are valid during the callback.
    let text = contents.iter().find(|c| unsafe { copy_string(c.mime) }.starts_with(b"text/plain"));
    let mut result = sys::GhosttyClipboardWriteResult_GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
    if let Some(text) = text {
        // SAFETY: as above.
        let data = String::from_utf8_lossy(&unsafe { copy_string(text.data) }).into_owned();
        let primary = write.location != sys::GhosttyClipboardLocation_GHOSTTY_CLIPBOARD_LOCATION_STANDARD;
        result = sys::GhosttyClipboardWriteResult_GHOSTTY_CLIPBOARD_WRITE_RESULT_DENIED;
        with_active(|ctx| {
            if ctx.allow_clipboard_write {
                ctx.events.push(Event::ClipboardWrite { text: data, primary });
                result = sys::GhosttyClipboardWriteResult_GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
            }
        });
    }
    let reply = sys::GhosttyClipboardWriteReply { result, ..sized!(sys::GhosttyClipboardWriteReply) };
    if let Some(reply_fn) = write.reply {
        // SAFETY: answering the request with libghostty's own reply function,
        // inside the callback as required.
        unsafe { reply_fn(write, &reply) };
    }
}

unsafe extern "C" fn cb_notification(
    _t: sys::GhosttyTerminal,
    _ud: *mut c_void,
    n: *const sys::GhosttyTerminalDesktopNotification,
) {
    // SAFETY: valid for the callback's duration.
    let Some(n) = (unsafe { n.as_ref() }) else { return };
    // SAFETY: borrowed strings valid during the callback.
    let (title, body) = unsafe { (copy_string(n.title), copy_string(n.body)) };
    let event = Event::Notification {
        title: String::from_utf8_lossy(&title).into_owned(),
        body: String::from_utf8_lossy(&body).into_owned(),
    };
    with_active(|ctx| ctx.events.push(event));
}

unsafe extern "C" fn cb_progress(
    _t: sys::GhosttyTerminal,
    _ud: *mut c_void,
    r: *const sys::GhosttyTerminalProgressReport,
) {
    // SAFETY: valid for the callback's duration.
    let Some(r) = (unsafe { r.as_ref() }) else { return };
    let state = match r.state {
        sys::GhosttyTerminalProgressState_GHOSTTY_TERMINAL_PROGRESS_STATE_SET => ProgressState::Set,
        sys::GhosttyTerminalProgressState_GHOSTTY_TERMINAL_PROGRESS_STATE_ERROR => ProgressState::Error,
        sys::GhosttyTerminalProgressState_GHOSTTY_TERMINAL_PROGRESS_STATE_INDETERMINATE => ProgressState::Indeterminate,
        sys::GhosttyTerminalProgressState_GHOSTTY_TERMINAL_PROGRESS_STATE_PAUSE => ProgressState::Pause,
        _ => ProgressState::Remove,
    };
    let progress = (r.progress >= 0).then_some(r.progress as u8);
    with_active(|ctx| ctx.events.push(Event::Progress { state, progress }));
}

unsafe extern "C" fn cb_semantic_prompt(
    _t: sys::GhosttyTerminal,
    _ud: *mut c_void,
    e: *const sys::GhosttyTerminalSemanticPrompt,
) {
    // SAFETY: valid for the callback's duration.
    let Some(e) = (unsafe { e.as_ref() }) else { return };
    if e.kind != sys::GhosttySemanticPromptKind_GHOSTTY_SEMANTIC_PROMPT_COMMAND_END {
        return;
    }
    let exit_code = e.has_exit_code.then_some(e.exit_code);
    with_active(|ctx| ctx.events.push(Event::CommandFinished { exit_code }));
}

unsafe extern "C" fn cb_render_hold(t: sys::GhosttyTerminal, _ud: *mut c_void, held: bool) {
    with_active(|ctx| {
        if held && !ctx.held {
            // Capture the frame the program wants left on screen while it
            // draws the next one; stop updating until the hold ends.
            let _ = ctx.render.update(t);
            ctx.held = true;
            ctx.held_since = Some(Instant::now());
        } else if !held {
            ctx.held = false;
            ctx.held_since = None;
        }
    });
}

unsafe extern "C" fn cb_device_attributes(
    _t: sys::GhosttyTerminal,
    _ud: *mut c_void,
    out: *mut sys::GhosttyDeviceAttributes,
) -> bool {
    // SAFETY: libghostty passes a valid, writable output struct.
    let Some(out) = (unsafe { out.as_mut() }) else { return false };
    // Same answers as the Ghostty app: VT220 with ANSI color.
    out.primary.conformance_level = 62;
    out.primary.features[0] = 22;
    out.primary.num_features = 1;
    out.secondary.device_type = 1;
    out.secondary.firmware_version = 10;
    out.secondary.rom_cartridge = 0;
    out.tertiary.unit_id = 0;
    true
}

unsafe extern "C" fn cb_size(_t: sys::GhosttyTerminal, _ud: *mut c_void, out: *mut sys::GhosttySizeReportSize) -> bool {
    // SAFETY: libghostty passes a valid, writable output struct.
    let Some(out) = (unsafe { out.as_mut() }) else { return false };
    let mut answered = false;
    with_active(|ctx| {
        out.rows = ctx.rows;
        out.columns = ctx.cols;
        out.cell_width = ctx.cell_width;
        out.cell_height = ctx.cell_height;
        answered = true;
    });
    answered
}

unsafe extern "C" fn cb_color_scheme(
    _t: sys::GhosttyTerminal,
    _ud: *mut c_void,
    out: *mut sys::GhosttyColorScheme,
) -> bool {
    // SAFETY: libghostty passes a valid, writable output.
    let Some(out) = (unsafe { out.as_mut() }) else { return false };
    let mut answered = false;
    with_active(|ctx| {
        *out = if ctx.dark {
            sys::GhosttyColorScheme_GHOSTTY_COLOR_SCHEME_DARK
        } else {
            sys::GhosttyColorScheme_GHOSTTY_COLOR_SCHEME_LIGHT
        };
        answered = true;
    });
    answered
}

unsafe extern "C" fn cb_paste_read(_ud: *mut c_void, _mime: sys::GhosttyString, writer: sys::GhosttyWriter) -> bool {
    PASTE_SOURCE.with_borrow(|data| {
        if data.is_empty() {
            return true;
        }
        match writer.write {
            // SAFETY: calling libghostty's writer with its own userdata and a
            // valid slice.
            Some(write) => unsafe { write(writer.userdata, data.as_ptr(), data.len()) },
            None => false,
        }
    })
}
