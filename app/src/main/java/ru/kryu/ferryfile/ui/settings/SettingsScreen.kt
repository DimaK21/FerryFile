package ru.kryu.ferryfile.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.kryu.ferryfile.BuildConfig
import ru.kryu.ferryfile.R
import ru.kryu.ferryfile.ui.theme.BroadsheetColors
import ru.kryu.ferryfile.ui.theme.BroadsheetTheme
import ru.kryu.ferryfile.ui.theme.BroadsheetType
import ru.kryu.ferryfile.ui.theme.SourceSerif4

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = BroadsheetTheme.colors
    val isRussian = LocalConfiguration.current.locales[0].language == "ru"
    val uriHandler = LocalUriHandler.current
    val privacyPolicyUrl = stringResource(R.string.privacy_policy_url)

    var portText by remember(uiState.port) { mutableStateOf(uiState.port.toString()) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onFolderPicked(uri.toString())
        }
    }

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onNavigateBack, modifier = Modifier.size(36.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.settings_back_content_description),
                        tint = colors.text,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(text = stringResource(R.string.settings_title), style = BroadsheetType.screenTitle, color = colors.text)
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(20.dp))
                Spacer(modifier = Modifier.fillMaxWidth().height(3.dp).background(colors.text))
                Spacer(modifier = Modifier.height(4.dp))
                Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.text))

                Spacer(modifier = Modifier.height(30.dp))

                // Server port
                Text(
                    text = stringResource(R.string.settings_server_port).uppercase(),
                    style = BroadsheetType.smallCapsLabel,
                    color = colors.neutral700
                )
                Spacer(modifier = Modifier.height(15.dp))

                val portFieldWidth = if (isRussian) 200.dp else 180.dp
                val portBorderColor = if (uiState.portError) colors.accent2700 else colors.divider
                val portCaptionColor = if (uiState.portError) colors.accent2700 else colors.text.copy(alpha = 0.7f)
                Text(
                    text = if (uiState.portError) {
                        stringResource(R.string.settings_port_error)
                    } else {
                        stringResource(R.string.settings_port_hint)
                    },
                    style = BroadsheetType.caption,
                    color = portCaptionColor
                )
                Spacer(modifier = Modifier.height(4.dp))
                BasicTextField(
                    value = portText,
                    onValueChange = { newValue ->
                        portText = newValue
                        viewModel.onPortChanged(newValue)
                    },
                    textStyle = BroadsheetType.listRowValue.copy(color = colors.text),
                    singleLine = true,
                    cursorBrush = SolidColor(colors.accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .width(portFieldWidth)
                        .height(42.dp)
                        .border(1.dp, portBorderColor, RoundedCornerShape(2.dp)),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            innerTextField()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(30.dp))
                Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                Spacer(modifier = Modifier.height(18.dp))

                // Use HTTPS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.settings_use_https),
                        style = BroadsheetType.sectionHeading,
                        color = colors.text,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    SegmentedToggle(checked = uiState.useHttps, onCheckedChange = { viewModel.onHttpsChanged(it) })
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_use_https_description),
                    fontFamily = SourceSerif4,
                    fontSize = 13.sp,
                    color = colors.neutral700,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(18.dp))
                Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))

                Spacer(modifier = Modifier.height(30.dp))

                // Dark theme
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = stringResource(R.string.settings_dark_theme), style = BroadsheetType.sectionHeading, color = colors.text)
                    SegmentedToggle(checked = uiState.darkTheme, onCheckedChange = { viewModel.onDarkThemeChanged(it) })
                }

                Spacer(modifier = Modifier.height(30.dp))

                // Storage folders
                Text(
                    text = stringResource(R.string.settings_storage_folders).uppercase(),
                    style = BroadsheetType.smallCapsLabel,
                    color = colors.neutral700
                )
                Spacer(modifier = Modifier.height(4.dp))
                Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.text))

                if (uiState.sharedFolders.isEmpty()) {
                    Spacer(modifier = Modifier.height(15.dp))
                    Text(
                        text = stringResource(R.string.settings_no_folders),
                        style = BroadsheetType.listRowValue.copy(fontSize = 15.sp),
                        color = colors.neutral700
                    )
                } else {
                    uiState.sharedFolders.forEach { folder ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = folder.displayName,
                                style = BroadsheetType.listRowValue,
                                color = colors.text,
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                modifier = Modifier
                                    .height(48.dp)
                                    .clickable(role = Role.Button) { viewModel.onFolderRemoved(folder.uri) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_remove),
                                    fontFamily = SourceSerif4,
                                    fontSize = 13.sp,
                                    color = colors.accent2700
                                )
                            }
                        }
                        Spacer(modifier = Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
                    }
                }

                Spacer(modifier = Modifier.height(15.dp))

                OutlinedButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    shape = RoundedCornerShape(2.dp),
                    border = BorderStroke(1.dp, colors.divider),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.text),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(text = stringResource(R.string.settings_add_folder), style = BroadsheetType.buttonLabel)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME).uppercase(),
                    textAlign = TextAlign.Center,
                    style = BroadsheetType.footer,
                    color = colors.neutral600
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_privacy_policy),
                    modifier = Modifier
                        .clickable { uriHandler.openUri(privacyPolicyUrl) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    style = BroadsheetType.footer,
                    color = colors.accent700
                )
            }
        }
    }
}

// Broadsheet has no Switch; it has a two-option segmented control (Off/On).
@Composable
private fun SegmentedToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BroadsheetTheme.colors
    Row(
        modifier = modifier
            .width(120.dp)
            .height(48.dp)
            .selectableGroup()
            .border(1.dp, colors.divider, RoundedCornerShape(2.dp))
    ) {
        SegmentOption(
            label = stringResource(R.string.settings_switch_off),
            selected = !checked,
            onClick = { onCheckedChange(false) },
            colors = colors,
            modifier = Modifier.weight(1f)
        )
        Spacer(
            modifier = Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(colors.divider)
        )
        SegmentOption(
            label = stringResource(R.string.settings_switch_on),
            selected = checked,
            onClick = { onCheckedChange(true) },
            colors = colors,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SegmentOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    colors: BroadsheetColors,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (selected) colors.accent else Color.Transparent)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = SourceSerif4,
            fontSize = 13.sp,
            color = if (selected) colors.bg else colors.text
        )
    }
}
