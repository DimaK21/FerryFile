package ru.kryu.ferryfile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import dagger.hilt.android.AndroidEntryPoint
import ru.kryu.ferryfile.domain.usecase.ObserveDarkThemeUseCase
import ru.kryu.ferryfile.ui.navigation.AppNavigation
import ru.kryu.ferryfile.ui.theme.FerryFileTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var observeDarkTheme: ObserveDarkThemeUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val darkTheme by observeDarkTheme().collectAsStateWithLifecycle()
            FerryFileTheme(darkTheme = darkTheme, dynamicColor = false) {
                AppNavigation()
            }
        }
    }
}
