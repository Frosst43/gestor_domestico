package com.frosst.gestordomestico

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.frosst.gestordomestico.exchange.ExchangeRoomSchema

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Automatico (tema del movil)"),
    DARK("dark", "Oscuro"),
    LIGHT("light", "Claro");
    companion object {
        fun fromValue(value: String?): ThemeMode = entries.firstOrNull { it.key == value } ?: SYSTEM
    }
}
class ShoppingApplication : Application() {
    val database: ShoppingDatabase by lazy {
        Room.databaseBuilder(applicationContext, ShoppingDatabase::class.java, "shopping.db")
            .addMigrations(SyncJournalSchema.MIGRATION_1_2, ExchangeRoomSchema.MIGRATION_2_3)
            .addCallback(SyncJournalSchema.onCreate)
            .addCallback(ExchangeRoomSchema.onCreate)
            .build()
    }
}
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = getSharedPreferences("gestor_domestico_settings", MODE_PRIVATE)
        var themeMode by mutableStateOf(ThemeMode.fromValue(settings.getString("theme_mode", null)))
        val dao = (application as ShoppingApplication).database.shoppingDao()
        val session = ViewModelProvider(this)[ShoppingSession::class.java]
        setContent {
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    ShoppingApp(dao = dao, themeMode = themeMode, session = session,
                        onThemeModeChanged = { selected ->
                            themeMode = selected
                            settings.edit().putString("theme_mode", selected.key).apply()
                        })
                    ElevatedButton(
                        onClick = { startActivity(Intent(this@MainActivity, LinkActivity::class.java)) },
                        modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(start = 16.dp, bottom = 16.dp)
                    ) { Text("Dispositivos") }
                }
            }
        }
    }
}
