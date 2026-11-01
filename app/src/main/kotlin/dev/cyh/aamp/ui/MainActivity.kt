package dev.cyh.aamp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * The phone screen, written in Compose. Nothing here runs on the Android Auto path: the playback
 * service never loads this activity or any Compose class.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AampTheme { AampApp() } }
    }
}
