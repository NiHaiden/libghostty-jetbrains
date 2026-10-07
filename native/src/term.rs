//! The terminal as the plugin sees it: libghostty plus the policy every
//! embedder needs (frame capture, synchronized-output timeouts, selection
//! gestures, search highlighting). Entirely safe code on top of `vt`.

use crate::frame as f;
use crate::vt::{
    self, CallbackCtx, CursorStyle, Event, GestureInput, GestureKind, KeyEncoder, KeyInput, Mode, MouseEncoder,
    MouseGeometry, MouseInput, OptionAsAlt, PasteOutcome, RenderState, Rgb, ScrollTo, Search, SearchStatus, StyleColor,
    Terminal,
};
use std::time::Duration;

/// A program may hold rendering (synchronized output) at most this long.
const HOLD_TIMEOUT: Duration = Duration::from_secs(1);

/// Bound on search work per frame so a huge scrollback can't stall painting.
const SEARCH_TICKS_PER_FRAME: usize = 64;

/// Option ids for [`Term::set_option`] (shared with the Kotlin side).
pub mod opt {
    pub const SCROLLBACK_BYTES: i32 = 1;
    pub const CURSOR_STYLE: i32 = 2;
    pub const CURSOR_BLINK: i32 = 3;
    pub const RESIZE_PULL_SCROLLBACK: i32 = 4;
    pub const OPTION_AS_ALT: i32 = 5;
    pub const TITLE_REPORT: i32 = 6;
    pub const COLOR_SCHEME: i32 = 7;
    pub const FOCUSED: i32 = 8;
    pub const CLICK_INTERVAL_MS: i32 = 9;
    pub const ALLOW_CLIPBOARD_WRITE: i32 = 10;
}

/// Query ids for [`Term::query`] (shared with the Kotlin side).
pub mod query {
    pub const COLS: i32 = 1;
    pub const ROWS: i32 = 2;
    pub const MOUSE_TRACKING: i32 = 3;
    pub const ALT_SCREEN: i32 = 4;
    pub const VIEWPORT_AT_BOTTOM: i32 = 5;
    pub const MODE: i32 = 6;
    pub const SCROLLBACK_ROWS: i32 = 7;
    pub const CURSOR_X: i32 = 8;
    pub const CURSOR_Y: i32 = 9;
    pub const HAS_SELECTION: i32 = 10;
    pub const MOUSE_SHAPE: i32 = 11;
}

/// Bits returned by [`Term::select_event`].
pub mod sel {
    pub const CHANGED: i32 = 1 << 0;
    pub const HAS: i32 = 1 << 1;
    pub const AUTOSCROLL_UP: i32 = 1 << 2;
    pub const AUTOSCROLL_DOWN: i32 = 1 << 3;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PasteResult {
    Ok,
    Unsafe,
    Error,
}

#[derive(Debug, Clone, Copy)]
struct Geometry {
    cols: u16,
    rows: u16,
    cell_width: u32,
    cell_height: u32,
    pad_left: u32,
    pad_top: u32,
}

#[derive(Debug)]
pub struct Term {
    vt: Terminal,
    keys: KeyEncoder,
    mouse: MouseEncoder,
    search: Option<Search>,
    geo: Geometry,
    force_full: bool,
    option_as_alt: OptionAsAlt,
    click_interval_ns: u64,
    frame: Vec<i32>,
    graphemes: Vec<u32>,
}

fn clamp_u16(v: i32) -> u16 {
    v.clamp(1, u16::MAX as i32) as u16
}

fn clamp_px(v: i32) -> u32 {
    v.max(0) as u32
}

impl Term {
    pub fn new(cols: i32, rows: i32) -> vt::Result<Term> {
        let (cols, rows) = (clamp_u16(cols), clamp_u16(rows));
        let ctx = CallbackCtx {
            events: Vec::new(),
            cols,
            rows,
            cell_width: 8,
            cell_height: 16,
            dark: true,
            allow_clipboard_write: false,
            held: false,
            held_since: None,
            render: RenderState::new()?,
        };
        let mut vt = Terminal::new(cols, rows, ctx)?;
        vt.set_scrollback_bytes(Some(10 * 1024 * 1024));
        Ok(Term {
            vt,
            keys: KeyEncoder::new()?,
            mouse: MouseEncoder::new()?,
            search: None,
            geo: Geometry { cols, rows, cell_width: 8, cell_height: 16, pad_left: 0, pad_top: 0 },
            force_full: true,
            option_as_alt: OptionAsAlt::No,
            click_interval_ns: 500_000_000,
            frame: Vec::new(),
            graphemes: Vec::new(),
        })
    }

