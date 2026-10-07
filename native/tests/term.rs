//! Integration tests for the terminal facade, driving it the way the plugin
//! does. (Ported from the old C test suite, plus regressions found since.)

use ghostty_jb::frame as f;
use ghostty_jb::term::{PasteResult, Term, opt, query, sel};
use ghostty_jb::vt::{Event, KeyInput, MouseInput, key, mods};

struct Frame(Vec<i32>);

impl Frame {
    fn cols(&self) -> usize {
        self.0[f::H_COLS] as usize
    }
    fn rows(&self) -> usize {
        self.0[f::H_ROWS] as usize
    }
    fn cell(&self, x: usize, y: usize) -> &[i32] {
        let base = f::HEADER_INTS + (y * self.cols() + x) * f::CELL_INTS;
        &self.0[base..base + f::CELL_INTS]
    }
    fn attrs(&self, x: usize, y: usize) -> i32 {
        self.cell(x, y)[f::C_ATTRS]
    }
    fn wide(&self, x: usize, y: usize) -> i32 {
        (self.attrs(x, y) & f::A_WIDE_MASK) >> f::A_WIDE_SHIFT
    }
    fn row_text(&self, y: usize) -> String {
        let mut s: String = (0..self.cols())
            .map(|x| match self.cell(x, y)[f::C_CODEPOINT] {
                0 => ' ',
                cp => char::from_u32(cp as u32).unwrap_or('?'),
            })
            .collect();
        s.truncate(s.trim_end().len());
        s
    }
}

fn snap(t: &mut Term) -> Frame {
    let frame = t.snapshot().expect("expected a dirty frame").to_vec();
    assert_eq!(frame[f::H_MAGIC], f::MAGIC);
    Frame(frame)
}

fn pty_bytes(events: Vec<Event>) -> Vec<u8> {
    events.into_iter().filter_map(|e| if let Event::WritePty(b) = e { Some(b) } else { None }).flatten().collect()
}

fn key(t: &mut Term, k: i32, m: u16, text: Option<&str>) -> String {
    let input = KeyInput { action: 1, key: k, mods: m, consumed_mods: 0, unshifted: 0, text };
    String::from_utf8(t.encode_key(&input)).unwrap()
}

#[test]
fn text_and_styles() {
    let mut t = Term::new(20, 5).unwrap();
    t.write(b"Hello \x1b[1;31mred\x1b[0m\r\n\x1b[4:3mcurly\x1b[0m");
    let fr = snap(&mut t);
    assert_eq!((fr.cols(), fr.rows()), (20, 5));
    assert_ne!(fr.0[f::H_DIRTY], 0);
    assert_eq!(fr.row_text(0), "Hello red");
    assert_ne!(fr.attrs(6, 0) & f::A_BOLD, 0);
    assert_ne!(fr.cell(6, 0)[f::C_FG] & f::COLOR_SET, 0);
    assert_eq!(fr.cell(0, 0)[f::C_FG] & f::COLOR_SET, 0);
    assert_eq!((fr.attrs(0, 1) & f::A_UNDERLINE_MASK) >> f::A_UNDERLINE_SHIFT, 3);
    assert_eq!((fr.0[f::H_CURSOR_X], fr.0[f::H_CURSOR_Y]), (5, 1));
    // Nothing changed: no new frame.
    assert!(t.snapshot().is_none());
}

#[test]
fn wide_chars_and_grapheme_clusters() {
    let mut t = Term::new(20, 3).unwrap();
    t.write("日x👩\u{200d}💻".as_bytes());
    let fr = snap(&mut t);
    assert_eq!(fr.cell(0, 0)[f::C_CODEPOINT], 0x65e5);
    assert_eq!(fr.wide(0, 0), 1);
    assert_eq!(fr.wide(1, 0), 2);
    assert_eq!(fr.cell(2, 0)[f::C_CODEPOINT], 'x' as i32);
    assert_eq!(fr.cell(3, 0)[f::C_CODEPOINT], 0x1f469);
    assert_ne!(fr.attrs(3, 0) & f::A_GRAPHEME, 0);
    let off = fr.0[f::H_GRAPHEME_OFFSET] as usize;
    assert_eq!(fr.0[f::H_GRAPHEME_LEN], 5);
    assert_eq!(&fr.0[off..off + 5], &[3, 3, 0x1f469, 0x200d, 0x1f4bb]);
}

