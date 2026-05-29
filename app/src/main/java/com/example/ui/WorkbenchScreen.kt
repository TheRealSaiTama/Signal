package com.example.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.unit.Dp

// STRICT INDUSTRIAL SHAPE
val IndustrialShape = RoundedCornerShape(2.dp)

// TYPOGRAPHY DEFINITIONS
val HeaderStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Medium,
    fontSize = 14.sp,
    color = Color.White
)

val DataLabelStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    letterSpacing = 0.1.em,
    color = Color(0xFFA1A1AA)
)

val CodecFooterStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Normal,
    fontSize = 12.sp,
    color = Color(0xFF52525B)
)

@Composable
fun WorkbenchScreen(
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current

    fun triggerHaptic() {
        try {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        } catch (e: Exception) {
            // Safe silence, prevents crash on devices without vibrator or physical haptic devices
        }
    }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showTelemetrySettings by remember { mutableStateOf(false) }

    // DERIVED UI STATE VALUES
    val isLoaded = uiState !is WorkbenchState.Idle && uiState !is WorkbenchState.Error
    val fileName = when (val state = uiState) {
        is WorkbenchState.SourceLoaded -> state.name
        is WorkbenchState.Processing -> "DSP_IN_PROGRESS.RAW"
        is WorkbenchState.Completed -> state.name
        else -> ""
    }
    val fileSize = when (val state = uiState) {
        is WorkbenchState.SourceLoaded -> state.size
        is WorkbenchState.Processing -> "CALCULATING..."
        is WorkbenchState.Completed -> state.size
        else -> ""
    }

    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    var isExporting by remember { mutableStateOf(false) }

    // PICKER LAUNCHER
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        try {
            if (uri != null) {
                triggerHaptic()
                val name = queryFileName(context, uri)
                val size = queryFileSize(context, uri)
                viewModel.selectSource(uri, name, size)
            }
        } catch (t: Throwable) {
            android.util.Log.e("WorkbenchScreen", "Error handling picked source", t)
            Toast.makeText(context, "ERROR LOADING SOURCE MEDIA", Toast.LENGTH_LONG).show()
        }
    }

    // PERMISSION LAUNCHER
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        try {
            if (isGranted) {
                videoPickerLauncher.launch("video/*")
            } else {
                // Try launching anyway because some system-level pickers don't require broad storage permission
                videoPickerLauncher.launch("video/*")
            }
        } catch (e: Exception) {
            try {
                videoPickerLauncher.launch("*/*")
            } catch (ex: Exception) {
                Toast.makeText(context, "ERROR: FILE PICKER CONSOLE FAULT", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun checkAndLaunchVideoPicker() {
        try {
            // Bypass potential crash in headless permission system by directly triggering the system file chooser.
            // Modern storage frameworks auto-grant read URI flags on pick.
            videoPickerLauncher.launch("video/*")
        } catch (e: Exception) {
            val permissionToCheck = if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.Manifest.permission.READ_MEDIA_VIDEO
            } else {
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            }
            
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                permissionToCheck
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            try {
                if (hasPermission) {
                    videoPickerLauncher.launch("*/*")
                } else {
                    permissionLauncher.launch(permissionToCheck)
                }
            } catch (e2: Exception) {
                Toast.makeText(context, "SYSTEM FAULT: ACCESSIBILITY NOT GRANTED // ABORTING", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ANIMATIONS
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val ringOpacity by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringOpacity"
    )

    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase"
    )

    val borderProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "borderProgress"
    )

    // PLATTER COLOR & SPEED MULTIPLIERS BASED ON STATEMACHINE
    val platterColor by animateColorAsState(
        targetValue = when (uiState) {
            is WorkbenchState.Processing -> Color(0xFFFF3333) // Turn Red for heavy computation
            is WorkbenchState.Completed -> Color(0xFF00FFCC) // Turn Green/Cyan upon Isolation success
            is WorkbenchState.SourceLoaded -> Color(0xFF00E5FF) // Solid Active Cyan
            is WorkbenchState.Error -> Color(0xFFFF3333) // Industrial Fault Red
            else -> Color(0xFFA1A1AA) // Gray Idle Platter
        },
        animationSpec = tween(150),
        label = "platterColor"
    )

    val waveSpeedFactor = if (uiState is WorkbenchState.Processing) 3.5f else 1.0f

    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationAngle"
    )

    val activeBorderBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.4f), Color(0xFF27272A))
    )
    val processingBorderBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFFFF3333).copy(alpha = 0.4f), Color(0xFF27272A))
    )
    val completedBorderBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF00FFCC).copy(alpha = 0.4f), Color(0xFF27272A))
    )
    val idleBorderBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF27272A), Color(0xFF18181B))
    )
    val currentBorderBrush = when (uiState) {
        is WorkbenchState.Processing -> processingBorderBrush
        is WorkbenchState.Completed -> completedBorderBrush
        is WorkbenchState.SourceLoaded -> activeBorderBrush
        else -> idleBorderBrush
    }

    val configBorderBrush = Brush.verticalGradient(
        colors = listOf(Color(0xFF00E5FF).copy(alpha = 0.4f), Color(0xFF27272A))
    )

    val glowColor = when (uiState) {
        is WorkbenchState.Processing -> Color(0xFFFF3333).copy(alpha = 0.5f)
        is WorkbenchState.Completed -> Color(0xFF00FFCC).copy(alpha = 0.5f)
        is WorkbenchState.Error -> Color(0xFFFF3333).copy(alpha = 0.5f)
        else -> Color(0xFF00E5FF).copy(alpha = 0.4f)
    }

    // THE AIRLOCK SCREEN LAYOUT
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // High-Tech Grid & Scanline Background
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSpacing = 40.dp.toPx()
            val gridColor = Color(0xFF1E293B).copy(alpha = 0.2f)
            
            // Draw grid lines
            var x = 0f
            while (x < size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5.dp.toPx())
                x += gridSpacing
            }
            var y = 0f
            while (y < size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
                y += gridSpacing
            }

            // Draw subtle corner decorative brackets (cyberpunk style)
            val padding = 16.dp.toPx()
            val bracketLen = 20.dp.toPx()
            val strokeW = 1.dp.toPx()
            val bracketColor = Color(0xFF27272A)
            
            // Top Left
            drawLine(bracketColor, Offset(padding, padding), Offset(padding + bracketLen, padding), strokeWidth = strokeW)
            drawLine(bracketColor, Offset(padding, padding), Offset(padding, padding + bracketLen), strokeWidth = strokeW)

            // Top Right
            drawLine(bracketColor, Offset(size.width - padding, padding), Offset(size.width - padding - bracketLen, padding), strokeWidth = strokeW)
            drawLine(bracketColor, Offset(size.width - padding, padding), Offset(size.width - padding, padding + bracketLen), strokeWidth = strokeW)

            // Bottom Left
            drawLine(bracketColor, Offset(padding, size.height - padding), Offset(padding + bracketLen, size.height - padding), strokeWidth = strokeW)
            drawLine(bracketColor, Offset(padding, size.height - padding), Offset(padding, size.height - padding - bracketLen), strokeWidth = strokeW)

            // Bottom Right
            drawLine(bracketColor, Offset(size.width - padding, size.height - padding), Offset(size.width - padding - bracketLen, size.height - padding), strokeWidth = strokeW)
            drawLine(bracketColor, Offset(size.width - padding, size.height - padding), Offset(size.width - padding, size.height - padding - bracketLen), strokeWidth = strokeW)
        }

        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 1. TOP BAR
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Image(
                        painter = painterResource(id = com.example.R.drawable.app_logo),
                        contentDescription = "Signal Logo Icon",
                        modifier = Modifier
                            .size(28.dp)
                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.3f), IndustrialShape)
                            .padding(1.dp)
                    )
                    Text(
                        text = "SIGNAL // CORE",
                        style = DataLabelStyle.copy(color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 0.15.em),
                        modifier = Modifier.testTag("signal_sys_label")
                    )
                }

                IconButton(
                    onClick = {
                        triggerHaptic()
                        showTelemetrySettings = !showTelemetrySettings
                    },
                    modifier = Modifier
                        .size(40.dp)
                        .border(1.dp, Color(0xFF27272A), IndustrialShape)
                        .testTag("settings_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "System Configuration Settings",
                        tint = if (showTelemetrySettings) Color(0xFF00E5FF) else Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            HorizontalDivider(
                color = Color(0xFF27272A),
                thickness = 1.dp
            )

            // MAIN CONTENT ZONE
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // CENTERPIECE: THE PLATTER (Takes up 70% of screen width)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.70f)
                            .aspectRatio(1f)
                            .testTag("platter_target"),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    enabled = uiState !is WorkbenchState.Processing // Disable click picker in process state
                                ) {
                                    triggerHaptic()
                                    if (uiState is WorkbenchState.Error) {
                                        viewModel.ejectMedium()
                                    } else {
                                        checkAndLaunchVideoPicker()
                                    }
                                }
                        ) {
                            val centerOffset = center
                            val ringRadius = size.minDimension / 2.3f

                            if (!isLoaded) {
                                // IDLE STATE: Circular ring with pulsing opacity
                                drawCircle(
                                    color = Color(0xFFA1A1AA).copy(alpha = ringOpacity),
                                    radius = ringRadius,
                                    center = centerOffset,
                                    style = Stroke(width = 2.dp.toPx())
                                )
                                // Draw minor tick marks inside the ring for a cool telemetry scope look
                                val tickCount = 8
                                for (i in 0 until tickCount) {
                                    val angle = (i.toFloat() / tickCount) * 2f * Math.PI.toFloat()
                                    val innerTick = ringRadius - 8.dp.toPx()
                                    val outerTick = ringRadius - 2.dp.toPx()
                                    val startX = centerOffset.x + cos(angle).toFloat() * innerTick
                                    val startY = centerOffset.y + sin(angle).toFloat() * innerTick
                                    val endX = centerOffset.x + cos(angle).toFloat() * outerTick
                                    val endY = centerOffset.y + sin(angle).toFloat() * outerTick
                                    drawLine(
                                        color = Color(0xFFA1A1AA).copy(alpha = ringOpacity * 0.4f),
                                        start = Offset(startX, startY),
                                        end = Offset(endX, endY),
                                        strokeWidth = 1.dp.toPx()
                                    )
                                }
                            } else {
                                // ACTIVE STATES: Constrained by state-based color state machine
                                drawCircle(
                                    color = platterColor,
                                    radius = ringRadius,
                                    center = centerOffset,
                                    style = Stroke(width = 2.dp.toPx())
                                )

                                // DRAW GEOMETRIC WAVEFORM AROUND CIRCUMFERENCE
                                val barCount = 120
                                val processedPhase = wavePhase * waveSpeedFactor
                                for (i in 0 until barCount) {
                                    val angle = (i.toFloat() / barCount) * 2f * Math.PI.toFloat()
                                    // Complex sine + phase offset for beautiful dynamic industrial wave
                                    val waveAmplitude = sin(angle * 8.0f - processedPhase * 1.5f) * cos(angle * 4.0f + processedPhase * 2.0f)
                                    val waveHeight = 16.dp.toPx() * (0.3f + 0.7f * ((waveAmplitude + 1f) / 2f))

                                    val innerR = ringRadius + 3.dp.toPx()
                                    val outerR = ringRadius + 3.dp.toPx() + waveHeight

                                    val startX = centerOffset.x + cos(angle).toFloat() * innerR
                                    val startY = centerOffset.y + sin(angle).toFloat() * innerR
                                    val endX = centerOffset.x + cos(angle).toFloat() * outerR
                                    val endY = centerOffset.y + sin(angle).toFloat() * outerR

                                    drawLine(
                                        color = platterColor,
                                        start = Offset(startX, startY),
                                        end = Offset(endX, endY),
                                        strokeWidth = 2.dp.toPx()
                                    )
                                }

                                // Rotate the canvas for processing telemetry sweeps
                                if (uiState is WorkbenchState.Processing) {
                                    rotate(rotationAngle) {
                                        // Draw crosshair or reticle
                                        val crossHairLen = 8.dp.toPx()
                                        drawLine(platterColor.copy(alpha = 0.5f), Offset(centerOffset.x - ringRadius + 2.dp.toPx(), centerOffset.y), Offset(centerOffset.x - ringRadius + 2.dp.toPx() + crossHairLen, centerOffset.y), strokeWidth = 1.5.dp.toPx())
                                        drawLine(platterColor.copy(alpha = 0.5f), Offset(centerOffset.x + ringRadius - 2.dp.toPx(), centerOffset.y), Offset(centerOffset.x + ringRadius - 2.dp.toPx() - crossHairLen, centerOffset.y), strokeWidth = 1.5.dp.toPx())
                                        drawLine(platterColor.copy(alpha = 0.5f), Offset(centerOffset.x, centerOffset.y - ringRadius + 2.dp.toPx()), Offset(centerOffset.x, centerOffset.y - ringRadius + 2.dp.toPx() + crossHairLen), strokeWidth = 1.5.dp.toPx())
                                        drawLine(platterColor.copy(alpha = 0.5f), Offset(centerOffset.x, centerOffset.y + ringRadius - 2.dp.toPx()), Offset(centerOffset.x, centerOffset.y + ringRadius - 2.dp.toPx() - crossHairLen), strokeWidth = 1.5.dp.toPx())
                                    }
                                }
                            }
                        }

                        // PLATTER CENTER TEXT STRUCTURE
                        val platterMainText = when (uiState) {
                            is WorkbenchState.Idle -> "AWAITING SOURCE"
                            is WorkbenchState.SourceLoaded -> "SIGNAL ACTIVE"
                            is WorkbenchState.Processing -> "DSP ACTIVE"
                            is WorkbenchState.Completed -> "SIGNAL CLEANED"
                            is WorkbenchState.Error -> "SYSTEM FAULT"
                        }
                        val platterSubText = when (uiState) {
                            is WorkbenchState.Idle -> "SELECT MEDIUM"
                            is WorkbenchState.SourceLoaded -> "TAP TO SWAP"
                            is WorkbenchState.Processing -> "DO NOT REMOVE"
                            is WorkbenchState.Completed -> "READY TO EXPORT"
                            is WorkbenchState.Error -> "TAP TO RESET"
                        }

                        // Logo on top half, status text on bottom half — clean separation
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize(0.75f)
                        ) {
                            if (uiState is WorkbenchState.Idle) {
                                Image(
                                    painter = painterResource(id = com.example.R.drawable.app_logo),
                                    contentDescription = "Signal Logo",
                                    modifier = Modifier
                                        .size(72.dp)
                                        .alpha(0.90f)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                            }
                            Text(
                                text = platterMainText,
                                style = DataLabelStyle.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = platterColor
                                ),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = platterSubText,
                                style = DataLabelStyle.copy(
                                    fontSize = 10.sp,
                                    color = Color(0xFF52525B)
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // SLEEK AUXILIARY PANEL
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, currentBorderBrush, IndustrialShape)
                            .circulatingBorder(glowColor, 1.dp, borderProgress)
                            .background(Color(0xFF18181B))
                            .padding(12.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    val ledColor = if (isLoaded) platterColor else Color(0xFF52525B)
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .background(ledColor, RoundedCornerShape(50))
                                            .border(1.dp, ledColor.copy(alpha = 0.5f), RoundedCornerShape(50))
                                    )
                                    Text(
                                        text = "MONITOR STATUS //",
                                        style = DataLabelStyle.copy(color = Color(0xFF52525B))
                                    )
                                }

                                val statusLabel = when (uiState) {
                                    is WorkbenchState.Idle -> "STATE_IDLE_OFFLINE"
                                    is WorkbenchState.SourceLoaded -> "STATE_CONNECTED"
                                    is WorkbenchState.Processing -> "STATE_DSP_BUSY"
                                    is WorkbenchState.Completed -> "STATE_READY_EXPORT"
                                    is WorkbenchState.Error -> "STATE_FAULT_RESTRICTED"
                                }

                                Text(
                                    text = statusLabel,
                                    style = DataLabelStyle.copy(
                                        color = platterColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }

                            if (isLoaded) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "FILE_NAME //", style = DataLabelStyle.copy(color = Color(0xFF52525B)))
                                    Text(
                                        text = fileName,
                                        style = DataLabelStyle.copy(color = Color.White),
                                        maxLines = 1,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(1f).padding(start = 16.dp)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "FILE_SIZE //", style = DataLabelStyle.copy(color = Color(0xFF52525B)))
                                    Text(text = fileSize, style = DataLabelStyle.copy(color = Color.White))
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // EJECT FLAT RECTANGLE BUTTON (Cancels sequence and frees space)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.dp, Color.White.copy(alpha = 0.2f), IndustrialShape)
                                        .clickable {
                                            triggerHaptic()
                                            viewModel.ejectMedium()
                                        }
                                        .padding(vertical = 10.dp)
                                        .testTag("eject_button"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "EJECT ACTIVE MEDIUM",
                                        style = DataLabelStyle.copy(
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            } else {
                                Text(
                                    text = "READY FOR SIGNAL INPUT ON CHANNEL 01.\nCLICK PLATTER RING TO INGEST VIDEO CHANNEL.",
                                    style = DataLabelStyle.copy(color = Color(0xFF52525B), fontSize = 11.sp),
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }

            // 3. FOOTER (only shown in Idle state to prevent overlapping and save space)
            if (uiState is WorkbenchState.Idle) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "CODECS: H.264  •  AAC  •  PCM",
                        style = CodecFooterStyle,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 4. BOTTOM ACTION & PROCESSING & RESULT CONSOLE (integrated into the Column for responsive auto-resizing)
            AnimatedVisibility(
                visible = uiState !is WorkbenchState.Idle,
                enter = slideInVertically(animationSpec = tween(200, easing = LinearEasing)) { it },
                exit = slideOutVertically(animationSpec = tween(200, easing = LinearEasing)) { it }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF050505))
                        .border(1.dp, currentBorderBrush, IndustrialShape)
                        .circulatingBorder(glowColor, 1.dp, borderProgress)
                        .padding(vertical = 20.dp, horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (val state = uiState) {
                        is WorkbenchState.SourceLoaded -> {
                            // Diagnostic Telemetry Panel
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .border(1.dp, currentBorderBrush, IndustrialShape)
                                    .background(Color(0xFF18181B))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "DIAGNOSTIC TELEMETRY //",
                                    style = DataLabelStyle.copy(color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                                )
                                HorizontalDivider(color = Color(0xFF27272A), thickness = 1.dp)
                                
                                val tel = state.telemetry
                                if (tel == null) {
                                    Text(
                                        text = "SYS_ANALYZING_PAYLOAD...",
                                        style = DataLabelStyle.copy(color = Color.White),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                } else {
                                    TelemetryMetricRow(
                                        label = "DURATION",
                                        value = formatDuration(tel.durationUs)
                                    )
                                    TelemetryMetricRow(
                                        label = "PEAK SIGNAL",
                                        value = String.format("%.1f DB", tel.peakDecibels)
                                    )
                                    TelemetryMetricRow(
                                        label = "NOISE FLOOR",
                                        value = if (tel.isNoiseFloorClean) "CLEAN" else "DETECTED (${String.format("%.1f DB", tel.noiseFloorDb)})"
                                    )
                                    TelemetryMetricRow(
                                        label = "ANOMALY",
                                        value = tel.anomaly.uppercase()
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(0.9f),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "TARGET ACQUIRED // READY FOR DSP",
                                    style = DataLabelStyle.copy(fontSize = 11.sp, color = Color(0xFFA1A1AA))
                                )
                                Text(
                                    text = "SYS_READY",
                                    style = DataLabelStyle.copy(fontSize = 11.sp, color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold)
                                )
                            }

                            // INITIATE SEQUENCE BUTTON (2px Cyan border, transparent background, custom sharp corners)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(52.dp)
                                    .border(2.dp, Color(0xFF00E5FF), IndustrialShape)
                                    .clickable {
                                        triggerHaptic()
                                        viewModel.initiateSequence()
                                    }
                                    .testTag("initiate_sequence_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "INITIATE SEQUENCE",
                                    style = DataLabelStyle.copy(
                                        color = Color(0xFF00E5FF),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                )
                            }
                        }
                        is WorkbenchState.Processing -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(0.9f),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "PURGING NOISE FLOOR...",
                                    style = DataLabelStyle.copy(
                                        fontSize = 11.sp,
                                        color = Color(0xFF00E5FF).copy(alpha = ringOpacity) // dynamic pulse matching circle pulse
                                    )
                                )
                                Text(
                                    text = "DSP_BUSY",
                                    style = DataLabelStyle.copy(fontSize = 11.sp, color = Color(0xFFFF3333), fontWeight = FontWeight.Bold)
                                )
                            }

                            // FLAT CYAN PROGRESS BAR (fills up based on State progress)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(52.dp)
                                    .border(1.dp, currentBorderBrush, IndustrialShape)
                                    .background(Color(0xFF18181B)) // Deep dark track
                                    .testTag("processing_progress_bar")
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(state.progress)
                                        .background(Color(0xFF00E5FF))
                                )
                                Text(
                                    text = "${(state.progress * 100).toInt()}% READY",
                                    style = DataLabelStyle.copy(
                                        color = if (state.progress > 0.55f) Color.Black else Color.White,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                        is WorkbenchState.Completed -> {
                            val consoleText = when (state.anomalyPurged) {
                                "LOW-FREQ RUMBLE" -> "LOW-FREQ RUMBLE PURGED"
                                else -> "SIGNAL ISOLATED // NOISE GATE APPLIED"
                            }

                            // Spectral Isolation Matrix Panel
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .border(1.dp, currentBorderBrush, IndustrialShape)
                                    .background(Color(0xFF18181B))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "SPECTRAL ISOLATION MATRIX //",
                                    style = DataLabelStyle.copy(color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold)
                                )
                                HorizontalDivider(color = Color(0xFF27272A), thickness = 1.dp)

                                TelemetryMetricRow("ENGINE", "DEEPFILTERNET3 (SOTA)")
                                TelemetryMetricRow("ATTENUATION", "-30dB NOISE FLOOR")
                                TelemetryMetricRow("DOMAIN", "SPECTRAL MASKING")
                                TelemetryMetricRow("LATENCY", "NATIVE JNI")
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(0.9f),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = consoleText,
                                    style = DataLabelStyle.copy(fontSize = 11.sp, color = Color(0xFFA1A1AA))
                                )
                                Text(
                                    text = "DSP_DONE",
                                    style = DataLabelStyle.copy(fontSize = 11.sp, color = Color(0xFF00FFCC), fontWeight = FontWeight.Bold)
                                )
                            }

                            // EXPORT CLEAN FEED BUTTON (Solid Cyan, Black Text, sharp custom corners)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(52.dp)
                                    .border(
                                        1.dp, 
                                        if (isExporting) Color(0xFF52525B) else Color(0xFF00E5FF), 
                                        IndustrialShape
                                    )
                                    .background(
                                        if (isExporting) Color(0xFF18181B) else Color(0xFF00E5FF)
                                    )
                                    .clickable(enabled = !isExporting) {
                                        triggerHaptic()
                                        coroutineScope.launch {
                                            isExporting = true
                                            val exportedUri = viewModel.exportFile(state.file, state.name, state.wavFile)
                                            isExporting = false
                                            if (exportedUri != null) {
                                                Toast.makeText(context, "PAYLOAD SAVED TO MUSIC/SIGNAL_CLEANED", Toast.LENGTH_SHORT).show()
                                                
                                                // Trigger Share Sheet
                                                val shareIntent = android.content.Intent().apply {
                                                    action = android.content.Intent.ACTION_SEND
                                                    putExtra(android.content.Intent.EXTRA_STREAM, exportedUri)
                                                    type = "audio/*"
                                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                try {
                                                    context.startActivity(android.content.Intent.createChooser(shareIntent, "Share Cleaned Audio"))
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "SHARE NOT SUPPORTED BY SYSTEM PLATFORM", Toast.LENGTH_SHORT).show()
                                                }
                                            } else {
                                                Toast.makeText(context, "EXPORT_ERROR // ABORTING", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }
                                    .testTag("export_clean_feed_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (isExporting) "EXPORTING_PAYLOAD..." else "EXPORT CLEAN FEED",
                                    style = DataLabelStyle.copy(
                                        color = if (isExporting) Color(0xFFA1A1AA) else Color.Black,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                )
                            }
                        }
                        is WorkbenchState.Error -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(0.9f),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "ERROR DETECTED // SYSTEM FAILURE",
                                    style = DataLabelStyle.copy(fontSize = 11.sp, color = Color(0xFFFF3333))
                                )
                                Text(
                                    text = "SYS_ERROR",
                                    style = DataLabelStyle.copy(fontSize = 11.sp, color = Color(0xFFFF3333), fontWeight = FontWeight.Bold)
                                )
                            }

                            // Mono-spaced error text
                            Text(
                                text = state.message.uppercase(),
                                style = DataLabelStyle.copy(
                                    color = Color(0xFFFF3333),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .border(1.dp, Color(0xFFFF3333), IndustrialShape)
                                    .background(Color(0xFF1F0707))
                                    .padding(12.dp)
                                    .testTag("error_message_text")
                            )

                            // RESET BUTTON (Red borders, dark background, sharp corners)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .height(52.dp)
                                    .border(1.dp, Color(0xFFFF3333), IndustrialShape)
                                    .background(Color(0xFF1A1010)) 
                                    .clickable {
                                        triggerHaptic()
                                        viewModel.ejectMedium()
                                    }
                                    .testTag("error_reset_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "RESET SEQUENCE",
                                    style = DataLabelStyle.copy(
                                        color = Color(0xFFFF3333),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        // TELEMETRY SETTINGS OVERLAY PANEL
        if (showTelemetrySettings) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.85f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showTelemetrySettings = false
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .border(1.dp, configBorderBrush, IndustrialShape)
                        .circulatingBorder(Color(0xFF00E5FF).copy(alpha = 0.5f), 1.dp, borderProgress)
                        .background(Color(0xFF18181B))
                        .padding(20.dp)
                        .clickable(enabled = false) {}, // Prevent clicks closing-through
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "SYSTEM CONFIGURATION",
                        style = HeaderStyle
                    )
                    HorizontalDivider(color = Color(0xFF27272A))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        TelemetryRow(label = "RENDER_ENGINE", value = "HARDWARE_VK_PIPELINE")
                        TelemetryRow(label = "LATENCY_TARGET", value = "12 MS")
                        TelemetryRow(label = "BUFFERING_MODE", value = "DIRECT_STRICT")
                        TelemetryRow(label = "CHROMA_SAMPLING", value = "4:4:4 SENSITIVE")
                        TelemetryRow(label = "DECODER_STATE", value = "ACTIVE_HW_ACCEL")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color(0xFF27272A), IndustrialShape)
                            .clickable {
                                triggerHaptic()
                                showTelemetrySettings = false
                            }
                            .padding(vertical = 10.dp)
                            .testTag("close_settings_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "[ CLOSE SYSTEMS CONSOLE ]",
                            style = DataLabelStyle.copy(color = Color.White)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label //",
            style = DataLabelStyle.copy(color = Color(0xFF52525B))
        )
        Text(
            text = value,
            style = DataLabelStyle.copy(color = Color(0xFF00E5FF), fontWeight = FontWeight.SemiBold)
        )
    }
}

// ENVIRONMENT DATA EXTRACTION UTILITIES
private fun queryFileName(context: Context, uri: Uri): String {
    var name = ""
    try {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(index) ?: ""
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("queryFileName", "Error querying content resolver for file name", e)
    }
    if (name.isEmpty()) {
        val path = uri.path
        if (path != null) {
            val cut = path.lastIndexOf('/')
            name = if (cut != -1) path.substring(cut + 1) else path
        }
    }
    return if (name.isEmpty()) "UNNAMED_STREAM.MP4" else name
}

private fun queryFileSize(context: Context, uri: Uri): String {
    var sizeBytes: Long = 0
    try {
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index != -1 && cursor.moveToFirst()) {
                    sizeBytes = cursor.getLong(index)
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("queryFileSize", "Error querying content resolver for file size", e)
    }
    if (sizeBytes <= 0) return "UNKNOWN SIZE"
    val kb = sizeBytes / 1024.0
    val mb = kb / 1024.0
    return if (mb >= 1.0) {
        String.format("%.2f MB", mb)
    } else {
        String.format("%.2f KB", kb)
    }
}

@Composable
fun TelemetryMetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label //",
            style = DataLabelStyle.copy(color = Color(0xFF52525B))
        )
        Text(
            text = value,
            style = DataLabelStyle.copy(color = Color(0xFF00E5FF), fontWeight = FontWeight.Normal)
        )
    }
}

