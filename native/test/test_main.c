/* Tests for the ghostty_jb facade. Run with `zig build test`. */
#include "ghostty_jb.h"

#include <ghostty/vt.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int failures = 0;

#define CHECK(cond)                                                    \
  do {                                                                 \
    if (!(cond)) {                                                     \
      fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, \
              #cond);                                                  \
      failures++;                                                      \
    }                                                                  \
  } while (0)

/* ---- Event capture ------------------------------------------------------ */

typedef struct {
  char pty[4096];
  size_t pty_len;
  char title[256];
  char clipboard[256];
  int bells;
  int allow_clipboard;
  int last_exit;
} events;

static int32_t on_event(void *ud, int32_t ev, int32_t value,
                        const uint8_t *data, int32_t len) {
  events *e = (events *)ud;
  switch (ev) {
    case GJB_EVENT_WRITE_PTY:
      if (e->pty_len + (size_t)len < sizeof(e->pty)) {
        memcpy(e->pty + e->pty_len, data, (size_t)len);
        e->pty_len += (size_t)len;
        e->pty[e->pty_len] = 0;
      }
      return 0;
    case GJB_EVENT_TITLE:
      snprintf(e->title, sizeof(e->title), "%.*s", (int)len, (const char *)data);
      return 0;
    case GJB_EVENT_BELL:
      e->bells++;
      return 0;
    case GJB_EVENT_CLIPBOARD_WRITE:
      snprintf(e->clipboard, sizeof(e->clipboard), "%.*s", (int)len,
               (const char *)data);
      return e->allow_clipboard;
    case GJB_EVENT_COMMAND_FINISHED:
      e->last_exit = value;
      return 0;
    default:
      return 0;
  }
}

static void reset_pty(events *e) {
  e->pty_len = 0;
  e->pty[0] = 0;
}

static void w(gjb_term *t, const char *s) {
  gjb_write(t, (const uint8_t *)s, (int32_t)strlen(s));
}

/* ---- Frame helpers ------------------------------------------------------ */

static int32_t frame[1 << 16];

static int32_t *cell(int32_t x, int32_t y) {
  return frame + GJB_HEADER_INTS +
         (y * frame[GJB_H_COLS] + x) * GJB_CELL_INTS;
}

static void snap(gjb_term *t) {
  int32_t n = gjb_snapshot(t, frame, (int32_t)(sizeof(frame) / 4));
  CHECK(n >= GJB_HEADER_INTS);
  CHECK(frame[GJB_H_MAGIC] == GJB_FRAME_MAGIC);
}

static void row_text(int32_t y, char *out, size_t cap) {
  size_t n = 0;
  for (int32_t x = 0; x < frame[GJB_H_COLS] && n + 1 < cap; x++) {
    int32_t cp = cell(x, y)[GJB_C_CODEPOINT];
    out[n++] = cp == 0 ? ' ' : (cp < 128 ? (char)cp : '?');
  }
  while (n > 0 && out[n - 1] == ' ') n--;
  out[n] = 0;
}

/* ---- Tests -------------------------------------------------------------- */

static void test_text_and_styles(void) {
  events e = {0};
  gjb_term *t = gjb_new(20, 5, on_event, &e);
  CHECK(t != NULL);
  w(t, "Hello \x1b[1;31mred\x1b[0m\r\n\x1b[4:3mcurly\x1b[0m");
  snap(t);
  CHECK(frame[GJB_H_COLS] == 20);
  CHECK(frame[GJB_H_ROWS] == 5);
  CHECK(frame[GJB_H_DIRTY] != 0);

  char line[64];
  row_text(0, line, sizeof(line));
  CHECK(strcmp(line, "Hello red") == 0);
  CHECK((cell(6, 0)[GJB_C_ATTRS] & GJB_A_BOLD) != 0);
  CHECK((cell(6, 0)[GJB_C_FG] & GJB_COLOR_SET) != 0);
  CHECK((cell(0, 0)[GJB_C_FG] & GJB_COLOR_SET) == 0);
  CHECK(((cell(0, 1)[GJB_C_ATTRS] & GJB_A_UNDERLINE_MASK) >>
         GJB_A_UNDERLINE_SHIFT) == 3);
  CHECK(frame[GJB_H_CURSOR_Y] == 1);
  CHECK(frame[GJB_H_CURSOR_X] == 5);

  /* Nothing changed: the next snapshot is header-only and clean. */
  int32_t n = gjb_snapshot(t, frame, (int32_t)(sizeof(frame) / 4));
  CHECK(n == GJB_HEADER_INTS);
  CHECK(frame[GJB_H_DIRTY] == 0);
  gjb_free(t);
}

