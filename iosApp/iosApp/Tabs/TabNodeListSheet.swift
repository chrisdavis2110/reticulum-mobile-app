// SPDX-License-Identifier: MIT
//
// Per-tab node picker — replaces the standalone Nodes tab. Each feature
// tab (Messages / Nomad / Rooms) presents this sheet from a list.bullet
// toolbar button, filtered to destinations that belong on that tab.

import Shared
import SwiftUI

enum TabNodeKind: String {
    case messagable
    case nomad
    case rrc

    var title: String {
        switch self {
        case .messagable: return "Contacts"
        case .nomad: return "NomadNet nodes"
        case .rrc: return "RRC hubs"
        }
    }

    var emptyMessage: String {
        switch self {
        case .messagable:
            return "No messageable destinations yet. Connect a transport, or add someone by hash."
        case .nomad:
            return "No NomadNet nodes seen yet — nodes announce on the nomadnetwork.node aspect."
        case .rrc:
            return "No RRC hubs seen yet — hubs announce on the rrc.hub aspect."
        }
    }

    func matches(_ dest: StoredDestination) -> Bool {
        switch self {
        case .messagable:
            return dest.isMessagable || (dest.publicKey.size == 0 && dest.appName == nil)
                || dest.appName == "lxmf.delivery"
        case .nomad:
            return dest.appName == "nomadnetwork.node"
        case .rrc:
            return dest.appName == "rrc.hub"
        }
    }
}

struct TabNodeListSheet: View {
    let kind: TabNodeKind
    let onSelect: (StoredDestination) -> Void
    var onAddManual: ((String, String) -> Void)? = nil
    var onApplyCard: ((IdentityCard.Payload) -> Void)? = nil

    @EnvironmentObject private var store: ReticulumStore
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""
    @State private var showAdd = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                searchBar
                Divider()
                if filtered.isEmpty {
                    ContentUnavailableView(
                        search.isEmpty ? kind.title : "No matches",
                        systemImage: search.isEmpty ? "list.bullet" : "magnifyingglass",
                        description: Text(search.isEmpty ? kind.emptyMessage : "Nothing matches “\(search)”.")
                    )
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    let addedHubHashes = Set(store.rrcHubs.map(\.destHash))
                    List(filtered, id: \.id) { dest in
                        TabNodeRow(
                            dest: dest,
                            trailing: trailingAction(for: dest, addedHubHashes: addedHubHashes)
                        ) {
                            onSelect(dest)
                            dismiss()
                        }
                    }
                    .listStyle(.plain)
                    .scrollDismissesKeyboard(.immediately)
                }
            }
            .navigationTitle(kind.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
                if onAddManual != nil {
                    ToolbarItem(placement: .primaryAction) {
                        Button { showAdd = true } label: {
                            Image(systemName: "plus")
                        }
                    }
                }
            }
            .sheet(isPresented: $showAdd) {
                if let onAddManual, let onApplyCard {
                    AddDestinationSheet(
                        onAddManual: { hash, label in
                            onAddManual(hash, label)
                            showAdd = false
                        },
                        onApplyCard: { card in
                            onApplyCard(card)
                            showAdd = false
                        }
                    )
                }
            }
        }
    }

    private func trailingAction(
        for dest: StoredDestination,
        addedHubHashes: Set<String>
    ) -> TabNodeRow.Trailing {
        let hash = dest.hash as String
        switch kind {
        case .messagable:
            return .contact(
                isContact: dest.favorite,
                onToggle: { store.toggleFavorite(hash: hash, favorite: !dest.favorite) }
            )
        case .nomad:
            return .favorite(
                isFavorite: dest.favorite,
                onToggle: { store.toggleFavorite(hash: hash, favorite: !dest.favorite) }
            )
        case .rrc:
            let already = addedHubHashes.contains(hash)
            return .addHub(
                alreadyAdded: already,
                onAdd: {
                    let name = dest.effectiveDisplayName
                    store.addRrcHub(
                        destHash: hash,
                        displayName: name.isEmpty ? (dest.appLabel ?? "RRC hub") : name,
                        nick: nil
                    )
                }
            )
        }
    }

    private var searchBar: some View {
        HStack {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField("Search by name or hash", text: $search)
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
        .padding(10)
        .background(Color.secondary.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    private var filtered: [StoredDestination] {
        let base = store.allDestinations.filter(kind.matches)
            .sorted { a, b in
                if a.favorite != b.favorite { return a.favorite && !b.favorite }
                return a.lastSeen > b.lastSeen
            }
        let q = search.trimmingCharacters(in: .whitespaces).lowercased()
        guard !q.isEmpty else { return base }
        return base.filter { d in
            d.effectiveDisplayName.lowercased().contains(q)
                || d.displayName.lowercased().contains(q)
                || (d.appLabel?.lowercased().contains(q) ?? false)
                || (d.hash as String).lowercased().contains(q)
        }
    }
}

private struct TabNodeRow: View {
    enum Trailing {
        case none
        case contact(isContact: Bool, onToggle: () -> Void)
        case favorite(isFavorite: Bool, onToggle: () -> Void)
        case addHub(alreadyAdded: Bool, onAdd: () -> Void)
    }

    let dest: StoredDestination
    var trailing: Trailing = .none
    let onSelect: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Button(action: onSelect) {
                HStack(spacing: 12) {
                    Image(systemName: iconName)
                        .font(.title3)
                        .foregroundStyle(.secondary)
                        .frame(width: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(displayName)
                            .font(.body)
                            .foregroundStyle(.primary)
                        Text(shortHash(dest.hash as String))
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                        if !meta.isEmpty {
                            Text(meta)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            trailingControl
        }
    }

    @ViewBuilder
    private var trailingControl: some View {
        switch trailing {
        case .none:
            EmptyView()
        case .contact(let isContact, let onToggle):
            Button(action: onToggle) {
                Image(systemName: isContact ? "person.badge.minus" : "person.badge.plus")
                    .font(.body)
                    .foregroundStyle(isContact ? Color.secondary : Color.accentColor)
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(isContact ? "Remove from Contacts" : "Add to Contacts")
        case .favorite(let isFavorite, let onToggle):
            Button(action: onToggle) {
                Image(systemName: isFavorite ? "star.fill" : "star")
                    .font(.body)
                    .foregroundStyle(isFavorite ? Color.accentColor : Color.secondary)
                    .frame(width: 36, height: 36)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(isFavorite ? "Remove favorite" : "Favorite")
        case .addHub(let alreadyAdded, let onAdd):
            if alreadyAdded {
                Text("Added")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 8)
            } else {
                Button("Add", action: onAdd)
                    .buttonStyle(.bordered)
                    .controlSize(.small)
            }
        }
    }

    private var iconName: String {
        switch dest.appName {
        case "lxmf.delivery": return "person.fill"
        case "rrc.hub": return "bubble.left.and.bubble.right"
        case "nomadnetwork.node": return "globe"
        default: return "mappin"
        }
    }

    private var displayName: String {
        let name = dest.effectiveDisplayName
        if !name.isEmpty && name != dest.appLabel { return name }
        return dest.appLabel ?? shortHash(dest.hash as String)
    }

    private var meta: String {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let ageMs = max(0, now - dest.lastSeen)
        var parts: [String] = []
        if dest.hopCount > 0 { parts.append("\(dest.hopCount) hop\(dest.hopCount == 1 ? "" : "s")") }
        if let r = dest.rssi { parts.append("RSSI \(Int(truncating: r)) dBm") }
        if dest.lastSeen > 0 { parts.append("seen \(relativeAge(ageMs))") }
        return parts.joined(separator: " · ")
    }
}
