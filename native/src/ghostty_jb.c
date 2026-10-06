/*
 * ghostty_jb - JNA-friendly facade over libghostty-vt. See ghostty_jb.h.
 */
#include "ghostty_jb.h"

#include <ghostty/vt.h>

#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

/* A program may hold rendering (synchronized output, mode 2026) for at most
 * this long before we force the screen to update again. */
#define HOLD_TIMEOUT_NS 1000000000LL

/* Bound on search work per snapshot so a huge scrollback can't stall a frame. */
#define SEARCH_TICKS_PER_FRAME 64

#define MAX_GRAPHEME 64

struct gjb_term {
  GhosttyTerminal term;
  GhosttyRenderState rs;
  GhosttyRenderStateRowIterator row_iter;
  GhosttyRenderStateRowCells cells;

  GhosttyKeyEncoder key_encoder;
  GhosttyKeyEvent key_event;
  GhosttyMouseEncoder mouse_encoder;
  GhosttyMouseEvent mouse_event;

  GhosttySelectionGesture gesture;
  GhosttySelectionGestureEvent gesture_events[4]; /* indexed by GJB_SEL_* */

  GhosttySearch search;
  GhosttySelection *search_matches;
  size_t search_matches_cap;

  gjb_event_fn cb;
  void *userdata;

  int32_t cols, rows;
  int32_t cell_w, cell_h, pad_left, pad_top;

  bool held;
  int64_t hold_start_ns;
  bool force_full;
  int32_t color_scheme; /* GhosttyColorScheme */
  GhosttyOptionAsAlt option_as_alt;
  uint64_t click_interval_ns;
};

/* ------------------------------------------------------------------------ */

