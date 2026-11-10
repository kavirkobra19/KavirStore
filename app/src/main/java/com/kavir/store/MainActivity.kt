package com.kavir.store

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.kavir.store.data.LocaleHelper
import com.kavir.store.install.InstallManager
import com.kavir.store.ui.AdminViewModel
import com.kavir.store.ui.KavirTheme
import com.kavir.store.ui.StoreApp
import com.kavir.store.ui.StoreViewModel

class MainActivity : ComponentActivity() {
    private val store: StoreViewModel by viewModels()
    private val admin: AdminViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        InstallManager.init(applicationContext)
        setContent {
            KavirTheme(store.theme) {
                StoreApp(store, admin, onLanguageChanged = { recreate() })
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The user may have installed or removed apps while we were in the background.
        store.refreshInstalled()
    }
}
