package com.frosst.gestordomestico

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.room.Room
import androidx.compose.material3.MaterialTheme

class ShoppingApplication : Application() {
    val database: ShoppingDatabase by lazy {
        Room.databaseBuilder(applicationContext, ShoppingDatabase::class.java, "shopping.db").build()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val dao = (application as ShoppingApplication).database.shoppingDao()
        setContent {
            MaterialTheme {
                ShoppingApp(dao)
            }
        }
    }
}