    /// Events produced since the last call, in order.
    pub fn take_events(&mut self) -> Vec<Event> {
        std::mem::take(&mut self.vt.ctx_mut().events)
    }

    // ---- Output & geometry ---------------------------------------------------

    pub fn write(&mut self, data: &[u8]) {
        self.vt.write(data);
    }

    pub fn reset(&mut self) {
        self.vt.reset();
        self.force_full = true;
    }

    pub fn resize(
        &mut self,
        cols: i32,
        rows: i32,
        cell_w: i32,
        cell_h: i32,
        pad_left: i32,
        pad_top: i32,
    ) -> vt::Result<()> {
        let (cols, rows) = (clamp_u16(cols), clamp_u16(rows));
        let (cell_width, cell_height) = (clamp_px(cell_w).max(1), clamp_px(cell_h).max(1));
        self.geo.cell_width = cell_width;
        self.geo.cell_height = cell_height;
        self.geo.pad_left = clamp_px(pad_left);
        self.geo.pad_top = clamp_px(pad_top);
        let result = self.vt.resize(cols, rows, cell_width, cell_height);
        if result.is_ok() {
            self.geo.cols = cols;
            self.geo.rows = rows;
        }
        self.force_full = true;
        result
    }

    // ---- Configuration -------------------------------------------------------

    /// Colors are 0xRRGGBB, `None` resets to libghostty's default. `palette`
    /// sets entries from index 0; with `generate_256` the rest of the 256
    /// colors are derived from the first 16.
    pub fn set_colors(
        &mut self,
        fg: Option<u32>,
        bg: Option<u32>,
        cursor: Option<u32>,
        palette: Option<&[i32]>,
        generate_256: bool,
    ) {
        let fg = fg.map(Rgb::from_u24);
        let bg = bg.map(Rgb::from_u24);
        self.vt.set_foreground(fg);
        self.vt.set_background(bg);
        self.vt.set_cursor_color(cursor.map(Rgb::from_u24));
        match palette {
            Some(p) if !p.is_empty() => {
                let mut pal = vt::default_palette();
                let n = p.len().min(256);
                for (dst, src) in pal.iter_mut().zip(&p[..n]) {
                    *dst = Rgb::from_u24(*src as u32);
                }
                if generate_256 && (16..256).contains(&n) {
                    let generated = vt::generate_palette(&pal, bg.unwrap_or(pal[0]), fg.unwrap_or(pal[15]));
                    pal[n..].copy_from_slice(&generated[n..]);
                }
                self.vt.set_palette(Some(&pal));
            }
            Some(_) => self.vt.set_palette(None),
            None => {}
        }
        self.force_full = true;
    }

    pub fn set_option(&mut self, option: i32, value: i64) -> bool {
        match option {
            opt::SCROLLBACK_BYTES => self.vt.set_scrollback_bytes(usize::try_from(value).ok()),
            opt::CURSOR_STYLE => self.vt.set_default_cursor_style(match value {
                0 => CursorStyle::Bar,
                2 => CursorStyle::Underline,
                3 => CursorStyle::BlockHollow,
                _ => CursorStyle::Block,
            }),
            opt::CURSOR_BLINK => self.vt.set_default_cursor_blink(value != 0),
            opt::RESIZE_PULL_SCROLLBACK => self.vt.set_resize_pull_scrollback(value != 0),
            opt::OPTION_AS_ALT => {
                self.option_as_alt = match value {
                    1 => OptionAsAlt::Yes,
                    2 => OptionAsAlt::Left,
                    3 => OptionAsAlt::Right,
                    _ => OptionAsAlt::No,
                }
            }
            opt::TITLE_REPORT => self.vt.set_title_report(value != 0),
            opt::COLOR_SCHEME => {
                let dark = value != 0;
                if dark != self.vt.ctx().dark {
                    self.vt.ctx_mut().dark = dark;
                    // Programs that opted into mode 2031 want to hear about it.
                    if self.vt.mode(Mode::COLOR_SCHEME_REPORT) {
                        self.vt.report_color_scheme(dark);
                    }
                }
            }
            opt::FOCUSED => self.force_full = true,
            opt::CLICK_INTERVAL_MS => self.click_interval_ns = value.max(0) as u64 * 1_000_000,
            opt::ALLOW_CLIPBOARD_WRITE => self.vt.ctx_mut().allow_clipboard_write = value != 0,
            _ => return false,
        }
        true
    }

