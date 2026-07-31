package com.mateof.passvault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mateof.passvault.BuildConfig
import com.mateof.passvault.R
import com.mateof.passvault.ui.theme.LocalSpacing

/**
 * The main menu, as a drawer.
 *
 * It replaces a row of four icons in the title bar, which was where every destination in the app
 * had ended up. Four unlabelled glyphs is not a menu: nobody knows what the cloud does until they
 * press it, and there was no room for a fifth.
 *
 * A drawer instead, with a name beside each icon. It also gives the app somewhere to say what it
 * is and which version is running — the two things somebody looks for first when they want to
 * report that something is wrong.
 */
enum class Destination { Wallet, Share, Server, Updates }

@Composable
fun MainDrawerSheet(
    current: Destination,
    onSelect: (Destination) -> Unit,
) {
    val spacing = LocalSpacing.current

    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
    ) {
        Row(
            modifier = Modifier.padding(spacing.large),
            horizontalArrangement = Arrangement.spacedBy(spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.ConfirmationNumber,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    // Shown here rather than buried in a settings screen, because "which version
                    // are you on" is the first question anybody asks about a bug.
                    text = BuildConfig.VERSION_NAME,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = spacing.medium))
        Spacer(Modifier.padding(spacing.tight))

        Entry(Destination.Wallet, current, Icons.Filled.ConfirmationNumber, R.string.events_title, onSelect)
        Entry(Destination.Share, current, Icons.Filled.Share, R.string.action_share, onSelect)
        Entry(Destination.Server, current, Icons.Filled.Cloud, R.string.server_title, onSelect)
        Entry(Destination.Updates, current, Icons.Filled.SystemUpdate, R.string.update_title, onSelect)
    }
}

@Composable
private fun Entry(
    destination: Destination,
    current: Destination,
    icon: ImageVector,
    label: Int,
    onSelect: (Destination) -> Unit,
) {
    NavigationDrawerItem(
        icon = { Icon(icon, contentDescription = null) },
        label = { Text(stringResource(label)) },
        selected = destination == current,
        onClick = { onSelect(destination) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}
