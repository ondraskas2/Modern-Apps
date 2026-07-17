package com.vayunmathur.weather.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.IconClearNight
import com.vayunmathur.library.ui.IconCloudy
import com.vayunmathur.library.ui.IconDrizzle
import com.vayunmathur.library.ui.IconFog
import com.vayunmathur.library.ui.IconPartlyCloudyDay
import com.vayunmathur.library.ui.IconPartlyCloudyNight
import com.vayunmathur.library.ui.IconRain
import com.vayunmathur.library.ui.IconSnow
import com.vayunmathur.library.ui.IconSunny
import com.vayunmathur.library.ui.IconThunder
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.weather.util.WeatherCondition

/**
 * Reusable weather-icon renderer. Same role as WeatherMaster's
 * `WeatherIconBox` — caller provides the icon composable, we render it at the
 * requested size in `onSurface` color (overridable).
 */
@Composable
fun WeatherIconBox(
    icon: @Composable (Modifier, Color) -> Unit,
    size: Dp = 24.dp,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    icon(Modifier.size(size), tint)
}

/** Composable icon for this condition. `isDay` swaps clear / partly-cloudy night variants. */
fun WeatherCondition.iconContent(isDay: Boolean): @Composable (Modifier, Color) -> Unit = when (this) {
    WeatherCondition.Clear ->
        { m, t -> if (isDay) IconSunny(m, t) else IconClearNight(m, t) }
    WeatherCondition.PartlyCloudy ->
        { m, t -> if (isDay) IconPartlyCloudyDay(m, t) else IconPartlyCloudyNight(m, t) }
    WeatherCondition.Cloudy -> { m, t -> IconCloudy(m, t) }
    WeatherCondition.Fog -> { m, t -> IconFog(m, t) }
    WeatherCondition.Drizzle -> { m, t -> IconDrizzle(m, t) }
    WeatherCondition.Rain -> { m, t -> IconRain(m, t) }
    WeatherCondition.Snow -> { m, t -> IconSnow(m, t) }
    WeatherCondition.Thunderstorm -> { m, t -> IconThunder(m, t) }
    WeatherCondition.Unknown -> { m, t -> IconCloudy(m, t) }
}
