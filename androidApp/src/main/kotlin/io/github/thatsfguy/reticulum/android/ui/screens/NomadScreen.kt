package io.github.thatsfguy.reticulum.android.ui.screens

import io.github.thatsfguy.reticulum.android.MainActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import io.github.thatsfguy.reticulum.android.ui.ReticulumViewModel
import io.github.thatsfguy.reticulum.engine.ReticulumEngine
import io.github.thatsfguy.reticulum.nomad.LinkTarget
import io.github.thatsfguy.reticulum.nomad.parseLinkTarget
import io.github.thatsfguy.reticulum.nomad.FormSubmitTarget
import io.github.thatsfguy.reticulum.nomad.parseFormSubmitTarget
import io.github.thatsfguy.reticulum.nomad.formatNomadUrl
import io.github.thatsfguy.reticulum.nomad.parseNomadUrl
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.store.StoredNomadPage
import kotlinx.coroutines.launch

/**
 * NomadNet directory + reader. Tap a node and the page fetch fires
 * automatically — no extra button — opening a Reticulum Link and
 * requesting `/page/index.mu` (upstream NomadNet default; spec §11).
 *
 * v0.1.48 additions:
 *   - search box at the top
 *   - filter chips: All / Favorites / Cached
 *   - star icon to toggle favorite per node
 *   - dot indicator on rows that have at least one cached page
 *   - cache-first render: prior page bytes show instantly, fresh fetch
 *     runs in the background and swaps on success. "Last pulled Xm ago"
 *     under the page title; Clear cache button alongside Reload/Back.
 */
private const val DEFAULT_PAGE_PATH = "/page/index.mu"

