package dev.timlock.jarvis.ui.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.timlock.jarvis.core.model.PerfHudData

/** §3.6 perf HUD: small monospace box. Place it top-right, below the status bar (the caller aligns it). */
@Composable
fun PerfHud(data: PerfHudData, modifier: Modifier = Modifier) {
    val px = LocalPx.current
    val style = JarvisType.MetricUnit.copy(sizePx = 18f).style(JarvisColors.TextSecondary)
    Box(
        modifier
            .background(JarvisColors.PanelFill, RoundedCornerShape(px.dp(6f)))
            .padding(horizontal = px.dp(10f), vertical = px.dp(6f)),
    ) {
        Column {
            Text("%.0f fps  p50 %.1f  p95 %.1f ms".format(data.fps, data.p50Ms, data.p95Ms), style = style)
            Text("tier ${data.tier}  prt ${data.particles}", style = style)
            Text("thermal ${data.thermal}  PSS ${data.pssMb} MB", style = style)
        }
    }
}