private fun formatDuration(durationUs: Long): String {
    val totalSeconds = durationUs / 1_000_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

fun Modifier.circulatingBorder(
    color: Color,
    borderWidth: Dp = 1.dp,
    progress: Float
) = this.drawWithContent {
    drawContent()
    
    val width = size.width
    val height = size.height
    val borderWidthPx = borderWidth.toPx()
    val cornerRadiusPx = 2.dp.toPx()
    
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                rect = androidx.compose.ui.geometry.Rect(0f, 0f, width, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadiusPx, cornerRadiusPx)
            )
        )
    }
    
    val pathMeasure = PathMeasure()
    pathMeasure.setPath(path, false)
    val pathLength = pathMeasure.length
    
    val segmentLen = if (120.dp.toPx() < pathLength * 0.25f) 120.dp.toPx() else pathLength * 0.25f
    
    val activePath = Path()
    val startDistance = (progress * pathLength) % pathLength
    val endDistance = (startDistance + segmentLen) % pathLength
    
    if (endDistance > startDistance) {
        pathMeasure.getSegment(startDistance, endDistance, activePath, true)
    } else {
        pathMeasure.getSegment(startDistance, pathLength, activePath, true)
        pathMeasure.getSegment(0f, endDistance, activePath, true)
    }
    
    drawPath(
        path = activePath,
        color = color,
        style = Stroke(width = borderWidthPx)
    )
}

