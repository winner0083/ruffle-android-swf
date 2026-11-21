package rs.ruffle

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.paddingFromBaseline
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import rs.ruffle.ui.theme.RuffleTheme
import rs.ruffle.ui.theme.SLIGHTLY_DEEMPHASIZED_ALPHA

@Composable
fun BrandBar() {
    Image(
        painter = painterResource(id = R.drawable.ic_logo_dark),
        contentDescription = stringResource(id = R.string.logo_description),
        modifier = Modifier
            .wrapContentSize(align = Alignment.Center)
            .padding(vertical = 75.dp)
    )
}

@Composable
fun SelectSwfRoute(openSwf: (uri: Uri) -> Unit) {
    SelectSwfScreen(
        openSwf = openSwf
    )
}

@Composable
fun SelectSwfScreen(openSwf: (uri: Uri) -> Unit) {
    val context = LocalContext.current
    var tree by remember { mutableStateOf(SwfLibrary.treeUri(context)) }
    var treeName by remember { mutableStateOf(SwfLibrary.treeName(context)) }
    var dir by remember { mutableStateOf(SwfLibrary.dirPath(context)) }
    var dirName by remember { mutableStateOf(SwfLibrary.dirName(context)) }
    var entries by remember { mutableStateOf<List<SwfLibrary.Entry>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var rescan by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    val hasSource = tree != null || dir != null

    LaunchedEffect(tree, dir, rescan) {
        val currentTree = tree
        val currentDir = dir
        if (currentTree == null && currentDir == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        scanning = true
        entries = withContext(Dispatchers.IO) {
            runCatching {
                if (currentTree != null) {
                    SwfLibrary.scan(context, currentTree)
                } else {
                    SwfLibrary.scanDir(File(currentDir!!))
                }
            }.getOrDefault(emptyList())
        }
        scanning = false
    }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Some providers do not support persistable grants.
            }
            SwfLibrary.setTree(context, uri, uri.lastPathSegment?.substringAfterLast('/'))
            treeName = SwfLibrary.treeName(context)
            tree = uri
            dir = null
            dirName = null
            message = null
        }
    }

    // Some TV builds expose no storage root in the SAF picker at all, so API <= 29
    // can also read Download/swf directly from shared storage.
    val startDirScan = {
        val candidate = SwfLibrary.candidateDirs().firstOrNull()
        if (candidate == null) {
            message = "未找到可扫描的 swf 目录"
        } else {
            SwfLibrary.setDir(context, candidate.absolutePath)
            dir = candidate.absolutePath
            dirName = candidate.name
            tree = null
            treeName = null
            message = null
        }
    }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startDirScan() else message = "需要存储读取权限才能扫描内置目录"
    }

    Scaffold { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentSize(align = Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "Ruffle 游戏库",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                    )
                }
            }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentSize(align = Alignment.Center)
                        .width(488.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = if (!hasSource) {
                            "选择存放 .swf 的文件夹，即可列出全部游戏"
                        } else {
                            "当前文件夹：${treeName ?: dirName ?: "swf"}"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = { pickTree.launch(tree) }) {
                            Text(if (hasSource) "更换文件夹" else "选择文件夹")
                        }
                        if (SwfLibrary.supportsDirScan()) {
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(
                                onClick = {
                                    if (SwfLibrary.hasStoragePermission(context)) {
                                        startDirScan()
                                    } else {
                                        storagePermission.launch(
                                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                                        )
                                    }
                                }
                            ) { Text("扫描内置目录") }
                        }
                        if (hasSource) {
                            Spacer(Modifier.width(12.dp))
                            OutlinedButton(onClick = { rescan++ }) { Text("重新扫描") }
                        }
                    }
                    message?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (hasSource) {
                        Text(
                            text = when {
                                scanning -> "正在扫描…"
                                else -> "共 ${entries.size} 个游戏"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(
                                alpha = SLIGHTLY_DEEMPHASIZED_ALPHA
                            ),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }

            if (hasSource && !scanning && entries.isEmpty()) {
                item {
                    Text(
                        text = "该文件夹里没有找到 .swf 文件",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }

            items(entries, key = { it.uri.toString() }) { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openSwf(entry.uri) }
                        .padding(vertical = 12.dp),
                ) {
                    Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
            }

            item {
                Column(
                    modifier = Modifier
                        .padding(top = 24.dp, bottom = 8.dp)
                        .fillMaxWidth()
                        .wrapContentSize(align = Alignment.Center),
                ) {
                    BrandBarSmall()
                    Text(
                        text = stringResource(id = R.string.work_in_progress_warning),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                    SelectSwfUrlOrFile(openSwf)
                }
            }
        }
    }
}

@Composable
private fun BrandBarSmall() {
    Text(
        text = "或者直接打开",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SLIGHTLY_DEEMPHASIZED_ALPHA),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SelectSwfUrlOrFile(openSwf: (uri: Uri) -> Unit) {
    val urlState by rememberSaveable(stateSaver = UrlStateSaver) {
        mutableStateOf(UrlState())
    }
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
    ) {
        val submitUrl = {
            if (urlState.isValid) {
                openSwf(Uri.parse(urlState.text))
            } else {
                urlState.enableShowErrors()
            }
        }
        OutlinedTextField(
            value = urlState.text,
            onValueChange = { urlState.text = it },
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentSize(align = Alignment.Center)
                .width(488.dp)
                .onFocusChanged { focusState ->
                    urlState.onFocusChange(focusState.isFocused)
                    if (!focusState.isFocused) {
                        urlState.enableShowErrors()
                    }
                },
            label = {
                Text(
                    text = stringResource(id = R.string.url),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium,
            isError = urlState.showErrors(),
            keyboardOptions = KeyboardOptions.Default.copy(
                imeAction = ImeAction.Go,
                keyboardType = KeyboardType.Uri
            ),
            keyboardActions = KeyboardActions(
                onDone = { submitUrl() }
            ),
            singleLine = true
        )
        urlState.getError()?.let { error -> TextFieldError(textError = error) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentSize(align = Alignment.Center),
            horizontalArrangement = Arrangement.Center,
        ) {
            Button(
                onClick = submitUrl,
                modifier = Modifier.padding(top = 12.dp, bottom = 3.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.open_url),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            Spacer(Modifier.width(12.dp))
            PickSwfButton(openSwf)
        }
    }
}

@Composable
fun PickSwfButton(onSelect: (uri: Uri) -> Unit) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        if (it != null) {
            onSelect(it)
        }
    }

    OutlinedButton(
        onClick = {
            launcher.launch(
                // Ideally "application/x-shockwave-flash" would be
                // used here, but Android doesn't recognize many
                // downloaded .swf files as such for some reason... :/
                "*/*"
            )
        },
        modifier = Modifier
            .padding(top = 12.dp, bottom = 24.dp)

    ) {
        Text(text = stringResource(id = R.string.select_a_swf))
    }
}

@Composable
fun TextFieldError(textError: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = textError,
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Preview(name = "Select SWF - Light", uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Select SWF - Dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun SelectSwfScreenPreview() {
    RuffleTheme {
        Surface {
            SelectSwfScreen(
                openSwf = {}
            )
        }
    }
}