@Composable
fun NomadScreen(viewModel: ReticulumViewModel) {
    val destinations by viewModel.allDestinations.collectAsState(initial = emptyList())
    val cachedHashes by viewModel.cachedNomadDestHashes.collectAsState(initial = emptySet())
    val search by viewModel.nomadSearch.collectAsState()
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // Same shape as Rooms: Favorites always shown in full; search only
    // filters the Discovered section.
    val allNomadNodes = remember(destinations) {
        destinations.filter { it.appName == "nomadnetwork.node" }
    }
    val favoriteNodes = remember(allNomadNodes) { allNomadNodes.filter { it.favorite } }
    val allDiscoveredNodes = remember(allNomadNodes) { allNomadNodes.filter { !it.favorite } }
    val discoveredNodes = remember(allDiscoveredNodes, search) {
        val q = search.trim().lowercase()
        if (q.isEmpty()) allDiscoveredNodes
        else allDiscoveredNodes.filter { d ->
            d.effectiveDisplayName.lowercase().contains(q) ||
                d.displayName.lowercase().contains(q) ||
                d.hash.lowercase().contains(q)
        }
    }

    var selected by remember { mutableStateOf<StoredDestination?>(null) }
    /** 0 = Nodes directory, 1 = Browser. Picking a node (or deep-link)
     *  switches to Browser; empty-history Back / list icon returns to
     *  Nodes without clearing the browser session. */
    var pane by remember { mutableIntStateOf(0) }
    var pageState by remember { mutableStateOf<PageState>(PageState.Loading) }
    var cacheInfo by remember { mutableStateOf<StoredNomadPage?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    /** Currently-displayed path. Reset to DEFAULT_PAGE_PATH whenever
     *  the user picks a fresh node from the directory; cross-node
     *  link follow assigns the target path explicitly. Same de-
     *  keying as historyStack (v1.2.16) — `remember(selected)` used
     *  to reset this on every selected change, which lost the
     *  cross-node link's explicit `tgt.path` assignment to the new
     *  MutableState's default initialiser. */
    var currentPath by remember { mutableStateOf(DEFAULT_PAGE_PATH) }

    // Deep-link consumer for LXMF-message Nomad links. When the
    // user taps a `<destHash>:/path` link inside a conversation
    // bubble, the linkifier dispatches via
    // ReticulumViewModel.openNomadPageFromLink, which queues a
    // NomadDeepLink and routes the user to the Nomad tab via
    // MainActivity. Here we observe the queued selection — when it
    // matches a known destination, drop the user straight into that
    // page at the requested path; if unknown, the destination was
    // added as a manual stub by the ViewModel so we can still load.
    val pendingNomad by viewModel.pendingNomadSelection.collectAsState()
    LaunchedEffect(pendingNomad, destinations) {
        val target = pendingNomad ?: return@LaunchedEffect
        val match = destinations.firstOrNull { it.hash == target.hash }
        if (match != null) {
            selected = match
            currentPath = target.path
            cacheInfo = null
            pageState = PageState.Loading
            reloadKey++
            pane = 1
            viewModel.consumePendingNomadSelection()
        }
        // If `match` is null the manual stub the ViewModel inserted
        // hasn't propagated through the destinations flow yet — the
        // re-fire of this LaunchedEffect on the next emission will
        // pick it up. consumePendingNomadSelection stays uncalled
        // until we successfully navigate.
    }
    /** When set, the next fetch carries form-field POST data. The map
     *  is `{ "field_<name>": "<value>", ... }` — engine forwards it as
     *  envelope element [2] (msgpack map). Upstream Node.py:109-111
     *  reads keys starting with `field_` / `var_` into env vars for
     *  the executable page handler. Cleared after the fetch completes
     *  so a subsequent Reload is a plain GET of `currentPath`. */
    var pendingPostData by remember { mutableStateOf<Map<String, String>?>(null) }
    /** v0.1.64: per-session opt-in to send a LINKIDENTIFY packet on
     *  the link before the REQUEST. Default off (privacy — see
     *  SPEC.md §11.6.6). User flips this from the node-view header
     *  when fetching ALLOW_LIST pages (operator-restricted areas,
     *  member-only chatrooms). Resets when the user backs out to
     *  the node list. */
    var identifyOnFetch by remember(selected) { mutableStateOf(false) }
    /** v0.1.65 / v1.2.15: navigation history per Browser.py:907-936.
     *  Each entry is `(dest, path, postData?)`; pushed when the
     *  user follows a link; popped on Back. Multi-step Back across
     *  same-node AND cross-node nav.
     *
     *  POST replay (v1.2.15): tester reported "search engine →
     *  search results → click a result → Back goes to the empty
     *  search form, not the results." Cause: form-submit results
     *  pages are POST-driven, and Back was re-fetching the path as
     *  a fresh GET. Now each history entry carries the POST data
     *  that brought us to it; Back restores both the path AND the
     *  POST data, so a result-page-with-query re-renders on Back
     *  rather than reverting to the empty form.
     *
     *  v1.2.16: do NOT key `remember` on `selected`. The previous
     *  `remember(selected)` wiped the list every time the user
     *  followed a cross-node link (because `selected` changes when
     *  hopping to the new dest), so Back found an empty stack and
     *  walked all the way out to the directory. The stack is reset
     *  explicitly in `onPick` when the user picks a fresh node from
     *  the directory; cross-node link follow now preserves history
     *  as intended. */
    val historyStack = remember { mutableStateListOf<NomadHistoryEntry>() }
    val forwardStack = remember { mutableStateListOf<NomadHistoryEntry>() }
    var urlInput by remember { mutableStateOf("") }
    /** Tracks the POST data that produced the currently-rendered
     *  page (null = the page was a GET). The fetch LaunchedEffect
     *  writes this AFTER each successful fetch so a subsequent link
     *  click captures it onto the history entry. Same de-keying
     *  rationale as historyStack: keep across cross-node hops; the
     *  LaunchedEffect will overwrite when the new page renders. */
    var currentPagePostData by remember { mutableStateOf<Map<String, String>?>(null) }

    // /file/ download flow — SAF round-trip via two state slots.
    //   fileInFlight  → "fetching file from the server, link path
    //                    used as a UI key" (disables further taps,
    //                    shows the progress chip).
    //   pendingFile   → "fetch complete, file bytes held in memory
    //                    waiting for the user's SAF picker choice"
    //                    (we can't pass ByteArray through the ARC
    //                    Intent — too large + Bundle limits).
    // Downloaded /file/ bytes are saved via MainActivity's Activity-level
    // save launcher (MainActivity.saveFile) — the per-composition
    // CreateDocument launcher dropped its result on some devices, leaving a
    // 0-byte file. fileInFlight marks an in-progress fetch (drives the
    // spinner); fileError surfaces a fetch/save problem.
    var fileInFlight by remember(selected) { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // v0.1.71: re-read the StoredDestination from the live `destinations`
    // flow on every recomposition rather than holding the snapshot the
    // user picked. Without this, toggling favorite (or any field) via
    // viewModel.setDestinationFavorite updates the repo but `selected`
    // still points at the OLD object — the star icon stays drawn at its
    // pre-toggle state until the user backs out and re-selects the node.
    val current = selected?.let { sel -> destinations.firstOrNull { it.hash == sel.hash } ?: sel }
    // v0.1.72: key on hash, NOT the StoredDestination object. The
    // v0.1.71 favorite-fix made `current` a fresh derivation from the
    // destinations flow on every recomposition. StoredDestination is a
    // data class but several fields are ByteArray (publicKey, destHash,
    // nameHash, ratchetPub, nextHop) — Kotlin data-class equals() uses
    // reference equality on arrays, so each flow re-emission produces an
    // "unequal" object even when bytes are identical, retriggering this
    // LaunchedEffect every time any destination field changes (RSSI,
    // lastSeen). The fix is to key on stable scalars: the hash string
    // identifies the node, and reloadKey already exists for explicit
    // refetches.
    LaunchedEffect(current?.hash, currentPath, reloadKey) {
        if (current != null) {
            val activeData = pendingPostData
            val isPost = activeData != null
            // GETs render cache-first; POSTs always show a fresh spinner
            // (cache key is by path only — a form submission is body-
            // dependent and shouldn't be served stale).
            if (!isPost) {
                val cached = viewModel.loadCachedNomadPageNow(current.hash, currentPath)
                cacheInfo = cached
                pageState = if (cached != null) PageState.Loaded(cached.source) else PageState.Loading
            } else {
                pageState = PageState.Loading
            }

            val result = viewModel.fetchNomadPageNow(
                current.hash, currentPath, activeData, identify = identifyOnFetch,
            )
            pendingPostData = null
            // Remember what POST data (if any) produced what we're
            // now showing — link clicks read this when pushing
            // history entries so Back can replay the same submit.
            currentPagePostData = activeData
            pageState = result.fold(
                onSuccess = { source ->
                    if (!isPost) {
                        cacheInfo = viewModel.loadCachedNomadPageNow(current.hash, currentPath)
                    }
                    PageState.Loaded(source)
                },
                onFailure = { err ->
                    val fallback = cacheInfo
                    if (fallback != null) PageState.LoadedStale(fallback.source, err.message ?: "fetch failed")
                    else PageState.Error(err.message ?: "fetch failed")
                },
            )
        }
    }

    // History-aware Back, shared by the nav-bar Back button AND the
    // Android system back button. v0.1.65 / v1.2.15: pop the prior
    // (dest, path, postData?) and restore all three — including
    // replaying POST data so a back-from-result lands on the full
    // results page, not the empty form. When the stack is empty we
    // drop back to the directory list (selected = null).
    fun restoreHistoryEntry(entry: NomadHistoryEntry) {
        val cur = current ?: return
        if (entry.dest.hash != cur.hash) {
            cacheInfo = null
            pageState = PageState.Loading
            selected = entry.dest
        }
        currentPath = entry.path
        pendingPostData = entry.postData
        reloadKey++
    }

    fun pushCurrentToHistory() {
        val cur = current ?: return
        historyStack += NomadHistoryEntry(cur, currentPath, currentPagePostData)
        forwardStack.clear()
    }

    fun navigateBack() {
        val cur = current ?: return
        val popped = if (historyStack.isNotEmpty()) historyStack.removeAt(historyStack.lastIndex) else null
        if (popped != null) {
            forwardStack += NomadHistoryEntry(cur, currentPath, currentPagePostData)
            restoreHistoryEntry(popped)
        } else {
            // Empty in-page history → Nodes pane; keep the browser
            // session so switching back to Browser resumes this page.
            pane = 0
        }
    }

    fun navigateForward() {
        val cur = current ?: return
        if (forwardStack.isEmpty()) return
        val next = forwardStack.removeAt(forwardStack.lastIndex)
        historyStack += NomadHistoryEntry(cur, currentPath, currentPagePostData)
        restoreHistoryEntry(next)
    }

    fun exitToNodeList() {
        pane = 0
    }

    fun navigateToUrl(raw: String) {
        val parsed = parseNomadUrl(raw, current?.hash)
        if (parsed == null) {
            if (current != null) {
                pageState = PageState.Error("Invalid Nomad URL. Try nodehash:/page/index.mu")
            }
            return
        }
        forwardStack.clear()
        coroutineScope.launch {
            val cur = current
            if (cur == null) {
                historyStack.clear()
                forwardStack.clear()
                currentPagePostData = null
                val dest = viewModel.resolveOrPrepareDestination(parsed.nodeHashHex)
                if (dest == null) return@launch
                cacheInfo = null
                pageState = PageState.Loading
                selected = dest
                currentPath = parsed.path
                reloadKey++
                pane = 1
                return@launch
            }
            if (parsed.nodeHashHex != cur.hash) {
                pushCurrentToHistory()
                val dest = viewModel.resolveOrPrepareDestination(parsed.nodeHashHex)
                if (dest == null) {
                    pageState = PageState.Error(
                        "Could not resolve destination ${parsed.nodeHashHex.take(8)}… " +
                            "(service not bound or invalid hash)",
                    )
                    return@launch
                }
                cacheInfo = null
                pageState = PageState.Loading
                selected = dest
            } else if (parsed.path != currentPath) {
                pushCurrentToHistory()
            }
            currentPath = parsed.path
            reloadKey++
            pane = 1
        }
    }

    LaunchedEffect(current?.hash, currentPath) {
        current?.let { urlInput = formatNomadUrl(it.hash, currentPath) }
    }

    // Issue #36: route the Android system back button through the same
    // in-page history as the nav-bar Back. Only enabled while a page is
    // open; on the directory list we leave it disabled so back propagates
    // to the Activity and leaves the Nomad tab normally (previously it
    // ALWAYS fell through, dropping the user onto another tab mid-browse).
    LaunchedEffect(destinations, selected?.hash) {
        val hash = selected?.hash ?: return@LaunchedEffect
        val fresh = destinations.firstOrNull { it.hash == hash } ?: return@LaunchedEffect
        if (fresh != selected) selected = fresh
    }

    // System back: in-page history while on Browser; empty history
    // switches to Nodes. On Nodes, leave disabled so Activity handles it.
    BackHandler(enabled = pane == 1) {
        if (current != null && historyStack.isNotEmpty()) navigateBack()
        else pane = 0
    }

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = pane) {
            Tab(
                selected = pane == 0,
                onClick = { pane = 0 },
                text = { Text("Nodes") },
            )
            Tab(
                selected = pane == 1,
                onClick = { pane = 1 },
                text = { Text("Browser") },
            )
        }
        when (pane) {
            0 -> NomadList(
                favorites = favoriteNodes,
                allDiscovered = allDiscoveredNodes,
                filteredDiscovered = discoveredNodes,
                search = search,
                onSearchChange = viewModel::setNomadSearch,
                cachedHashes = cachedHashes,
                onPick = {
                    selected = it
                    cacheInfo = null
                    pageState = PageState.Loading
                    historyStack.clear()
                    forwardStack.clear()
                    currentPagePostData = null
                    currentPath = DEFAULT_PAGE_PATH
                    pane = 1
                },
                onToggleFavorite = { d -> viewModel.setDestinationFavorite(d.hash, !d.favorite) },
            )
            else -> {
                if (current == null) {
                    Column(Modifier.fillMaxSize()) {
                        NomadBrowserBar(
                            urlInput = urlInput,
                            onUrlInputChange = { urlInput = it },
                            canReload = false,
                            canGoForward = false,
                            onHome = { urlInput = "" },
                            onReload = {},
                            onBack = { pane = 0 },
                            onForward = {},
                            onGo = { navigateToUrl(urlInput) },
                        )
                        Box(
                            Modifier.fillMaxSize().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Pick a node from the Nodes tab, or enter a Nomad URL above.",
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                            )
                        }
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        FileDownloadStatus(
                            inflightPath = fileInFlight,
                            error = fileError,
                            onDismissError = { fileError = null },
                        )
                        NomadNodeView(
                            node = current,
                            currentPath = currentPath,
                            pageState = pageState,
                            cacheInfo = cacheInfo,
                            identifyOnFetch = identifyOnFetch,
                            urlInput = urlInput,
                            onUrlInputChange = { urlInput = it },
                            canGoForward = forwardStack.isNotEmpty(),
                            onGoHome = { navigateToUrl(formatNomadUrl(current.hash, DEFAULT_PAGE_PATH)) },
                            onGoToUrl = { navigateToUrl(it) },
                            onForward = { navigateForward() },
                            onToggleIdentify = {
                                identifyOnFetch = !identifyOnFetch
                                reloadKey++
                            },
                            onReload = { reloadKey++ },
                            onClearCache = {
                                viewModel.clearNomadPageCache(current.hash, currentPath) {
                                    cacheInfo = null
                                    reloadKey++
                                }
                            },
                            onBack = { navigateBack() },
                            onExitToList = { exitToNodeList() },
                            onToggleFavorite = { viewModel.setDestinationFavorite(current.hash, !current.favorite) },
                            onLinkClick = { target ->
                // v0.1.56: dispatch via parseLinkTarget — covers same-node,
                // cross-node `<hex>:/path`, bare hash, `nnn@<hex>` shorthand,
                // and `lxmf@<hex>` (out-of-scope for browser, surfaced as
                // a help message). Mirrors upstream Browser.py:184-259.
                when (val tgt = parseLinkTarget(target)) {
                    is LinkTarget.SameNode -> {
                        // /file/<path> → download flow, not page nav.
                        // Server-side `Node.py:115-127` resolves /file/
                        // against the node's filespath and the link
                        // layer wraps the response with has_metadata=1
                        // per SPEC §10.2 step 1; our fetchNomadFile
                        // extracts the bytes + filename from the
                        // §10.4 ADV + metadata prefix.
                        if (tgt.path.startsWith("/file/")) {
                            if (fileInFlight != null) {
                                // Concurrent /file/ tap — just ignore.
                                // Reticulum links serialize requests
                                // through the link's responseDeferred;
                                // we mirror that on the UI side so the
                                // user can't queue 5 downloads at once.
                            } else {
                                fileInFlight = tgt.path
                                fileError = null
                                coroutineScope.launch {
                                    val result = viewModel.fetchNomadFileNow(
                                        destinationHash = current.hash,
                                        path = tgt.path,
                                        identify = identifyOnFetch,
                                    )
                                    result.onSuccess { file ->
                                        // Hand the downloaded bytes to
                                        // MainActivity's Activity-level save
                                        // launcher (the per-composition one
                                        // dropped its result → 0-byte files).
                                        val activity = context.findActivity() as? MainActivity
                                        if (activity != null) {
                                            activity.saveFile(file.filename, file.bytes)
                                        } else {
                                            fileError = "could not open save dialog"
                                        }
                                        fileInFlight = null
                                    }.onFailure {
                                        fileError = "/file/ download failed: ${it.message}"
                                        fileInFlight = null
                                    }
                                }
                            }
                        } else {
                            // v0.1.65: push current (dest, path, postData)
                            // onto the history stack BEFORE navigating
                            // so Back can walk back through visited
                            // pages — including replaying any POST that
                            // produced the page we're leaving.
                            pushCurrentToHistory()
                            currentPath = tgt.path
                        }
                    }
                    is LinkTarget.CrossNode -> {
                        // Resolve the target destination — uses the announce-
                        // populated record if we have one, otherwise inserts
                        // a manual stub + kicks a path request. Then swap
                        // selection so the LaunchedEffect re-fires for the
                        // new (destHash, path) tuple.
                        coroutineScope.launch {
                            val dest = viewModel.resolveOrPrepareDestination(tgt.destHashHex)
                            if (dest != null) {
                                pushCurrentToHistory()
                                cacheInfo = null
                                pageState = PageState.Loading
                                selected = dest
                                currentPath = tgt.path
                            } else {
                                pageState = PageState.Error(
                                    "Could not resolve destination ${tgt.destHashHex.take(8)}… " +
                                        "(service not bound or invalid hash)"
                                )
                            }
                        }
                    }
                    is LinkTarget.Lxmf -> {
                        // Resolve / create the destination stub so it shows
                        // up in the Messages tab list, then route through
                        // the same `pendingOpenContact` deep-link signal a
                        // notification tap uses (v0.1.84). The Activity
                        // collector switches the NavController to Messages
                        // and selects the conversation in one step.
                        coroutineScope.launch {
                            val dest = viewModel.resolveOrPrepareDestination(tgt.destHashHex)
                            if (dest != null) {
                                viewModel.toggleFavorite(tgt.destHashHex, true)
                                viewModel.openContact(tgt.destHashHex)
                            } else {
                                pageState = PageState.Error(
                                    "Could not resolve LXMF target ${tgt.destHashHex.take(8)}… " +
                                        "(service not bound or invalid hash)"
                                )
                            }
                        }
                    }
                    is LinkTarget.Unknown -> {
                        pageState = PageState.Error("Unrecognized link: $target")
                    }
                }
            },
            onSubmitForm = { target, prefixedData ->
                // v0.1.61: MicronView's buildSubmitData already adds the
                // `field_` / `var_` prefixes per Browser.py:198-241 and
                // omits unchecked checkboxes per :226-241. We forward
                // the dict verbatim — engine msgpack-encodes the envelope
                // once (pre-encoding here would land as msgpack bin in
                // slot [2] and silently break form submission, see
                // v0.1.53 fix).
                //
                // v1.2.17: dispatch on the form-target's *kind*, not
                // just its path. MeshChat-style nodes and the
                // NomadSearch reference service emit cross-node form
                // actions (`<32hex>:/page/q.mu`) — pre-v1.2.17 we ran
                // those through `resolveSubmitPath` which silently
                // collapsed them to `currentPath`, so the POST went
                // nowhere useful and the page just refreshed in place.
                // `parseFormSubmitTarget` returns a sealed
                // [FormSubmitTarget] that lets us:
                //   - SameNode: keep prior v0.1.87 behavior
                //   - CrossNode: resolve / path-discover the dest,
                //     push history, swap selected + currentPath, then
                //     POST. Mirrors the onLinkClick CrossNode branch
                //     above plus pendingPostData.
                //   - Self (empty / `:` / lxmf / garbage): submit
                //     against the current page with no nav change.
                when (val resolved = parseFormSubmitTarget(currentPath, target)) {
                    is FormSubmitTarget.SameNode -> {
                        pendingPostData = prefixedData
                        if (resolved.path != currentPath) {
                            pushCurrentToHistory()
                        }
                        currentPath = resolved.path
                        reloadKey++
                    }
                    is FormSubmitTarget.CrossNode -> {
                        coroutineScope.launch {
                            val dest = viewModel.resolveOrPrepareDestination(resolved.destHashHex)
                            if (dest != null) {
                                pushCurrentToHistory()
                                pendingPostData = prefixedData
                                cacheInfo = null
                                pageState = PageState.Loading
                                selected = dest
                                currentPath = resolved.path
                                reloadKey++
                            } else {
                                pageState = PageState.Error(
                                    "Could not resolve form target ${resolved.destHashHex.take(8)}… " +
                                        "(service not bound or invalid hash)"
                                )
                            }
                        }
                    }
                    is FormSubmitTarget.Self -> {
                        // Empty / `:` / lxmf / garbage target →
                        // self-submit. No nav, no history push.
                        pendingPostData = prefixedData
                        reloadKey++
                    }
                }
            },
            fetchPartial = { url, fields ->
                // v0.1.67: partials fetch from the CURRENT node, not
                // any other destination. The link reuse cache from
                // v0.1.66 means this doesn't pay a fresh LRPROOF for
                // each partial — they share the active link with the
                // host page. fields are passed as a `var_<key>=value`
                // dict so the server's partial handler can use them.
                val varData: Map<String, String>? = fields.takeIf { it.isNotEmpty() }
                    ?.mapNotNull { entry ->
                        val eq = entry.indexOf('=')
                        if (eq > 0) "var_${entry.substring(0, eq)}" to entry.substring(eq + 1)
                        else null
                    }?.toMap()
                viewModel.fetchNomadPageNow(
                    current.hash, url, data = varData, identify = identifyOnFetch,
                ).getOrNull()
            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Small banner above the page body that surfaces /file/ download
 * progress and errors. Stays out of the way (collapsed) when nothing
 * is happening; shows a one-line indicator otherwise. The success
 * case is implicit — the Android Storage Access Framework picker
 * appears, which the user can't miss.
 */
@Composable
private fun FileDownloadStatus(
    inflightPath: String?,
    error: String?,
    onDismissError: () -> Unit,
) {
    when {
        inflightPath != null -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "Downloading ${inflightPath.substringAfterLast('/')}…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        error != null -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissError) {
                    Icon(
                        Icons.Default.Clear,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun NomadSearchBar(
    search: String,
    onSearchChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = search,
        onValueChange = onSearchChange,
        placeholder = { Text("Search discovered nodes") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = if (search.isNotEmpty()) {
            {
                IconButton(onClick = { onSearchChange("") }) {
                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                }
            }
        } else null,
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private sealed class PageState {
    object Loading : PageState()
    data class Loaded(val source: String) : PageState()
    /** Fresh fetch failed but we have cached content — show cache + a notice. */
    data class LoadedStale(val source: String, val staleReason: String) : PageState()
    data class Error(val message: String) : PageState()
}

/** One entry on the Nomad in-page Back stack. Carries the POST
 *  data that produced the page so a Back replay can re-issue the
 *  form submit instead of reverting to an empty GET. v1.2.15. */
private data class NomadHistoryEntry(
    val dest: StoredDestination,
    val path: String,
    val postData: Map<String, String>?,
)

@Composable
private fun NomadList(
    favorites: List<StoredDestination>,
    allDiscovered: List<StoredDestination>,
    filteredDiscovered: List<StoredDestination>,
    search: String,
    onSearchChange: (String) -> Unit,
    cachedHashes: Set<String>,
    onPick: (StoredDestination) -> Unit,
    onToggleFavorite: (StoredDestination) -> Unit,
) {
    if (favorites.isEmpty() && allDiscovered.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No NomadNet nodes yet. They show up here automatically as " +
                    "`nomadnetwork.node` announces arrive — star one to pin it " +
                    "under Favorites.",
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        return
    }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize()) {
        if (allDiscovered.isNotEmpty()) {
            NomadSearchBar(
                search = search,
                onSearchChange = onSearchChange,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (favorites.isNotEmpty()) {
                item("favorites_header") {
                    Text(
                        "Favorites",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(favorites, key = { "f-${it.hash}" }) { node ->
                    NomadNodeRow(
                        node = node,
                        ageMs = (now - node.lastSeen).coerceAtLeast(0),
                        cached = node.hash in cachedHashes,
                        onPick = onPick,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }
            if (allDiscovered.isNotEmpty()) {
                item("discovered_header") {
                    Text(
                        "Discovered on the network",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                if (filteredDiscovered.isEmpty()) {
                    item("discovered_empty") {
                        Text(
                            "No nodes match \"$search\".",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                } else {
                    items(filteredDiscovered, key = { "d-${it.hash}" }) { node ->
                        NomadNodeRow(
                            node = node,
                            ageMs = (now - node.lastSeen).coerceAtLeast(0),
                            cached = node.hash in cachedHashes,
                            onPick = onPick,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NomadNodeRow(
    node: StoredDestination,
    ageMs: Long,
    cached: Boolean,
    onPick: (StoredDestination) -> Unit,
    onToggleFavorite: (StoredDestination) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(node) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cached) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(Modifier.size(8.dp))
                }
                Text(
                    node.effectiveDisplayName.ifBlank { node.appLabel ?: "(unnamed)" },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Text(
                node.hash,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val meta = buildList {
                if (node.hopCount > 0) add("${node.hopCount} hop${if (node.hopCount != 1) "s" else ""}")
                node.rssi?.let { add("RSSI $it dBm") }
                add("seen ${formatAge(ageMs)}")
                if (cached) add("cached")
            }
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onToggleFavorite(node) }) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = if (node.favorite) "Unfavorite" else "Favorite",
                tint = if (node.favorite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun formatAge(ms: Long): String = when {
    ms < 60_000L            -> "${ms / 1000}s ago"
    ms < 60 * 60_000L       -> "${ms / 60_000L}m ago"
    ms < 24 * 60 * 60_000L  -> "${ms / (60 * 60_000L)}h ago"
    else                    -> "${ms / (24 * 60 * 60_000L)}d ago"
}

@Composable
private fun NomadNodeView(
    node: StoredDestination,
    currentPath: String,
    pageState: PageState,
    cacheInfo: StoredNomadPage?,
    identifyOnFetch: Boolean = false,
    urlInput: String = "",
    onUrlInputChange: (String) -> Unit = {},
    canGoForward: Boolean = false,
    onGoHome: () -> Unit = {},
    onGoToUrl: (String) -> Unit = {},
    onForward: () -> Unit = {},
    onExitToList: () -> Unit = {},
    onToggleIdentify: () -> Unit = {},
    onReload: () -> Unit,
    onClearCache: () -> Unit,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit = {},
    onLinkClick: (target: String) -> Unit = {},
    onSubmitForm: (target: String, fields: Map<String, String>) -> Unit = { _, _ -> },
    fetchPartial: suspend (String, List<String>) -> String? = { _, _ -> null },
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        NomadBrowserBar(
            urlInput = urlInput,
            onUrlInputChange = onUrlInputChange,
            canReload = pageState !is PageState.Loading,
            canGoForward = canGoForward,
            onExitToList = onExitToList,
            onHome = onGoHome,
            onReload = onReload,
            onBack = onBack,
            onForward = onForward,
            onGo = { onGoToUrl(urlInput) },
        )
        // v0.1.79: icon nav bar in place of word-button row. Icons are
        // spread evenly across the width with a small caption under each
        // so the meaning isn't ambiguous. Lock toggles the v0.1.64 opt-in
        // LINKIDENTIFY (sends CTX_LINKIDENTIFY before the REQUEST so
        // ALLOW_LIST pages can authenticate us); default off because
        // identifying pins our identity hash to the node operator.
        // Share fires the system share sheet with the upstream-NomadNet
        // cross-node link format `<destHash>:/path` (Browser.py:248
        // parser entry-point). Pasteable into any NomadNet client; not
        // yet tappable in our own LXMF messages until the linkifier
        // gains the same regex — see the v1.2.14 / ios-v1.0.78 follow-up.
        NomadNavBar(
            favorite = node.favorite,
            identifyOnFetch = identifyOnFetch,
            canClearCache = cacheInfo != null,
            canReload = pageState !is PageState.Loading,
            onBack = onBack,
            onReload = onReload,
            onToggleFavorite = onToggleFavorite,
            onToggleIdentify = onToggleIdentify,
            onClearCache = onClearCache,
            onShare = {
                val link = "${node.hash}:${currentPath}"
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, link)
                    putExtra(android.content.Intent.EXTRA_SUBJECT, "Nomad page")
                }
                context.startActivity(android.content.Intent.createChooser(intent, "Share Nomad page"))
            },
        )
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                node.effectiveDisplayName.ifBlank { "(unnamed NomadNet node)" },
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                node.hash,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            cacheInfo?.let { ci ->
                val age = formatAge((System.currentTimeMillis() - ci.fetchedAt).coerceAtLeast(0))
                Text(
                    "Last pulled $age (${ci.byteSize} B)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (pageState is PageState.LoadedStale) {
                Text(
                    "Showing cached version — fresh fetch failed: ${pageState.staleReason}",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        when (pageState) {
            PageState.Loading ->
                Column(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        "Establishing link and requesting $currentPath …",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

            is PageState.Error ->
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fetch failed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        pageState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Tap Reload to retry. Settings → diagnostics log shows " +
                            "LRPROOF / RESPONSE timing if you want to dig in.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    node.telemetry?.takeIf { it.isNotEmpty() }?.let { tel ->
                        Spacer(Modifier.height(8.dp))
                        Text("Telemetry", style = MaterialTheme.typography.titleMedium)
                        Text(
                            tel.entries.joinToString("\n") { "  ${it.key} = ${it.value}" },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }

            is PageState.Loaded ->
                MicronView(
                    source = pageState.source,
                    onLinkClick = onLinkClick,
                    onLinkClickWithFields = onSubmitForm,
                    fetchPartial = fetchPartial,
                )

            is PageState.LoadedStale ->
                MicronView(
                    source = pageState.source,
                    onLinkClick = onLinkClick,
                    onLinkClickWithFields = onSubmitForm,
                    fetchPartial = fetchPartial,
                )
        }
    }
}

@Composable
private fun NomadBrowserBar(
    urlInput: String,
    onUrlInputChange: (String) -> Unit,
    canReload: Boolean,
    canGoForward: Boolean,
    onExitToList: (() -> Unit)? = null,
    onHome: () -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onGo: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    val disabled = muted.copy(alpha = 0.35f)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (onExitToList != null) {
                IconButton(onClick = onExitToList) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Node list", tint = accent)
                }
            }
            IconButton(onClick = onHome) {
                Icon(Icons.Default.Home, contentDescription = "Home", tint = accent)
            }
            IconButton(onClick = onReload, enabled = canReload) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = "Reload",
                    tint = if (canReload) accent else disabled,
                )
            }
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = accent)
            }
            IconButton(onClick = onForward, enabled = canGoForward) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Forward",
                    tint = if (canGoForward) accent else disabled,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            OutlinedTextField(
                value = urlInput,
                onValueChange = onUrlInputChange,
                placeholder = { Text("nodehash:/page/index.mu", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onGo, enabled = urlInput.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Go",
                    tint = if (urlInput.isNotBlank()) accent else disabled,
                )
            }
        }
    }
}

@Composable
private fun NomadNavBar(
    favorite: Boolean,
    identifyOnFetch: Boolean,
    canClearCache: Boolean,
    canReload: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleIdentify: () -> Unit,
    onClearCache: () -> Unit,
    onShare: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NavBarButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            label = "Back",
            tint = muted,
            onClick = onBack,
        )
        NavBarButton(
            icon = Icons.Default.Refresh,
            label = "Reload",
            tint = if (canReload) muted else muted.copy(alpha = 0.4f),
            enabled = canReload,
            onClick = onReload,
        )
        NavBarButton(
            icon = Icons.Default.Star,
            label = if (favorite) "Favorited" else "Favorite",
            tint = if (favorite) accent else muted.copy(alpha = 0.5f),
            onClick = onToggleFavorite,
        )
        NavBarButton(
            icon = Icons.Default.Share,
            label = "Share",
            tint = muted,
            onClick = onShare,
        )
        NavBarButton(
            icon = Icons.Default.Lock,
            label = if (identifyOnFetch) "Identified" else "Anonymous",
            tint = if (identifyOnFetch) accent else muted,
            onClick = onToggleIdentify,
        )
        NavBarButton(
            icon = Icons.Default.Delete,
            label = "Clear cache",
            tint = if (canClearCache) muted else muted.copy(alpha = 0.4f),
            enabled = canClearCache,
            onClick = onClearCache,
        )
    }
}

@Composable
private fun NavBarButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
        )
    }
}
