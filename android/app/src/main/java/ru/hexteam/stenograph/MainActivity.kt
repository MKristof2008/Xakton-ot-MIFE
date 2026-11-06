package ru.hexteam.stenograph

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import ru.hexteam.stenograph.ui.screens.AppNavHost
import ru.hexteam.stenograph.ui.theme.StenographTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StenographTheme {
                // Трёхэкранный интерфейс по docs/design/UI_SPEC.md.
                // Первый одноэкранный макет (ui/MainScreen.kt) сохранён для сравнения.
                AppNavHost()
            }
        }
    }
}
