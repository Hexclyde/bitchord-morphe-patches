use super::{
    Arc, AtomicBool, Client, DEFAULT_WISHLIST_INTERVAL, Duration, HashMap,
    Instant, Ordering, Result, RwLockExt, Search, SearchResult, ServerMessage,
    SoulseekRs, info, next_search_token, sleep,
};

impl Client {
    pub fn search(
        &self,
        query: &str,
        timeout: Duration,
    ) -> Result<Vec<SearchResult>> {
        self.search_with_cancel(query, timeout, None)
    }

    /// Send `query` as a wishlist search (server code 103) and return at once.
    ///
    /// Results accumulate under `query`, so the caller reads them back with
    /// [`Client::get_search_results`] after waiting however long it wants to.
    /// Starting several wishes and then waiting once is the whole point: waiting
    /// per wish would cost one full search window each.
    ///
    /// The server rate-limits these to the interval it announced — see
    /// [`Client::wishlist_interval`] — so a wish re-sent sooner than that comes
    /// back empty.
    ///
    /// # Errors
    /// [`SoulseekRs::NotConnected`] when there is no server connection.
    pub fn start_wishlist_search(&self, query: &str) -> Result<()> {
        self.send_search(query, true)
    }

    /// How long to wait between wishlist searches: what the server announced in
    /// code 104, or [`DEFAULT_WISHLIST_INTERVAL`] until it has.
    #[must_use]
    pub fn wishlist_interval(&self) -> Duration {
        self.context
            .read_safe()
            .ok()
            .and_then(|ctx| ctx.wishlist_interval)
            .map_or(DEFAULT_WISHLIST_INTERVAL, |seconds| {
                Duration::from_secs(u64::from(seconds))
            })
    }

    pub fn search_with_cancel(
        &self,
        query: &str,
        timeout: Duration,
        cancel_flag: Option<Arc<AtomicBool>>,
    ) -> Result<Vec<SearchResult>> {
        self.send_search(query, false)?;
        Self::collect_for(timeout, cancel_flag);
        Ok(self.get_search_results(query))
    }

    /// Collect a bounded initial batch once useful results have settled.
    ///
    /// `useful` counts candidates acceptable for the caller's preferred policy;
    /// unrelated/disabled files must return zero. No useful result keeps the
    /// entire timeout, as with [`Client::search_with_cancel`]. This deliberately
    /// returns a shortlist, not every late responder. The full-window API above
    /// remains available for callers that need the longer collection window.
    pub fn search_adaptive_with_cancel<F>(
        &self,
        query: &str,
        timeout: Duration,
        cancel_flag: Option<Arc<AtomicBool>>,
        useful: F,
    ) -> Result<Vec<SearchResult>>
    where
        F: Fn(&SearchResult) -> usize,
    {
        self.send_search(query, false)?;
        let start = Instant::now();
        let mut window = AdaptiveWindow::new(timeout);
        loop {
            if cancel_flag
                .as_ref()
                .is_some_and(|flag| flag.load(Ordering::Acquire))
            {
                break;
            }
            // Read counts under the lock; cloning the whole result pool on
            // every poll would cost megabytes for broad searches.
            let count = self.context.read_safe().ok().map(|ctx| {
                ctx.searches.get(query).map_or(0, |search| {
                    search.results.iter().map(&useful).sum()
                })
            });
            // A contended/failed read is not evidence of a quiet network.
            if let Some(count) = count {
                if window.finished(start.elapsed(), count) {
                    break;
                }
            } else if start.elapsed() >= timeout {
                break;
            }
            sleep(
                Duration::from_millis(50)
                    .min(timeout.saturating_sub(start.elapsed())),
            );
        }
        Ok(self.get_search_results(query))
    }

