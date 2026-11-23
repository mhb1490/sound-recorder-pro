package ir.soundrecorder.pro

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ir.soundrecorder.pro.service.RecordingService
import ir.soundrecorder.pro.storage.AudioStorage
import ir.soundrecorder.pro.storage.RecordingFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RecorderHome() } }
    }
}

@Composable
private fun RecorderHome() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by RecordingService.state.collectAsState()
    var files by remember { mutableStateOf(emptyList<RecordingFile>()) }
    var mode by rememberSaveable { mutableStateOf(RecordingService.MODE_MIC) }
    var pendingMode by rememberSaveable { mutableStateOf(RecordingService.MODE_MIC) }
    var launching by rememberSaveable { mutableStateOf(false) }
    var localMessage by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf<String?>(null) }
    var renameItem by remember { mutableStateOf<RecordingFile?>(null) }
    var newName by remember { mutableStateOf("") }
    var deleteItem by remember { mutableStateOf<RecordingFile?>(null) }
    var busyFile by remember { mutableStateOf(false) }
    val active = state.phase != "idle"

    fun stopPlayer() { player?.let { runCatching { it.release() } }; player = null; playing = null }
    DisposableEffect(Unit) { onDispose { stopPlayer() } }
    LaunchedEffect(state.revision, refresh) {
        try { files = withContext(Dispatchers.IO) { AudioStorage.list(context) } }
        catch (e: Exception) { localMessage = "خطای خواندن فایل‌ها: ${e.localizedMessage}" }
    }
    LaunchedEffect(state.revision) { if (state.revision > 0) launching = false }

    fun begin(data: Intent? = null, code: Int = Activity.RESULT_CANCELED) {
        try {
            val intent = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_MODE, pendingMode)
                if (data != null) { putExtra(RecordingService.EXTRA_DATA, data); putExtra(RecordingService.EXTRA_RESULT_CODE, code) }
            }
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) { launching = false; localMessage = "شروع ضبط ناموفق بود: ${e.localizedMessage}" }
    }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) begin(result.data, result.resultCode)
        else { launching = false; localMessage = "اجازه ضبط صدای داخلی داده نشد. برای ضبط محیط، حالت میکروفون را انتخاب کنید." }
    }
    fun continueStart() {
        if (pendingMode == RecordingService.MODE_MIC) begin() else try {
            projectionLauncher.launch(checkNotNull(context.getSystemService(MediaProjectionManager::class.java)).createScreenCaptureIntent())
        } catch (e: Exception) { launching = false; localMessage = "درخواست ضبط داخلی ناموفق بود: ${e.localizedMessage}" }
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { continueStart() }
    fun afterMicPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        else continueStart()
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicPermission() else { launching = false; localMessage = "مجوز ضبط صدا لازم است." }
    }
    fun start() {
        stopPlayer(); localMessage = null; pendingMode = mode; launching = true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) afterMicPermission()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun stop() {
        localMessage = null
        try { context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)) }
        catch (e: Exception) { localMessage = "خطای توقف: ${e.localizedMessage}" }
    }
    fun play(file: RecordingFile) {
        if (playing == file.uri.toString()) { stopPlayer(); return }
        stopPlayer(); localMessage = null
        try {
            val p = MediaPlayer()
            player = p; playing = file.uri.toString()
            p.setDataSource(context, file.uri)
            p.setOnPreparedListener { if (player === it) it.start() }
            p.setOnCompletionListener { if (player === it) stopPlayer() }
            p.setOnErrorListener { mp, _, _ -> if (player === mp) { stopPlayer(); localMessage = "پخش فایل ناموفق بود." }; true }
            p.prepareAsync()
        } catch (e: Exception) { stopPlayer(); localMessage = "پخش فایل ناموفق بود: ${e.localizedMessage}" }
    }
    fun share(file: RecordingFile) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = file.mime
                putExtra(Intent.EXTRA_STREAM, file.uri)
                clipData = ClipData.newRawUri(file.name, file.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "اشتراک‌گذاری فایل صوتی"))
        } catch (e: Exception) { localMessage = "اشتراک‌گذاری ناموفق بود: ${e.localizedMessage}" }
    }
    fun changeFile(success: String, operation: () -> Unit) {
        stopPlayer(); busyFile = true; localMessage = null
        scope.launch {
            try { withContext(Dispatchers.IO) { operation() }; localMessage = success; refresh++ }
            catch (e: Exception) { localMessage = "عملیات ناموفق بود: ${e.localizedMessage}" }
            finally { busyFile = false }
        }
    }

    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Sound Recorder Pro", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(88.dp)) {
                        Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, null, modifier = Modifier.size(46.dp)) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(when (state.phase) { "running" -> "ضبط در حال اجرا"; "starting" -> "در حال شروع"; "stopping" -> "در حال ذخیره"; else -> "ضبط متوقف است" }, style = MaterialTheme.typography.headlineSmall)
                    Text(localMessage ?: state.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("نوع ضبط", fontWeight = FontWeight.Bold)
                        listOf(RecordingService.MODE_MIC to "میکروفون / صدای محیط (M4A)", RecordingService.MODE_INTERNAL to "صدای داخلی مجاز (WAV)", RecordingService.MODE_BOTH to "هر دو، در دو فایل جداگانه").forEach { (value, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = mode == value, onClick = { mode = value }, enabled = !active && !launching)
                                Text(label)
                            }
                        }
                        Text("ضبط تماس مستقیم در APK معمولی پشتیبانی نمی‌شود. میکروفون ممکن است هنگام تماس توسط سیستم مسدود شود. ضبط را فقط با اطلاع و رضایت افراد انجام دهید.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Button(onClick = { if (active) stop() else start() }, enabled = !launching && !busyFile && state.phase != "starting" && state.phase != "stopping", modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(if (active) Icons.Default.Stop else Icons.Default.Mic, null)
                    Spacer(Modifier.width(8.dp)); Text(if (active) "توقف ضبط" else if (launching) "در انتظار مجوز…" else "شروع ضبط")
                }
            }
            item { Text("محل ذخیره: Music/SoundRecorderPro • تقسیم فایل هر ۵ دقیقه", style = MaterialTheme.typography.bodySmall) }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("فایل‌های ضبط‌شده (${files.size})", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { refresh++ }) { Text("به‌روزرسانی") }
                }
            }
            if (files.isEmpty()) item { Text("هنوز فایل نهایی ذخیره نشده است.") }
            items(files, key = { it.uri.toString() }) { file ->
                val date = remember(file.date) { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(file.date)) }
                val duration = if (file.duration > 0) String.format(Locale.US, "%02d:%02d", file.duration / 60000, file.duration / 1000 % 60) else "—"
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(file.name, fontWeight = FontWeight.Medium)
                        Text("$date • ${file.size / 1024} KB • $duration", style = MaterialTheme.typography.bodySmall)
                        if (file.privateFile != null) Text("حافظه خصوصی؛ برای انتقال به Music، خروجی را بزنید.", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { play(file) }, enabled = !active && !launching && !busyFile) { Text(if (playing == file.uri.toString()) "توقف" else "پخش") }
                            TextButton(onClick = { share(file) }, enabled = !busyFile) { Text("ارسال") }
                            if (file.privateFile != null) TextButton(onClick = { changeFile("فایل به Music منتقل شد.") { AudioStorage.export(context, file) } }, enabled = !busyFile) { Text("خروجی") }
                        }
                        Row {
                            TextButton(onClick = { newName = file.name.substringBeforeLast('.'); renameItem = file }, enabled = !busyFile) { Text("تغییر نام") }
                            TextButton(onClick = { deleteItem = file }, enabled = !busyFile) { Text("حذف") }
                        }
                    }
                }
            }
        }
    }
    renameItem?.let { file -> AlertDialog(onDismissRequest = { renameItem = null }, title = { Text("تغییر نام فایل") }, text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { renameItem = null; changeFile("نام فایل تغییر کرد.") { AudioStorage.rename(context, file, newName) } }) { Text("ذخیره") } }, dismissButton = { TextButton(onClick = { renameItem = null }) { Text("انصراف") } }) }
    deleteItem?.let { file -> AlertDialog(onDismissRequest = { deleteItem = null }, title = { Text("حذف فایل؟") }, text = { Text(file.name) }, confirmButton = { TextButton(onClick = { deleteItem = null; changeFile("فایل حذف شد.") { AudioStorage.delete(context, file) } }) { Text("حذف") } }, dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("انصراف") } }) }
}