static int64_t now_ns(void) {
  struct timespec ts;
  timespec_get(&ts, TIME_UTC);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int32_t emit(gjb_term *t, int32_t event, int32_t value,
                    const uint8_t *data, size_t len) {
  if (t->cb == NULL) return 0;
  return t->cb(t->userdata, event, value, data, (int32_t)len);
}

static int32_t rgb(GhosttyColorRgb c) {
  return ((int32_t)c.r << 16) | ((int32_t)c.g << 8) | (int32_t)c.b;
}

static GhosttyColorRgb from_rgb(int32_t v) {
  GhosttyColorRgb c = {(uint8_t)((v >> 16) & 0xff), (uint8_t)((v >> 8) & 0xff),
                       (uint8_t)(v & 0xff)};
  return c;
}

/* Copies src into out (truncating) and returns the full length. */
static int32_t copy_out(const uint8_t *src, size_t len, uint8_t *out,
                        int32_t cap) {
  if (out != NULL && cap > 0) {
    size_t n = len < (size_t)cap ? len : (size_t)cap;
    if (n > 0) memcpy(out, src, n);
  }
  return (int32_t)len;
}

/* ---- Terminal callbacks ------------------------------------------------- */

static void on_write_pty(GhosttyTerminal term, void *ud, const uint8_t *data,
                         size_t len) {
  (void)term;
  emit((gjb_term *)ud, GJB_EVENT_WRITE_PTY, 0, data, len);
}

static void on_bell(GhosttyTerminal term, void *ud) {
  (void)term;
  emit((gjb_term *)ud, GJB_EVENT_BELL, 0, NULL, 0);
}

static void on_title_changed(GhosttyTerminal term, void *ud) {
  GhosttyString s = {0};
  if (ghostty_terminal_get(term, GHOSTTY_TERMINAL_DATA_TITLE, &s) ==
      GHOSTTY_SUCCESS)
    emit((gjb_term *)ud, GJB_EVENT_TITLE, 0, s.ptr, s.len);
}

static void on_pwd_changed(GhosttyTerminal term, void *ud) {
  GhosttyString s = {0};
  if (ghostty_terminal_get(term, GHOSTTY_TERMINAL_DATA_PWD, &s) ==
      GHOSTTY_SUCCESS)
    emit((gjb_term *)ud, GJB_EVENT_PWD, 0, s.ptr, s.len);
}

static bool mime_is_text(GhosttyString mime) {
  static const char text_plain[] = "text/plain";
  size_t n = sizeof(text_plain) - 1;
  return mime.len >= n && memcmp(mime.ptr, text_plain, n) == 0;
}

static void on_clipboard_write(GhosttyTerminal term, void *ud,
                               const GhosttyClipboardWrite *write) {
  (void)term;
  gjb_term *t = (gjb_term *)ud;
  GhosttyClipboardWriteReply reply = {0};
  reply.size = sizeof(reply);
  reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_DENIED;

  const GhosttyClipboardContent *text = NULL;
  for (size_t i = 0; i < write->contents_len; i++) {
    if (mime_is_text(write->contents[i].mime)) {
      text = &write->contents[i];
      break;
    }
  }

  if (write->contents_len == 0) {
    reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
  } else if (text == NULL) {
    reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_UNSUPPORTED;
  } else {
    int32_t primary =
        write->location == GHOSTTY_CLIPBOARD_LOCATION_STANDARD ? 0 : 1;
    if (emit(t, GJB_EVENT_CLIPBOARD_WRITE, primary, text->data.ptr,
             text->data.len) != 0)
      reply.result = GHOSTTY_CLIPBOARD_WRITE_RESULT_SUCCESS;
  }

  if (write->reply != NULL) write->reply(write, &reply);
}

static void on_notification(GhosttyTerminal term, void *ud,
                            const GhosttyTerminalDesktopNotification *n) {
  (void)term;
  size_t len = n->title.len + 1 + n->body.len;
  uint8_t *buf = malloc(len);
  if (buf == NULL) return;
  if (n->title.len) memcpy(buf, n->title.ptr, n->title.len);
  buf[n->title.len] = 0;
  if (n->body.len) memcpy(buf + n->title.len + 1, n->body.ptr, n->body.len);
  emit((gjb_term *)ud, GJB_EVENT_NOTIFICATION, 0, buf, len);
  free(buf);
}

static void on_progress(GhosttyTerminal term, void *ud,
                        const GhosttyTerminalProgressReport *r) {
  (void)term;
  int32_t value = ((int32_t)r->state << 8) | ((int32_t)(uint8_t)r->progress);
  emit((gjb_term *)ud, GJB_EVENT_PROGRESS, value, NULL, 0);
}

static void on_semantic_prompt(GhosttyTerminal term, void *ud,
                               const GhosttyTerminalSemanticPrompt *e) {
  (void)term;
  if (e->kind != GHOSTTY_SEMANTIC_PROMPT_COMMAND_END) return;
  int32_t code = e->has_exit_code ? e->exit_code : INT32_MIN;
  emit((gjb_term *)ud, GJB_EVENT_COMMAND_FINISHED, code, NULL, 0);
}

static void on_render_hold(GhosttyTerminal term, void *ud, bool held) {
  gjb_term *t = (gjb_term *)ud;
  if (held && !t->held) {
    /* Capture the frame the program wants left on screen while it draws the
     * next one, then stop updating until the hold ends. */
    ghostty_render_state_update(t->rs, term);
    t->held = true;
    t->hold_start_ns = now_ns();
  } else if (!held) {
    t->held = false;
  }
}

static bool on_device_attributes(GhosttyTerminal term, void *ud,
                                 GhosttyDeviceAttributes *out) {
  (void)term;
  (void)ud;
  /* Same answers as the Ghostty app: VT220 with ANSI color. */
  out->primary.conformance_level = 62;
  out->primary.features[0] = 22;
  out->primary.num_features = 1;
  out->secondary.device_type = 1;
  out->secondary.firmware_version = 10;
  out->secondary.rom_cartridge = 0;
  out->tertiary.unit_id = 0;
  return true;
}

static bool on_size(GhosttyTerminal term, void *ud, GhosttySizeReportSize *out) {
  (void)term;
  gjb_term *t = (gjb_term *)ud;
  out->rows = (uint16_t)t->rows;
  out->columns = (uint16_t)t->cols;
  out->cell_width = (uint32_t)t->cell_w;
  out->cell_height = (uint32_t)t->cell_h;
  return true;
}

static bool on_color_scheme(GhosttyTerminal term, void *ud,
                            GhosttyColorScheme *out) {
  (void)term;
  *out = (GhosttyColorScheme)((gjb_term *)ud)->color_scheme;
  return true;
}

/* ---- Lifecycle ---------------------------------------------------------- */

int32_t gjb_abi_version(void) { return GJB_ABI_VERSION; }

void gjb_free(gjb_term *t) {
  if (t == NULL) return;
  if (t->search) ghostty_search_free(t->search);
  free(t->search_matches);
  for (int i = 0; i < 4; i++)
    if (t->gesture_events[i])
      ghostty_selection_gesture_event_free(t->gesture_events[i]);
  if (t->gesture) ghostty_selection_gesture_free(t->gesture, t->term);
  if (t->mouse_event) ghostty_mouse_event_free(t->mouse_event);
  if (t->mouse_encoder) ghostty_mouse_encoder_free(t->mouse_encoder);
  if (t->key_event) ghostty_key_event_free(t->key_event);
  if (t->key_encoder) ghostty_key_encoder_free(t->key_encoder);
  if (t->cells) ghostty_render_state_row_cells_free(t->cells);
  if (t->row_iter) ghostty_render_state_row_iterator_free(t->row_iter);
  if (t->rs) ghostty_render_state_free(t->rs);
  if (t->term) ghostty_terminal_free(t->term);
  free(t);
}

#define SET_CB(opt, fn) \
  ghostty_terminal_set(t->term, opt, (const void *)(fn))

gjb_term *gjb_new(int32_t cols, int32_t rows, gjb_event_fn cb,
                  void *userdata) {
  if (cols < 1) cols = 1;
  if (rows < 1) rows = 1;

  gjb_term *t = calloc(1, sizeof(*t));
  if (t == NULL) return NULL;
  t->cb = cb;
  t->userdata = userdata;
  t->cols = cols;
  t->rows = rows;
  t->cell_w = 8;
  t->cell_h = 16;
  t->color_scheme = GHOSTTY_COLOR_SCHEME_DARK;
  t->option_as_alt = GHOSTTY_OPTION_AS_ALT_FALSE;
  t->click_interval_ns = 500000000ULL;

  static const GhosttySelectionGestureEventType gesture_types[4] = {
      GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_PRESS,
      GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_RELEASE,
      GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_DRAG,
      GHOSTTY_SELECTION_GESTURE_EVENT_TYPE_AUTOSCROLL_TICK,
  };

  if (ghostty_terminal_new(NULL, &t->term, (uint16_t)cols, (uint16_t)rows) !=
          GHOSTTY_SUCCESS ||
      ghostty_render_state_new(NULL, &t->rs) != GHOSTTY_SUCCESS ||
      ghostty_render_state_row_iterator_new(NULL, &t->row_iter) !=
          GHOSTTY_SUCCESS ||
      ghostty_render_state_row_cells_new(NULL, &t->cells) != GHOSTTY_SUCCESS ||
      ghostty_key_encoder_new(NULL, &t->key_encoder) != GHOSTTY_SUCCESS ||
      ghostty_key_event_new(NULL, &t->key_event) != GHOSTTY_SUCCESS ||
      ghostty_mouse_encoder_new(NULL, &t->mouse_encoder) != GHOSTTY_SUCCESS ||
      ghostty_mouse_event_new(NULL, &t->mouse_event) != GHOSTTY_SUCCESS ||
      ghostty_selection_gesture_new(NULL, &t->gesture) != GHOSTTY_SUCCESS) {
    gjb_free(t);
    return NULL;
  }
  for (int i = 0; i < 4; i++) {
    if (ghostty_selection_gesture_event_new(NULL, &t->gesture_events[i],
                                            gesture_types[i]) !=
        GHOSTTY_SUCCESS) {
      gjb_free(t);
      return NULL;
    }
  }

  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_USERDATA, t);
  SET_CB(GHOSTTY_TERMINAL_OPT_WRITE_PTY, on_write_pty);
  SET_CB(GHOSTTY_TERMINAL_OPT_BELL, on_bell);
  SET_CB(GHOSTTY_TERMINAL_OPT_TITLE_CHANGED, on_title_changed);
  SET_CB(GHOSTTY_TERMINAL_OPT_PWD_CHANGED, on_pwd_changed);
  SET_CB(GHOSTTY_TERMINAL_OPT_CLIPBOARD_WRITE, on_clipboard_write);
  SET_CB(GHOSTTY_TERMINAL_OPT_DESKTOP_NOTIFICATION, on_notification);
  SET_CB(GHOSTTY_TERMINAL_OPT_PROGRESS_REPORT, on_progress);
  SET_CB(GHOSTTY_TERMINAL_OPT_SEMANTIC_PROMPT, on_semantic_prompt);
  SET_CB(GHOSTTY_TERMINAL_OPT_RENDER_HOLD, on_render_hold);
  SET_CB(GHOSTTY_TERMINAL_OPT_DEVICE_ATTRIBUTES, on_device_attributes);
  SET_CB(GHOSTTY_TERMINAL_OPT_SIZE, on_size);
  SET_CB(GHOSTTY_TERMINAL_OPT_COLOR_SCHEME, on_color_scheme);

  /* We advertise ourselves as xterm-256color since the xterm-ghostty terminfo
   * entry is rarely installed on machines running an IDE. */
  static const char terminfo[] = "xterm-256color";
  GhosttyString tn = {(const uint8_t *)terminfo, sizeof(terminfo) - 1};
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_TERMINFO_NAME, &tn);

  /* Like the Ghostty app: cluster graphemes (emoji ZWJ sequences, flags...)
   * into single cells using Unicode widths. */
  GhosttyTerminalModeConfig grapheme = {GHOSTTY_MODE_GRAPHEME_CLUSTER, true};
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_MODE_DEFAULT, &grapheme);

  size_t scrollback = 10 * 1024 * 1024;
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES,
                       &scrollback);

  t->force_full = true;
  return t;
}

void gjb_write(gjb_term *t, const uint8_t *data, int32_t len) {
  if (t == NULL || data == NULL || len <= 0) return;
  ghostty_terminal_vt_write(t->term, data, (size_t)len);
}

