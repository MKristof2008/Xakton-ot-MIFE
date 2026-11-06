package ru.hexteam.stenograph.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.hexteam.stenograph.ui.AppViewModel

/**
 * Хост трёх экранов по docs/design/UI_SPEC.md:
 * импорт → обработка → результат. Переходы управляются состоянием
 * [AppViewModel.UiState.screen], чтобы переживать поворот экрана.
 */
@Composable
fun AppNavHost(vm: AppViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        when (state.screen) {
            AppViewModel.Screen.IMPORT -> ImportScreen(state, vm)
            AppViewModel.Screen.PROCESSING -> ProcessingScreen(state, vm)
            AppViewModel.Screen.RESULT -> ResultScreen(state, vm)
        }
    }
}
