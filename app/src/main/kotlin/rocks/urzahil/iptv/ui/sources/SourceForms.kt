package rocks.urzahil.iptv.ui.sources

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import rocks.urzahil.iptv.data.db.entities.SourceType
import rocks.urzahil.iptv.data.repo.M3uUrlInspector
import rocks.urzahil.iptv.data.repo.SourceForm
import rocks.urzahil.iptv.data.repo.XtreamLoginResult
import rocks.urzahil.iptv.ui.appViewModel
import rocks.urzahil.iptv.ui.components.DetailScaffold
import rocks.urzahil.iptv.ui.theme.Dimens
import java.text.DateFormat
import java.util.Date

@Composable
private fun FormColumn(padding: androidx.compose.foundation.layout.PaddingValues, content: @Composable () -> Unit) {
    Column(
        Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    supporting: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supporting?.let { { Text(it) } },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, supporting: String? = null) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = if (visible) "Hide" else "Show")
            }
        },
        supportingText = supporting?.let { { Text(it) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FormatChoice(value: String, onChange: (String) -> Unit) {
    Text("Live stream format", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        listOf("ts" to "MPEG-TS", "m3u8" to "HLS").forEachIndexed { i, (v, label) ->
            SegmentedButton(
                selected = value == v,
                onClick = { onChange(v) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
            ) { Text(label) }
        }
    }
}

@Composable
private fun TestResult(state: TestState) {
    when (state) {
        TestState.Idle -> Unit
        TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.padding(4.dp))
            Text("Checking login")
        }
        is TestState.Done -> when (val r = state.result) {
            is XtreamLoginResult.Ok -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Login works", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    val lines = buildList {
                        r.status?.let { add("Status: $it") }
                        r.expiresAtMs?.let { add("Expires: ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}") }
                        r.maxConnections?.let { add("Connections: ${r.activeConnections ?: 0} of $it in use") }
                        if (r.formats.isNotEmpty()) add("Formats: ${r.formats.joinToString()}")
                    }
                    lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                }
            }
            is XtreamLoginResult.Failed -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(r.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun SaveError(state: SaveState) {
    if (state is SaveState.Failed) Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
fun AddXtreamScreen(onDone: () -> Unit) {
    val vm = appViewModel { SourceEditorViewModel(it) }
    val test by vm.test.collectAsStateWithLifecycle()
    val save by vm.save.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("") }
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var format by rememberSaveable { mutableStateOf("ts") }
    LaunchedEffect(save) { if (save is SaveState.Saved) onDone() }
    fun form() = SourceForm(name = name.ifBlank { server.substringAfter("://").substringBefore('/').substringBefore(':') }, location = server, username = user, password = pass, streamFormat = format)

    DetailScaffold("Add Xtream account", onBack = onDone) { padding ->
        FormColumn(padding) {
            Field(name, { name = it }, "Name (optional)")
            Field(server, { server = it; vm.resetTest() }, "Server address", keyboardType = KeyboardType.Uri, supporting = "For example http://example.com:8080")
            Field(user, { user = it; vm.resetTest() }, "Username")
            PasswordField(pass, { pass = it; vm.resetTest() }, "Password")
            FormatChoice(format) { format = it }
            TestResult(test)
            SaveError(save)
            OutlinedButton(onClick = { vm.testXtream(form()) }, enabled = test != TestState.Running, modifier = Modifier.fillMaxWidth()) { Text("Test login") }
            Button(
                onClick = { vm.add(SourceType.XTREAM, form()) },
                enabled = save != SaveState.Saving && server.isNotBlank() && user.isNotBlank() && pass.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add and import") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddM3uScreen(onDone: () -> Unit) {
    val vm = appViewModel { SourceEditorViewModel(it) }
    val save by vm.save.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var useFile by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var ua by rememberSaveable { mutableStateOf("") }
    var fileUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            fileUri = uri
            if (name.isBlank()) name = displayName(context, uri).substringBeforeLast('.')
        }
    }
    LaunchedEffect(save) { if (save is SaveState.Saved) onDone() }
    val xtream = if (!useFile) M3uUrlInspector.detectXtream(url) else null

    DetailScaffold("Add M3U playlist", onBack = onDone) { padding ->
        FormColumn(padding) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "Web address", true to "File").forEachIndexed { i, (v, label) ->
                    SegmentedButton(selected = useFile == v, onClick = { useFile = v }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            Field(name, { name = it }, "Name")
            if (useFile) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Text(fileUri?.let { displayName(context, it) } ?: "Choose playlist file")
                }
            } else {
                Field(url, { url = it }, "Playlist address", keyboardType = KeyboardType.Uri)
                Field(ua, { ua = it }, "User agent (optional)")
            }
            if (xtream != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This looks like an Xtream account", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(
                            "Adding it as an Xtream account gives better catchup and TV guide support.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        OutlinedButton(onClick = {
                            vm.add(
                                SourceType.XTREAM,
                                SourceForm(name = name.ifBlank { xtream.base.substringAfter("://").substringBefore('/') }, location = xtream.base, username = xtream.username, password = xtream.password),
                            )
                        }) { Text("Add as Xtream account") }
                    }
                }
            }
            SaveError(save)
            Button(
                onClick = {
                    if (useFile) vm.add(SourceType.M3U_FILE, SourceForm(name = name.ifBlank { "Playlist" }), fileUri)
                    else vm.add(SourceType.M3U_URL, SourceForm(name = name.ifBlank { url.substringAfter("://").substringBefore('/') }, location = url, userAgent = ua))
                },
                enabled = save != SaveState.Saving && (if (useFile) fileUri != null else url.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add and import") }
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String =
    try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: "Playlist file"

@Composable
fun SourceEditScreen(sourceId: Long, onDone: () -> Unit) {
    val vm = appViewModel { SourceEditorViewModel(it) }
    val save by vm.save.collectAsStateWithLifecycle()
    val editable by vm.editable.collectAsStateWithLifecycle()
    val epg by remember(sourceId) { vm.epgSources(sourceId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    LaunchedEffect(sourceId) { vm.load(sourceId) }
    LaunchedEffect(save) { if (save is SaveState.Saved) onDone() }

    DetailScaffold("Edit source", onBack = onDone) { padding ->
        val e = editable
        if (e == null) {
            FormColumn(padding) { CircularProgressIndicator() }
            return@DetailScaffold
        }
        val s = e.source
        var name by remember(e) { mutableStateOf(s.name) }
        var location by remember(e) { mutableStateOf(e.location) }
        var user by remember(e) { mutableStateOf(e.username) }
        var pass by remember(e) { mutableStateOf("") }
        var ua by remember(e) { mutableStateOf(s.userAgent.orEmpty()) }
        var referrer by remember(e) { mutableStateOf(s.referrer.orEmpty()) }
        var format by remember(e) { mutableStateOf(s.streamFormat) }
        var tz by remember(e) { mutableStateOf(s.tzOverride.orEmpty()) }
        var epgUrl by remember { mutableStateOf("") }
        var epgError by remember { mutableStateOf<String?>(null) }

        FormColumn(padding) {
            Field(name, { name = it }, "Name")
            when (s.sourceType) {
                SourceType.XTREAM -> {
                    Field(location, { location = it }, "Server address", keyboardType = KeyboardType.Uri)
                    Field(user, { user = it }, "Username")
                    PasswordField(pass, { pass = it }, "Password", supporting = "Leave empty to keep the current password")
                    FormatChoice(format) { format = it }
                    Field(tz, { tz = it }, "Server time zone override", supporting = "For example Europe/Berlin. Leave empty to use the server's own.")
                }
                SourceType.M3U_URL -> Field(location, { location = it }, "Playlist address", keyboardType = KeyboardType.Uri)
                SourceType.M3U_FILE -> Text("Local file playlist", style = MaterialTheme.typography.bodyMedium)
            }
            Field(ua, { ua = it }, "User agent (optional)")
            Field(referrer, { referrer = it }, "Referrer (optional)")

            Text("TV guide (XMLTV) addresses", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            if (epg.isEmpty()) Text("None yet. They are added automatically from the provider or playlist, or you can add your own.", style = MaterialTheme.typography.bodySmall)
            epg.forEach { es ->
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(es.displayName, maxLines = 1) },
                    supportingContent = { Text(if (es.userAdded) "Added by you" else "From provider") },
                    trailingContent = if (es.userAdded) {
                        { IconButton(onClick = { vm.removeEpg(es.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") } }
                    } else null,
                )
            }
            Field(epgUrl, { epgUrl = it; epgError = null }, "Add XMLTV address", keyboardType = KeyboardType.Uri, supporting = epgError)
            OutlinedButton(
                onClick = { vm.addEpgUrl(sourceId, epgUrl) { err -> if (err == null) epgUrl = "" else epgError = err } },
                enabled = epgUrl.isNotBlank(),
            ) { Text("Add address") }

            SaveError(save)
            Button(
                onClick = {
                    vm.update(sourceId, SourceForm(name, location, user, pass, ua, referrer, format, tz))
                },
                enabled = save != SaveState.Saving && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save and refresh") }
        }
    }
}
