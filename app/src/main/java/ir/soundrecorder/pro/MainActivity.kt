package ir.soundrecorder.pro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.Context
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
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import ir.soundrecorder.pro.service.RecordingService
import ir.soundrecorder.pro.storage.AudioStorage
import java.io.File
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
    var recording by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(AudioStorage.list(context)) }
    var message by remember { mutableStateOf("برای شروع، مجوزهای لازم را تأیید کنید.") }

    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(context, RecordingService::class.java).apply {
                action = RecordingService.ACTION_START
                putExtra(RecordingService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(RecordingService.EXTRA_DATA, result.data)
            }
            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                recording = true
                message = "درخواست ضبط ارسال شد. وضعیت واقعی سرویس در اعلان اندروید نمایش داده می‌شود."
            } catch (t: Throwable) {
                message = "شروع ضبط ناموفق بود: ${t.localizedMessage ?: "خطای سیستم"}"
            }
        } else {
            message = "اجازه ضبط صدای داخلی داده نشد؛ ضبط شروع نشد."
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } else message = "برای ضبط محیط، اجازه دسترسی به میکروفون لازم است."
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun start() {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun stop() {
        context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        recording = false
        message = "درخواست توقف ارسال شد."
        files = AudioStorage.list(context)
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Sound Recorder Pro", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                AssistChip(onClick = { files = AudioStorage.list(context) }, label = { Text("به‌روزرسانی") }, leadingIcon = { Icon(Icons.Default.Folder, null) })
            }
            Spacer(Modifier.height(28.dp))
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(116.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(54.dp)) }
            }
            Spacer(Modifier.height(14.dp))
            Text(if (recording) "ضبط در حال اجرا" else "ضبط متوقف است", style = MaterialTheme.typography.headlineSmall)
            Text("${message}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(22.dp))
            Button(onClick = { if (recording) stop() else start() }, modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (recording) "توقف ضبط" else "شروع ضبط")
            }
            Spacer(Modifier.height(20.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    InfoLine("صدای محیط", "میکروفون")
                    InfoLine("صدای داخلی", "فقط برنامه‌های مجاز")
                    InfoLine("فرمت فایل", "M4A / AAC")
                    InfoLine("تقسیم فایل", "هر ۵ دقیقه")
                    InfoLine("محل ذخیره", "حافظه خصوصی برنامه")
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("فایل‌های ضبط‌شده", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${files.size} فایل", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
            if (files.isEmpty()) {
                Text("هنوز فایلی ذخیره نشده است.", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(files, key = { it.absolutePath }) { file -> RecordingFileRow(file) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("ضبط با اعلان دائمی و کنترل توقف انجام می‌شود. صدای محافظت‌شده یا تماس‌ها ممکن است قابل ضبط نباشند.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecordingFileRow(file: File) {
    val date = remember(file.lastModified()) { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(file.lastModified())) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text("$date • ${file.length() / 1024} KB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