    // ---- Queries -------------------------------------------------------------

    pub fn query(&self, what: i32, arg: i32) -> i64 {
        match what {
            query::COLS => self.geo.cols as i64,
            query::ROWS => self.geo.rows as i64,
            query::MOUSE_TRACKING => self.vt.mouse_tracking() as i64,
            query::ALT_SCREEN => self.vt.alt_screen() as i64,
            query::VIEWPORT_AT_BOTTOM => self.vt.viewport_at_bottom() as i64,
            query::MODE => {
                let n = arg.unsigned_abs().min(0x7fff) as u16;
                self.vt.mode(if arg < 0 { Mode::ansi(n) } else { Mode::dec(n) }) as i64
            }
            query::SCROLLBACK_ROWS => self.vt.scrollback_rows() as i64,
            query::CURSOR_X => self.vt.cursor_position().0 as i64,
            query::CURSOR_Y => self.vt.cursor_position().1 as i64,
            query::HAS_SELECTION => self.vt.has_selection() as i64,
            query::MOUSE_SHAPE => self.vt.mouse_shape() as i64,
            _ => 0,
        }
    }

    pub fn title(&self) -> String {
        self.vt.title()
    }

    pub fn pwd(&self) -> String {
        self.vt.pwd()
    }

    pub fn mode(&self, dec_mode: u16) -> bool {
        self.vt.mode(Mode::dec(dec_mode))
    }

    // ---- Viewport ------------------------------------------------------------

    /// kind: 0 top, 1 bottom, 2 delta (rows, negative = history), 3 absolute row.
    pub fn scroll(&mut self, kind: i32, value: i64) {
        let to = match kind {
            0 => ScrollTo::Top,
            1 => ScrollTo::Bottom,
            2 => ScrollTo::Delta(value.clamp(isize::MIN as i64, isize::MAX as i64) as isize),
            3 => ScrollTo::Row(value.max(0) as usize),
            _ => return,
        };
        self.vt.scroll(to);
        self.force_full = true;
    }

    // ---- Frames --------------------------------------------------------------

    /// Marks the next snapshot as fully dirty (e.g. after a theme change).
    pub fn invalidate(&mut self) {
        self.force_full = true;
    }

    /// Captures the viewport into the flat frame layout described in
    /// [`crate::frame`]. Returns `None` when nothing changed.
    pub fn snapshot(&mut self) -> Option<&[i32]> {
        let held = self.vt.ctx().held;
        if held && self.vt.ctx().held_since.is_some_and(|t| t.elapsed() > HOLD_TIMEOUT) {
            // The program held rendering too long; take control back.
            self.vt.set_mode(Mode::SYNC_OUTPUT, false);
            let ctx = self.vt.ctx_mut();
            ctx.held = false;
            ctx.held_since = None;
        }
        if !self.vt.ctx().held && self.vt.update_render_state().is_err() {
            return None;
        }

        let search_pending = match self.search.as_mut() {
            Some(s) => s.step(&self.vt, SEARCH_TICKS_PER_FRAME) != SearchStatus::Complete,
            None => false,
        };
        let dirty =
            if self.force_full || self.search.is_some() { vt::Dirty::Full } else { self.vt.render_state().dirty() };
        if dirty == vt::Dirty::Clean {
            return None;
        }

        let header = self.header(dirty, search_pending);
        let (cols, rows) = (header[f::H_COLS] as usize, header[f::H_ROWS] as usize);
        let colors = self.vt.render_state().colors();

        self.frame.clear();
        self.frame.extend_from_slice(&header);
        self.frame.resize(f::HEADER_INTS + cols * rows * f::CELL_INTS, 0);
        let mut pool: Vec<i32> = Vec::new();
        fill_cells(self.vt.render_state(), &colors, cols, rows, &mut self.frame, &mut pool, &mut self.graphemes);

        if let Some(search) = self.search.as_mut() {
            let (matches, selected) = search.highlights(&self.vt);
            let cells = &mut self.frame[f::HEADER_INTS..];
            for span in &matches {
                mark_span(cells, cols, rows, span, f::A_SEARCH_MATCH);
            }
            if let Some(span) = selected {
                mark_span(cells, cols, rows, &span, f::A_SEARCH_SELECTED);
            }
            self.frame[f::H_SEARCH_TOTAL] = search.total().min(i32::MAX as usize) as i32;
            self.frame[f::H_SEARCH_SELECTED] = search.selected_index().map_or(-1, |i| i.min(i32::MAX as usize) as i32);
        }

        let pool_offset = self.frame.len();
        self.frame.extend_from_slice(&pool);
        self.frame[f::H_GRAPHEME_OFFSET] = pool_offset as i32;
        self.frame[f::H_GRAPHEME_LEN] = pool.len() as i32;
        self.frame[f::H_FRAME_INTS] = self.frame.len() as i32;

        self.vt.render_state().clean();
        self.force_full = false;
        Some(&self.frame)
    }

