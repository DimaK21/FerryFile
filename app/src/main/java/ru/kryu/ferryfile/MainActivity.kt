package ru.kryu.ferryfile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import ru.kryu.ferryfile.ui.navigation.AppNavigation
import ru.kryu.ferryfile.ui.theme.FerryFileTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FerryFileTheme {
                AppNavigation()
            }
        }
    }
}
