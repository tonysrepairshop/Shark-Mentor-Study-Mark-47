
$content = Get-Content -Path "C:\Users\hp\AndroidStudioProjects\SharkMentor\app\src\main\java\com\example\sharkmentor\ui\screens\HomeScreen.kt" -Raw
$shimmerFunction = @"
@Composable
fun rememberShimmerBrush(): androidx.compose.ui.graphics.Brush {
    val transition = rememberInfiniteTransition(label = "Shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerTranslate"
    )
    return androidx.compose.ui.graphics.Brush.linearGradient(
        colors = listOf(
            Color(0xFF1E293B),
            Color(0xFF334155),
            Color(0xFF1E293B)
        ),
        start = Offset.Zero,
        end = Offset(x = translateAnim, y = translateAnim)
    )
}
"@

$content = $content.Replace("import kotlin.math.roundToInt`n", "import kotlin.math.roundToInt`nimport androidx.compose.animation.Crossfade`nimport androidx.compose.animation.core.LinearEasing`n")
$content = $content + "`n" + $shimmerFunction
Set-Content -Path "C:\Users\hp\AndroidStudioProjects\SharkMentor\app\src\main\java\com\example\sharkmentor\ui\screens\HomeScreen.kt" -Value $content

