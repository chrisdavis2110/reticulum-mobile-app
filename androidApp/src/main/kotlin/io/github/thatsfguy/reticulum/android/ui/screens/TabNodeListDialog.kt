package io.github.thatsfguy.reticulum.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.thatsfguy.reticulum.store.StoredDestination
import io.github.thatsfguy.reticulum.util.shortHash

enum class TabNodeKind {
    Messagable,
    Nomad,
    Rrc,
}

fun TabNodeKind.title(): String = when (this) {
    TabNodeKind.Messagable -> "Contacts"
    TabNodeKind.Nomad -> "NomadNet nodes"
    TabNodeKind.Rrc -> "RRC hubs"
}

fun TabNodeKind.emptyMessage(): String = when (this) {
    TabNodeKind.Messagable ->
        "No messageable destinations yet. Connect a transport, or add someone by hash."
    TabNodeKind.Nomad ->
        "No NomadNet nodes seen yet — nodes announce on the nomadnetwork.node aspect."
    TabNodeKind.Rrc ->
        "No RRC hubs seen yet — hubs announce on the rrc.hub aspect."
}

fun TabNodeKind.matches(dest: StoredDestination): Boolean = when (this) {
    TabNodeKind.Messagable ->
        dest.isMessagable ||
            (dest.publicKey.isEmpty() && dest.appName == null) ||
            dest.appName == "lxmf.delivery"
    TabNodeKind.Nomad -> dest.appName == "nomadnetwork.node"
    TabNodeKind.Rrc -> dest.appName == "rrc.hub"
}

/**
 * Per-tab node picker dialog — replaces the standalone Nodes tab.
 * Each feature tab presents this from a list icon, filtered to
 * destinations that belong on that tab.
 */
@Composable
fun TabNodeListDialog(
    kind: TabNodeKind,
    destinations: List<StoredDestination>,
    onDismiss: () -> Unit,
    onSelect: (StoredDestination) -> Unit,
    onAddManual: ((hash: String, label: String) -> Unit)? = null,
    onToggleContact: ((StoredDestination) -> Unit)? = null,
    onToggleFavorite: ((StoredDestination) -> Unit)? = null,
    addedHubHashes: Set<String> = emptySet(),
    onAddHub: ((StoredDestination) -> Unit)? = null,
) {
    var search by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    val filtered = remember(destinations, kind, search) {
        val base = destinations
            .filter { kind.matches(it) }
            .sortedWith(
                compareByDescending<StoredDestination> { it.favorite }
                    .thenByDescending { it.lastSeen },
            )
        val q = search.trim().lowercase()
        if (q.isEmpty()) base
        else base.filter {
            it.effectiveDisplayName.lowercase().contains(q) ||
                it.displayName.lowercase().contains(q) ||
                (it.appLabel?.lowercase()?.contains(q) == true) ||
                it.hash.lowercase().contains(q)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.fillMaxWidth(0.94f),
        title = {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(kind.title(), modifier = Modifier.weight(1f))
                if (onAddManual != null) {
                    IconButton(onClick = { showAdd = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add by hash")
                    }
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search by name or hash") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (search.isNotEmpty()) {
                        {
                            IconButton(onClick = { search = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    } else null,
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                )
                if (filtered.isEmpty()) {
                    Text(
                        if (search.isBlank()) kind.emptyMessage()
                        else "Nothing matches \"$search\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        items(filtered, key = { it.hash }) { dest ->
                            TabNodeRow(
                                dest = dest,
                                trailing = when (kind) {
                                    TabNodeKind.Messagable -> if (onToggleContact != null) {
                                        TabNodeTrailing.Contact(
                                            isContact = dest.favorite,
                                            onToggle = { onToggleContact(dest) },
                                        )
                                    } else TabNodeTrailing.None
                                    TabNodeKind.Nomad -> if (onToggleFavorite != null) {
                                        TabNodeTrailing.Favorite(
                                            isFavorite = dest.favorite,
                                            onToggle = { onToggleFavorite(dest) },
                                        )
                                    } else TabNodeTrailing.None
                                    TabNodeKind.Rrc -> if (onAddHub != null) {
                                        TabNodeTrailing.AddHub(
                                            alreadyAdded = dest.hash in addedHubHashes,
                                            onAdd = { onAddHub(dest) },
                                        )
                                    } else TabNodeTrailing.None
                                },
                                onClick = {
                                    onSelect(dest)
                                    onDismiss()
                                },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )

    if (showAdd && onAddManual != null) {
        SimpleAddByHashDialog(
            onDismiss = { showAdd = false },
            onConfirm = { hash, label ->
                onAddManual(hash, label)
                showAdd = false
            },
        )
    }
}

@Composable
private fun SimpleAddByHashDialog(
    onDismiss: () -> Unit,
    onConfirm: (hash: String, label: String) -> Unit,
) {
    var hash by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    val cleaned = remember(hash) {
        hash.lowercase().filter { it != ':' && it != ' ' && it != '-' }
    }
    val valid = cleaned.length == 32 && cleaned.all { it in '0'..'9' || it in 'a'..'f' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add by hash") },
        text = {
            Column {
                OutlinedTextField(
                    value = hash,
                    onValueChange = { hash = it },
                    label = { Text("Destination hash (32 hex)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(cleaned, label) },
                enabled = valid,
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private sealed class TabNodeTrailing {
    data object None : TabNodeTrailing()
    data class Contact(val isContact: Boolean, val onToggle: () -> Unit) : TabNodeTrailing()
    data class Favorite(val isFavorite: Boolean, val onToggle: () -> Unit) : TabNodeTrailing()
    data class AddHub(val alreadyAdded: Boolean, val onAdd: () -> Unit) : TabNodeTrailing()
}

@Composable
private fun TabNodeRow(
    dest: StoredDestination,
    trailing: TabNodeTrailing = TabNodeTrailing.None,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text(
                    dest.effectiveDisplayName
                        .takeIf { it.isNotBlank() && it != dest.appLabel }
                        ?: dest.appLabel
                        ?: shortHash(dest.hash),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    shortHash(dest.hash),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val meta = buildList {
                    if (dest.hopCount > 0) add("${dest.hopCount} hop${if (dest.hopCount == 1) "" else "s"}")
                    dest.rssi?.let { add("RSSI $it dBm") }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        when (val action = trailing) {
            is TabNodeTrailing.None -> Unit
            is TabNodeTrailing.Contact -> {
                IconButton(onClick = action.onToggle) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = if (action.isContact) "Remove from Contacts" else "Add to Contacts",
                        tint = if (action.isContact) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                    )
                }
            }
            is TabNodeTrailing.Favorite -> {
                IconButton(onClick = action.onToggle) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = if (action.isFavorite) "Remove favorite" else "Favorite",
                        tint = if (action.isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                    )
                }
            }
            is TabNodeTrailing.AddHub -> {
                if (action.alreadyAdded) {
                    Text(
                        "Added",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                } else {
                    TextButton(onClick = action.onAdd) { Text("Add") }
                }
            }
        }
    }
}