    /// Register `query` and put its search on the wire. Returns as soon as the
    /// message is queued; nothing has answered yet.
    fn send_search(&self, query: &str, wishlist: bool) -> Result<()> {
        info!("Searching for {}", query);

        let token = self.register_search(query)?;
        let Some(handle) = &self.server_handle else {
            return Err(SoulseekRs::NotConnected);
        };
        let query = query.to_string();
        let _ = handle.send(if wishlist {
            ServerMessage::WishlistSearch { token, query }
        } else {
            ServerMessage::FileSearch { token, query }
        });
        Ok(())
    }

    /// Search one user's shares (server code 42) and return at once.
    ///
    /// Results arrive from that user alone and accumulate under `query`, read
    /// back with [`Client::get_search_results`] — the same place a plain
    /// search's results land, since the replies are ordinary
    /// `FileSearchResponse` frames.
    ///
    /// # Errors
    /// [`SoulseekRs::NotConnected`] when there is no server connection.
    pub fn search_user(&self, username: &str, query: &str) -> Result<()> {
        let token = self.register_search(query)?;
        self.send_server_message(
            crate::message::server::MessageFactory::build_user_search(
                username, token, query,
            ),
        )
    }

    /// Search the shares of everyone in `room` (server code 120), returning at
    /// once. Results accumulate under `query` exactly as above.
    ///
    /// # Errors
    /// [`SoulseekRs::NotConnected`] when there is no server connection.
    pub fn search_room(&self, room: &str, query: &str) -> Result<()> {
        let token = self.register_search(query)?;
        self.send_server_message(
            crate::message::server::MessageFactory::build_room_search(
                room, token, query,
            ),
        )
    }

    /// Take a token for `query` and register it, so replies quoting that token
    /// are filed under `query` when they arrive.
    fn register_search(&self, query: &str) -> Result<u32> {
        if self.server_handle.is_none() {
            return Err(SoulseekRs::NotConnected);
        }
        let token = next_search_token();
        self.context.write_safe()?.searches.insert(
            query.to_string(),
            Search {
                token,
                results: Vec::new(),
            },
        );
        Ok(token)
    }

    /// The phrases the server excludes from the search network (code 160), as
    /// last announced. Files whose path carries one are left out of the
    /// replies this client sends. Empty until the server has said.
    #[must_use]
    pub fn excluded_search_phrases(&self) -> Vec<String> {
        self.context
            .read_safe()
            .map(|ctx| ctx.excluded_search_phrases())
            .unwrap_or_default()
    }

    /// Let responses accumulate for `timeout`, or until cancelled.
    ///
    /// Peers answer a search over their own connections whenever they get round
    /// to it, so there is nothing to await — the window is the whole protocol.
    /// Late responders are often the better sources, which is why this runs the
    /// window out rather than returning on the first hit.
    pub fn collect_for(
        timeout: Duration,
        cancel_flag: Option<Arc<AtomicBool>>,
    ) {
        let start = Instant::now();
        while start.elapsed() < timeout {
            sleep(Duration::from_millis(100));
            if let Some(flag) = &cancel_flag
                && flag.load(Ordering::Relaxed)
            {
                info!("Search cancelled by user");
                return;
            }
        }
    }

    #[must_use]
    pub fn get_search_results_count(&self, search_key: &str) -> usize {
        self.context
            .read_safe()
            .ok()
            .and_then(|ctx| {
                ctx.searches.get(search_key).map(|s| s.results.len())
            })
            .unwrap_or(0)
    }

    #[must_use]
    pub fn get_search_results(&self, search_key: &str) -> Vec<SearchResult> {
        self.context
            .read_safe()
            .ok()
            .and_then(|ctx| {
                ctx.searches.get(search_key).map(|s| s.results.clone())
            })
            .unwrap_or_default()
    }

    /// Non-blocking variant that returns None if the lock is unavailable
    #[must_use]
    pub fn try_get_search_results(
        &self,
        search_key: &str,
    ) -> Option<Vec<SearchResult>> {
        self.context.try_read().ok().and_then(|ctx| {
            ctx.searches.get(search_key).map(|s| s.results.clone())
        })
    }