    fn header(&mut self, dirty: vt::Dirty, search_pending: bool) -> [i32; f::HEADER_INTS] {
        let mut h = [0i32; f::HEADER_INTS];
        let state = self.vt.render_state();
        let (cols, rows) = state.size();
        let colors = state.colors();
        let cursor = state.cursor();
        h[f::H_MAGIC] = f::MAGIC;
        h[f::H_COLS] = cols as i32;
        h[f::H_ROWS] = rows as i32;
        h[f::H_DIRTY] = dirty as i32;
        h[f::H_FG] = colors.foreground.to_u24() as i32;
        h[f::H_BG] = colors.background.to_u24() as i32;
        h[f::H_CURSOR_COLOR] = colors.cursor.map_or(0, |c| c.to_u24() as i32 | f::COLOR_SET);
        let mut cf = 0;
        for (on, bit) in [
            (cursor.visible, f::CURSOR_F_VISIBLE),
            (cursor.blinking, f::CURSOR_F_BLINKING),
            (cursor.wide_tail, f::CURSOR_F_WIDE_TAIL),
            (cursor.in_viewport, f::CURSOR_F_IN_VIEWPORT),
            (cursor.password_input, f::CURSOR_F_PASSWORD),
        ] {
            if on {
                cf |= bit;
            }
        }
        h[f::H_CURSOR_FLAGS] = cf;
        h[f::H_CURSOR_X] = cursor.x as i32;
        h[f::H_CURSOR_Y] = cursor.y as i32;
        h[f::H_CURSOR_STYLE] = cursor.style;

        let sb = self.vt.scrollbar();
        let clamp = |v: u64| v.min(i32::MAX as u64) as i32;
        h[f::H_SCROLL_TOTAL] = clamp(sb.total);
        h[f::H_SCROLL_OFFSET] = clamp(sb.offset);
        h[f::H_SCROLL_LEN] = clamp(sb.len);

        let mut flags = 0;
        for (on, bit) in [
            (self.vt.mouse_tracking(), f::F_MOUSE_TRACKING),
            (self.vt.alt_screen(), f::F_ALT_SCREEN),
            (self.vt.viewport_at_bottom(), f::F_VIEWPORT_AT_BOTTOM),
            (self.vt.ctx().held, f::F_RENDER_HELD),
            (self.vt.mode(Mode::REVERSE_COLORS), f::F_REVERSE_COLORS),
            (search_pending, f::F_SEARCH_PENDING),
        ] {
            if on {
                flags |= bit;
            }
        }
        h[f::H_FLAGS] = flags;
        h[f::H_SEARCH_TOTAL] = -1;
        h[f::H_SEARCH_SELECTED] = -1;
        h[f::H_FRAME_INTS] = f::HEADER_INTS as i32;
        h
    }

    // ---- Input ---------------------------------------------------------------

    pub fn encode_key(&mut self, input: &KeyInput<'_>) -> Vec<u8> {
        self.keys.encode(&self.vt, input, self.option_as_alt).unwrap_or_default()
    }