static void test_wide_and_graphemes(void) {
  events e = {0};
  gjb_term *t = gjb_new(20, 3, on_event, &e);
  /* CJK wide char, then a ZWJ emoji sequence (woman + ZWJ + laptop). */
  w(t, "\xe6\x97\xa5" "x" "\xf0\x9f\x91\xa9\xe2\x80\x8d\xf0\x9f\x92\xbb");
  snap(t);
  CHECK(cell(0, 0)[GJB_C_CODEPOINT] == 0x65e5);
  CHECK(((cell(0, 0)[GJB_C_ATTRS] & GJB_A_WIDE_MASK) >> GJB_A_WIDE_SHIFT) == 1);
  CHECK(((cell(1, 0)[GJB_C_ATTRS] & GJB_A_WIDE_MASK) >> GJB_A_WIDE_SHIFT) == 2);
  CHECK(cell(2, 0)[GJB_C_CODEPOINT] == 'x');
  CHECK(cell(3, 0)[GJB_C_CODEPOINT] == 0x1f469);
  CHECK((cell(3, 0)[GJB_C_ATTRS] & GJB_A_GRAPHEME) != 0);
  int32_t off = frame[GJB_H_GRAPHEME_OFFSET];
  CHECK(frame[GJB_H_GRAPHEME_LEN] == 5);
  CHECK(frame[off] == 3);     /* cell index */
  CHECK(frame[off + 1] == 3); /* codepoints */
  CHECK(frame[off + 3] == 0x200d);
  CHECK(frame[off + 4] == 0x1f4bb);
  gjb_free(t);
}

static void test_queries_and_events(void) {
  events e = {0};
  gjb_term *t = gjb_new(20, 5, on_event, &e);
  w(t, "\x1b[c");
  CHECK(strcmp(e.pty, "\x1b[?62;22c") == 0);
  reset_pty(&e);
  w(t, "\x1b[>c");
  CHECK(strcmp(e.pty, "\x1b[>1;10;0c") == 0);
  reset_pty(&e);
  w(t, "\x1b[6n"); /* cursor position report */
  CHECK(strcmp(e.pty, "\x1b[1;1R") == 0);

  w(t, "\x1b]2;my title\x07");
  CHECK(strcmp(e.title, "my title") == 0);
  uint8_t buf[64];
  int32_t n = gjb_query_string(t, 1, buf, sizeof(buf));
  CHECK(n == 8 && memcmp(buf, "my title", 8) == 0);

  w(t, "\x07");
  CHECK(e.bells == 1);

  /* OSC 52 clipboard write, base64("hi") = "aGk=". */
  e.allow_clipboard = 1;
  w(t, "\x1b]52;c;aGk=\x07");
  CHECK(strcmp(e.clipboard, "hi") == 0);
  gjb_free(t);
}

static int32_t key(gjb_term *t, int32_t k, int32_t mods, const char *utf8,
                   char *out) {
  int32_t n = gjb_encode_key(t, 1, k, mods, 0, 0, (const uint8_t *)utf8,
                             utf8 ? (int32_t)strlen(utf8) : 0,
                             (uint8_t *)out, 64);
  if (n >= 0) out[n] = 0;
  return n;
}

enum {
  KEY_A = GHOSTTY_KEY_A,
  KEY_C = GHOSTTY_KEY_C,
  KEY_ENTER = GHOSTTY_KEY_ENTER,
  KEY_ARROW_UP = GHOSTTY_KEY_ARROW_UP,
};

static void test_keys(void) {
  events e = {0};
  gjb_term *t = gjb_new(20, 5, on_event, &e);
  char out[64];
  CHECK(key(t, KEY_A, 0, "a", out) == 1 && strcmp(out, "a") == 0);
  CHECK(key(t, KEY_ENTER, 0, NULL, out) == 1 && strcmp(out, "\r") == 0);
  CHECK(key(t, KEY_C, 2 /* ctrl */, "c", out) == 1 && out[0] == 3);
  CHECK(key(t, KEY_ARROW_UP, 0, NULL, out) == 3 && strcmp(out, "\x1b[A") == 0);
  w(t, "\x1b[?1h"); /* application cursor keys */
  CHECK(key(t, KEY_ARROW_UP, 0, NULL, out) == 3 && strcmp(out, "\x1bOA") == 0);
  CHECK(key(t, KEY_A, 4 /* alt */, "a", out) == 2 && strcmp(out, "\x1b" "a") == 0);
  gjb_free(t);
}