    /// Drop a search and everything it collected.
    ///
    /// Returns whether there was one to drop. A client that stays up for days
    /// would otherwise hold every result set it has ever seen, and a caller
    /// that has dismissed a search has no other way to say so.
    #[must_use]
    pub fn forget_search(&self, search_key: &str) -> bool {
        self.context
            .write_safe()
            .is_ok_and(|mut ctx| ctx.searches.remove(search_key).is_some())
    }

    #[must_use]
    pub fn get_all_searches(&self) -> HashMap<String, Search> {
        self.context
            .read_safe()
            .map(|ctx| ctx.searches.clone())
            .unwrap_or_default()
    }

    /// Every registered search and how many files it has collected, without
    /// copying the result sets.
    ///
    /// This is the accessor to poll. [`Client::get_all_searches`] clones every
    /// result of every search, which for a popular query is megabytes per
    /// call — a caller that only wants to know what exists and whether
    /// anything new arrived reads the counts instead.
    #[must_use]
    pub fn search_file_counts(&self) -> Vec<(String, usize)> {
        self.context
            .read_safe()
            .map(|ctx| {
                ctx.searches
                    .iter()
                    .map(|(query, search)| {
                        (
                            query.clone(),
                            search
                                .results
                                .iter()
                                .map(|result| result.files.len())
                                .sum(),
                        )
                    })
                    .collect()
            })
            .unwrap_or_default()
    }
}

/// Clock-independent collection policy, driven by monotonic elapsed time.
struct AdaptiveWindow {
    timeout: Duration,
    first_useful: Option<Duration>,
    last_change: Duration,
    useful_count: usize,
}

impl AdaptiveWindow {
    fn new(timeout: Duration) -> Self {
        Self {
            timeout,
            first_useful: None,
            last_change: Duration::ZERO,
            useful_count: 0,
        }
    }

    fn finished(&mut self, elapsed: Duration, count: usize) -> bool {
        if elapsed >= self.timeout {
            return true;
        }
        if count > self.useful_count {
            self.first_useful.get_or_insert(elapsed);
            self.last_change = elapsed;
            self.useful_count = count;
        }
        let Some(first) = self.first_useful else {
            return false;
        };
        elapsed.saturating_sub(first) >= Duration::from_millis(2_000)
            || (elapsed.saturating_sub(first) >= Duration::from_millis(750)
                && elapsed.saturating_sub(self.last_change)
                    >= Duration::from_millis(350))
    }
}

#[cfg(test)]
mod adaptive_tests {
    use super::*;

    fn ms(value: u64) -> Duration {
        Duration::from_millis(value)
    }

    #[test]
    fn no_useful_hits_keep_full_window() {
        let mut window = AdaptiveWindow::new(ms(12_000));
        for elapsed in [0, 100, 1_000, 11_999] {
            assert!(!window.finished(ms(elapsed), 0));
        }
        assert!(window.finished(ms(12_000), 0));
    }

    #[test]
    fn initial_batch_waits_for_settle_and_late_burst() {
        let mut window = AdaptiveWindow::new(ms(12_000));
        assert!(!window.finished(ms(100), 1));
        assert!(!window.finished(ms(800), 1));
        assert!(!window.finished(ms(850), 2));
        assert!(!window.finished(ms(1_199), 2));
        assert!(window.finished(ms(1_200), 2));
    }

    #[test]
    fn continuous_results_and_late_first_hit_are_bounded() {
        let mut window = AdaptiveWindow::new(ms(12_000));
        for i in 1..=20 {
            assert!(!window.finished(ms(i * 100), i as usize));
        }
        assert!(window.finished(ms(2_100), 21));
        let mut late = AdaptiveWindow::new(ms(12_000));
        assert!(!late.finished(ms(11_900), 1));
        assert!(late.finished(ms(12_000), 1));
    }
}