    pub fn encode_mouse(&mut self, input: &MouseInput) -> Vec<u8> {
        let geo = MouseGeometry {
            cols: self.geo.cols,
            rows: self.geo.rows,
            cell_width: self.geo.cell_width,
            cell_height: self.geo.cell_height,
            pad_left: self.geo.pad_left,
            pad_top: self.geo.pad_top,
        };
        self.mouse.encode(&self.vt, input, &geo).unwrap_or_default()
    }

    /// Empty unless the program enabled focus reporting (mode 1004).
    pub fn encode_focus(&self, gained: bool) -> Vec<u8> {
        if self.vt.mode(Mode::FOCUS_EVENT) { vt::encode_focus(gained) } else { Vec::new() }
    }

    /// Pastes text; the encoded bytes are reported as `Event::WritePty`.
    pub fn paste(&mut self, text: &str, allow_unsafe: bool) -> PasteResult {
        match self.vt.paste(text.as_bytes(), allow_unsafe) {
            Ok(PasteOutcome::Written) => PasteResult::Ok,
            Ok(PasteOutcome::Rejected) => PasteResult::Unsafe,
            Err(_) => PasteResult::Error,
        }
    }

    // ---- Selection -----------------------------------------------------------

    /// kind: 0 press, 1 release, 2 drag, 3 autoscroll tick. Returns `sel::*` bits.
    pub fn select_event(&mut self, kind: i32, x: f64, y: f64, time_ns: i64, rectangle: bool) -> i32 {
        let kind = match kind {
            0 => GestureKind::Press,
            1 => GestureKind::Release,
            2 => GestureKind::Drag,
            3 => GestureKind::AutoscrollTick,
            _ => return 0,
        };
        let x = if x.is_finite() { x } else { 0.0 };
        let y = if y.is_finite() { y } else { 0.0 };
        let r = self.vt.gesture(GestureInput {
            kind,
            x,
            y,
            time_ns: time_ns.max(0) as u64,
            rectangle,
            click_interval_ns: self.click_interval_ns,
            cols: self.geo.cols,
            rows: self.geo.rows,
            cell_width: self.geo.cell_width,
            cell_height: self.geo.cell_height,
            pad_left: self.geo.pad_left,
            pad_top: self.geo.pad_top,
        });
        let mut bits = 0;
        for (on, bit) in [
            (r.changed, sel::CHANGED),
            (r.has_selection, sel::HAS),
            (r.autoscroll_up, sel::AUTOSCROLL_UP),
            (r.autoscroll_down, sel::AUTOSCROLL_DOWN),
        ] {
            if on {
                bits |= bit;
            }
        }
        // Selection changes don't mark the render state dirty by themselves.
        if r.changed || r.autoscroll_up || r.autoscroll_down {
            self.force_full = true;
        }
        bits
    }

    pub fn clear_selection(&mut self) {
        self.vt.clear_selection();
        self.force_full = true;
    }

    pub fn select_all(&mut self) {
        self.vt.select_all();
        self.force_full = true;
    }

    pub fn selection_text(&self) -> Option<String> {
        self.vt.selection_text()
    }

    pub fn screen_text(&self) -> Option<String> {
        self.vt.screen_text()
    }

    pub fn hyperlink_at(&self, col: i32, row: i32) -> Option<String> {
        let col = u16::try_from(col).ok()?;
        let row = u16::try_from(row).ok()?;
        self.vt.hyperlink_at(col, row).filter(|s| !s.is_empty())
    }

    // ---- Search --------------------------------------------------------------

    /// Sets the search needle; `None`/empty ends the search.
    pub fn set_search(&mut self, needle: Option<&str>) -> bool {
        self.force_full = true;
        let Some(needle) = needle.filter(|n| !n.is_empty()) else {
            self.search = None;
            return true;
        };
        if self.search.is_none() {
            match Search::new(&self.vt) {
                Ok(s) => self.search = Some(s),
                Err(_) => return false,
            }
        }
        let search = self.search.as_mut().expect("just created");
        let ok = search.set_needle(&self.vt, needle.as_bytes()).is_ok();
        search.step(&self.vt, 0);
        ok
    }