void gjb_reset(gjb_term *t) {
  if (t == NULL) return;
  ghostty_terminal_reset(t->term);
  t->held = false;
  t->force_full = true;
}

int32_t gjb_resize(gjb_term *t, int32_t cols, int32_t rows, int32_t cell_w,
                   int32_t cell_h, int32_t pad_left, int32_t pad_top) {
  if (t == NULL) return GHOSTTY_INVALID_VALUE;
  if (cols < 1) cols = 1;
  if (rows < 1) rows = 1;
  if (cols > 0xffff) cols = 0xffff;
  if (rows > 0xffff) rows = 0xffff;
  if (cell_w < 1) cell_w = 1;
  if (cell_h < 1) cell_h = 1;
  t->cell_w = cell_w;
  t->cell_h = cell_h;
  t->pad_left = pad_left < 0 ? 0 : pad_left;
  t->pad_top = pad_top < 0 ? 0 : pad_top;
  GhosttyResult r = ghostty_terminal_resize(t->term, (uint16_t)cols,
                                            (uint16_t)rows, (uint32_t)cell_w,
                                            (uint32_t)cell_h);
  if (r == GHOSTTY_SUCCESS) {
    t->cols = cols;
    t->rows = rows;
  }
  t->force_full = true;
  return r;
}

/* ---- Configuration ------------------------------------------------------ */

void gjb_set_colors(gjb_term *t, int32_t fg, int32_t bg, int32_t cursor,
                    const int32_t *palette, int32_t palette_len,
                    int32_t generate_256) {
  if (t == NULL) return;
  GhosttyColorRgb c;
  if (fg < 0) {
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, NULL);
  } else {
    c = from_rgb(fg);
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_FOREGROUND, &c);
  }
  if (bg < 0) {
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, NULL);
  } else {
    c = from_rgb(bg);
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_BACKGROUND, &c);
  }
  if (cursor < 0) {
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, NULL);
  } else {
    c = from_rgb(cursor);
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_CURSOR, &c);
  }

  if (palette != NULL && palette_len > 0) {
    GhosttyColorRgb pal[256];
    ghostty_color_palette_default(pal);
    int32_t n = palette_len > 256 ? 256 : palette_len;
    for (int32_t i = 0; i < n; i++) pal[i] = from_rgb(palette[i]);

    if (generate_256 && n >= 16 && n < 256) {
      /* Derive the 6x6x6 cube and grayscale ramp from the base 16 colors and
       * the background/foreground so 256-color apps match the theme. */
      GhosttyColorPaletteMask skip = {{0, 0, 0, 0}};
      GhosttyColorRgb gbg = bg < 0 ? pal[0] : from_rgb(bg);
      GhosttyColorRgb gfg = fg < 0 ? pal[15] : from_rgb(fg);
      GhosttyColorRgb out[256];
      ghostty_color_palette_generate(pal, &skip, &gbg, &gfg, false, out);
      for (int i = n; i < 256; i++) pal[i] = out[i];
    }
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, pal);
  } else if (palette != NULL) {
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_COLOR_PALETTE, NULL);
  }
  t->force_full = true;
}

int32_t gjb_set_option(gjb_term *t, int32_t option, int64_t value) {
  if (t == NULL) return GHOSTTY_INVALID_VALUE;
  switch (option) {
    case GJB_OPT_SCROLLBACK_BYTES: {
      if (value < 0)
        return ghostty_terminal_set(
            t->term, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, NULL);
      size_t v = (size_t)value;
      return ghostty_terminal_set(
          t->term, GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_BYTES, &v);
    }
    case GJB_OPT_CURSOR_STYLE: {
      GhosttyTerminalCursorStyle s = (GhosttyTerminalCursorStyle)value;
      return ghostty_terminal_set(
          t->term, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_STYLE, &s);
    }
    case GJB_OPT_CURSOR_BLINK: {
      bool b = value != 0;
      return ghostty_terminal_set(
          t->term, GHOSTTY_TERMINAL_OPT_DEFAULT_CURSOR_BLINK, &b);
    }
    case GJB_OPT_RESIZE_PULL_SCROLLBACK: {
      bool b = value != 0;
      return ghostty_terminal_set(
          t->term, GHOSTTY_TERMINAL_OPT_RESIZE_PULL_SCROLLBACK, &b);
    }
    case GJB_OPT_OPTION_AS_ALT:
      t->option_as_alt = (GhosttyOptionAsAlt)value;
      return GHOSTTY_SUCCESS;
    case GJB_OPT_TITLE_REPORT: {
      bool b = value != 0;
      return ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_TITLE_REPORT,
                                  &b);
    }
    case GJB_OPT_COLOR_SCHEME: {
      int32_t scheme = value ? GHOSTTY_COLOR_SCHEME_DARK
                             : GHOSTTY_COLOR_SCHEME_LIGHT;
      if (scheme != t->color_scheme) {
        t->color_scheme = scheme;
        /* Programs that opted into mode 2031 want to hear about changes. */
        GhosttyTerminalModeConfig m = {GHOSTTY_MODE_COLOR_SCHEME_REPORT, false};
        if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_MODE, &m) ==
                GHOSTTY_SUCCESS &&
            m.value) {
          char buf[32];
          size_t n = 0;
          if (ghostty_color_scheme_report_encode((GhosttyColorScheme)scheme,
                                                 buf, sizeof(buf),
                                                 &n) == GHOSTTY_SUCCESS)
            emit(t, GJB_EVENT_WRITE_PTY, 0, (const uint8_t *)buf, n);
        }
      }
      return GHOSTTY_SUCCESS;
    }
    case GJB_OPT_CLICK_INTERVAL_MS:
      t->click_interval_ns = value <= 0 ? 0 : (uint64_t)value * 1000000ULL;
      return GHOSTTY_SUCCESS;
    case GJB_OPT_FOCUSED:
      t->force_full = true;
      return GHOSTTY_SUCCESS;
    default:
      return GHOSTTY_INVALID_VALUE;
  }
}

/* ---- Queries ------------------------------------------------------------ */

static bool get_bool(gjb_term *t, GhosttyTerminalData key) {
  bool v = false;
  ghostty_terminal_get(t->term, key, &v);
  return v;
}

static bool mode_value(gjb_term *t, GhosttyMode mode) {
  GhosttyTerminalModeConfig m = {mode, false};
  if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_MODE, &m) !=
      GHOSTTY_SUCCESS)
    return false;
  return m.value;
}

