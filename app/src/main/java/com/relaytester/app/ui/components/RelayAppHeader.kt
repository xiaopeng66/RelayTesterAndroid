package com.relaytester.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.relaytester.app.R
import com.relaytester.app.ui.navigation.AppDestination
import com.relaytester.app.ui.navigation.AppDestinationTabs

/** Shared product header with deliberate spacing between the brand mark and title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayAppHeader(
    subtitle: String,
    selectedDestination: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
    modifier: Modifier = Modifier,
    onConfigurationBackup: (() -> Unit)? = null,
    onOpenUpdates: (() -> Unit)? = null,
    /**
     * Draws a dot on the update icon.
     *
     * The one piece of update state that is visible without opening anything: the panel is
     * a separate screen away from wherever the user is, and the only honest way to say
     * "there is something for you" from the header is a mark on the way in.
     */
    hasUpdate: Boolean = false,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        TopAppBar(
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Relay Tester", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                onConfigurationBackup?.let { onClick ->
                    IconButton(onClick = onClick) {
                        Icon(
                            imageVector = Icons.Outlined.ImportExport,
                            contentDescription = "导入或导出配置",
                        )
                    }
                }
                onOpenUpdates?.let { onClick ->
                    Box {
                        IconButton(onClick = onClick) {
                            Icon(
                                imageVector = Icons.Outlined.SystemUpdateAlt,
                                // The dot is invisible to a screen reader, so the news is
                                // carried by the name as well.
                                contentDescription = if (hasUpdate) "关于与更新，有新版本" else "关于与更新",
                            )
                        }
                        if (hasUpdate) {
                            // A plain dot in the error colour, which is the same colour the
                            // panel uses for "something is waiting for you".
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 8.dp, end = 8.dp)
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.error, CircleShape),
                            )
                        }
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        AppDestinationTabs(
            selected = selectedDestination,
            onSelected = onDestinationSelected,
        )
    }
}
