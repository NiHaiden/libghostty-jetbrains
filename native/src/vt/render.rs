//! Render state: an incrementally updated snapshot of the viewport.

use super::{Error, Result, Rgb, check, sized};
use crate::sys;
use std::ffi::c_void;
use std::marker::PhantomData;
use std::ptr::{self, NonNull};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dirty {
    Clean = 0,
    Partial = 1,
    Full = 2,
}

#[derive(Debug, Clone)]
pub struct Colors {
    pub foreground: Rgb,
    pub background: Rgb,
    /// Cursor color requested by the program (OSC 12) or configured.
    pub cursor: Option<Rgb>,
    pub palette: [Rgb; 256],
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Cursor {
    pub visible: bool,
    pub blinking: bool,
    pub wide_tail: bool,
    pub in_viewport: bool,
    pub password_input: bool,
    pub x: u16,
    pub y: u16,
    /// `CursorStyle` discriminant (bar, block, underline, hollow block).
    pub style: i32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum StyleColor {
    #[default]
    None,
    Palette(u8),
    Rgb(Rgb),
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct CellStyle {
    pub bold: bool,
    pub italic: bool,
    pub faint: bool,
    pub blink: bool,
    pub inverse: bool,
    pub invisible: bool,
    pub strikethrough: bool,
    pub overline: bool,
    /// 0 none, 1 single, 2 double, 3 curly, 4 dotted, 5 dashed.
    pub underline: u8,
    pub underline_color: StyleColor,
}

#[derive(Debug)]
pub struct RenderState {
    state: NonNull<sys::GhosttyRenderStateImpl>,
    rows: NonNull<sys::GhosttyRenderStateRowIteratorImpl>,
    cells: NonNull<sys::GhosttyRenderStateRowCellsImpl>,
}

// SAFETY: like `Terminal`, render state has no thread affinity and is only
// used through `&mut`/`&` borrows of its owner.
unsafe impl Send for RenderState {}

impl Drop for RenderState {
    fn drop(&mut self) {
        // SAFETY: we own all three handles.
        unsafe {
            sys::ghostty_render_state_row_cells_free(self.cells.as_ptr());
            sys::ghostty_render_state_row_iterator_free(self.rows.as_ptr());
            sys::ghostty_render_state_free(self.state.as_ptr());
        }
    }
}

fn new_handle<T>(f: impl FnOnce(*mut *mut T) -> sys::GhosttyResult) -> Result<NonNull<T>> {
    let mut raw: *mut T = ptr::null_mut();
    check(f(&mut raw))?;
    NonNull::new(raw).ok_or(Error::OUT_OF_MEMORY)
}

impl RenderState {
    pub fn new() -> Result<RenderState> {
        // SAFETY: plain constructors with the default allocator.
        let state = new_handle(|out| unsafe { sys::ghostty_render_state_new(ptr::null(), out) })?;
        let rows = match new_handle(|out| unsafe { sys::ghostty_render_state_row_iterator_new(ptr::null(), out) }) {
            Ok(r) => r,
            Err(e) => {
                // SAFETY: freeing what we just created.
                unsafe { sys::ghostty_render_state_free(state.as_ptr()) };
                return Err(e);
            }
        };
        let cells = match new_handle(|out| unsafe { sys::ghostty_render_state_row_cells_new(ptr::null(), out) }) {
            Ok(c) => c,
            Err(e) => {
                // SAFETY: freeing what we just created.
                unsafe {
                    sys::ghostty_render_state_row_iterator_free(rows.as_ptr());
                    sys::ghostty_render_state_free(state.as_ptr());
                }
                return Err(e);
            }
        };
        Ok(RenderState { state, rows, cells })
    }

    /// Captures `terminal`. Only called by `Terminal`, which owns this state.
    pub(super) fn update(&mut self, terminal: sys::GhosttyTerminal) -> Result<()> {
        // SAFETY: `terminal` is the live terminal owning this render state.
        check(unsafe { sys::ghostty_render_state_update(self.state.as_ptr(), terminal) })
    }

    fn get<T: Default>(&self, data: sys::GhosttyRenderStateData) -> T {
        let mut out = T::default();
        // SAFETY: callers pick `T` matching the documented output type.
        unsafe { sys::ghostty_render_state_get(self.state.as_ptr(), data, &mut out as *mut T as *mut c_void) };
        out
    }

    pub fn dirty(&self) -> Dirty {
        match self.get::<sys::GhosttyRenderStateDirty>(sys::GhosttyRenderStateData_GHOSTTY_RENDER_STATE_DATA_DIRTY) {
            sys::GhosttyRenderStateDirty_GHOSTTY_RENDER_STATE_DIRTY_FALSE => Dirty::Clean,
            sys::GhosttyRenderStateDirty_GHOSTTY_RENDER_STATE_DIRTY_PARTIAL => Dirty::Partial,
            _ => Dirty::Full,
        }
    }

    /// (cols, rows) of the captured viewport.
    pub fn size(&self) -> (u16, u16) {
        let cols: u16 = self.get(sys::GhosttyRenderStateData_GHOSTTY_RENDER_STATE_DATA_COLS);
        let rows: u16 = self.get(sys::GhosttyRenderStateData_GHOSTTY_RENDER_STATE_DATA_ROWS);
        (cols, rows)
    }

    pub fn colors(&self) -> Colors {
        let mut c = sized!(sys::GhosttyRenderStateColors);
        // SAFETY: DATA_COLORS fills a sized GhosttyRenderStateColors.
        unsafe {
            sys::ghostty_render_state_get(
                self.state.as_ptr(),
                sys::GhosttyRenderStateData_GHOSTTY_RENDER_STATE_DATA_COLORS,
                &mut c as *mut _ as *mut c_void,
            )
        };
        Colors {
            foreground: Rgb::from_sys(c.foreground),
            background: Rgb::from_sys(c.background),
            cursor: c.cursor_has_value.then(|| Rgb::from_sys(c.cursor)),
            palette: c.palette.map(Rgb::from_sys),
        }
    }

    pub fn cursor(&self) -> Cursor {
        let mut c = sized!(sys::GhosttyRenderStateCursor);
        // SAFETY: DATA_CURSOR fills a sized GhosttyRenderStateCursor.
        let r = unsafe {
            sys::ghostty_render_state_get(
                self.state.as_ptr(),
                sys::GhosttyRenderStateData_GHOSTTY_RENDER_STATE_DATA_CURSOR,
                &mut c as *mut _ as *mut c_void,
            )
        };
        if r != sys::GhosttyResult_GHOSTTY_SUCCESS {
            return Cursor::default();
        }
        Cursor {
            visible: c.visible,
            blinking: c.blinking,
            wide_tail: c.wide_tail,
            in_viewport: c.viewport_has_value,
            password_input: c.password_input,
            x: c.viewport_x,
            y: c.viewport_y,
            style: c.visual_style as i32,
        }
    }

    /// Resets both global and per-row dirty state after a full frame.
    pub fn clean(&mut self) {
        // SAFETY: plain call on our handle.
        unsafe { sys::ghostty_render_state_clean(self.state.as_ptr()) };
    }

    /// Iterates the captured rows, top to bottom.
    pub fn rows(&mut self) -> Result<Rows<'_>> {
        let mut iter = self.rows.as_ptr();
        // SAFETY: DATA_ROW_ITERATOR (re)initializes our iterator handle.
        check(unsafe {
            sys::ghostty_render_state_get(
                self.state.as_ptr(),
                sys::GhosttyRenderStateData_GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                &mut iter as *mut _ as *mut c_void,
            )
        })?;
        Ok(Rows { iter, cells: self.cells.as_ptr(), _state: PhantomData })
    }
}

/// Lending iterator over rows; borrows the render state mutably.
#[derive(Debug)]
pub struct Rows<'a> {
    iter: sys::GhosttyRenderStateRowIterator,
    cells: sys::GhosttyRenderStateRowCells,
    _state: PhantomData<&'a mut RenderState>,
}

impl Rows<'_> {
    pub fn next_row(&mut self) -> Option<Row<'_>> {
        // SAFETY: the iterator belongs to the borrowed render state.
        let more = unsafe { sys::ghostty_render_state_row_iterator_next(self.iter) };
        more.then_some(Row { iter: self.iter, cells: self.cells, _rows: PhantomData })
    }
}

