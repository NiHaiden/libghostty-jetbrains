/*
 * ghostty_jb - a small, JNA-friendly facade over libghostty-vt.
 *
 * libghostty-vt's C API is rich, fine-grained and explicitly unstable. The
 * JetBrains plugin talks to this facade instead so that:
 *
 *   - every call takes only primitives, pointers and int32 lengths (trivial to
 *     bind with JNA, identical on every 64-bit platform),
 *   - a whole frame is captured in ONE call into a flat int32 buffer instead of
 *     thousands of per-cell calls across the JNI boundary,
 *   - upstream API churn is absorbed here rather than in Kotlin.
 *
 * Threading: a gjb_term is NOT thread-safe. The caller must serialize all calls
 * for a given handle (the plugin uses one lock per terminal). Event callbacks
 * are invoked synchronously on the thread that made the call (usually the pty
 * reader thread inside gjb_write).
 */
#ifndef GHOSTTY_JB_H
#define GHOSTTY_JB_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#define GJB_API __declspec(dllexport)
#else
#define GJB_API __attribute__((visibility("default")))
#endif

/* Bumped whenever the ABI below changes incompatibly. */
#define GJB_ABI_VERSION 1

typedef struct gjb_term gjb_term;

/* ---- Events ------------------------------------------------------------- */

enum {
  /* Bytes that must be written to the pty (query replies, paste data...). */
  GJB_EVENT_WRITE_PTY = 1,
  /* BEL. */
  GJB_EVENT_BELL = 2,
  /* Title changed (OSC 0/2). data = UTF-8 title. */
  GJB_EVENT_TITLE = 3,
  /* Working directory changed (OSC 7 etc). data = UTF-8 URI or path. */
  GJB_EVENT_PWD = 4,
  /* Program wants to set the clipboard (OSC 52...). data = UTF-8 text,
   * value = 1 for the primary selection. Return 1 to allow, 0 to deny. */
  GJB_EVENT_CLIPBOARD_WRITE = 5,
  /* Desktop notification (OSC 9 / 777). data = title, NUL, body. */
  GJB_EVENT_NOTIFICATION = 6,
  /* Progress report (OSC 9;4). value = (state << 8) | (progress & 0xff),
   * progress is -1 (0xff) when absent. */
  GJB_EVENT_PROGRESS = 7,
  /* A command finished (OSC 133;D). value = exit code or INT32_MIN. */
  GJB_EVENT_COMMAND_FINISHED = 8,
};

/* Return value is only meaningful for events that document it. */
typedef int32_t (*gjb_event_fn)(void *userdata, int32_t event, int32_t value,
                                const uint8_t *data, int32_t len);

/* ---- Lifecycle ---------------------------------------------------------- */

GJB_API int32_t gjb_abi_version(void);

/* Returns NULL on allocation failure. */
GJB_API gjb_term *gjb_new(int32_t cols, int32_t rows, gjb_event_fn cb,
                          void *userdata);
GJB_API void gjb_free(gjb_term *t);

/* Feed bytes read from the pty. */
GJB_API void gjb_write(gjb_term *t, const uint8_t *data, int32_t len);

/* Full reset (RIS). */
GJB_API void gjb_reset(gjb_term *t);

/* Resize the grid. Cell and padding sizes are in device pixels and are used
 * for pixel mouse reports, size queries and mapping pointer positions. */
GJB_API int32_t gjb_resize(gjb_term *t, int32_t cols, int32_t rows,
                           int32_t cell_w, int32_t cell_h, int32_t pad_left,
                           int32_t pad_top);

/* ---- Configuration ------------------------------------------------------ */

/* Colors are 0xRRGGBB, or -1 to reset to the built-in default. palette may be
 * NULL (keep), otherwise palette_len entries starting at index 0 are set and
 * the remaining entries of the 256-color palette are kept at their defaults
 * (or generated from the base 16 when generate_256 != 0). */
GJB_API void gjb_set_colors(gjb_term *t, int32_t fg, int32_t bg,
                            int32_t cursor, const int32_t *palette,
                            int32_t palette_len, int32_t generate_256);

