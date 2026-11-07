package cc.ptoe.easytier.compose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import cc.ptoe.easytier.compose.core.EasyTierRuntimeCoordinator
import cc.ptoe.easytier.compose.data.GlobalSettingsRepository
import cc.ptoe.easytier.compose.data.ProfileRepository
import cc.ptoe.easytier.compose.ui.EasyTierApp
import cc.ptoe.easytier.compose.ui.EasyTierViewModel
import cc.ptoe.easytier.compose.ui.theme.EasyTierTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: EasyTierViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(this, Factory(this))[EasyTierViewModel::class.java]
        setContent {
            EasyTierTheme {
                EasyTierApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A new connection is explicit. Sticky recovery belongs to the ordinary Service.
    }

    private class Factory(
        private val activity: MainActivity,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EasyTierViewModel(
            ProfileRepository(activity.applicationContext),
            // UI, boot receiver and the foreground Service share one runtime owner.
            EasyTierRuntimeCoordinator.getInstance(activity.applicationContext),
            GlobalSettingsRepository(activity.applicationContext),
        ) as T
    }
}
