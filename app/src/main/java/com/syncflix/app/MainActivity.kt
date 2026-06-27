package com.syncflix.app

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.syncflix.app.ui.navigation.SyncFlixNavHost
import com.syncflix.app.ui.theme.SyncFlixTheme
import java.util.Locale

class MainActivity : ComponentActivity() {

    // SyncFlix est francophone : on force le français quelle que soit la langue du téléphone.
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(Locale.FRENCH)
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // « dark-first » : SyncFlixTheme suit le système, mais l'app est pensée sombre.
            SyncFlixTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncFlixNavHost()
                }
            }
        }
    }
}
