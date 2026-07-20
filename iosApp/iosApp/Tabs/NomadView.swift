// SPDX-License-Identifier: MIT
//
// Nomad tab — list nomadnetwork.node destinations, fetch
// /page/index.mu via the engine when a node is tapped, render the
// resulting micron document with the full MicronView renderer.
//
// Scope: list browsing + rich micron rendering (MicronView.swift),
// history-aware Back across same-node AND cross-node nav, per-row
// favorite toggle, per-row meta line (hops/RSSI/age), page-level
// identify toggle, per-page clear-cache, and /file/ downloads.
// In-page links are dispatched through the shared `parseLinkTarget`
// (commonMain) so same-node, cross-node `<hex>:/path`, and `lxmf@`
// links all route the same way Android's NomadScreen does.

import Shared
import SwiftUI
import UniformTypeIdentifiers

/// RetiNet / MeshChatX-style browser chrome — nav buttons on one row,
/// URL field + Go on the next so the address bar stays visible on narrow phones.
private struct NomadBrowserChrome: View {
    @Binding var urlInput: String
    var canReload: Bool
    var canGoForward: Bool
    let onHome: () -> Void
    let onReload: () -> Void
    let onBack: () -> Void
    let onForward: () -> Void
    let onGo: () -> Void

    var body: some View {
        VStack(spacing: 6) {
            HStack(spacing: 4) {
                browserButton("house", disabled: false, action: onHome)
                browserButton("arrow.clockwise", disabled: !canReload, action: onReload)
                browserButton("chevron.left", disabled: false, action: onBack)
                browserButton("chevron.right", disabled: !canGoForward, action: onForward)
                Spacer(minLength: 0)
            }
            HStack(spacing: 6) {
                TextField("nodehash:/page/index.mu", text: $urlInput)
                    .font(.caption.monospaced())
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(.horizontal, 8)
                    .padding(.vertical, 6)
                    .background(Color.secondary.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                    .frame(maxWidth: .infinity)
                    .onSubmit { onGo() }
                browserButton(
                    "arrow.right.circle.fill",
                    disabled: urlInput.trimmingCharacters(in: .whitespaces).isEmpty,
                    action: onGo
                )
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .background(Color.secondary.opacity(0.06))
    }

    private func browserButton(_ systemName: String, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.system(size: 16))
                .frame(width: 30, height: 30)
        }
        .disabled(disabled)
        .foregroundStyle(disabled ? Color.secondary.opacity(0.35) : Color.accentColor)
    }
}

struct NomadView: View {
    @EnvironmentObject private var store: ReticulumStore

    /// Target for the Browser pane — set when the user picks a node
    /// from Nodes, enters a URL, or follows an OpenNomadPageEvent deep
    /// link. `sessionId` bumps on every open so NomadPageView remounts
    /// with a fresh history stack even when reopening the same hash.
    struct BrowserSession: Hashable {
        let hash: String
        let path: String
        let sessionId: Int
    }

    private enum Pane: Hashable {
        case nodes
        case browser
    }

    @State private var pane: Pane = .nodes
    @State private var search: String = ""
    @State private var browserUrlInput = ""
    @State private var browserSession: BrowserSession? = nil
    @State private var nextSessionId = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("View", selection: $pane) {
                    Text("Nodes").tag(Pane.nodes)
                    Text("Browser").tag(Pane.browser)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)
                .padding(.vertical, 8)

                ZStack {
                    nodesPane
                        .opacity(pane == .nodes ? 1 : 0)
                        .allowsHitTesting(pane == .nodes)
                        .accessibilityHidden(pane != .nodes)
                    browserPane
                        .opacity(pane == .browser ? 1 : 0)
                        .allowsHitTesting(pane == .browser)
                        .accessibilityHidden(pane != .browser)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .navigationTitle(pane == .nodes ? "Nomad" : "Browser")
            .navigationBarTitleDisplayMode(.inline)
        }
        // Open-Nomad-page deep-link (e.g. a `<destHash>:/path` link
        // tapped in an LXMF message bubble). ContentView already
        // switched the tab; open the Browser pane at that location.
        .onChange(of: store.openNomadPageEvent) { _, new in
            guard let event = new else { return }
            openInBrowser(hash: event.hash, path: event.path)
        }
    }

