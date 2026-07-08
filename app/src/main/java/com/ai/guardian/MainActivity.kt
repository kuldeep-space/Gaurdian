package com.ai.guardian

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.ai.guardian.ui.theme.GuardianAITheme
import com.ai.guardian.viewmodel.MainViewModel
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure foreground service is running
        val serviceIntent = android.content.Intent(this, com.ai.guardian.services.GuardianForegroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        
        setContent {
            val viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                factory = ViewModelProvider.AndroidViewModelFactory.getInstance(application)
            )
            // Collect the persisted theme mode. Changes here recompose GuardianAITheme
            // instantly across the entire composition tree — no restart needed.
            val themeMode by viewModel.themeMode.collectAsState()

            GuardianAITheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    com.ai.guardian.ui.navigation.AppNavigation(viewModel)
                }
            }
        }
    }
}
