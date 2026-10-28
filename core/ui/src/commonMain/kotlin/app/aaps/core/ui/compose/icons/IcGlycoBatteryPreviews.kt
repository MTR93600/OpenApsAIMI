package app.aaps.core.ui.compose.icons

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Preview(showBackground = true)
@Composable
internal fun IcGlycoBatteryIconPreview() {
    Icon(
        imageVector = IcGlycoBattery,
        contentDescription = null,
        modifier = Modifier
            .padding(0.dp)
            .size(48.dp),
        tint = Color(0xFF94A3B8)
    )
}

/*

<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:strokeColor="#94A3B8"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000"
        android:pathData="M5,7 L5,17 C5,17.55 5.45,18 6,18 L17,18 C17.55,18 18,17.55 18,17 L18,7 C18,6.45 17.55,6 17,6 L6,6 C5.45,6 5,6.45 5,7z" />
    <path
        android:strokeColor="#94A3B8"
        android:strokeWidth="1.8"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000"
        android:pathData="M18,10 L19.5,10 C19.78,10 20,10.22 20,10.5 L20,13.5 C20,13.78 19.78,14 19.5,14 L18,14" />
    <path
        android:fillColor="#94A3B8"
        android:pathData="M8,10 L8,14 L10,14 L10,10z" />
    <path
        android:fillColor="#94A3B8"
        android:pathData="M11.5,10 L11.5,14 L13.5,14 L13.5,10z" />
</vector>
 */
