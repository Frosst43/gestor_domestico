package com.frosst.gestordomestico

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room

enum class ThemeMode(val key: String, val label: String) {
    SYSTEM("system", "Automatico (tema del movil)"),
    DARK("dark", "Oscuro"),
    LIGHT("light", "Claro");

    companion object {
        fun fromValue(value: String?): ThemeMode =
            entries.firstOrNull { it.key == value } ?: SYSTEM
    }
}

class ShoppingApplication : Application() {
    val database: ShoppingDatabase by lazy {
        Room.databaseBuilder(applicationContext, ShoppingDatabase::class.java, "shopping.db").build()
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
                ShoppingApp(
                    dao = dao,
                    themeMode = themeMode,
                    session = session,
                    onThemeModeChanged = { selected ->
                        themeMode = selected
                        settings.edit().putString("theme_mode", selected.key).apply()
                    }
                )
            }
        }
    }
}
