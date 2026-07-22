package com.example.counterpro

import android.content.Context
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

// ==========================================
// 1. DATA LAYER (Room Database)
// ==========================================

@Entity(tableName = "tap_history")
data class TapLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface CounterDao {
    @Insert
    suspend fun insertTap(tap: TapLog)

    @Query("DELETE FROM tap_history WHERE id = (SELECT MAX(id) FROM tap_history)")
    suspend fun deleteLastTap()

    @Query("SELECT COUNT(*) FROM tap_history")
    fun getTotalCount(): Flow<Int>

    @Query("DELETE FROM tap_history")
    suspend fun clearAll()
}

@Database(entities = [TapLog::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun counterDao(): CounterDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "counter_pro_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ==========================================
// 2. SETTINGS LAYER (DataStore)
// ==========================================

private val Context.dataStore by preferencesDataStore(name = "user_settings")

enum class AppTheme { OLED_BLACK, OCEAN_BLUE, EMERALD_GREEN }

class SettingsRepository(private val context: Context) {
    private object Keys {
        val VIBRATION = booleanPreferencesKey("vibration")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val THEME = stringPreferencesKey("app_theme")
    }

    val vibrationEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.VIBRATION] ?: true }
    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { it[Keys.KEEP_SCREEN_ON] ?: false }
    val selectedTheme: Flow<AppTheme> = context.dataStore.data.map { 
        AppTheme.valueOf(it[Keys.THEME] ?: AppTheme.OLED_BLACK.name) 
    }

    suspend fun setVibration(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VIBRATION] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { it[Keys.THEME] = theme.name }
    }
}

// ==========================================
// 3. VIEWMODEL & STATE
// ==========================================

class CounterViewModel(
    private val dao: CounterDao,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val count: StateFlow<Int> = dao.getTotalCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val vibrationEnabled = settingsRepository.vibrationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val keepScreenOn = settingsRepository.keepScreenOn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val selectedTheme = settingsRepository.selectedTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.OLED_BLACK)

    fun increment() {
        viewModelScope.launch { dao.insertTap(TapLog()) }
    }

    fun undo() {
        viewModelScope.launch { dao.deleteLastTap() }
    }

    fun reset() {
        viewModelScope.launch { dao.clearAll() }
    }

    fun toggleVibration(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setVibration(enabled) }
    }

    fun toggleKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepScreenOn(enabled) }
    }

    fun updateTheme(theme: AppTheme) {
        viewModelScope.launch { settingsRepository.setTheme(theme) }
    }
}

class CounterViewModelFactory(
    private val dao: CounterDao,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CounterViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return CounterViewModel(dao, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ==========================================
// 4. THEME & COLOR SCHEMES
// ==========================================

private val OLEDBlackScheme = darkColorScheme(
    primary = Color(0xFFBB86FC),
    background = Color(0xFF000000),
    surface = Color(0xFF121212),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

private val OceanBlueScheme = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    background = Color(0xFF0D1B2A),
    surface = Color(0xFF1B263B),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

private val EmeraldGreenScheme = darkColorScheme(
    primary = Color(0xFF81C784),
    background = Color(0xFF0A1C14),
    surface = Color(0xFF122C20),
    onPrimary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun CounterProTheme(
    appTheme: AppTheme,
    content: @Composable () -> Unit
) {
    val colorScheme = when (appTheme) {
        AppTheme.OLED_BLACK -> OLEDBlackScheme
        AppTheme.OCEAN_BLUE -> OceanBlueScheme
        AppTheme.EMERALD_GREEN -> EmeraldGreenScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}

// ==========================================
// 5. MAIN ACTIVITY (Hardware Button Interception)
// ==========================================

class MainActivity : ComponentActivity() {

    private val viewModel: CounterViewModel by viewModels {
        val db = AppDatabase.getDatabase(applicationContext)
        val repo = SettingsRepository(applicationContext)
        CounterViewModelFactory(db.counterDao(), repo)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val theme by viewModel.selectedTheme.collectAsState()
            val keepScreenOn by viewModel.keepScreenOn.collectAsState()

            // Keep Screen Awake toggle handling
            SideEffect {
                if (keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            CounterProTheme(appTheme = theme) {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }

    // Intercept Volume Up key to increment counter
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            viewModel.increment()
            vibratePhone()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun vibratePhone() {
        if (!viewModel.vibrationEnabled.value) return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            @Suppress("DEPRECATION")
            vibrator.vibrate(40)
        }
    }
}

// ==========================================
// 6. UI SCREENS & NAVIGATION
// ==========================================

@Composable
fun MainAppScreen(viewModel: CounterViewModel) {
    var currentScreen by remember { mutableStateOf("counter") }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (currentScreen == "counter") {
            CounterScreen(
                viewModel = viewModel,
                onNavigateToSettings = { currentScreen = "settings" }
            )
        } else {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { currentScreen = "counter" }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CounterScreen(
    viewModel: CounterViewModel,
    onNavigateToSettings: () -> Unit
) {
    val count by viewModel.count.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Counter Pro") },
                actions = {
                    IconButton(onClick = { viewModel.undo() }) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { showResetDialog = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround
        ) {
            // Digital Counter Display
            AnimatedContent(
                targetState = count,
                transitionSpec = { fadeIn() + slideInVertically() togetherWith fadeOut() + slideOutVertically() },
                label = "CountAnimation"
            ) { targetCount ->
                Text(
                    text = "$targetCount",
                    fontSize = 96.sp,
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Circular TAP Button
            Surface(
                modifier = Modifier
                    .size(260.dp)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { viewModel.increment() },
                            onLongPress = { viewModel.increment() }
                        )
                    },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 12.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "TAP",
                        fontSize = 36.sp,
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text("Reset Counter?") },
                text = { Text("This will clear your active counter back to 0. Are you sure?") },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.reset()
                        showResetDialog = false
                    }) {
                        Text("Reset")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: CounterViewModel,
    onBack: () -> Unit
) {
    val vibrationEnabled by viewModel.vibrationEnabled.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val selectedTheme by viewModel.selectedTheme.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Back", color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Preferences", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Vibration Feedback")
                Switch(
                    checked = vibrationEnabled,
                    onCheckedChange = { viewModel.toggleVibration(it) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Keep Screen Awake")
                Switch(
                    checked = keepScreenOn,
                    onCheckedChange = { viewModel.toggleKeepScreenOn(it) }
                )
            }

            HorizontalDivider()

            Text("Theme Selection", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

            AppTheme.values().forEach { theme ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = (selectedTheme == theme),
                        onClick = { viewModel.updateTheme(theme) }
                    )
                    Text(
                        text = theme.name.replace("_", " "),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
