package ru.kryu.ferryfile.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kryu.ferryfile.R
import ru.kryu.ferryfile.ui.theme.BroadsheetTheme
import ru.kryu.ferryfile.ui.theme.BroadsheetType

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = BroadsheetTheme.colors
    val context = LocalContext.current
    var startAfterNotificationPermission by rememberSaveable { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && startAfterNotificationPermission) {
            viewModel.onStartClicked()
        }
        startAfterNotificationPermission = false
    }

    fun startServer() {
        val needsNotificationPermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED

        if (needsNotificationPermission) {
            startAfterNotificationPermission = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.onStartClicked()
        }
    }

    // Re-read the server state every time this screen comes back to the foreground, not just
    // once per composition: the server can be stopped from outside the app (the notification's
    // Stop action stops the service while the activity is merely paused, not recreated), and
    // ServerRepository.refresh() is what notices that and republishes Stopped — but only if
    // something actually calls it. ON_RESUME also fires on first display, so this still covers
    // the initial load LaunchedEffect(Unit) used to handle.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        // Отступы системных панелей уже заданы в AppNavigation, Scaffold их не добавляет.
        contentWindowInsets = WindowInsets(0),
        containerColor = colors.bg
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            // Masthead toolbar stays visible while the page content scrolls.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.brand_name),
                    style = BroadsheetType.masthead,
                    color = colors.text
                )
                IconButton(onClick = onNavigateToSettings, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.home_settings_content_description),
                        tint = colors.text,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(20.dp))

                val isNoWifi = uiState.isRunning && !uiState.hasWifi
                // Only known while running: HomeUiState carries the live server URL, not the
                // configured port (HomeViewModel.kt is off-limits for this restyle).
                val port = remember(uiState.url) {
                    Regex(""":(\d+)$""").find(uiState.url)?.groupValues?.get(1)
                }

                // Head pair: thick rule, dateline row, thin rule.
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(colors.text)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = if (isNoWifi) {
                            stringResource(R.string.home_dateline_no_network)
                        } else {
                            stringResource(R.string.home_dateline_network, port ?: "—")
                        }.uppercase(),
                        style = BroadsheetType.smallCapsLabel,
                        color = if (isNoWifi) colors.accent2700 else colors.neutral700
                    )
                    Text(
                        text = when {
                            uiState.isStarting -> stringResource(R.string.home_status_starting)
                            uiState.isStopping -> stringResource(R.string.home_status_stopping)
                            uiState.isRunning -> stringResource(R.string.home_status_running)
                            else -> stringResource(R.string.home_status_stopped)
                        }.uppercase(),
                        style = BroadsheetType.smallCapsLabel,
                        color = if (uiState.isRunning || uiState.isBusy) colors.accent700 else colors.neutral700
                    )
                }
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.text)
                )

                if (uiState.isBusy) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        color = colors.accent,
                        trackColor = colors.neutral300
                    )
                }

                Spacer(modifier = Modifier.height(30.dp))

                // Headline + standfirst
                if (isNoWifi) {
                    val fullText = stringResource(R.string.home_no_wifi_connection)
                    val dashIndex = fullText.indexOf('—')
                    val headlineText = if (dashIndex >= 0) fullText.substring(0, dashIndex).trim() else fullText
                    val standfirstText = if (dashIndex >= 0) fullText.substring(dashIndex + 1).trim() else ""

                    Text(text = headlineText, style = BroadsheetType.headline(), color = colors.accent2700)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(text = standfirstText, style = BroadsheetType.standfirst, color = colors.accent2700)
                } else {
                    val headlineText = when {
                        uiState.isStarting -> stringResource(R.string.home_status_starting)
                        uiState.isStopping -> stringResource(R.string.home_status_stopping)
                        uiState.isRunning -> stringResource(R.string.home_headline_running)
                        else -> stringResource(R.string.home_headline_stopped)
                    }
                    Text(
                        text = headlineText,
                        style = BroadsheetType.headline(),
                        color = if (uiState.isBusy) colors.neutral700 else colors.text
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val standfirstText = when {
                        uiState.isStarting -> stringResource(R.string.home_starting_standfirst)
                        uiState.isStopping -> stringResource(R.string.home_stopping_standfirst)
                        uiState.isRunning -> stringResource(R.string.home_running_standfirst)
                        else -> stringResource(R.string.home_stopped_standfirst)
                    }
                    Text(text = standfirstText, style = BroadsheetType.standfirst, color = colors.neutral800)
                }

                // Connection block (running + Wi-Fi) / no-Wi-Fi block (running, no Wi-Fi)
                if (uiState.isRunning) {
                    Spacer(modifier = Modifier.height(30.dp))
                    if (uiState.hasWifi) {
                        Text(
                            text = stringResource(R.string.home_open_in_browser),
                            style = BroadsheetType.smallCapsLabel,
                            color = colors.neutral700
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = uiState.url, style = BroadsheetType.address, color = colors.accent700)

                        Spacer(modifier = Modifier.height(20.dp))

                        Text(
                            text = stringResource(R.string.home_pin),
                            style = BroadsheetType.smallCapsLabel,
                            color = colors.neutral700
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = uiState.pin, style = BroadsheetType.pin, color = colors.text)
                        Spacer(modifier = Modifier.height(6.dp))
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(colors.accent)
                        )

                        if (uiState.certificateFingerprint.isNotBlank()) {
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = stringResource(R.string.home_certificate_fingerprint),
                                style = BroadsheetType.smallCapsLabel,
                                color = colors.neutral700
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.certificateFingerprint,
                                style = BroadsheetType.fingerprint,
                                color = colors.text
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.home_certificate_hint),
                                style = BroadsheetType.fingerprint,
                                color = colors.neutral700
                            )
                        }
                    } else {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(colors.accent2700)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.home_address_label),
                            style = BroadsheetType.smallCapsLabel,
                            color = colors.neutral700
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_address_unavailable),
                            style = BroadsheetType.address,
                            color = colors.neutral600
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = stringResource(R.string.home_pin),
                            style = BroadsheetType.smallCapsLabel,
                            color = colors.neutral700
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = uiState.pin, style = BroadsheetType.pinSmall, color = colors.text)
                    }
                }

                // Notice block
                if (!uiState.hasSharedFolders) {
                    Spacer(modifier = Modifier.height(30.dp))
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.text)
                    )
                    Column(modifier = Modifier.padding(vertical = 14.dp)) {
                        Text(
                            text = stringResource(R.string.home_notice_kicker).uppercase(),
                            style = BroadsheetType.smallCapsLabel,
                            color = colors.accent2700
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.home_no_folders_shared_title),
                            style = BroadsheetType.sectionHeading,
                            color = colors.text
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.home_no_folders_shared_message),
                            style = BroadsheetType.listRowValue,
                            color = colors.text
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clickable(role = Role.Button, onClick = onNavigateToSettings),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = stringResource(R.string.home_add_folder) + " →",
                                style = BroadsheetType.buttonLabel,
                                color = colors.accent
                            )
                        }
                    }
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.divider)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // Action, pinned to the bottom
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.divider)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = {
                    when {
                        uiState.isRunning -> viewModel.onStopClicked()
                        !uiState.isBusy -> startServer()
                    }
                },
                enabled = !uiState.isBusy,
                shape = RoundedCornerShape(2.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.bg,
                    disabledContainerColor = colors.accent,
                    disabledContentColor = colors.bg
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                val displayLabel = when {
                    uiState.isStarting -> stringResource(R.string.home_status_starting)
                    uiState.isStopping -> stringResource(R.string.home_status_stopping)
                    uiState.isRunning -> stringResource(R.string.home_stop_server)
                    else -> stringResource(R.string.home_start_server)
                }
                Text(
                    text = displayLabel,
                    style = BroadsheetType.buttonLabel,
                    modifier = if (uiState.isBusy) Modifier.alpha(0.45f) else Modifier
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