#[test]
fn query_replies_and_events() {
    let mut t = Term::new(20, 5).unwrap();
    t.write(b"\x1b[c");
    assert_eq!(pty_bytes(t.take_events()), b"\x1b[?62;22c");
    t.write(b"\x1b[>c");
    assert_eq!(pty_bytes(t.take_events()), b"\x1b[>1;10;0c");
    t.write(b"\x1b[6n");
    assert_eq!(pty_bytes(t.take_events()), b"\x1b[1;1R");

    t.write(b"\x1b]2;my title\x07\x07");
    let events = t.take_events();
    assert!(events.contains(&Event::TitleChanged("my title".into())));
    assert!(events.contains(&Event::Bell));
    assert_eq!(t.title(), "my title");
}

#[test]
fn clipboard_writes_follow_policy() {
    let mut t = Term::new(20, 5).unwrap();
    t.write(b"\x1b]52;c;aGk=\x07"); // base64("hi")
    assert!(t.take_events().is_empty(), "denied by default");
    t.set_option(opt::ALLOW_CLIPBOARD_WRITE, 1);
    t.write(b"\x1b]52;c;aGk=\x07");
    assert_eq!(t.take_events(), vec![Event::ClipboardWrite { text: "hi".into(), primary: false }]);
}

#[test]
fn keys() {
    let mut t = Term::new(20, 5).unwrap();
    assert_eq!(key(&mut t, key::A, 0, Some("a")), "a");
    assert_eq!(key(&mut t, key::ENTER, 0, None), "\r");
    assert_eq!(key(&mut t, key::C, mods::CTRL, Some("c")), "\x03");
    assert_eq!(key(&mut t, key::ARROW_UP, 0, None), "\x1b[A");
    assert_eq!(key(&mut t, key::ARROW_RIGHT, mods::CTRL, None), "\x1b[1;5C");
    assert_eq!(key(&mut t, key::BACKSPACE, 0, None), "\x7f");
    // On macOS, Alt is Option and composes characters unless "option as alt" is on.
    let alt_a = if cfg!(target_os = "macos") { "a" } else { "\x1ba" };
    assert_eq!(key(&mut t, key::A, mods::ALT, Some("a")), alt_a);
    t.set_option(opt::OPTION_AS_ALT, 1);
    assert_eq!(key(&mut t, key::A, mods::ALT, Some("a")), "\x1ba");
    t.set_option(opt::OPTION_AS_ALT, 0);
    t.write(b"\x1b[?1h"); // application cursor keys
    assert_eq!(key(&mut t, key::ARROW_UP, 0, None), "\x1bOA");
    t.write(b"\x1b[>1u"); // Kitty keyboard protocol
    assert_eq!(key(&mut t, key::ESCAPE, 0, None), "\x1b[27u");
}

#[test]
fn out_of_range_values_from_java_are_rejected_safely() {
    let mut t = Term::new(20, 5).unwrap();
    // Garbage enum values must never reach libghostty (Zig would panic).
    let bad = KeyInput { action: 99, key: 100_000, mods: u16::MAX, consumed_mods: 0, unshifted: u32::MAX, text: None };
    let _ = t.encode_key(&bad);
    let bad = KeyInput { action: -5, key: -1, mods: 0, consumed_mods: 0, unshifted: 0, text: Some("x") };
    let _ = t.encode_key(&bad);
    t.write(b"\x1b[?1000h");
    let m = MouseInput { action: 77, button: 999, mods: 0, x: f32::NAN, y: -1e30, any_button_pressed: true };
    let _ = t.encode_mouse(&m);
    assert_eq!(t.select_event(42, f64::NAN, f64::INFINITY, -1, false), 0);
    t.select_event(0, f64::NAN, f64::INFINITY, -1, false);
    t.scroll(99, i64::MAX);
    t.scroll(2, i64::MIN);
    t.set_option(999, 1);
    t.set_option(opt::CURSOR_STYLE, 12345);
    assert!(t.resize(-5, 0, -1, i32::MAX, -3, -3).is_ok());
    assert_eq!(t.hyperlink_at(-1, 70000), None);
    snap(&mut t);
}

#[test]
fn paste_respects_bracketed_mode() {
    let mut t = Term::new(20, 5).unwrap();
    let text = "echo hi\nrm -rf /";
    assert_eq!(t.paste(text, false), PasteResult::Unsafe);
    assert!(pty_bytes(t.take_events()).is_empty());
    assert_eq!(t.paste(text, true), PasteResult::Ok);
    assert_eq!(pty_bytes(t.take_events()), b"echo hi\rrm -rf /");
    t.write(b"\x1b[?2004h");
    assert_eq!(t.paste(text, false), PasteResult::Ok);
    assert_eq!(pty_bytes(t.take_events()), b"\x1b[200~echo hi\nrm -rf /\x1b[201~");
}

