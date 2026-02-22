package fpv.joshattic.us

import android.util.Size
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs

val PrimaryGreen = Color(0xFFA5D6A7)
val SurfaceDark = Color(0xFF1C1C1E)
val SurfaceLight = Color(0xFF2C2C2E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onDismiss: () -> Unit,
    availableVideoResolutions: List<Size>,
    availablePhotoResolutions: List<Size>,
    onSettingsChanged: () -> Unit
) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Video", "Photo", "App")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        TopAppBar(
            title = { Text("Settings", color = Color.White, fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black)
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Black,
            contentColor = PrimaryGreen
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { 
                        Text(
                            title, 
                            color = if (selectedTab == index) PrimaryGreen else Color.Gray,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                        ) 
                    }
                )
            }
        }

        AnimatedContent(
            targetState = selectedTab,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
            },
            label = "TabTransition"
        ) { targetTab ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                when (targetTab) {
                    0 -> VideoSettingsTab(settings, availableVideoResolutions, onSettingsChanged)
                    1 -> PhotoSettingsTab(settings, availablePhotoResolutions, onSettingsChanged)
                    2 -> AppSettingsTab(settings, onSettingsChanged)
                }
            }
        }
    }
}

@Composable
fun SettingCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(16.dp)
    ) {
        Text(
            text = title.uppercase(), 
            color = PrimaryGreen, 
            style = MaterialTheme.typography.labelMedium, 
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        content()
    }
}

