package com.arloor.tuke

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arloor.tuke.ui.TukeApp
import com.arloor.tuke.ui.TukeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as TukeApplication).appContainer
        setContent {
            TukeTheme {
                TukeApp(container = container)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        (application as TukeApplication).appContainer.engineController.ensureStarted()
    }
}