static void test_paste(void) {
  events e = {0};
  gjb_term *t = gjb_new(20, 5, on_event, &e);
  const char *text = "echo hi\nrm -rf /";
  CHECK(gjb_paste(t, (const uint8_t *)text, (int32_t)strlen(text), 0) == 1);
  CHECK(e.pty_len == 0);
  CHECK(gjb_paste(t, (const uint8_t *)text, (int32_t)strlen(text), 1) == 0);
  CHECK(strcmp(e.pty, "echo hi\rrm -rf /") == 0);
  reset_pty(&e);
  w(t, "\x1b[?2004h");
  CHECK(gjb_paste(t, (const uint8_t *)text, (int32_t)strlen(text), 0) == 0);
  CHECK(strcmp(e.pty, "\x1b[200~echo hi\nrm -rf /\x1b[201~") == 0);
  gjb_free(t);
}

static void test_selection(void) {
  events e = {0};
  gjb_term *t = gjb_new(20, 5, on_event, &e);
  gjb_resize(t, 20, 5, 10, 20, 0, 0);
  w(t, "hello world\r\nsecond line");
  int32_t r = gjb_select_event(t, GJB_SEL_PRESS, 2, 10, 1000, 0);
  CHECK((r & GJB_SEL_R_HAS) == 0);
  r = gjb_select_event(t, GJB_SEL_DRAG, 46, 10, 0, 0);
  CHECK((r & GJB_SEL_R_CHANGED) != 0);
  CHECK((r & GJB_SEL_R_HAS) != 0);
  gjb_select_event(t, GJB_SEL_RELEASE, 46, 10, 0, 0);

  uint8_t buf[64];
  int32_t n = gjb_selection_text(t, buf, sizeof(buf));
  CHECK(n == 5 && memcmp(buf, "hello", 5) == 0);

  snap(t);
  CHECK((cell(0, 0)[GJB_C_ATTRS] & GJB_A_SELECTED) != 0);
  CHECK((cell(4, 0)[GJB_C_ATTRS] & GJB_A_SELECTED) != 0);
  CHECK((cell(5, 0)[GJB_C_ATTRS] & GJB_A_SELECTED) == 0);

  /* Double click selects a word. */
  gjb_select_clear(t);
  gjb_select_event(t, GJB_SEL_PRESS, 75, 10, 1000000000LL, 0);
  gjb_select_event(t, GJB_SEL_RELEASE, 75, 10, 0, 0);
  gjb_select_event(t, GJB_SEL_PRESS, 75, 10, 1100000000LL, 0);
  n = gjb_selection_text(t, buf, sizeof(buf));
  CHECK(n == 5 && memcmp(buf, "world", 5) == 0);

  /* A plain click clears it again. */
  gjb_select_event(t, GJB_SEL_RELEASE, 75, 10, 0, 0);
  r = gjb_select_event(t, GJB_SEL_PRESS, 5, 30, 9000000000LL, 0);
  CHECK((r & GJB_SEL_R_HAS) == 0);
  CHECK(gjb_selection_text(t, buf, sizeof(buf)) == -1);

  /* Selection changes alone must produce a new (dirty) frame. */
  snap(t);
  gjb_select_all(t);
  snap(t);
  CHECK(frame[GJB_H_DIRTY] != 0);
  CHECK((cell(0, 1)[GJB_C_ATTRS] & GJB_A_SELECTED) != 0);
  n = gjb_selection_text(t, buf, sizeof(buf));
  CHECK(n == 23 && memcmp(buf, "hello world\nsecond line", 23) == 0);
  gjb_free(t);
}

static void test_scrollback_and_search(void) {
  events e = {0};
  gjb_term *t = gjb_new(20, 5, on_event, &e);
  char line[32];
  for (int i = 0; i < 30; i++) {
    snprintf(line, sizeof(line), "line %d\r\n", i);
    w(t, line);
  }
  snap(t);
  CHECK(frame[GJB_H_SCROLL_TOTAL] >= 30);
  CHECK(frame[GJB_H_SCROLL_LEN] == 5);
  CHECK((frame[GJB_H_FLAGS] & GJB_F_VIEWPORT_AT_BOTTOM) != 0);

  gjb_scroll(t, GJB_SCROLL_DELTA, -3);
  CHECK(gjb_query(t, GJB_Q_VIEWPORT_AT_BOTTOM, 0) == 0);
  snap(t);
  char text[64];
  row_text(0, text, sizeof(text));
  CHECK(strcmp(text, "line 23") == 0);
  gjb_scroll(t, GJB_SCROLL_BOTTOM, 0);
  CHECK(gjb_query(t, GJB_Q_VIEWPORT_AT_BOTTOM, 0) == 1);

  const char *needle = "line 1";
  CHECK(gjb_search_set(t, (const uint8_t *)needle, 6) == 0);
  /* "line 1" plus "line 10".."line 19" */
  for (int i = 0; i < 10; i++) snap(t);
  CHECK(frame[GJB_H_SEARCH_TOTAL] == 11);
  int32_t idx = gjb_search_select(t, 1);
  CHECK(idx == 0);
  snap(t);
  CHECK(frame[GJB_H_SEARCH_SELECTED] == 0);
  /* The newest match ("line 19") is selected and scrolled into view. */
  int found = 0;
  for (int32_t y = 0; y < frame[GJB_H_ROWS]; y++) {
    row_text(y, text, sizeof(text));
    if (strcmp(text, "line 19") == 0) {
      found = 1;
      CHECK((cell(0, y)[GJB_C_ATTRS] & GJB_A_SEARCH_SELECTED) != 0);
    }
  }
  CHECK(found);
  gjb_search_set(t, NULL, 0);
  snap(t);
  CHECK(frame[GJB_H_SEARCH_TOTAL] == -1);
  gjb_free(t);
}