int64_t gjb_query(gjb_term *t, int32_t what, int32_t arg) {
  if (t == NULL) return 0;
  switch (what) {
    case GJB_Q_COLS:
      return t->cols;
    case GJB_Q_ROWS:
      return t->rows;
    case GJB_Q_MOUSE_TRACKING:
      return get_bool(t, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING);
    case GJB_Q_ALT_SCREEN: {
      GhosttyTerminalScreen s = GHOSTTY_TERMINAL_SCREEN_PRIMARY;
      ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_ACTIVE_SCREEN, &s);
      return s == GHOSTTY_TERMINAL_SCREEN_ALTERNATE;
    }
    case GJB_Q_VIEWPORT_AT_BOTTOM:
      return get_bool(t, GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE);
    case GJB_Q_MODE:
      return mode_value(t, arg < 0 ? ghostty_mode_new((uint16_t)-arg, true)
                                   : ghostty_mode_new((uint16_t)arg, false));
    case GJB_Q_SCROLLBACK_ROWS: {
      size_t v = 0;
      ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SCROLLBACK_ROWS, &v);
      return (int64_t)v;
    }
    case GJB_Q_CURSOR_X: {
      uint16_t v = 0;
      ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_CURSOR_X, &v);
      return v;
    }
    case GJB_Q_CURSOR_Y: {
      uint16_t v = 0;
      ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_CURSOR_Y, &v);
      return v;
    }
    case GJB_Q_HAS_SELECTION: {
      GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
      return ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SELECTION,
                                  &sel) == GHOSTTY_SUCCESS;
    }
    case GJB_Q_MOUSE_SHAPE: {
      GhosttyMouseShape s = GHOSTTY_MOUSE_SHAPE_TEXT;
      ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_MOUSE_SHAPE, &s);
      return s;
    }
    default:
      return 0;
  }
}

int32_t gjb_query_string(gjb_term *t, int32_t what, uint8_t *out,
                         int32_t cap) {
  if (t == NULL) return 0;
  GhosttyString s = {0};
  GhosttyTerminalData key =
      what == 2 ? GHOSTTY_TERMINAL_DATA_PWD : GHOSTTY_TERMINAL_DATA_TITLE;
  if (ghostty_terminal_get(t->term, key, &s) != GHOSTTY_SUCCESS) return 0;
  return copy_out(s.ptr, s.len, out, cap);
}

/* ---- Viewport ----------------------------------------------------------- */

void gjb_scroll(gjb_term *t, int32_t kind, int64_t value) {
  if (t == NULL) return;
  GhosttyTerminalScrollViewport sv;
  memset(&sv, 0, sizeof(sv));
  switch (kind) {
    case GJB_SCROLL_TOP:
      sv.tag = GHOSTTY_SCROLL_VIEWPORT_TOP;
      break;
    case GJB_SCROLL_BOTTOM:
      sv.tag = GHOSTTY_SCROLL_VIEWPORT_BOTTOM;
      break;
    case GJB_SCROLL_DELTA:
      sv.tag = GHOSTTY_SCROLL_VIEWPORT_DELTA;
      sv.value.delta = (intptr_t)value;
      break;
    case GJB_SCROLL_ROW:
      sv.tag = GHOSTTY_SCROLL_VIEWPORT_ROW;
      sv.value.row = value < 0 ? 0 : (size_t)value;
      break;
    default:
      return;
  }
  ghostty_terminal_scroll_viewport(t->term, sv);
  t->force_full = true;
}

/* ---- Search helpers ----------------------------------------------------- */

/* Marks [start, end] (linear, inclusive, viewport coordinates) in the cell
 * buffer with the given attribute bit. */
static void mark_range(gjb_term *t, int32_t *cells, int32_t cols, int32_t rows,
                       const GhosttySelection *sel, int32_t bit) {
  GhosttyPointCoordinate a, b;
  GhosttySelection ordered = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (ghostty_terminal_selection_ordered(t->term, sel,
                                         GHOSTTY_SELECTION_ORDER_FORWARD,
                                         &ordered) != GHOSTTY_SUCCESS)
    ordered = *sel;
  if (ghostty_terminal_point_from_grid_ref(t->term, &ordered.start,
                                           GHOSTTY_POINT_TAG_VIEWPORT,
                                           &a) != GHOSTTY_SUCCESS)
    return;
  if (ghostty_terminal_point_from_grid_ref(t->term, &ordered.end,
                                           GHOSTTY_POINT_TAG_VIEWPORT,
                                           &b) != GHOSTTY_SUCCESS)
    return;
  for (uint32_t y = a.y; y <= b.y && y < (uint32_t)rows; y++) {
    int32_t x0 = y == a.y ? a.x : 0;
    int32_t x1 = y == b.y ? b.x : cols - 1;
    if (x1 >= cols) x1 = cols - 1;
    for (int32_t x = x0; x <= x1; x++)
      cells[((int32_t)y * cols + x) * GJB_CELL_INTS + GJB_C_ATTRS] |= bit;
  }
}

/* Drives the search forward a bounded amount. Returns true if more work is
 * pending. */
static bool search_step(gjb_term *t) {
  if (t->search == NULL) return false;
  ghostty_search_feed(t->search);
  GhosttySearchStatus status = GHOSTTY_SEARCH_STATUS_COMPLETE;
  for (int i = 0; i < SEARCH_TICKS_PER_FRAME; i++) {
    if (ghostty_search_tick(t->search, &status) != GHOSTTY_SUCCESS) break;
    if (status != GHOSTTY_SEARCH_STATUS_RUNNING) break;
  }
  return status != GHOSTTY_SEARCH_STATUS_COMPLETE;
}

static void search_highlight(gjb_term *t, int32_t *cells, int32_t cols,
                             int32_t rows) {
  GhosttySelectionBuffer buf = {t->search_matches, t->search_matches_cap, 0};
  GhosttyResult r = ghostty_search_get(
      t->search, GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES, &buf);
  if (r == GHOSTTY_OUT_OF_SPACE) {
    size_t cap = buf.len + 16;
    GhosttySelection *m = realloc(t->search_matches, cap * sizeof(*m));
    if (m == NULL) return;
    for (size_t i = 0; i < cap; i++) {
      memset(&m[i], 0, sizeof(m[i]));
      m[i].size = sizeof(m[i]);
    }
    t->search_matches = m;
    t->search_matches_cap = cap;
    buf.ptr = m;
    buf.cap = cap;
    buf.len = 0;
    r = ghostty_search_get(t->search, GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES,
                           &buf);
  }
  if (r == GHOSTTY_SUCCESS) {
    for (size_t i = 0; i < buf.len; i++)
      mark_range(t, cells, cols, rows, &buf.ptr[i], GJB_A_SEARCH_MATCH);
  }

  GhosttySelection selected = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (ghostty_search_get(t->search, GHOSTTY_SEARCH_DATA_SELECTED_MATCH,
                         &selected) == GHOSTTY_SUCCESS)
    mark_range(t, cells, cols, rows, &selected, GJB_A_SEARCH_SELECTED);
}

/* ---- Snapshot ----------------------------------------------------------- */

static int32_t style_attrs(const GhosttyStyle *s) {
  int32_t a = 0;
  if (s->bold) a |= GJB_A_BOLD;
  if (s->italic) a |= GJB_A_ITALIC;
  if (s->faint) a |= GJB_A_FAINT;
  if (s->blink) a |= GJB_A_BLINK;
  if (s->inverse) a |= GJB_A_INVERSE;
  if (s->invisible) a |= GJB_A_INVISIBLE;
  if (s->strikethrough) a |= GJB_A_STRIKETHROUGH;
  if (s->overline) a |= GJB_A_OVERLINE;
  int32_t ul = s->underline;
  if (ul < 0) ul = 0;
  if (ul > 7) ul = 1;
  a |= (ul << GJB_A_UNDERLINE_SHIFT) & GJB_A_UNDERLINE_MASK;
  return a;
}

