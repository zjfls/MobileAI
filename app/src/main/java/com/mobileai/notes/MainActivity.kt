package com.mobileai.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.mobileai.notes.ui.theme.MobileAINotesTheme
import com.mobileai.notes.ui.MobileAINotesApp
import com.mobileai.notes.oppo.OppoPenKit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OppoPenKit.tryInit(this)
        setContent {
            MobileAINotesTheme {
                AppRoot {
                    MobileAINotesApp()
                }
            }
        }
    }
}

@Composable
private fun AppRoot(content: @Composable () -> Unit) {
    Surface(color = Color(0xFFF7F7F8)) {
        content()
    }
}