#[derive(Debug)]
pub struct Row<'a> {
    iter: sys::GhosttyRenderStateRowIterator,
    cells: sys::GhosttyRenderStateRowCells,
    _rows: PhantomData<&'a mut ()>,
}

impl Row<'_> {
    /// Inclusive selected column range in this row, if any.
    pub fn selection(&self) -> Option<(u16, u16)> {
        let mut sel = sized!(sys::GhosttyRenderStateRowSelection);
        // SAFETY: ROW_DATA_SELECTION fills a sized struct (NO_VALUE when none).
        let r = unsafe {
            sys::ghostty_render_state_row_get(
                self.iter,
                sys::GhosttyRenderStateRowData_GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION,
                &mut sel as *mut _ as *mut c_void,
            )
        };
        (r == sys::GhosttyResult_GHOSTTY_SUCCESS).then_some((sel.start_x, sel.end_x))
    }

    pub fn cells(&mut self) -> Result<Cells<'_>> {
        let mut cells = self.cells;
        // SAFETY: ROW_DATA_CELLS (re)initializes our cells handle for this row.
        check(unsafe {
            sys::ghostty_render_state_row_get(
                self.iter,
                sys::GhosttyRenderStateRowData_GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                &mut cells as *mut _ as *mut c_void,
            )
        })?;
        Ok(Cells { cells, _row: PhantomData })
    }
}

#[derive(Debug)]
pub struct Cells<'a> {
    cells: sys::GhosttyRenderStateRowCells,
    _row: PhantomData<&'a mut ()>,
}

impl Cells<'_> {
    pub fn next_cell(&mut self) -> Option<Cell<'_>> {
        // SAFETY: the handle belongs to the borrowed row.
        let more = unsafe { sys::ghostty_render_state_row_cells_next(self.cells) };
        more.then_some(Cell { cells: self.cells, _cells: PhantomData })
    }
}

