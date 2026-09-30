package dev.legendsayantan.backstage.ui

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import dev.legendsayantan.backstage.core.BackStageEngine
import dev.legendsayantan.backstage.core.platform.PlatformContext

class MainActivity : FragmentActivity() {
    private lateinit var engine: BackStageEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val platformContext = PlatformContext(
            androidContext = applicationContext,
            activityProvider = { this },
        )
        engine = BackStageEngine(platformContext)

        setContent {
            BackStageApp(engine = engine)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::engine.isInitialized) {
            engine.close()
        }
    }
}