enum {
  GJB_OPT_SCROLLBACK_BYTES = 1,    /* int64 bytes, <0 = unlimited */
  GJB_OPT_CURSOR_STYLE = 2,        /* GJB_CURSOR_* */
  GJB_OPT_CURSOR_BLINK = 3,        /* bool */
  GJB_OPT_RESIZE_PULL_SCROLLBACK = 4, /* bool, false for ConPTY */
  GJB_OPT_OPTION_AS_ALT = 5,       /* 0 false, 1 true, 2 left, 3 right */
  GJB_OPT_TITLE_REPORT = 6,        /* bool */
  GJB_OPT_COLOR_SCHEME = 7,        /* 0 light, 1 dark (CSI ? 996 n) */
  GJB_OPT_FOCUSED = 8,             /* bool, rendering hint only */
  GJB_OPT_CLICK_INTERVAL_MS = 9,   /* max ms between multi-clicks */
};
GJB_API int32_t gjb_set_option(gjb_term *t, int32_t option, int64_t value);

/* ---- Queries ------------------------------------------------------------ */

enum {
  GJB_Q_COLS = 1,
  GJB_Q_ROWS = 2,
  GJB_Q_MOUSE_TRACKING = 3,      /* bool: app wants mouse events */
  GJB_Q_ALT_SCREEN = 4,          /* bool */
  GJB_Q_VIEWPORT_AT_BOTTOM = 5,  /* bool */
  GJB_Q_MODE = 6,                /* arg = DEC mode number (ANSI if < 0) */
  GJB_Q_SCROLLBACK_ROWS = 7,
  GJB_Q_CURSOR_X = 8,
  GJB_Q_CURSOR_Y = 9,
  GJB_Q_HAS_SELECTION = 10,
  GJB_Q_MOUSE_SHAPE = 11,
};
GJB_API int64_t gjb_query(gjb_term *t, int32_t what, int32_t arg);

/* Copies a string property (1 = title, 2 = pwd) as UTF-8. Returns the full
 * length, which may exceed cap (in which case out is truncated). */
GJB_API int32_t gjb_query_string(gjb_term *t, int32_t what, uint8_t *out,
                                 int32_t cap);

/* ---- Viewport ----------------------------------------------------------- */

enum {
  GJB_SCROLL_TOP = 0,
  GJB_SCROLL_BOTTOM = 1,
  GJB_SCROLL_DELTA = 2, /* rows, negative = towards history */
  GJB_SCROLL_ROW = 3,   /* absolute row offset from top of scrollback */
};
GJB_API void gjb_scroll(gjb_term *t, int32_t kind, int64_t value);

/* ---- Frame snapshot ------------------------------------------------------
 *
 * Captures everything needed to draw the viewport into a flat int32 buffer.
 * Returns the number of int32s written, or -(required size) if cap is too
 * small (nothing useful is written in that case).
 *
 * When nothing changed since the last snapshot, only the header is written
 * and header[GJB_H_DIRTY] == 0; the caller should keep its previous frame.
 *
 * Layout:
 *   [0, GJB_HEADER_INTS)             header, see GJB_H_*
 *   cells: cols*rows*GJB_CELL_INTS   row-major, see GJB_C_*
 *   grapheme pool                    repeated {cell_index, n, cp_1..cp_n} for
 *                                    cells whose cluster has n > 1 codepoints
 */
#define GJB_HEADER_INTS 32
#define GJB_CELL_INTS 5