    // MARK: - Nodes pane

    @ViewBuilder
    private var nodesPane: some View {
        if favorites.isEmpty && allDiscovered.isEmpty {
            ContentUnavailableView(
                "No NomadNet nodes",
                systemImage: "doc.text.magnifyingglass",
                description: Text(emptyMessage)
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            VStack(spacing: 0) {
                if !allDiscovered.isEmpty {
                    HStack {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("Search discovered nodes", text: $search)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        if !search.isEmpty {
                            Button { search = "" } label: {
                                Image(systemName: "xmark.circle.fill")
                            }
                            .buttonStyle(.plain)
                            .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.bar)
                }
                List {
                    if !favorites.isEmpty {
                        Section("Favorites") {
                            ForEach(favorites, id: \.id) { node in
                                NomadRow(
                                    node: node,
                                    onToggleFavorite: { fav in
                                        store.toggleFavorite(hash: node.hash, favorite: fav)
                                    }
                                )
                                .contentShape(Rectangle())
                                .onTapGesture {
                                    openInBrowser(hash: node.hash as String, path: "/page/index.mu")
                                }
                            }
                        }
                    }
                    if !allDiscovered.isEmpty {
                        Section("Discovered on the network") {
                            if filteredDiscovered.isEmpty {
                                Text("No nodes match “\(search)”.")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            } else {
                                ForEach(filteredDiscovered, id: \.id) { node in
                                    NomadRow(
                                        node: node,
                                        onToggleFavorite: { fav in
                                            store.toggleFavorite(hash: node.hash, favorite: fav)
                                        }
                                    )
                                    .contentShape(Rectangle())
                                    .onTapGesture {
                                        openInBrowser(hash: node.hash as String, path: "/page/index.mu")
                                    }
                                }
                            }
                        }
                    }
                }
                .listStyle(.insetGrouped)
                .scrollDismissesKeyboard(.immediately)
            }
        }
    }

    // MARK: - Browser pane

    @ViewBuilder
    private var browserPane: some View {
        if let session = browserSession,
           let node = store.allDestinations.first(where: { ($0.hash as String) == session.hash }) {
            NomadPageView(
                node: node,
                initialPath: session.path,
                isActive: pane == .browser,
                onExitToNodes: { pane = .nodes }
            )
            .id(session.sessionId)
        } else if let session = browserSession {
            // Stub not yet in the store (deep-link / URL bar) — show
            // chrome so the user can retry once the destination lands.
            VStack(spacing: 0) {
                NomadBrowserChrome(
                    urlInput: $browserUrlInput,
                    canReload: false,
                    canGoForward: false,
                    onHome: { browserUrlInput = "" },
                    onReload: {},
                    onBack: { pane = .nodes },
                    onForward: {},
                    onGo: { navigateFromBrowserUrl(browserUrlInput) }
                )
                ContentUnavailableView(
                    "Destination not found",
                    systemImage: "questionmark.circle",
                    description: Text("Waiting for \(session.hash.prefix(8))… to appear in the local store. Re-enter the URL or pick a node from Nodes.")
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            VStack(spacing: 0) {
                NomadBrowserChrome(
                    urlInput: $browserUrlInput,
                    canReload: false,
                    canGoForward: false,
                    onHome: { browserUrlInput = "" },
                    onReload: {},
                    onBack: { pane = .nodes },
                    onForward: {},
                    onGo: { navigateFromBrowserUrl(browserUrlInput) }
                )
                ContentUnavailableView(
                    "No page open",
                    systemImage: "globe",
                    description: Text("Pick a node from the Nodes tab, or enter a Nomad URL above.")
                )
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }

    private func openInBrowser(hash: String, path: String) {
        nextSessionId += 1
        browserSession = BrowserSession(hash: hash, path: path, sessionId: nextSessionId)
        browserUrlInput = LinkTargetKt.formatNomadUrl(nodeHashHex: hash, path: path)
        pane = .browser
    }

    private func navigateFromBrowserUrl(_ raw: String) {
        guard let parsed = LinkTargetKt.parseNomadUrl(raw: raw, currentNodeHash: nil) else { return }
        if !store.allDestinations.contains(where: { ($0.hash as String) == parsed.nodeHashHex }) {
            store.addManualDestination(hashHex: parsed.nodeHashHex, label: "")
            store.requestPath(hashHex: parsed.nodeHashHex)
        }
        openInBrowser(hash: parsed.nodeHashHex, path: parsed.path)
    }

    private var nomadNodes: [StoredDestination] {
        store.allDestinations.filter { $0.appName == "nomadnetwork.node" }
    }

    /// Starred nodes — always shown in full (search does not filter them),
    /// matching Rooms' "My hubs" section.
    private var favorites: [StoredDestination] {
        nomadNodes.filter(\.favorite)
    }

    /// Every non-favorite NomadNet node (pre-search).
    private var allDiscovered: [StoredDestination] {
        nomadNodes.filter { !$0.favorite }
    }

    /// Discovered nodes matching the search box.
    private var filteredDiscovered: [StoredDestination] {
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return allDiscovered }
        return allDiscovered.filter { d in
            d.effectiveDisplayName.lowercased().contains(q) ||
                d.displayName.lowercased().contains(q) ||
                (d.appLabel?.lowercased().contains(q) ?? false) ||
                d.hash.lowercased().contains(q)
        }
    }

    private var emptyMessage: String {
        "Connect a transport on Settings; nodes appear here as their announces arrive. Star one to pin it under Favorites."
    }
}

// MARK: - Row

private struct NomadRow: View {
    let node: StoredDestination
    let onToggleFavorite: (Bool) -> Void

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 2) {
                Text(displayName)
                    .font(.body)
                Text(node.hash)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                if !meta.isEmpty {
                    Text(meta)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
            }
            Spacer()
            // NavigationLink swallows tap on the whole row; wrap the
            // star in a Button with .buttonStyle(.borderless) so iOS
            // routes the tap to the button only, not the row.
            Button { onToggleFavorite(!node.favorite) } label: {
                Image(systemName: node.favorite ? "star.fill" : "star")
                    .foregroundStyle(node.favorite ? Color.accentColor : .secondary)
            }
            .buttonStyle(.borderless)
        }
    }

    private var displayName: String {
        let name = node.effectiveDisplayName
        if !name.isEmpty { return name }
        return node.appLabel ?? "(unnamed)"
    }

    /// `<hops> hop(s) · RSSI <X> dBm · seen Xm ago`.
    /// Mirrors the same shape NodesView.NodeRow uses. The predictive
    /// "stale" / "far — link may be slow" flags (and their red/orange
    /// tint) were dropped for parity with Android: per-network announce
    /// cadences made the staleness guess misleading, so the line is
    /// facts-only in a neutral colour.
    private var meta: String {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let ageMs = max(0, now - node.lastSeen)
        var parts: [String] = []
        if node.hopCount > 0 { parts.append("\(node.hopCount) hop\(node.hopCount == 1 ? "" : "s")") }
        if let r = node.rssi { parts.append("RSSI \(Int(truncating: r)) dBm") }
        if node.lastSeen > 0 { parts.append("seen \(formatAge(ageMs))") }
        return parts.joined(separator: " · ")
    }

    private func formatAge(_ ageMs: Int64) -> String {
        let s = ageMs / 1000
        if s < 60 { return "\(s)s ago" }
        if s < 3600 { return "\(s / 60)m ago" }
        if s < 86_400 { return "\(s / 3600)h ago" }
        return "\(s / 86_400)d ago"
    }
}

// MARK: - Per-page fetch + render

/// One entry on the Nomad page-history stack — carries enough to
/// restore a cross-node hop AND replay the form submit that
/// produced the page. `postData == nil` means "the page was a
/// plain GET, re-fetch as such on Back"; non-nil means "POST these
/// `field_<name>` / `var_<k>` entries on Back so a search-results
/// page comes back with its query, not an empty form".
private struct NomadHistoryEntry {
    let hash: String
    let title: String
    let path: String
    let postData: [String: String]?
}

private struct NomadPageView: View {
    /// The node this view was opened on — kept only to seed the
    /// initial `currentHash` / `currentTitle` and as a favorite
    /// fallback. Cross-node link follow swaps `currentHash` away
    /// from it.
    let node: StoredDestination
    /// Called when the user leaves the browser session for the Nodes
    /// pane (toolbar Nodes button, or Back with an empty in-page
    /// history). Parent switches the segmented control; the page
    /// session stays mounted so returning to Browser resumes it.
    let onExitToNodes: () -> Void
    /// False while the parent shows the Nodes pane (view stays mounted
    /// under opacity 0). Hides this page's nav title/toolbar so they
    /// don't bleed onto the Nodes chrome.
    var isActive: Bool = true
    @EnvironmentObject private var store: ReticulumStore

    /// Destination + title currently being browsed. Starts as `node`
    /// and is reassigned in place when a cross-node link is followed
    /// (mirrors the Android `selected` @State on NomadScreen).
    @State private var currentHash: String
    @State private var currentTitle: String

    @State private var pageState: PageState = .loading
    @State private var path: String = "/page/index.mu"
    /// Stack of previously-visited (node, path, postData?) entries.
    /// Each in-page link follow pushes the current location before
    /// navigating; the leading-edge Back button pops it. Covers
    /// same-node AND cross-node nav, and replays the form POST that
    /// produced the page so a back-from-result lands on the full
    /// search results, not the empty form.
    @State private var history: [NomadHistoryEntry] = []
    @State private var forwardHistory: [NomadHistoryEntry] = []
    @State private var urlInput: String = ""
    /// The POST data (if any) that produced the currently-displayed
    /// page. `nil` when the page was a plain GET. Captured into the
    /// next pushed `NomadHistoryEntry` so Back can replay the submit.
    @State private var currentPagePostData: [String: String]? = nil
    /// Opt-in LINKIDENTIFY before REQUEST. Required for ALLOW_LIST
    /// pages whose handler keys auth on the remote identity hash.
    /// Off by default — identifying reveals the user's long-term
    /// identity hash to the page operator (SPEC.md §11.6.6 privacy
    /// note). Persisted to the server's view of "this user" only;
    /// stored locally just in @State for this page session.
    @State private var identify: Bool = false
    @State private var showClearCacheConfirm: Bool = false

    // /file/ download state — mirrors NomadScreen.kt's pendingFile +
    // fileInFlight pair. The .fileExporter sheet only opens once we
    // have the bytes in memory (can't pre-launch with placeholder
    // content because SwiftUI needs the FileDocument).
    @State private var fileInFlightPath: String? = nil
    @State private var pendingDownload: NomadFileDocument? = nil
    @State private var fileError: String? = nil

    init(
        node: StoredDestination,
        initialPath: String = "/page/index.mu",
        isActive: Bool = true,
        onExitToNodes: @escaping () -> Void
    ) {
        self.node = node
        self.isActive = isActive
        self.onExitToNodes = onExitToNodes
        _currentHash = State(initialValue: node.hash)
        _path = State(initialValue: initialPath)
        let name = node.effectiveDisplayName
        _currentTitle = State(initialValue: name.isEmpty ? "(unnamed)" : name)
    }

    enum PageState: Equatable {
        case loading
        case loaded(String)
        case error(String)
    }

    var body: some View {
        VStack(spacing: 0) {
            browserBar
            ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                switch pageState {
                case .loading:
                    HStack {
                        ProgressView()
                        Text("Establishing link and requesting \(path)…")
                            .font(.callout)
                    }
                    .padding(.vertical)
                case .loaded(let source):
                    MicronView(
                        source: source,
                        onLinkClick: { target in handleLinkClick(target) },
                        onLinkClickWithFields: { target, data in
                            // Form-submit link tap. v1.2.17 /
                            // ios-v1.0.80: dispatch on the form
                            // target's *kind*, not just its path,
                            // so a cross-node form action
                            // (`<32hex>:/page/q.mu`, what MeshChat
                            // and NomadSearch emit) doesn't silently
                            // get collapsed to a self-submit on the
                            // current page. parseFormSubmitTarget
                            // returns a sealed enum with .sameNode /
                            // .crossNode / .self cases; handler:
                            //   - sameNode: pushHistory + set path
                            //     + submit (prior behavior).
                            //   - crossNode: resolve / add manual
                            //     stub if unknown (mirrors
                            //     handleLinkClick's CrossNode
                            //     branch), pushHistory, swap
                            //     currentHash + currentTitle + path,
                            //     then submit on the new dest.
                            //   - self: just submit; no nav.
                            let parsed = LinkTargetKt.parseFormSubmitTarget(
                                currentPath: path, target: target)
                            if let same = parsed as? FormSubmitTarget.SameNode {
                                pushHistory()
                                path = same.path
                                submit(data: data)
                            } else if let cross = parsed as? FormSubmitTarget.CrossNode {
                                if !store.allDestinations.contains(where: { ($0.hash as String) == cross.destHashHex }) {
                                    store.addManualDestination(
                                        hashHex: cross.destHashHex,
                                        label: ""
                                    )
                                    store.requestPath(hashHex: cross.destHashHex)
                                }
                                pushHistory()
                                currentHash = cross.destHashHex
                                if let live = store.allDestinations.first(where: { ($0.hash as String) == cross.destHashHex }) {
                                    let name = live.effectiveDisplayName
                                    currentTitle = name.isEmpty ? "(unnamed)" : name
                                }
                                path = cross.path
                                submit(data: data)
                            } else {
                                // Self / empty / lxmf / unparseable:
                                // submit against the current page,
                                // no nav change, no history push.
                                submit(data: data)
                            }
                        }
                    )
                case .error(let msg):
                    Text(msg)
                        .font(.callout)
                        .foregroundStyle(.red)
                }

                // /file/ download status banner. Sits above the page
                // body so the user retains reading context while the
                // download progresses. The .fileExporter sheet pops
                // once we have the bytes; success is implicit (sheet
                // appears). Failure surfaces here with a dismiss
                // button.
                if let inflight = fileInFlightPath {
                    HStack(spacing: 8) {
                        ProgressView().scaleEffect(0.7)
                        Text("Downloading \(inflight.components(separatedBy: "/").last ?? "file")…")
                            .font(.caption)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.gray.opacity(0.15))
                } else if let err = fileError {
                    HStack(spacing: 8) {
                        Text(err)
                            .font(.caption)
                            .foregroundStyle(.red)
                        Spacer()
                        Button {
                            fileError = nil
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Color.red.opacity(0.08))
                }
            }
            .padding()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        // .fileExporter pops once pendingDownload is set with the
        // bytes + filename returned from fetchNomadFileBridge. User
        // picks a destination (Files app, iCloud Drive, Dropbox via
        // extension, etc.), iOS writes via FileDocument.fileWrapper.
        .fileExporter(
            isPresented: Binding(
                get: { pendingDownload != nil },
                set: { if !$0 { pendingDownload = nil; fileInFlightPath = nil } }
            ),
            document: pendingDownload,
            contentType: .data,
            defaultFilename: pendingDownload?.filename ?? "download"
        ) { result in
            pendingDownload = nil
            fileInFlightPath = nil
            if case .failure(let err) = result {
                fileError = "Couldn't save file: \(err.localizedDescription)"
            }
        }
        // Scrolling the page (or any rich-Micron form input list)
        // dismisses the keyboard. .interactively so the keyboard
        // tracks the swipe — feels less abrupt than .immediately
        // when the user is reading a long page mid-typing.
        .scrollDismissesKeyboard(.interactively)
        // No `.keyboardDoneToolbar()` — that accessory bar sat on top
        // of micron form fields and the URL bar. Scroll-to-dismiss
        // already covers exit.
        .navigationTitle(isActive ? currentTitle : "Nomad")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(isActive ? .visible : .hidden, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button { onExitToNodes() } label: {
                    Label("Nodes", systemImage: "list.bullet")
                }
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                ShareLink(item: "\(currentHash):\(path)") {
                    Image(systemName: "square.and.arrow.up")
                }

                // Favorite toggle — parity with the Android Nomad page
                // toolbar. Reads the live favorite state out of
                // store.allDestinations so the glyph updates when the
                // store re-emits after toggleFavorite persists.
                Button {
                    store.toggleFavorite(hash: currentHash, favorite: !liveFavorite)
                } label: {
                    Image(systemName: liveFavorite ? "star.fill" : "star")
                        .foregroundStyle(liveFavorite ? Color.accentColor : .secondary)
                }

                // Identify toggle. Same convention as Android's
                // NomadScreen.kt:629 — always a closed-padlock glyph,
                // tint-only state (accent = identifying, muted =
                // anonymous). The closed-lock-by-default reads as
                // "your identity is sealed unless you explicitly
                // unseal it" instead of the "open=safe" inversion the
                // earlier iOS rendering implied. Toggling triggers a
                // re-fetch since auth state changes the response.
                Button {
                    identify.toggle()
                    fetch()
                } label: {
                    Image(systemName: "lock.fill")
                        .foregroundStyle(identify ? Color.accentColor : .secondary)
                }

                Button {
                    showClearCacheConfirm = true
                } label: {
                    Image(systemName: "tray.and.arrow.down")
                }
            }
        }
        .alert("Clear cached pages?", isPresented: $showClearCacheConfirm) {
            Button("Clear", role: .destructive) {
                store.clearNomadCache(destHash: currentHash)
            }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text("Removes every cached page from \(currentTitle) on this device. Next fetch will hit the network. The cache is local only.")
        }
        .task { syncUrlBar(); fetch() }
        .onChange(of: currentHash) { _, _ in syncUrlBar() }
        .onChange(of: path) { _, _ in syncUrlBar() }
        .onChange(of: store.allDestinations) { _, dests in
            // When a URL-bar / cross-node stub later announces, swap
            // the nav title from the short-hash placeholder to the
            // node's real name.
            guard let live = dests.first(where: { ($0.hash as String) == currentHash }) else { return }
            let name = live.effectiveDisplayName
            guard !name.isEmpty, name != currentTitle else { return }
            currentTitle = name
        }
    }

    // MARK: - Browser chrome (RetiNet / MeshChatX-style)

    private var browserBar: some View {
        NomadBrowserChrome(
            urlInput: $urlInput,
            canReload: pageState != .loading,
            canGoForward: !forwardHistory.isEmpty,
            onHome: { goHome() },
            onReload: { fetch() },
            onBack: { goBack() },
            onForward: { goForward() },
            onGo: { goToUrl() }
        )
    }

    /// Leave the browser pane for the Nodes directory. Session stays
    /// mounted so switching back to Browser resumes this page.
    private func exitToNodeList() {
        onExitToNodes()
    }

    private func syncUrlBar() {
        urlInput = LinkTargetKt.formatNomadUrl(nodeHashHex: currentHash, path: path)
    }

    private func goHome() {
        let home = LinkTargetKt.formatNomadUrl(nodeHashHex: currentHash, path: "/page/index.mu")
        navigateToUrl(home)
    }

    private func goBack() {
        if let prior = history.popLast() {
            forwardHistory.append(NomadHistoryEntry(
                hash: currentHash, title: currentTitle, path: path,
                postData: currentPagePostData,
            ))
            restoreHistoryEntry(prior)
        } else {
            onExitToNodes()
        }
    }

    private func goForward() {
        guard let next = forwardHistory.popLast() else { return }
        pushHistory()
        restoreHistoryEntry(next)
    }

    private func goToUrl() {
        navigateToUrl(urlInput)
    }

    private func restoreHistoryEntry(_ entry: NomadHistoryEntry) {
        currentHash = entry.hash
        currentTitle = entry.title
        path = entry.path
        syncUrlBar()
        if let data = entry.postData {
            submit(data: data)
        } else {
            fetch()
        }
    }

    private func navigateToUrl(_ raw: String) {
        guard let parsed = LinkTargetKt.parseNomadUrl(raw: raw, currentNodeHash: currentHash) else {
            pageState = .error("Invalid Nomad URL. Try nodehash:/page/index.mu")
            return
        }
        forwardHistory.removeAll()
        if parsed.nodeHashHex != currentHash {
            pushHistory()
            if !store.allDestinations.contains(where: { ($0.hash as String) == parsed.nodeHashHex }) {
                store.addManualDestination(hashHex: parsed.nodeHashHex, label: "")
                store.requestPath(hashHex: parsed.nodeHashHex)
            }
            currentHash = parsed.nodeHashHex
            if let live = store.allDestinations.first(where: { ($0.hash as String) == parsed.nodeHashHex }) {
                let name = live.effectiveDisplayName
                currentTitle = name.isEmpty ? "(unnamed)" : name
            } else {
                currentTitle = String(parsed.nodeHashHex.prefix(8)) + "…"
            }
        } else if parsed.path != path {
            pushHistory()
        }
        path = parsed.path
        syncUrlBar()
        fetch()
    }

    /// Live favorite flag for this node — re-derived on every render
    /// from the store's published destinations so the toolbar star
    /// updates immediately after toggleFavorite persists. Falls back
    /// to the initial `node.favorite` if the row isn't in the live
    /// list yet (e.g. straight after a deletion-undo).
    ///
    /// `as String` disambiguates against NSObject's inherited `hash:
    /// Int` — Kotlin/Native exports StoredDestination as an NSObject
    /// subclass, so the closure sees both the Kotlin `hash: String`
    /// field and the inherited `hash: Int` and the closure body
    /// becomes ambiguous. Same fix MessagesView.swift uses on the
    /// `path.append(dest.hash as String)` call site.
    private var liveFavorite: Bool {
        let target = currentHash
        if let d = store.allDestinations.first(where: { ($0.hash as String) == target }) {
            return d.favorite
        }
        // A cross-node hop to a node not yet in the live list — fall
        // back to the entry node's flag only when we're still on it.
        return target == (node.hash as String) ? node.favorite : false
    }

    /// Dispatch an in-page micron link tap through the shared
    /// `parseLinkTarget` (commonMain) — the same routing Android's
    /// NomadScreen uses. Same-node paths navigate (or download for
    /// `/file/`), cross-node links swap the browsed node, `lxmf@`
    /// links open a conversation, and anything unparseable surfaces
    /// as an error rather than a silent no-op.
    private func handleLinkClick(_ target: String) {
        let parsed = LinkTargetKt.parseLinkTarget(raw: target)
        if let same = parsed as? LinkTarget.SameNode {
            if same.path.hasPrefix("/file/") {
                downloadFile(path: same.path)
            } else {
                pushHistory()
                path = same.path
                fetch()
            }
        } else if let cross = parsed as? LinkTarget.CrossNode {
            followCrossNode(hash: cross.destHashHex, path: cross.path)
        } else if let lxmf = parsed as? LinkTarget.Lxmf {
            // Resolve / create the contact so it shows in Messages,
            // then route through openContact (the same deep-link
            // signal a notification tap uses). Mirrors Android's
            // LinkTarget.Lxmf branch.
            if !store.allDestinations.contains(where: { ($0.hash as String) == lxmf.destHashHex }) {
                store.addManualDestination(hashHex: lxmf.destHashHex, label: "")
            }
            store.toggleFavorite(hash: lxmf.destHashHex, favorite: true)
            store.openContact(hash: lxmf.destHashHex)
        } else {
            pageState = .error("Unrecognized link: \(target)")
        }
    }

    /// Push the current (node, title, path, postData) onto the
    /// history stack before an in-page navigation, so the leading-
    /// edge Back button can walk it and replay POST submits.
    private func pushHistory() {
        history.append(NomadHistoryEntry(
            hash: currentHash, title: currentTitle, path: path,
            postData: currentPagePostData,
        ))
        forwardHistory.removeAll()
    }

    /// Follow a cross-node link: swap the browsed destination to
    /// [hash] and load [newPath]. If the target node isn't known
    /// yet, add a manual stub so it lands in the Nodes list and the
    /// engine can path-discover it; `fetchNomadPageBridge` re-primes
    /// the path before LINKREQ regardless. Mirrors Android's
    /// resolveOrPrepareDestination + CrossNode branch.
    private func followCrossNode(hash: String, path newPath: String) {
        pushHistory()
        let known = store.allDestinations.first { ($0.hash as String) == hash }
        if known == nil {
            store.addManualDestination(hashHex: hash, label: "")
            // Fire-and-forget path request so the reply lands while the
            // user waits for fetch() — matches Android resolveOrPrepareDestination.
            store.requestPath(hashHex: hash)
        }
        currentHash = hash
        if let name = known?.effectiveDisplayName, !name.isEmpty {
            currentTitle = name
        } else {
            currentTitle = String(hash.prefix(8)) + "…"
        }
        path = newPath
        fetch()
    }

    private func fetch() {
        pageState = .loading
        // GET wipes any prior page's POST data — the response we're
        // about to render came from no form input.
        currentPagePostData = nil
        Task {
            do {
                let r = try await IosEngineFactoryKt.fetchNomadPageBridge(
                    engine: store.engine,
                    destinationHash: currentHash,
                    path: path,
                    identify: identify
                )
                if let src = r.source {
                    pageState = .loaded(src)
                } else {
                    pageState = .error(r.errorMessage ?? "Unknown error")
                }
            } catch {
                pageState = .error("\(error)")
            }
        }
    }

    /// Tap on a `/file/<...>` link — fetches the file bytes + server-
    /// supplied filename via fetchNomadFileBridge, then surfaces a
    /// .fileExporter sheet so the user picks a save destination.
    /// Concurrent taps are ignored while one's in flight (mirrors
    /// the Android NomadScreen fileInFlight gate).
    private func downloadFile(path filePath: String) {
        if fileInFlightPath != nil { return }  // serialize taps
        fileInFlightPath = filePath
        fileError = nil
        Task {
            do {
                let r = try await IosEngineFactoryKt.fetchNomadFileBridge(
                    engine: store.engine,
                    destinationHash: currentHash,
                    path: filePath,
                    identify: identify
                )
                if let bytes = r.bytes, let filename = r.filename {
                    // Copy KotlinByteArray → Data, same byte-by-byte
                    // pattern as identity export / image send. K/N
                    // doesn't expose a fast bulk copy through the
                    // Swift bridge.
                    let count = Int(bytes.size)
                    var data = Data(count: count)
                    for i in 0..<count {
                        data[i] = UInt8(bitPattern: bytes.get(index: Int32(i)))
                    }
                    pendingDownload = NomadFileDocument(data: data, filename: filename)
                } else {
                    fileError = r.errorMessage ?? "File fetch failed"
                    fileInFlightPath = nil
                }
            } catch {
                fileError = "File fetch threw: \(error)"
                fileInFlightPath = nil
            }
        }
    }

    /// POST a form-submit dict (`field_<name>` / `var_<k>` entries
    /// collected from the user's input fields by the MicronView link-
    /// tap handler) and render the response. Same engine call as
    /// fetch() but routed through the with-data bridge so the
    /// envelope's [2] element carries the dict instead of `nil`.
    private func submit(data: [String: String]) {
        pageState = .loading
        // Record the POST data that produced this page so a later
        // pushHistory() captures it onto the entry and Back can
        // replay the submit instead of reverting to an empty GET.
        currentPagePostData = data
        Task {
            do {
                let r = try await IosEngineFactoryKt.fetchNomadPageWithDataBridge(
                    engine: store.engine,
                    destinationHash: currentHash,
                    path: path,
                    identify: identify,
                    data: data
                )
                if let src = r.source {
                    pageState = .loaded(src)
                } else {
                    pageState = .error(r.errorMessage ?? "Unknown error")
                }
            } catch {
                pageState = .error("\(error)")
            }
        }
    }
}

// (Plain-text micron stripper retired — full MicronView now lives in
// MicronView.swift and renders headings, paragraphs, fields, tables,
// partials, and form-submit links to parity with Android.)

/// FileDocument wrapper for a NomadNet `/file/` download. Carries
/// the bytes + the server-supplied filename through SwiftUI's
/// `.fileExporter` so the user picks a save destination (Files app,
/// iCloud Drive, Dropbox, etc.) and iOS writes the bytes via
/// `fileWrapper(configuration:)`. Same shape as `RmidDocument` in
/// SettingsView for identity export.
///
/// The `filename` field is the suggested default in the exporter
/// dialog; the user can rename before saving. `data` is the raw
/// file bytes (metadata prefix already stripped by
/// `Resource.assemble`).
struct NomadFileDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.data] }
    var data: Data
    var filename: String

    init(data: Data, filename: String) {
        self.data = data
        self.filename = filename
    }

    init(configuration: ReadConfiguration) throws {
        // /file/ downloads only flow one direction (server → us); we
        // never read FileDocuments back from disk via this path.
        // Throw so SwiftUI surfaces a clear error if someone wires
        // read-mode by mistake.
        throw CocoaError(.fileReadUnsupportedScheme)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: data)
    }
}