static void test_mouse_and_focus(void) {
  events e = {0};
  gjb_term *t = gjb_new(20, 5, on_event, &e);
  gjb_resize(t, 20, 5, 10, 20, 0, 0);
  uint8_t out[64];
  CHECK(gjb_encode_mouse(t, 0, 1, 0, 25, 30, 1, out, sizeof(out)) == 0);
  w(t, "\x1b[?1000h\x1b[?1006h");
  CHECK(gjb_query(t, GJB_Q_MOUSE_TRACKING, 0) == 1);
  int32_t n = gjb_encode_mouse(t, 0, 1, 0, 25, 30, 1, out, sizeof(out));
  CHECK(n > 0);
  out[n > 0 ? n : 0] = 0;
  CHECK(strcmp((char *)out, "\x1b[<0;3;2M") == 0);

  CHECK(gjb_encode_focus(t, 1, out, sizeof(out)) == 0);
  w(t, "\x1b[?1004h");
  n = gjb_encode_focus(t, 1, out, sizeof(out));
  CHECK(n == 3 && memcmp(out, "\x1b[I", 3) == 0);
  gjb_free(t);
}

static void test_synchronized_output(void) {
  events e = {0};
  gjb_term *t = gjb_new(20, 3, on_event, &e);
  w(t, "before");
  snap(t);
  w(t, "\x1b[?2026h\r\x1b[2Kafter");
  snap(t);
  CHECK((frame[GJB_H_FLAGS] & GJB_F_RENDER_HELD) != 0);
  char text[64];
  row_text(0, text, sizeof(text));
  CHECK(strcmp(text, "before") == 0);
  w(t, "\x1b[?2026l");
  snap(t);
  row_text(0, text, sizeof(text));
  CHECK(strcmp(text, "after") == 0);
  gjb_free(t);
}

static void test_resize_and_small_buffer(void) {
  events e = {0};
  gjb_term *t = gjb_new(10, 2, on_event, &e);
  CHECK(gjb_resize(t, 30, 8, 9, 18, 2, 2) == 0);
  CHECK(gjb_query(t, GJB_Q_COLS, 0) == 30);
  int32_t small[GJB_HEADER_INTS + 4];
  int32_t n = gjb_snapshot(t, small, (int32_t)(sizeof(small) / 4));
  CHECK(n == -(GJB_HEADER_INTS + 30 * 8 * GJB_CELL_INTS));
  /* The dirty state survives a failed snapshot. */
  snap(t);
  CHECK(frame[GJB_H_DIRTY] != 0);
  CHECK(frame[GJB_H_COLS] == 30);
  gjb_free(t);
}

static void test_semantic_prompt(void) {
  events e = {0};
  e.last_exit = -12345;
  gjb_term *t = gjb_new(20, 5, on_event, &e);
  w(t, "\x1b]133;A\x07$ \x1b]133;B\x07" "false\r\n\x1b]133;C\x07\x1b]133;D;1\x07");
  CHECK(e.last_exit == 1);
  gjb_free(t);
}

int main(void) {
  CHECK(gjb_abi_version() == GJB_ABI_VERSION);
  test_text_and_styles();
  test_wide_and_graphemes();
  test_queries_and_events();
  test_keys();
  test_paste();
  test_selection();
  test_scrollback_and_search();
  test_mouse_and_focus();
  test_synchronized_output();
  test_resize_and_small_buffer();
  test_semantic_prompt();
  if (failures) {
    fprintf(stderr, "%d check(s) failed\n", failures);
    return 1;
  }
  printf("all native tests passed\n");
  return 0;
}
