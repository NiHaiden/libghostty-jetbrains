//! Layout of the flat `int` frame handed to the plugin (see `Frame.kt`).
//!
//! ```text
//! [0, HEADER_INTS)               header, see H_*
//! cols*rows*CELL_INTS            cells, row-major, see C_*
//! grapheme pool                  {cell_index, n, cp_1..cp_n} for clusters with n > 1
//! ```

pub const HEADER_INTS: usize = 32;
pub const CELL_INTS: usize = 5;
pub const MAGIC: i32 = 0x474a_4231; // "GJB1"
pub const COLOR_SET: i32 = 1 << 24;

pub const H_MAGIC: usize = 0;
pub const H_COLS: usize = 1;
pub const H_ROWS: usize = 2;
pub const H_DIRTY: usize = 3;
pub const H_FG: usize = 4;
pub const H_BG: usize = 5;
pub const H_CURSOR_COLOR: usize = 6;
pub const H_CURSOR_FLAGS: usize = 7;
pub const H_CURSOR_X: usize = 8;
pub const H_CURSOR_Y: usize = 9;
pub const H_CURSOR_STYLE: usize = 10;
pub const H_SCROLL_TOTAL: usize = 11;
pub const H_SCROLL_OFFSET: usize = 12;
pub const H_SCROLL_LEN: usize = 13;
pub const H_FLAGS: usize = 14;
pub const H_GRAPHEME_OFFSET: usize = 15;
pub const H_GRAPHEME_LEN: usize = 16;
pub const H_SEARCH_TOTAL: usize = 17;
pub const H_SEARCH_SELECTED: usize = 18;
pub const H_FRAME_INTS: usize = 19;

pub const CURSOR_F_VISIBLE: i32 = 1 << 0;
pub const CURSOR_F_BLINKING: i32 = 1 << 1;
pub const CURSOR_F_WIDE_TAIL: i32 = 1 << 2;
pub const CURSOR_F_IN_VIEWPORT: i32 = 1 << 3;
pub const CURSOR_F_PASSWORD: i32 = 1 << 4;

pub const F_MOUSE_TRACKING: i32 = 1 << 0;
pub const F_ALT_SCREEN: i32 = 1 << 1;
pub const F_VIEWPORT_AT_BOTTOM: i32 = 1 << 2;
pub const F_RENDER_HELD: i32 = 1 << 3;
pub const F_REVERSE_COLORS: i32 = 1 << 4;
pub const F_SEARCH_PENDING: i32 = 1 << 5;

pub const C_CODEPOINT: usize = 0;
pub const C_FG: usize = 1;
pub const C_BG: usize = 2;
pub const C_ATTRS: usize = 3;
pub const C_UNDERLINE: usize = 4;

pub const A_BOLD: i32 = 1 << 0;
pub const A_ITALIC: i32 = 1 << 1;
pub const A_FAINT: i32 = 1 << 2;
pub const A_BLINK: i32 = 1 << 3;
pub const A_INVERSE: i32 = 1 << 4;
pub const A_INVISIBLE: i32 = 1 << 5;
pub const A_STRIKETHROUGH: i32 = 1 << 6;
pub const A_OVERLINE: i32 = 1 << 7;
pub const A_UNDERLINE_SHIFT: i32 = 8;
pub const A_UNDERLINE_MASK: i32 = 7 << 8;
pub const A_WIDE_SHIFT: i32 = 11;
pub const A_WIDE_MASK: i32 = 3 << 11;
pub const A_SELECTED: i32 = 1 << 13;
pub const A_GRAPHEME: i32 = 1 << 14;
pub const A_HYPERLINK: i32 = 1 << 15;
pub const A_SEARCH_MATCH: i32 = 1 << 16;
pub const A_SEARCH_SELECTED: i32 = 1 << 17;
