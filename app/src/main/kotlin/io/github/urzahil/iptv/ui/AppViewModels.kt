package io.github.urzahil.iptv.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.urzahil.iptv.App
import io.github.urzahil.iptv.di.AppContainer

val Context.appContainer: AppContainer get() = (applicationContext as App).container

/** ViewModel creation without a DI framework. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = LocalContext.current.appContainer
    return viewModel(factory = viewModelFactory { initializer { create(container) } })
}