/// The current cell of a [`Cells`] iterator.
#[derive(Debug)]
pub struct Cell<'a> {
    cells: sys::GhosttyRenderStateRowCells,
    _cells: PhantomData<&'a mut ()>,
}

impl Cell<'_> {
    fn get<T: Default>(&self, data: sys::GhosttyRenderStateRowCellsData) -> Option<T> {
        let mut out = T::default();
        // SAFETY: callers pick `T` matching the documented output type.
        let r = unsafe { sys::ghostty_render_state_row_cells_get(self.cells, data, &mut out as *mut T as *mut c_void) };
        (r == sys::GhosttyResult_GHOSTTY_SUCCESS).then_some(out)
    }

    fn raw_cell(&self) -> Option<sys::GhosttyCell> {
        self.get(sys::GhosttyRenderStateRowCellsData_GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW)
    }

    /// 0 narrow, 1 wide, 2 spacer tail, 3 spacer head.
    pub fn wide(&self) -> u8 {
        let Some(raw) = self.raw_cell() else { return 0 };
        let mut wide: sys::GhosttyCellWide = sys::GhosttyCellWide_GHOSTTY_CELL_WIDE_NARROW;
        // SAFETY: CELL_DATA_WIDE outputs a GhosttyCellWide.
        unsafe {
            sys::ghostty_cell_get(raw, sys::GhosttyCellData_GHOSTTY_CELL_DATA_WIDE, &mut wide as *mut _ as *mut c_void)
        };
        wide.clamp(0, 3) as u8
    }

    pub fn has_hyperlink(&self) -> bool {
        let Some(raw) = self.raw_cell() else { return false };
        let mut link = false;
        // SAFETY: CELL_DATA_HAS_HYPERLINK outputs a bool.
        unsafe {
            sys::ghostty_cell_get(
                raw,
                sys::GhosttyCellData_GHOSTTY_CELL_DATA_HAS_HYPERLINK,
                &mut link as *mut _ as *mut c_void,
            )
        };
        link
    }

    /// The cell's grapheme cluster into `buf` (empty for blank cells).
    pub fn graphemes(&self, buf: &mut Vec<u32>) {
        buf.clear();
        let len: u32 = self
            .get(sys::GhosttyRenderStateRowCellsData_GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN)
            .unwrap_or(0);
        if len == 0 {
            return;
        }
        buf.resize(len as usize, 0);
        // SAFETY: GRAPHEMES_BUF writes exactly `len` u32s, which `buf` holds.
        let r = unsafe {
            sys::ghostty_render_state_row_cells_get(
                self.cells,
                sys::GhosttyRenderStateRowCellsData_GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
                buf.as_mut_ptr() as *mut c_void,
            )
        };
        if r != sys::GhosttyResult_GHOSTTY_SUCCESS {
            buf.clear();
        }
    }

    /// The cell's style, or `None` for unstyled cells.
    pub fn style(&self) -> Option<CellStyle> {
        let styled: bool = self
            .get(sys::GhosttyRenderStateRowCellsData_GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING)
            .unwrap_or(false);
        if !styled {
            return None;
        }
        let mut s = sized!(sys::GhosttyStyle);
        // SAFETY: CELLS_DATA_STYLE fills a sized GhosttyStyle.
        let r = unsafe {
            sys::ghostty_render_state_row_cells_get(
                self.cells,
                sys::GhosttyRenderStateRowCellsData_GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE,
                &mut s as *mut _ as *mut c_void,
            )
        };
        if r != sys::GhosttyResult_GHOSTTY_SUCCESS {
            return None;
        }
        Some(CellStyle {
            bold: s.bold,
            italic: s.italic,
            faint: s.faint,
            blink: s.blink,
            inverse: s.inverse,
            invisible: s.invisible,
            strikethrough: s.strikethrough,
            overline: s.overline,
            underline: s.underline.clamp(0, 7) as u8,
            underline_color: style_color(s.underline_color),
        })
    }

    /// Explicit foreground (palette resolved), or `None` for the default.
    pub fn fg(&self) -> Option<Rgb> {
        self.get::<sys::GhosttyColorRgb>(
            sys::GhosttyRenderStateRowCellsData_GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR,
        )
        .map(Rgb::from_sys)
    }

    /// Explicit background (palette resolved), or `None` for the default.
    pub fn bg(&self) -> Option<Rgb> {
        self.get::<sys::GhosttyColorRgb>(
            sys::GhosttyRenderStateRowCellsData_GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR,
        )
        .map(Rgb::from_sys)
    }
}

fn style_color(c: sys::GhosttyStyleColor) -> StyleColor {
    match c.tag {
        // SAFETY: the tag says which union arm is initialized.
        sys::GhosttyStyleColorTag_GHOSTTY_STYLE_COLOR_PALETTE => StyleColor::Palette(unsafe { c.value.palette }),
        // SAFETY: as above.
        sys::GhosttyStyleColorTag_GHOSTTY_STYLE_COLOR_RGB => StyleColor::Rgb(Rgb::from_sys(unsafe { c.value.rgb })),
        _ => StyleColor::None,
    }
}