#[test]
fn selection_gestures() {
    let mut t = Term::new(20, 5).unwrap();
    t.resize(20, 5, 10, 20, 0, 0).unwrap();
    t.write(b"hello world\r\nsecond line");
    snap(&mut t);

    assert_eq!(t.select_event(0, 2.0, 10.0, 1_000, false) & sel::HAS, 0);
    let r = t.select_event(2, 46.0, 10.0, 0, false);
    assert_ne!(r & sel::CHANGED, 0);
    assert_ne!(r & sel::HAS, 0);
    t.select_event(1, 46.0, 10.0, 0, false);
    assert_eq!(t.selection_text().as_deref(), Some("hello"));

    // The selection change alone must produce a new frame.
    let fr = snap(&mut t);
    assert_ne!(fr.attrs(0, 0) & f::A_SELECTED, 0);
    assert_ne!(fr.attrs(4, 0) & f::A_SELECTED, 0);
    assert_eq!(fr.attrs(5, 0) & f::A_SELECTED, 0);

    // Double click selects a word, triple click the line.
    t.clear_selection();
    t.select_event(0, 75.0, 10.0, 1_000_000_000, false);
    t.select_event(1, 75.0, 10.0, 0, false);
    t.select_event(0, 75.0, 10.0, 1_100_000_000, false);
    assert_eq!(t.selection_text().as_deref(), Some("world"));
    t.select_event(1, 75.0, 10.0, 0, false);
    t.select_event(0, 75.0, 10.0, 1_200_000_000, false);
    assert_eq!(t.selection_text().as_deref(), Some("hello world"));

    // A plain click (later, elsewhere) clears it.
    t.select_event(1, 75.0, 10.0, 0, false);
    assert_eq!(t.select_event(0, 5.0, 30.0, 9_000_000_000, false) & sel::HAS, 0);
    assert_eq!(t.selection_text(), None);

    snap(&mut t);
    t.select_all();
    let fr = snap(&mut t);
    assert_ne!(fr.attrs(0, 1) & f::A_SELECTED, 0);
    assert_eq!(t.selection_text().as_deref(), Some("hello world\nsecond line"));
}

#[test]
fn double_click_after_drag_selects_word() {
    let mut t = Term::new(40, 4).unwrap();
    t.resize(40, 4, 10, 20, 0, 0).unwrap();
    t.write(b"selecting this text works fine");
    t.select_event(0, 1.0, 5.0, 1_000_000_000, false);
    t.select_event(2, 100.0, 5.0, 0, false);
    t.select_event(1, 100.0, 5.0, 0, false);
    assert_eq!(t.selection_text().as_deref(), Some("selecting"));
    t.select_event(0, 225.0, 5.0, 1_900_000_000, false);
    t.select_event(1, 225.0, 5.0, 0, false);
    t.select_event(0, 225.0, 5.0, 1_980_000_000, false);
    assert_eq!(t.selection_text().as_deref(), Some("works"));
}

#[test]
fn scrollback_and_search() {
    let mut t = Term::new(20, 5).unwrap();
    for i in 0..30 {
        t.write(format!("line {i}\r\n").as_bytes());
    }
    let fr = snap(&mut t);
    assert!(fr.0[f::H_SCROLL_TOTAL] >= 30);
    assert_eq!(fr.0[f::H_SCROLL_LEN], 5);
    assert_ne!(fr.0[f::H_FLAGS] & f::F_VIEWPORT_AT_BOTTOM, 0);

    t.scroll(2, -3);
    assert_eq!(t.query(query::VIEWPORT_AT_BOTTOM, 0), 0);
    assert_eq!(snap(&mut t).row_text(0), "line 23");
    t.scroll(1, 0);
    assert_eq!(t.query(query::VIEWPORT_AT_BOTTOM, 0), 1);

    assert!(t.set_search(Some("line 1")));
    let mut fr = snap(&mut t);
    for _ in 0..10 {
        fr = snap(&mut t);
    }
    assert_eq!(fr.0[f::H_SEARCH_TOTAL], 11); // "line 1" and "line 10".."line 19"
    assert_eq!(t.search_select(1), 0);
    let fr = snap(&mut t);
    assert_eq!(fr.0[f::H_SEARCH_SELECTED], 0);
    let row = (0..fr.rows()).find(|&y| fr.row_text(y) == "line 19").expect("newest match scrolled into view");
    assert_ne!(fr.attrs(0, row) & f::A_SEARCH_SELECTED, 0);

    t.set_search(None);
    assert_eq!(snap(&mut t).0[f::H_SEARCH_TOTAL], -1);
    assert!(t.screen_text().unwrap().contains("line 0\nline 1\n"));
}