static int32_t resolve_style_color(GhosttyStyleColor c,
                                   const GhosttyRenderStateColors *colors) {
  switch (c.tag) {
    case GHOSTTY_STYLE_COLOR_RGB:
      return rgb(c.value.rgb) | GJB_COLOR_SET;
    case GHOSTTY_STYLE_COLOR_PALETTE:
      return rgb(colors->palette[c.value.palette]) | GJB_COLOR_SET;
    default:
      return 0;
  }
}

static void fill_header(gjb_term *t, int32_t *buf,
                        const GhosttyRenderStateColors *colors,
                        int32_t dirty) {
  memset(buf, 0, GJB_HEADER_INTS * sizeof(int32_t));
  buf[GJB_H_MAGIC] = GJB_FRAME_MAGIC;
  buf[GJB_H_DIRTY] = dirty;
  buf[GJB_H_FRAME_INTS] = GJB_HEADER_INTS;
  buf[GJB_H_SEARCH_TOTAL] = -1;
  buf[GJB_H_SEARCH_SELECTED] = -1;

  uint16_t cols = 0, rows = 0;
  ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_COLS, &cols);
  ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_ROWS, &rows);
  buf[GJB_H_COLS] = cols;
  buf[GJB_H_ROWS] = rows;

  buf[GJB_H_FG] = rgb(colors->foreground);
  buf[GJB_H_BG] = rgb(colors->background);
  buf[GJB_H_CURSOR_COLOR] =
      rgb(colors->cursor) | (colors->cursor_has_value ? GJB_COLOR_SET : 0);

  GhosttyRenderStateCursor cur = GHOSTTY_INIT_SIZED(GhosttyRenderStateCursor);
  if (ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_CURSOR,
                               &cur) == GHOSTTY_SUCCESS) {
    int32_t f = 0;
    if (cur.visible) f |= GJB_CURSOR_F_VISIBLE;
    if (cur.blinking) f |= GJB_CURSOR_F_BLINKING;
    if (cur.wide_tail) f |= GJB_CURSOR_F_WIDE_TAIL;
    if (cur.viewport_has_value) f |= GJB_CURSOR_F_IN_VIEWPORT;
    if (cur.password_input) f |= GJB_CURSOR_F_PASSWORD;
    buf[GJB_H_CURSOR_FLAGS] = f;
    buf[GJB_H_CURSOR_X] = cur.viewport_x;
    buf[GJB_H_CURSOR_Y] = cur.viewport_y;
    buf[GJB_H_CURSOR_STYLE] = (int32_t)cur.visual_style;
  }

  GhosttyTerminalScrollbar sb = {0, 0, 0};
  if (ghostty_terminal_get(t->term, GHOSTTY_TERMINAL_DATA_SCROLLBAR, &sb) ==
      GHOSTTY_SUCCESS) {
    buf[GJB_H_SCROLL_TOTAL] = sb.total > INT32_MAX ? INT32_MAX : (int32_t)sb.total;
    buf[GJB_H_SCROLL_OFFSET] =
        sb.offset > INT32_MAX ? INT32_MAX : (int32_t)sb.offset;
    buf[GJB_H_SCROLL_LEN] = sb.len > INT32_MAX ? INT32_MAX : (int32_t)sb.len;
  }

  int32_t flags = 0;
  if (get_bool(t, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING))
    flags |= GJB_F_MOUSE_TRACKING;
  if (gjb_query(t, GJB_Q_ALT_SCREEN, 0)) flags |= GJB_F_ALT_SCREEN;
  if (get_bool(t, GHOSTTY_TERMINAL_DATA_VIEWPORT_ACTIVE))
    flags |= GJB_F_VIEWPORT_AT_BOTTOM;
  if (t->held) flags |= GJB_F_RENDER_HELD;
  if (mode_value(t, GHOSTTY_MODE_REVERSE_COLORS)) flags |= GJB_F_REVERSE_COLORS;
  buf[GJB_H_FLAGS] = flags;
}