@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceLight)
            .padding(4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selectedOption
            val backgroundColor by animateColorAsState(if (isSelected) PrimaryGreen else Color.Transparent, label = "bg")
            val textColor by animateColorAsState(if (isSelected) Color.Black else Color.White, label = "text")
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOptionSelected(value) }
                    .background(backgroundColor)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = textColor, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun VideoSettingsTab(settings: AppSettings, availableResolutions: List<Size>, onSettingsChanged: () -> Unit) {
    var aspectRatio by remember { mutableStateOf(settings.videoAspectRatio) }
    var resolutionIndex by remember { mutableStateOf(settings.videoResolutionIndex) }
    var bitrate by remember { mutableStateOf(settings.videoBitrate) }
    var audioChannels by remember { mutableStateOf(settings.audioChannels) }
    var audioSampleRate by remember { mutableStateOf(settings.audioSampleRate) }
    var audioBitrate by remember { mutableStateOf(settings.audioBitrate) }

    val filteredResolutions = remember(aspectRatio, availableResolutions) {
        filterResolutionsByAspectRatio(availableResolutions, aspectRatio)
    }

    LaunchedEffect(aspectRatio) {
        if (resolutionIndex >= filteredResolutions.size) {
            resolutionIndex = 0
            settings.videoResolutionIndex = 0
            onSettingsChanged()
        }
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SettingCard("Video Format") {
            Text("Aspect Ratio", color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            SegmentedControl(
                options = listOf("1:1" to "1:1", "5:4" to "5:4", "4:3" to "4:3", "16:9" to "16:9"),
                selectedOption = aspectRatio,
                onOptionSelected = {
                    aspectRatio = it
                    settings.videoAspectRatio = it
                    resolutionIndex = 0
                    settings.videoResolutionIndex = 0
                    onSettingsChanged()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (filteredResolutions.isNotEmpty()) {
                val currentRes = filteredResolutions.getOrNull(resolutionIndex) ?: filteredResolutions.first()
                val mp = (currentRes.width * currentRes.height) / 1_000_000f
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Resolution", color = Color.White)
                    Text("${currentRes.width}x${currentRes.height} (%.1f MP)".format(mp), color = PrimaryGreen, fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val maxIndex = maxOf(0, filteredResolutions.size - 1)
                val sliderValue = if (maxIndex == 0) 1f else (maxIndex - resolutionIndex).toFloat()
                
                Slider(
                    value = sliderValue,
                    onValueChange = { 
                        if (maxIndex > 0) {
                            val newIndex = maxIndex - it.toInt()
                            resolutionIndex = newIndex
                            settings.videoResolutionIndex = newIndex
                            onSettingsChanged()
                        }
                    },
                    valueRange = 0f..maxOf(1f, maxIndex.toFloat()),
                    steps = maxOf(0, maxIndex - 1),
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryGreen,
                        activeTrackColor = PrimaryGreen,
                        inactiveTrackColor = SurfaceLight,
                        activeTickColor = SurfaceDark,
                        inactiveTickColor = PrimaryGreen.copy(alpha = 0.5f)
                    )
                )
            } else {
                Text("No resolutions available for this aspect ratio.", color = Color.Gray)
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Video Bitrate", color = Color.White)
                Text("$bitrate Mbps", color = PrimaryGreen, fontWeight = FontWeight.Bold)
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Slider(
                value = bitrate.toFloat(),
                onValueChange = { 
                    bitrate = it.toInt()
                    settings.videoBitrate = bitrate
                    onSettingsChanged()
                },
                valueRange = 1f..14f,
                steps = 12,
                colors = SliderDefaults.colors(
                    thumbColor = PrimaryGreen,
                    activeTrackColor = PrimaryGreen,
                    inactiveTrackColor = SurfaceLight,
                    activeTickColor = SurfaceDark,
                    inactiveTickColor = PrimaryGreen.copy(alpha = 0.5f)
                )
            )
        }

        SettingCard("Audio") {
            Text("Channels", color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            SegmentedControl(
                options = listOf(1 to "Mono", 2 to "Stereo"),
                selectedOption = audioChannels,
                onOptionSelected = {
                    audioChannels = it
                    settings.audioChannels = it
                    onSettingsChanged()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Sample Rate", color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            SegmentedControl(
                options = listOf(44100 to "44.1 kHz", 48000 to "48 kHz"),
                selectedOption = audioSampleRate,
                onOptionSelected = {
                    audioSampleRate = it
                    settings.audioSampleRate = it
                    onSettingsChanged()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Bitrate", color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            SegmentedControl(
                options = listOf(128000 to "128 kbps", 256000 to "256 kbps"),
                selectedOption = audioBitrate,
                onOptionSelected = {
                    audioBitrate = it
                    settings.audioBitrate = it
                    onSettingsChanged()
                }
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun PhotoSettingsTab(settings: AppSettings, availableResolutions: List<Size>, onSettingsChanged: () -> Unit) {
    var aspectRatio by remember { mutableStateOf(settings.photoAspectRatio) }
    var resolutionIndex by remember { mutableStateOf(settings.photoResolutionIndex) }

    val filteredResolutions = remember(aspectRatio, availableResolutions) {
        filterResolutionsByAspectRatio(availableResolutions, aspectRatio)
    }

    LaunchedEffect(aspectRatio) {
        if (resolutionIndex >= filteredResolutions.size) {
            resolutionIndex = 0
            settings.photoResolutionIndex = 0
            onSettingsChanged()
        }
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SettingCard("Photo Format") {
            Text("Aspect Ratio", color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            SegmentedControl(
                options = listOf("1:1" to "1:1", "5:4" to "5:4", "4:3" to "4:3", "16:9" to "16:9"),
                selectedOption = aspectRatio,
                onOptionSelected = {
                    aspectRatio = it
                    settings.photoAspectRatio = it
                    resolutionIndex = 0
                    settings.photoResolutionIndex = 0
                    onSettingsChanged()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            if (filteredResolutions.isNotEmpty()) {
                val currentRes = filteredResolutions.getOrNull(resolutionIndex) ?: filteredResolutions.first()
                val mp = (currentRes.width * currentRes.height) / 1_000_000f
                
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Resolution", color = Color.White)
                    Text("%.1f MP (${currentRes.width}x${currentRes.height})".format(mp), color = PrimaryGreen, fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val maxIndex = maxOf(0, filteredResolutions.size - 1)
                val sliderValue = if (maxIndex == 0) 1f else (maxIndex - resolutionIndex).toFloat()
                
                Slider(
                    value = sliderValue,
                    onValueChange = { 
                        if (maxIndex > 0) {
                            val newIndex = maxIndex - it.toInt()
                            resolutionIndex = newIndex
                            settings.photoResolutionIndex = newIndex
                            onSettingsChanged()
                        }
                    },
                    valueRange = 0f..maxOf(1f, maxIndex.toFloat()),
                    steps = maxOf(0, maxIndex - 1),
                    colors = SliderDefaults.colors(
                        thumbColor = PrimaryGreen,
                        activeTrackColor = PrimaryGreen,
                        inactiveTrackColor = SurfaceLight,
                        activeTickColor = SurfaceDark,
                        inactiveTickColor = PrimaryGreen.copy(alpha = 0.5f)
                    )
                )
            } else {
                Text("No resolutions available for this aspect ratio.", color = Color.Gray)
            }
        }
    }
}

@Composable
fun AppSettingsTab(settings: AppSettings, onSettingsChanged: () -> Unit) {
    var defaultCamera by remember { mutableStateOf(settings.defaultCamera) }
    var rememberMode by remember { mutableStateOf(settings.rememberMode) }

    Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        SettingCard("General") {
            Text("Default Camera", color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
            SegmentedControl(
                options = listOf("50" to "Left", "51" to "Right"),
                selectedOption = defaultCamera,
                onOptionSelected = {
                    defaultCamera = it
                    settings.defaultCamera = it
                    onSettingsChanged()
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(), 
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Remember Last Used Mode", color = Color.White, fontWeight = FontWeight.Bold)
                Switch(
                    checked = rememberMode,
                    onCheckedChange = {
                        rememberMode = it
                        settings.rememberMode = it
                        onSettingsChanged()
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White, 
                        checkedTrackColor = PrimaryGreen,
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = SurfaceLight,
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }
        }
    }
}

fun filterResolutionsByAspectRatio(resolutions: List<Size>, aspectRatio: String): List<Size> {
    val targetRatio = when (aspectRatio) {
        "1:1" -> 1.0f
        "5:4" -> 5.0f / 4.0f
        "4:3" -> 4.0f / 3.0f
        "16:9" -> 16.0f / 9.0f
        else -> 1.0f
    }
    
    return resolutions.filter { size ->
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        val ratio1 = w / h
        val ratio2 = h / w
        abs(ratio1 - targetRatio) < 0.1f || abs(ratio2 - targetRatio) < 0.1f
    }.sortedByDescending { it.width * it.height }
}