#[test]
fn mouse_and_focus_reporting() {
    let mut t = Term::new(20, 5).unwrap();
    t.resize(20, 5, 10, 20, 0, 0).unwrap();
    let press = MouseInput { action: 0, button: 1, mods: 0, x: 25.0, y: 30.0, any_button_pressed: true };
    assert!(t.encode_mouse(&press).is_empty());
    t.write(b"\x1b[?1000h\x1b[?1006h");
    assert_eq!(t.query(query::MOUSE_TRACKING, 0), 1);
    assert_eq!(t.encode_mouse(&press), b"\x1b[<0;3;2M");

    assert!(t.encode_focus(true).is_empty());
    t.write(b"\x1b[?1004h");
    assert_eq!(t.encode_focus(true), b"\x1b[I");
}

#[test]
fn synchronized_output_holds_frames() {
    let mut t = Term::new(20, 3).unwrap();
    t.write(b"before");
    snap(&mut t);
    t.write(b"\x1b[?2026h\r\x1b[2Kafter");
    t.invalidate();
    let fr = snap(&mut t);
    assert_ne!(fr.0[f::H_FLAGS] & f::F_RENDER_HELD, 0);
    assert_eq!(fr.row_text(0), "before");
    t.write(b"\x1b[?2026l");
    assert_eq!(snap(&mut t).row_text(0), "after");
}

#[test]
fn resize_and_colors() {
    let mut t = Term::new(10, 2).unwrap();
    t.resize(30, 8, 9, 18, 2, 2).unwrap();
    assert_eq!(t.query(query::COLS, 0), 30);
    let fr = snap(&mut t);
    assert_eq!((fr.cols(), fr.rows()), (30, 8));

    let palette: Vec<i32> = (0..16).map(|i| i * 0x10_1010).collect();
    t.set_colors(Some(0xdd_dddd), Some(0x1e_1f22), Some(0xff_00ff), Some(&palette), true);
    t.write(b"\x1b[31mr\x1b[38;5;196mx");
    let fr = snap(&mut t);
    assert_eq!(fr.0[f::H_FG], 0xdd_dddd);
    assert_eq!(fr.0[f::H_BG], 0x1e_1f22);
    assert_eq!(fr.0[f::H_CURSOR_COLOR], 0xff_00ff | f::COLOR_SET);
    assert_eq!(fr.cell(0, 0)[f::C_FG], 0x10_1010 | f::COLOR_SET);
    assert_ne!(fr.cell(1, 0)[f::C_FG] & f::COLOR_SET, 0);
}

#[test]
fn semantic_prompt_reports_exit_codes() {
    let mut t = Term::new(20, 5).unwrap();
    t.write(b"\x1b]133;A\x07$ \x1b]133;B\x07false\r\n\x1b]133;C\x07\x1b]133;D;1\x07");
    assert!(t.take_events().contains(&Event::CommandFinished { exit_code: Some(1) }));
}

#[test]
fn hyperlinks() {
    let mut t = Term::new(30, 3).unwrap();
    t.write(b"\x1b]8;;https://ghostty.org\x1b\\link\x1b]8;;\x1b\\ plain");
    let fr = snap(&mut t);
    assert_ne!(fr.attrs(0, 0) & f::A_HYPERLINK, 0);
    assert_eq!(fr.attrs(6, 0) & f::A_HYPERLINK, 0);
    assert_eq!(t.hyperlink_at(1, 0).as_deref(), Some("https://ghostty.org"));
    assert_eq!(t.hyperlink_at(6, 0), None);
}

#[test]
fn color_scheme_change_is_reported_when_requested() {
    let mut t = Term::new(20, 5).unwrap();
    t.set_option(opt::COLOR_SCHEME, 0);
    assert!(t.take_events().is_empty());
    t.write(b"\x1b[?2031h");
    t.take_events();
    t.set_option(opt::COLOR_SCHEME, 1);
    assert_eq!(pty_bytes(t.take_events()), b"\x1b[?997;1n");
}