int32_t gjb_snapshot(gjb_term *t, int32_t *buf, int32_t cap) {
  if (t == NULL || buf == NULL) return GHOSTTY_INVALID_VALUE;
  if (cap < GJB_HEADER_INTS) return -GJB_HEADER_INTS;

  if (t->held && now_ns() - t->hold_start_ns > HOLD_TIMEOUT_NS) {
    /* The program held rendering too long; take control back. */
    GhosttyTerminalModeConfig m = {GHOSTTY_MODE_SYNC_OUTPUT, false};
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_MODE, &m);
    t->held = false;
  }
  if (!t->held) {
    GhosttyResult r = ghostty_render_state_update(t->rs, t->term);
    if (r != GHOSTTY_SUCCESS) return r;
  }

  bool search_pending = search_step(t);

  GhosttyRenderStateDirty dirty = GHOSTTY_RENDER_STATE_DIRTY_FALSE;
  ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_DIRTY, &dirty);
  if (t->force_full || t->search != NULL) dirty = GHOSTTY_RENDER_STATE_DIRTY_FULL;

  GhosttyRenderStateColors colors = GHOSTTY_INIT_SIZED(GhosttyRenderStateColors);
  ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_COLORS, &colors);

  fill_header(t, buf, &colors, (int32_t)dirty);
  if (search_pending) buf[GJB_H_FLAGS] |= GJB_F_SEARCH_PENDING;
  if (dirty == GHOSTTY_RENDER_STATE_DIRTY_FALSE) return GJB_HEADER_INTS;

  int32_t cols = buf[GJB_H_COLS], rows = buf[GJB_H_ROWS];
  int64_t cell_ints = (int64_t)cols * rows * GJB_CELL_INTS;
  int64_t need = GJB_HEADER_INTS + cell_ints;
  if (need > cap) return (int32_t)-need;

  int32_t *cells = buf + GJB_HEADER_INTS;
  memset(cells, 0, (size_t)cell_ints * sizeof(int32_t));
  int64_t pool = need; /* grapheme pool write position */
  bool overflow = false;

  if (ghostty_render_state_get(t->rs, GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR,
                               &t->row_iter) != GHOSTTY_SUCCESS)
    return GHOSTTY_INVALID_VALUE;

  int32_t y = 0;
  while (y < rows && ghostty_render_state_row_iterator_next(t->row_iter)) {
    GhosttyRenderStateRowSelection rsel =
        GHOSTTY_INIT_SIZED(GhosttyRenderStateRowSelection);
    bool has_sel = ghostty_render_state_row_get(
                       t->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_SELECTION,
                       &rsel) == GHOSTTY_SUCCESS;

    if (ghostty_render_state_row_get(t->row_iter, GHOSTTY_RENDER_STATE_ROW_DATA_CELLS,
                                     &t->cells) != GHOSTTY_SUCCESS) {
      y++;
      continue;
    }

    int32_t x = 0;
    while (x < cols && ghostty_render_state_row_cells_next(t->cells)) {
      int32_t *c = cells + ((int64_t)y * cols + x) * GJB_CELL_INTS;
      int32_t attrs = 0;

      GhosttyCell raw = 0;
      if (ghostty_render_state_row_cells_get(
              t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_RAW, &raw) ==
          GHOSTTY_SUCCESS) {
        GhosttyCellWide wide = GHOSTTY_CELL_WIDE_NARROW;
        bool link = false;
        ghostty_cell_get(raw, GHOSTTY_CELL_DATA_WIDE, &wide);
        ghostty_cell_get(raw, GHOSTTY_CELL_DATA_HAS_HYPERLINK, &link);
        attrs |= ((int32_t)wide << GJB_A_WIDE_SHIFT) & GJB_A_WIDE_MASK;
        if (link) attrs |= GJB_A_HYPERLINK;
      }

      uint32_t glen = 0;
      ghostty_render_state_row_cells_get(
          t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_LEN, &glen);
      if (glen > 0) {
        uint32_t cps[MAX_GRAPHEME];
        uint32_t *cp = cps;
        uint32_t *heap = NULL;
        if (glen > MAX_GRAPHEME) {
          heap = malloc(glen * sizeof(uint32_t));
          cp = heap;
        }
        if (cp != NULL &&
            ghostty_render_state_row_cells_get(
                t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_BUF,
                cp) == GHOSTTY_SUCCESS) {
          c[GJB_C_CODEPOINT] = (int32_t)cp[0];
          if (glen > 1) {
            attrs |= GJB_A_GRAPHEME;
            if (pool + 2 + glen <= cap) {
              buf[pool] = y * cols + x;
              buf[pool + 1] = (int32_t)glen;
              for (uint32_t i = 0; i < glen; i++)
                buf[pool + 2 + i] = (int32_t)cp[i];
            } else {
              overflow = true;
            }
            pool += 2 + glen;
          }
        }
        free(heap);
      }

      bool styled = false;
      ghostty_render_state_row_cells_get(
          t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_HAS_STYLING, &styled);
      if (styled) {
        GhosttyStyle style = GHOSTTY_INIT_SIZED(GhosttyStyle);
        if (ghostty_render_state_row_cells_get(
                t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE, &style) ==
            GHOSTTY_SUCCESS) {
          attrs |= style_attrs(&style);
          c[GJB_C_UNDERLINE] = resolve_style_color(style.underline_color, &colors);
        }
      }

      GhosttyColorRgb col;
      if (ghostty_render_state_row_cells_get(
              t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR, &col) ==
          GHOSTTY_SUCCESS)
        c[GJB_C_FG] = rgb(col) | GJB_COLOR_SET;
      if (ghostty_render_state_row_cells_get(
              t->cells, GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR, &col) ==
          GHOSTTY_SUCCESS)
        c[GJB_C_BG] = rgb(col) | GJB_COLOR_SET;

      if (has_sel && x >= rsel.start_x && x <= rsel.end_x)
        attrs |= GJB_A_SELECTED;

      c[GJB_C_ATTRS] = attrs;
      x++;
    }
    y++;
  }

  if (overflow) return (int32_t)-pool;

  if (t->search != NULL) {
    search_highlight(t, cells, cols, rows);
    size_t total = 0, selected = 0;
    if (ghostty_search_get(t->search, GHOSTTY_SEARCH_DATA_TOTAL_MATCHES,
                           &total) == GHOSTTY_SUCCESS)
      buf[GJB_H_SEARCH_TOTAL] = total > INT32_MAX ? INT32_MAX : (int32_t)total;
    if (ghostty_search_get(t->search, GHOSTTY_SEARCH_DATA_SELECTED_INDEX,
                           &selected) == GHOSTTY_SUCCESS)
      buf[GJB_H_SEARCH_SELECTED] = (int32_t)selected;
  }

  buf[GJB_H_GRAPHEME_OFFSET] = (int32_t)need;
  buf[GJB_H_GRAPHEME_LEN] = (int32_t)(pool - need);
  buf[GJB_H_FRAME_INTS] = (int32_t)pool;

  ghostty_render_state_clean(t->rs);
  t->force_full = false;
  return (int32_t)pool;
}

void gjb_invalidate(gjb_term *t) {
  if (t != NULL) t->force_full = true;
}

/* ---- Input encoding ----------------------------------------------------- */

int32_t gjb_encode_key(gjb_term *t, int32_t action, int32_t key, int32_t mods,
                       int32_t consumed_mods, int32_t unshifted,
                       const uint8_t *utf8, int32_t utf8_len, uint8_t *out,
                       int32_t cap) {
  if (t == NULL || out == NULL || cap <= 0) return GHOSTTY_INVALID_VALUE;
  ghostty_key_encoder_setopt_from_terminal(t->key_encoder, t->term);
  ghostty_key_encoder_setopt(t->key_encoder,
                             GHOSTTY_KEY_ENCODER_OPT_MACOS_OPTION_AS_ALT,
                             &t->option_as_alt);

  ghostty_key_event_set_action(t->key_event, (GhosttyKeyAction)action);
  ghostty_key_event_set_key(t->key_event, (GhosttyKey)key);
  ghostty_key_event_set_mods(t->key_event, (GhosttyMods)mods);
  ghostty_key_event_set_consumed_mods(t->key_event, (GhosttyMods)consumed_mods);
  ghostty_key_event_set_unshifted_codepoint(t->key_event, (uint32_t)unshifted);
  ghostty_key_event_set_composing(t->key_event, false);
  ghostty_key_event_set_utf8(t->key_event, (const char *)utf8,
                             utf8 != NULL && utf8_len > 0 ? (size_t)utf8_len : 0);

  size_t written = 0;
  GhosttyResult r = ghostty_key_encoder_encode(
      t->key_encoder, t->key_event, (char *)out, (size_t)cap, &written);
  /* Don't keep a pointer to the caller's text around. */
  ghostty_key_event_set_utf8(t->key_event, NULL, 0);
  if (r != GHOSTTY_SUCCESS) return r;
  return (int32_t)written;
}