    /// Selects the next (`dir >= 0`, older) or previous match and scrolls to
    /// it. Returns the selected index or -1.
    pub fn search_select(&mut self, dir: i32) -> i32 {
        let Some(search) = self.search.as_mut() else { return -1 };
        self.force_full = true;
        match search.select(&mut self.vt, dir >= 0) {
            Ok(Some(i)) => i.min(i32::MAX as usize) as i32,
            _ => -1,
        }
    }
}

/// Writes every cell of the render state into `frame` (after the header).
fn fill_cells(
    state: &mut RenderState,
    colors: &vt::Colors,
    cols: usize,
    rows: usize,
    frame: &mut [i32],
    pool: &mut Vec<i32>,
    graphemes: &mut Vec<u32>,
) {
    let Ok(mut row_iter) = state.rows() else { return };
    let cells = &mut frame[f::HEADER_INTS..];
    let mut y = 0usize;
    while y < rows {
        let Some(mut row) = row_iter.next_row() else { break };
        let selection = row.selection();
        if let Ok(mut row_cells) = row.cells() {
            let mut x = 0usize;
            while x < cols {
                let Some(cell) = row_cells.next_cell() else { break };
                let base = (y * cols + x) * f::CELL_INTS;
                let c = &mut cells[base..base + f::CELL_INTS];
                let mut attrs = ((cell.wide() as i32) << f::A_WIDE_SHIFT) & f::A_WIDE_MASK;
                if cell.has_hyperlink() {
                    attrs |= f::A_HYPERLINK;
                }

                cell.graphemes(graphemes);
                if let Some(&first) = graphemes.first() {
                    c[f::C_CODEPOINT] = first as i32;
                    if graphemes.len() > 1 {
                        attrs |= f::A_GRAPHEME;
                        pool.push((y * cols + x) as i32);
                        pool.push(graphemes.len() as i32);
                        pool.extend(graphemes.iter().map(|&cp| cp as i32));
                    }
                }

                if let Some(style) = cell.style() {
                    for (on, bit) in [
                        (style.bold, f::A_BOLD),
                        (style.italic, f::A_ITALIC),
                        (style.faint, f::A_FAINT),
                        (style.blink, f::A_BLINK),
                        (style.inverse, f::A_INVERSE),
                        (style.invisible, f::A_INVISIBLE),
                        (style.strikethrough, f::A_STRIKETHROUGH),
                        (style.overline, f::A_OVERLINE),
                    ] {
                        if on {
                            attrs |= bit;
                        }
                    }
                    attrs |= ((style.underline as i32) << f::A_UNDERLINE_SHIFT) & f::A_UNDERLINE_MASK;
                    c[f::C_UNDERLINE] = match style.underline_color {
                        StyleColor::Rgb(rgb) => rgb.to_u24() as i32 | f::COLOR_SET,
                        StyleColor::Palette(i) => colors.palette[i as usize].to_u24() as i32 | f::COLOR_SET,
                        StyleColor::None => 0,
                    };
                }
                if let Some(fg) = cell.fg() {
                    c[f::C_FG] = fg.to_u24() as i32 | f::COLOR_SET;
                }
                if let Some(bg) = cell.bg() {
                    c[f::C_BG] = bg.to_u24() as i32 | f::COLOR_SET;
                }
                if let Some((start, end)) = selection
                    && (start as usize..=end as usize).contains(&x)
                {
                    attrs |= f::A_SELECTED;
                }
                c[f::C_ATTRS] = attrs;
                x += 1;
            }
        }
        y += 1;
    }
}

/// Sets `bit` on every cell of a linear (wrapping) viewport span.
fn mark_span(cells: &mut [i32], cols: usize, rows: usize, span: &vt::Span, bit: i32) {
    let (sx, sy) = (span.start.0 as usize, span.start.1 as usize);
    let (ex, ey) = (span.end.0 as usize, span.end.1 as usize);
    for y in sy..=ey.min(rows.saturating_sub(1)) {
        let x0 = if y == sy { sx } else { 0 };
        let x1 = if y == ey { ex } else { cols.saturating_sub(1) };
        for x in x0..=x1.min(cols.saturating_sub(1)) {
            cells[(y * cols + x) * f::CELL_INTS + f::C_ATTRS] |= bit;
        }
    }
}