enum {
  GJB_H_MAGIC = 0, /* GJB_FRAME_MAGIC */
  GJB_H_COLS = 1,
  GJB_H_ROWS = 2,
  GJB_H_DIRTY = 3, /* 0 clean, 1 partial, 2 full */
  GJB_H_FG = 4,
  GJB_H_BG = 5,
  GJB_H_CURSOR_COLOR = 6, /* rgb | GJB_COLOR_SET when the app set one */
  GJB_H_CURSOR_FLAGS = 7, /* GJB_CURSOR_F_* */
  GJB_H_CURSOR_X = 8,
  GJB_H_CURSOR_Y = 9,
  GJB_H_CURSOR_STYLE = 10, /* GJB_CURSOR_* */
  GJB_H_SCROLL_TOTAL = 11, /* rows, scrollback + viewport */
  GJB_H_SCROLL_OFFSET = 12,
  GJB_H_SCROLL_LEN = 13,
  GJB_H_FLAGS = 14, /* GJB_F_* */
  GJB_H_GRAPHEME_OFFSET = 15,
  GJB_H_GRAPHEME_LEN = 16,
  GJB_H_SEARCH_TOTAL = 17,    /* -1 when no search is active */
  GJB_H_SEARCH_SELECTED = 18, /* -1 when no match is selected */
  GJB_H_FRAME_INTS = 19,      /* total ints in this frame */
};

#define GJB_FRAME_MAGIC 0x474a4231 /* "GJB1" */
#define GJB_COLOR_SET (1 << 24)

enum {
  GJB_CURSOR_F_VISIBLE = 1 << 0,
  GJB_CURSOR_F_BLINKING = 1 << 1,
  GJB_CURSOR_F_WIDE_TAIL = 1 << 2,
  GJB_CURSOR_F_IN_VIEWPORT = 1 << 3,
  GJB_CURSOR_F_PASSWORD = 1 << 4,
};

enum {
  GJB_CURSOR_BAR = 0,
  GJB_CURSOR_BLOCK = 1,
  GJB_CURSOR_UNDERLINE = 2,
  GJB_CURSOR_BLOCK_HOLLOW = 3,
};

enum {
  GJB_F_MOUSE_TRACKING = 1 << 0,
  GJB_F_ALT_SCREEN = 1 << 1,
  GJB_F_VIEWPORT_AT_BOTTOM = 1 << 2,
  GJB_F_RENDER_HELD = 1 << 3,
  GJB_F_REVERSE_COLORS = 1 << 4,
  GJB_F_SEARCH_PENDING = 1 << 5, /* search still running; snapshot again */
};

enum {
  GJB_C_CODEPOINT = 0, /* base codepoint, 0 = empty */
  GJB_C_FG = 1,        /* rgb | GJB_COLOR_SET, else use default fg */
  GJB_C_BG = 2,        /* rgb | GJB_COLOR_SET, else use default bg */
  GJB_C_ATTRS = 3,     /* GJB_A_* */
  GJB_C_UNDERLINE = 4, /* underline color rgb | GJB_COLOR_SET */
};

enum {
  GJB_A_BOLD = 1 << 0,
  GJB_A_ITALIC = 1 << 1,
  GJB_A_FAINT = 1 << 2,
  GJB_A_BLINK = 1 << 3,
  GJB_A_INVERSE = 1 << 4,
  GJB_A_INVISIBLE = 1 << 5,
  GJB_A_STRIKETHROUGH = 1 << 6,
  GJB_A_OVERLINE = 1 << 7,
  GJB_A_UNDERLINE_SHIFT = 8, /* 3 bits: 0 none, 1 single, 2 double,
                                3 curly, 4 dotted, 5 dashed */
  GJB_A_UNDERLINE_MASK = 7 << 8,
  GJB_A_WIDE_SHIFT = 11, /* 2 bits: 0 narrow, 1 wide, 2 spacer tail,
                            3 spacer head */
  GJB_A_WIDE_MASK = 3 << 11,
  GJB_A_SELECTED = 1 << 13,
  GJB_A_GRAPHEME = 1 << 14,
  GJB_A_HYPERLINK = 1 << 15,
  GJB_A_SEARCH_MATCH = 1 << 16,
  GJB_A_SEARCH_SELECTED = 1 << 17,
};

GJB_API int32_t gjb_snapshot(gjb_term *t, int32_t *buf, int32_t cap);

/* Marks the next snapshot as fully dirty (e.g. after a theme change). */
GJB_API void gjb_invalidate(gjb_term *t);