int32_t gjb_encode_mouse(gjb_term *t, int32_t action, int32_t button,
                         int32_t mods, float x, float y,
                         int32_t any_button_pressed, uint8_t *out,
                         int32_t cap) {
  if (t == NULL || out == NULL || cap <= 0) return GHOSTTY_INVALID_VALUE;
  if (!get_bool(t, GHOSTTY_TERMINAL_DATA_MOUSE_TRACKING)) return 0;

  ghostty_mouse_encoder_setopt_from_terminal(t->mouse_encoder, t->term);
  GhosttyMouseEncoderSize size = GHOSTTY_INIT_SIZED(GhosttyMouseEncoderSize);
  size.cell_width = (uint32_t)t->cell_w;
  size.cell_height = (uint32_t)t->cell_h;
  size.padding_left = (uint32_t)t->pad_left;
  size.padding_top = (uint32_t)t->pad_top;
  size.screen_width = (uint32_t)(t->pad_left + t->cols * t->cell_w);
  size.screen_height = (uint32_t)(t->pad_top + t->rows * t->cell_h);
  ghostty_mouse_encoder_setopt(t->mouse_encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE,
                               &size);
  bool pressed = any_button_pressed != 0;
  ghostty_mouse_encoder_setopt(t->mouse_encoder,
                               GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED,
                               &pressed);
  bool track_last_cell = true;
  ghostty_mouse_encoder_setopt(t->mouse_encoder,
                               GHOSTTY_MOUSE_ENCODER_OPT_TRACK_LAST_CELL,
                               &track_last_cell);

  ghostty_mouse_event_set_action(t->mouse_event, (GhosttyMouseAction)action);
  if (button > 0)
    ghostty_mouse_event_set_button(t->mouse_event, (GhosttyMouseButton)button);
  else
    ghostty_mouse_event_clear_button(t->mouse_event);
  ghostty_mouse_event_set_mods(t->mouse_event, (GhosttyMods)mods);
  GhosttyMousePosition pos = {x, y};
  ghostty_mouse_event_set_position(t->mouse_event, pos);

  size_t written = 0;
  GhosttyResult r = ghostty_mouse_encoder_encode(
      t->mouse_encoder, t->mouse_event, (char *)out, (size_t)cap, &written);
  if (r != GHOSTTY_SUCCESS) return r;
  return (int32_t)written;
}

int32_t gjb_encode_focus(gjb_term *t, int32_t gained, uint8_t *out,
                         int32_t cap) {
  if (t == NULL || out == NULL || cap <= 0) return GHOSTTY_INVALID_VALUE;
  if (!mode_value(t, GHOSTTY_MODE_FOCUS_EVENT)) return 0;
  size_t written = 0;
  GhosttyResult r = ghostty_focus_encode(
      gained ? GHOSTTY_FOCUS_GAINED : GHOSTTY_FOCUS_LOST, (char *)out,
      (size_t)cap, &written);
  if (r != GHOSTTY_SUCCESS) return r;
  return (int32_t)written;
}

typedef struct {
  const uint8_t *data;
  size_t len;
} paste_src;

static bool paste_read(void *ud, GhosttyString mime, GhosttyWriter writer) {
  (void)mime;
  paste_src *src = (paste_src *)ud;
  if (src->len == 0) return true;
  return writer.write(writer.userdata, src->data, src->len);
}

int32_t gjb_paste(gjb_term *t, const uint8_t *utf8, int32_t len,
                  int32_t allow_unsafe) {
  if (t == NULL || utf8 == NULL || len < 0) return GHOSTTY_INVALID_VALUE;
  paste_src src = {utf8, (size_t)len};
  static const char text_plain[] = "text/plain";
  GhosttyString mimes[1] = {{(const uint8_t *)text_plain, sizeof(text_plain) - 1}};
  GhosttyPaste paste;
  memset(&paste, 0, sizeof(paste));
  paste.size = sizeof(paste);
  paste.location = GHOSTTY_CLIPBOARD_LOCATION_STANDARD;
  /* SOURCE_TEXT: we always hand over the text directly; we don't implement
   * the Kitty clipboard read protocol that paste events would require. */
  paste.source = GHOSTTY_PASTE_SOURCE_TEXT;
  paste.mimes = mimes;
  paste.mimes_len = 1;
  paste.reader.read = paste_read;
  paste.reader.userdata = &src;
  paste.allow_unsafe = allow_unsafe != 0;

  bool written = false;
  GhosttyResult r = ghostty_terminal_paste(t->term, &paste, &written);
  if (r == GHOSTTY_REJECTED) return 1;
  return r == GHOSTTY_SUCCESS ? 0 : r;
}

/* ---- Selection ---------------------------------------------------------- */

static int32_t clamp_i32(int32_t v, int32_t lo, int32_t hi) {
  return v < lo ? lo : (v > hi ? hi : v);
}

/* Maps a surface position to the viewport cell under it (clamped). */
static bool ref_at(gjb_term *t, double x, double y, GhosttyGridRef *out) {
  int32_t col = (int32_t)((x - t->pad_left) / t->cell_w);
  int32_t row = (int32_t)((y - t->pad_top) / t->cell_h);
  if (y < t->pad_top) row = 0;
  if (x < t->pad_left) col = 0;
  GhosttyPoint p;
  memset(&p, 0, sizeof(p));
  p.tag = GHOSTTY_POINT_TAG_VIEWPORT;
  p.value.coordinate.x = (uint16_t)clamp_i32(col, 0, t->cols - 1);
  p.value.coordinate.y = (uint32_t)clamp_i32(row, 0, t->rows - 1);
  memset(out, 0, sizeof(*out));
  out->size = sizeof(*out);
  return ghostty_terminal_grid_ref(t->term, p, out) == GHOSTTY_SUCCESS;
}

int32_t gjb_select_event(gjb_term *t, int32_t type, double x, double y,
                         int64_t time_ns, int32_t rectangle) {
  if (t == NULL || type < 0 || type > 3) return 0;
  GhosttySelectionGestureEvent ev = t->gesture_events[type];

  GhosttyGridRef ref;
  bool have_ref = ref_at(t, x, y, &ref);
  ghostty_selection_gesture_event_set(
      ev, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REF, have_ref ? &ref : NULL);

  if (type != GJB_SEL_RELEASE) {
    GhosttySurfacePosition pos = {x, y};
    ghostty_selection_gesture_event_set(
        ev, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_POSITION, &pos);
  }
  if (type == GJB_SEL_PRESS) {
    uint64_t ts = (uint64_t)time_ns;
    ghostty_selection_gesture_event_set(
        ev, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_TIME_NS, &ts);
    ghostty_selection_gesture_event_set(
        ev, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_INTERVAL_NS,
        &t->click_interval_ns);
    double distance = (double)t->cell_w;
    ghostty_selection_gesture_event_set(
        ev, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_REPEAT_DISTANCE, &distance);
  }
  if (type == GJB_SEL_DRAG || type == GJB_SEL_AUTOSCROLL_TICK) {
    bool rect = rectangle != 0;
    ghostty_selection_gesture_event_set(
        ev, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_RECTANGLE, &rect);
    GhosttySelectionGestureGeometry geo = {
        (uint32_t)t->cols, (uint32_t)t->cell_w, (uint32_t)t->pad_left,
        (uint32_t)(t->pad_top + t->rows * t->cell_h)};
    ghostty_selection_gesture_event_set(
        ev, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_GEOMETRY, &geo);
  }
  if (type == GJB_SEL_AUTOSCROLL_TICK) {
    GhosttyPointCoordinate vp = {
        (uint16_t)clamp_i32((int32_t)((x - t->pad_left) / t->cell_w), 0,
                            t->cols - 1),
        (uint32_t)clamp_i32((int32_t)((y - t->pad_top) / t->cell_h), 0,
                            t->rows - 1)};
    ghostty_selection_gesture_event_set(
        ev, GHOSTTY_SELECTION_GESTURE_EVENT_OPT_VIEWPORT, &vp);
  }

  int32_t result = 0;
  GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
  GhosttyResult r = ghostty_selection_gesture_event(
      t->gesture, t->term, ev, type == GJB_SEL_RELEASE ? NULL : &sel);
  if (r == GHOSTTY_SUCCESS && type != GJB_SEL_RELEASE) {
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
    result |= GJB_SEL_R_CHANGED;
  } else if (r == GHOSTTY_NO_VALUE && type == GJB_SEL_PRESS) {
    /* A plain single click clears any existing selection. */
    if (gjb_query(t, GJB_Q_HAS_SELECTION, 0)) {
      ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
      result |= GJB_SEL_R_CHANGED;
    }
  }

  GhosttySelectionGestureAutoscroll as = GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_NONE;
  if (ghostty_selection_gesture_get(t->gesture, t->term,
                                    GHOSTTY_SELECTION_GESTURE_DATA_AUTOSCROLL,
                                    &as) == GHOSTTY_SUCCESS) {
    if (as == GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_UP)
      result |= GJB_SEL_R_AUTOSCROLL_UP;
    else if (as == GHOSTTY_SELECTION_GESTURE_AUTOSCROLL_DOWN)
      result |= GJB_SEL_R_AUTOSCROLL_DOWN;
  }

  if (gjb_query(t, GJB_Q_HAS_SELECTION, 0)) result |= GJB_SEL_R_HAS;
  /* Selection changes don't mark the render state dirty by themselves. */
  if (result & (GJB_SEL_R_CHANGED | GJB_SEL_R_AUTOSCROLL_UP |
                GJB_SEL_R_AUTOSCROLL_DOWN))
    t->force_full = true;
  return result;
}

