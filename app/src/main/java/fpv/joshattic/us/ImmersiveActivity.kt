package fpv.joshattic.us

import android.os.Bundle
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.compose.composePanel
import com.meta.spatial.core.Entity
import fpv.joshattic.us.ui.theme.FPVTheme

class ImmersiveActivity : AppSystemActivity() {

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(
            PanelRegistration(R.id.panel_main)
                .config {
                    width = 1.28f
                    height = 0.72f
                    layoutWidthInPx = 1280
                    layoutHeightInPx = 720
                }
                .composePanel(::setupComposeView)
        )
    }

    private fun setupComposeView(composeView: ComposeView) {
        composeView.setContent {
            FPVTheme {
               HorizonCameraPanel()
            }
        }
    }
}
