package lab.dawnforge.hermes.feature.connect.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import lab.dawnforge.hermes.feature.connect.ConnectViewModel
import lab.dawnforge.hermes.feature.connect.FormColumn
import lab.dawnforge.hermes.feature.connect.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanScreen(viewModel: ConnectViewModel, onDone: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }

    LaunchedEffect(Unit) {
        if (!granted && hasCamera) permission.launch(Manifest.permission.CAMERA)
    }
    val unreadable = stringResource(R.string.feature_connect_scan_unreadable)
    LaunchedEffect(state.unreadableScan) {
        if (state.unreadableScan) {
            viewModel.onUnreadableScanShown()
            snackbar.showSnackbar(unreadable)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_connect_scan_title)) },
                navigationIcon = {
                    TextButton(onClick = onDone) { Text(stringResource(R.string.feature_connect_back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        FormColumn(Modifier.padding(padding)) {
            when {
                !hasCamera -> Text(stringResource(R.string.feature_connect_scan_no_camera))

                !granted -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.feature_connect_scan_permission))
                    Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                        Text(stringResource(R.string.feature_connect_scan_allow))
                    }
                }

                else -> {
                    Text(stringResource(R.string.feature_connect_scan_hint), style = MaterialTheme.typography.bodyLarge)
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(3f / 4f),
                    ) {
                        CameraPreview(onCode = { text -> if (viewModel.onScanned(text)) onDone() })
                    }
                }
            }
        }
    }
}

/** Back-camera preview that reports each decoded QR code to [onCode] on the main thread. */
@Composable
private fun CameraPreview(onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val previewView = remember { PreviewView(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val decoder = QrDecoder()
        // Ignore frames while the last code is being handled, so one code is reported once.
        val handling = AtomicBoolean(false)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(analysisExecutor) { image ->
                    val text = if (handling.get()) null else image.use(decoder::decodeLuminance)
                    if (text != null && handling.compareAndSet(false, true)) {
                        ContextCompat.getMainExecutor(context).execute {
                            currentOnCode(text)
                            handling.set(false)
                        }
                    }
                }
            }
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
    }
    // The use cases are bound to this screen's lifecycle, so CameraX releases the camera when the
    // screen goes away; only the analysis thread needs stopping.
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth())
}

/** Reads the Y plane of a YUV_420_888 frame, the format ImageAnalysis delivers by default. */
private fun QrDecoder.decodeLuminance(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
    return decode(bytes, image.width, image.height, plane.rowStride)
}