/* ---- Input encoding ------------------------------------------------------
 * All encoders write into out and return the number of bytes, 0 when the
 * event produces no output, or a negative GhosttyResult on error. */

/* action: 0 release, 1 press, 2 repeat. key: GhosttyKey. mods: GhosttyMods.
 * utf8: text the key produces in the current layout without ctrl/alt applied
 * (may be NULL). unshifted: codepoint of the key without shift (0 unknown). */
GJB_API int32_t gjb_encode_key(gjb_term *t, int32_t action, int32_t key,
                               int32_t mods, int32_t consumed_mods,
                               int32_t unshifted, const uint8_t *utf8,
                               int32_t utf8_len, uint8_t *out, int32_t cap);

/* action: 0 press, 1 release, 2 motion. button: GhosttyMouseButton (0 none).
 * x/y in surface pixels. Returns 0 when the app isn't tracking the mouse. */
GJB_API int32_t gjb_encode_mouse(gjb_term *t, int32_t action, int32_t button,
                                 int32_t mods, float x, float y,
                                 int32_t any_button_pressed, uint8_t *out,
                                 int32_t cap);

/* Returns 0 unless the app enabled focus reporting (mode 1004). */
GJB_API int32_t gjb_encode_focus(gjb_term *t, int32_t gained, uint8_t *out,
                                 int32_t cap);

/* Pastes UTF-8 text; the encoded bytes are delivered via GJB_EVENT_WRITE_PTY.
 * Returns 0 on success, 1 if rejected as unsafe (retry with allow_unsafe),
 * negative GhosttyResult on error. */
GJB_API int32_t gjb_paste(gjb_term *t, const uint8_t *utf8, int32_t len,
                          int32_t allow_unsafe);

/* ---- Selection ---------------------------------------------------------- */

enum {
  GJB_SEL_PRESS = 0,
  GJB_SEL_RELEASE = 1,
  GJB_SEL_DRAG = 2,
  GJB_SEL_AUTOSCROLL_TICK = 3,
};

/* Return bits for gjb_select_event. */
enum {
  GJB_SEL_R_CHANGED = 1 << 0,   /* selection was set or cleared */
  GJB_SEL_R_HAS = 1 << 1,       /* a selection exists afterwards */
  GJB_SEL_R_AUTOSCROLL_UP = 1 << 2,
  GJB_SEL_R_AUTOSCROLL_DOWN = 1 << 3,
};

/* Feeds a pointer event to the selection gesture state machine (handles
 * single/double/triple click, drag, autoscroll). x/y in surface pixels,
 * time_ns monotonic. */
GJB_API int32_t gjb_select_event(gjb_term *t, int32_t type, double x,
                                 double y, int64_t time_ns, int32_t rectangle);
GJB_API void gjb_select_clear(gjb_term *t);
GJB_API void gjb_select_all(gjb_term *t);

/* Copies the selected text as UTF-8 (trailing whitespace trimmed, soft wraps
 * unwrapped). Returns the full length (may exceed cap), or -1 if nothing is
 * selected. */
GJB_API int32_t gjb_selection_text(gjb_term *t, uint8_t *out, int32_t cap);

/* Whole screen + scrollback as plain text. Same return convention. */
GJB_API int32_t gjb_screen_text(gjb_term *t, uint8_t *out, int32_t cap);

/* OSC 8 hyperlink URI under a viewport cell. Returns full length, or -1. */
GJB_API int32_t gjb_hyperlink_at(gjb_term *t, int32_t col, int32_t row,
                                 uint8_t *out, int32_t cap);

/* ---- Search -------------------------------------------------------------
 * Matches are highlighted in snapshots while a search is active. */

/* Sets the needle (UTF-8). An empty needle ends the search. */
GJB_API int32_t gjb_search_set(gjb_term *t, const uint8_t *utf8, int32_t len);
/* dir: 1 next (older), -1 previous (newer). Scrolls the match into view.
 * Returns the selected index or -1. */
GJB_API int32_t gjb_search_select(gjb_term *t, int32_t dir);

#ifdef __cplusplus
}
#endif

#endif /* GHOSTTY_JB_H */