void gjb_select_clear(gjb_term *t) {
  if (t == NULL) return;
  ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SELECTION, NULL);
  ghostty_selection_gesture_reset(t->gesture, t->term);
  t->force_full = true;
}

void gjb_select_all(gjb_term *t) {
  if (t == NULL) return;
  GhosttySelection sel = GHOSTTY_INIT_SIZED(GhosttySelection);
  if (ghostty_terminal_select_all(t->term, &sel) == GHOSTTY_SUCCESS)
    ghostty_terminal_set(t->term, GHOSTTY_TERMINAL_OPT_SELECTION, &sel);
  t->force_full = true;
}

int32_t gjb_selection_text(gjb_term *t, uint8_t *out, int32_t cap) {
  if (t == NULL) return -1;
  GhosttyTerminalSelectionFormatOptions opts =
      GHOSTTY_INIT_SIZED(GhosttyTerminalSelectionFormatOptions);
  opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  opts.unwrap = true;
  opts.trim = true;
  opts.selection = NULL; /* the active selection */

  uint8_t *ptr = NULL;
  size_t len = 0;
  GhosttyResult r =
      ghostty_terminal_selection_format_alloc(t->term, NULL, opts, &ptr, &len);
  if (r != GHOSTTY_SUCCESS) return -1;
  int32_t n = copy_out(ptr, len, out, cap);
  ghostty_free(NULL, ptr, len);
  return n;
}

int32_t gjb_screen_text(gjb_term *t, uint8_t *out, int32_t cap) {
  if (t == NULL) return -1;
  GhosttyFormatterTerminalOptions opts =
      GHOSTTY_INIT_SIZED(GhosttyFormatterTerminalOptions);
  opts.emit = GHOSTTY_FORMATTER_FORMAT_PLAIN;
  opts.unwrap = true;
  opts.trim = true;
  opts.extra.size = sizeof(opts.extra);
  opts.extra.screen.size = sizeof(opts.extra.screen);

  GhosttyFormatter f = NULL;
  if (ghostty_formatter_terminal_new(NULL, &f, t->term, opts) != GHOSTTY_SUCCESS)
    return -1;
  uint8_t *ptr = NULL;
  size_t len = 0;
  GhosttyResult r = ghostty_formatter_format_alloc(f, NULL, &ptr, &len);
  ghostty_formatter_free(f);
  if (r != GHOSTTY_SUCCESS) return -1;
  int32_t n = copy_out(ptr, len, out, cap);
  ghostty_free(NULL, ptr, len);
  return n;
}

int32_t gjb_hyperlink_at(gjb_term *t, int32_t col, int32_t row, uint8_t *out,
                         int32_t cap) {
  if (t == NULL || col < 0 || row < 0 || col >= t->cols || row >= t->rows)
    return -1;
  GhosttyPoint p;
  memset(&p, 0, sizeof(p));
  p.tag = GHOSTTY_POINT_TAG_VIEWPORT;
  p.value.coordinate.x = (uint16_t)col;
  p.value.coordinate.y = (uint32_t)row;
  GhosttyGridRef ref = GHOSTTY_INIT_SIZED(GhosttyGridRef);
  if (ghostty_terminal_grid_ref(t->term, p, &ref) != GHOSTTY_SUCCESS) return -1;

  size_t len = 0;
  GhosttyResult r = ghostty_grid_ref_hyperlink_uri(&ref, NULL, 0, &len);
  if (r == GHOSTTY_SUCCESS) return len == 0 ? -1 : 0;
  if (r != GHOSTTY_OUT_OF_SPACE || len == 0) return -1;
  uint8_t *tmp = malloc(len);
  if (tmp == NULL) return -1;
  size_t got = 0;
  r = ghostty_grid_ref_hyperlink_uri(&ref, tmp, len, &got);
  int32_t n = r == GHOSTTY_SUCCESS ? copy_out(tmp, got, out, cap) : -1;
  free(tmp);
  return n;
}

/* ---- Search ------------------------------------------------------------- */

int32_t gjb_search_set(gjb_term *t, const uint8_t *utf8, int32_t len) {
  if (t == NULL) return GHOSTTY_INVALID_VALUE;
  if (utf8 == NULL || len <= 0) {
    if (t->search != NULL) {
      ghostty_search_free(t->search);
      t->search = NULL;
    }
    t->force_full = true;
    return GHOSTTY_SUCCESS;
  }
  if (t->search == NULL) {
    GhosttyResult r = ghostty_search_new(NULL, &t->search, t->term);
    if (r != GHOSTTY_SUCCESS) {
      t->search = NULL;
      return r;
    }
  }
  GhosttyString needle = {utf8, (size_t)len};
  GhosttyResult r =
      ghostty_search_set(t->search, GHOSTTY_SEARCH_OPT_NEEDLE, &needle);
  ghostty_search_feed(t->search);
  t->force_full = true;
  return r;
}

int32_t gjb_search_select(gjb_term *t, int32_t dir) {
  if (t == NULL || t->search == NULL) return -1;
  /* Make sure the results are complete before navigating. */
  ghostty_search_run(t->search);
  GhosttyResult r = ghostty_search_set(
      t->search,
      dir >= 0 ? GHOSTTY_SEARCH_OPT_SELECT_NEXT : GHOSTTY_SEARCH_OPT_SELECT_PREV,
      NULL);
  if (r != GHOSTTY_SUCCESS) return -1;
  size_t idx = 0;
  if (ghostty_search_get(t->search, GHOSTTY_SEARCH_DATA_SELECTED_INDEX, &idx) !=
      GHOSTTY_SUCCESS)
    return -1;
  t->force_full = true;
  return (int32_t)idx;
}
