package com.keyspaceapps.goodtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.keyspaceapps.goodtv.AppGraph
import com.keyspaceapps.goodtv.appGraph

/** `viewModel()` with the app graph injected. Scoped to the nearest ViewModelStoreOwner (a nav entry). */
@Composable
inline fun <reified VM : ViewModel> goodTvViewModel(key: String? = null, crossinline create: (AppGraph) -> VM): VM {
    val graph = LocalContext.current.appGraph()
    return viewModel(key = key, factory = viewModelFactory { initializer { create(graph) } })
}
