//! Incremental search over the screen and scrollback.

use super::terminal::Terminal;
use super::{Error, Result, check, sized};
use crate::sys;
use std::ffi::c_void;
use std::ptr::{self, NonNull};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SearchStatus {
    Running,
    FeedRequired,
    Complete,
}

/// A match in inclusive viewport coordinates, start before end.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Span {
    pub start: (u16, u32),
    pub end: (u16, u32),
}

/// A search bound to a terminal. Every call takes that terminal so the borrow
/// checker serializes search work with terminal mutations, as libghostty
/// requires.
#[derive(Debug)]
pub struct Search {
    raw: NonNull<sys::GhosttySearchImpl>,
    terminal: sys::GhosttyTerminal,
    matches: Vec<sys::GhosttySelection>,
}

// SAFETY: no thread affinity; used through `&mut self` only.
unsafe impl Send for Search {}

impl Drop for Search {
    fn drop(&mut self) {
        // SAFETY: we own the handle (safe to free before or after the terminal).
        unsafe { sys::ghostty_search_free(self.raw.as_ptr()) };
    }
}

impl Search {
    pub fn new(term: &Terminal) -> Result<Search> {
        let mut raw = ptr::null_mut();
        // SAFETY: plain constructor borrowing the live terminal.
        check(unsafe { sys::ghostty_search_new(ptr::null(), &mut raw, term.raw_handle()) })?;
        let raw = NonNull::new(raw).ok_or(Error::OUT_OF_MEMORY)?;
        Ok(Search { raw, terminal: term.raw_handle(), matches: Vec::new() })
    }

    fn assert_bound(&self, term: &Terminal) {
        assert!(self.terminal == term.raw_handle(), "search used with a different terminal");
    }

    pub fn set_needle(&mut self, term: &Terminal, needle: &[u8]) -> Result<()> {
        self.assert_bound(term);
        let s = sys::GhosttyString { ptr: needle.as_ptr(), len: needle.len() };
        // SAFETY: the needle is copied by libghostty.
        check(unsafe {
            sys::ghostty_search_set(
                self.raw.as_ptr(),
                sys::GhosttySearchOption_GHOSTTY_SEARCH_OPT_NEEDLE,
                &s as *const _ as *const c_void,
            )
        })
    }

    /// Catches up with terminal changes and does a bounded amount of work.
    pub fn step(&mut self, term: &Terminal, max_ticks: usize) -> SearchStatus {
        self.assert_bound(term);
        // SAFETY: reads the terminal, which `term` keeps borrowed.
        unsafe { sys::ghostty_search_feed(self.raw.as_ptr()) };
        let mut status = sys::GhosttySearchStatus_GHOSTTY_SEARCH_STATUS_COMPLETE;
        for _ in 0..max_ticks {
            // SAFETY: works only on the search's own copies.
            if unsafe { sys::ghostty_search_tick(self.raw.as_ptr(), &mut status) } != sys::GhosttyResult_GHOSTTY_SUCCESS
            {
                break;
            }
            if status != sys::GhosttySearchStatus_GHOSTTY_SEARCH_STATUS_RUNNING {
                break;
            }
        }
        match status {
            sys::GhosttySearchStatus_GHOSTTY_SEARCH_STATUS_RUNNING => SearchStatus::Running,
            sys::GhosttySearchStatus_GHOSTTY_SEARCH_STATUS_FEED_REQUIRED => SearchStatus::FeedRequired,
            _ => SearchStatus::Complete,
        }
    }

    fn get<T: Default>(&self, data: sys::GhosttySearchData) -> Option<T> {
        let mut out = T::default();
        // SAFETY: callers pick `T` matching the documented output type.
        let r = unsafe { sys::ghostty_search_get(self.raw.as_ptr(), data, &mut out as *mut T as *mut c_void) };
        (r == sys::GhosttyResult_GHOSTTY_SUCCESS).then_some(out)
    }

    pub fn total(&self) -> usize {
        self.get(sys::GhosttySearchData_GHOSTTY_SEARCH_DATA_TOTAL_MATCHES).unwrap_or(0)
    }

    pub fn selected_index(&self) -> Option<usize> {
        self.get(sys::GhosttySearchData_GHOSTTY_SEARCH_DATA_SELECTED_INDEX)
    }

    /// Selects the next (older) or previous match and scrolls it into view.
    pub fn select(&mut self, term: &mut Terminal, next: bool) -> Result<Option<usize>> {
        self.assert_bound(term);
        // Make sure results are complete before navigating.
        // SAFETY: reads the terminal, exclusively borrowed by `term`.
        unsafe { sys::ghostty_search_run(self.raw.as_ptr()) };
        let opt = if next {
            sys::GhosttySearchOption_GHOSTTY_SEARCH_OPT_SELECT_NEXT
        } else {
            sys::GhosttySearchOption_GHOSTTY_SEARCH_OPT_SELECT_PREV
        };
        // SAFETY: may scroll the terminal's viewport; `term` is borrowed mutably.
        check(unsafe { sys::ghostty_search_set(self.raw.as_ptr(), opt, ptr::null()) })?;
        Ok(self.selected_index())
    }

    /// Matches on the viewport and the selected match, in viewport coordinates.
    pub fn highlights(&mut self, term: &Terminal) -> (Vec<Span>, Option<Span>) {
        self.assert_bound(term);
        let mut spans = Vec::new();
        loop {
            let mut buf =
                sys::GhosttySelectionBuffer { ptr: self.matches.as_mut_ptr(), cap: self.matches.len(), len: 0 };
            // SAFETY: `buf` describes our own vector's storage.
            let r = unsafe {
                sys::ghostty_search_get(
                    self.raw.as_ptr(),
                    sys::GhosttySearchData_GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES,
                    &mut buf as *mut _ as *mut c_void,
                )
            };
            if r == sys::GhosttyResult_GHOSTTY_OUT_OF_SPACE {
                self.matches.resize(buf.len + 16, sized!(sys::GhosttySelection));
                continue;
            }
            if r == sys::GhosttyResult_GHOSTTY_SUCCESS {
                for sel in &self.matches[..buf.len.min(self.matches.len())] {
                    if let Some((start, end)) = term.selection_to_viewport(sel) {
                        spans.push(Span { start, end });
                    }
                }
            }
            break;
        }
        let selected = self
            .get_selected()
            .and_then(|sel| term.selection_to_viewport(&sel))
            .map(|(start, end)| Span { start, end });
        (spans, selected)
    }

    fn get_selected(&self) -> Option<sys::GhosttySelection> {
        let mut sel = sized!(sys::GhosttySelection);
        // SAFETY: SELECTED_MATCH fills a sized GhosttySelection.
        let r = unsafe {
            sys::ghostty_search_get(
                self.raw.as_ptr(),
                sys::GhosttySearchData_GHOSTTY_SEARCH_DATA_SELECTED_MATCH,
                &mut sel as *mut _ as *mut c_void,
            )
        };
        (r == sys::GhosttyResult_GHOSTTY_SUCCESS).then_some(sel)
    }
}
