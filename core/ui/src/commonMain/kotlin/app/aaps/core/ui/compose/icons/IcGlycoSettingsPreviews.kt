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
internal fun IcGlycoSettingsIconPreview() {
    Icon(
        imageVector = IcGlycoSettings,
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
        android:strokeWidth="2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000"
        android:pathData="M12,12 m-3,0 a3,3 0,1 1,6 0 a3,3 0,1 1,-6 0" />
    <path
        android:strokeColor="#94A3B8"
        android:strokeWidth="2"
        android:strokeLineCap="round"
        android:strokeLineJoin="round"
        android:fillColor="#00000000"
        android:pathData="M19.4,15 a1.65,1.65 0,0 0,0.33 1.82l0.06,0.06 a2,2 0,0 1,0 2.83,2 2,0 0 1,-2.83 0l-0.06,-0.06 a1.65,1.65 0,0 0,-1.82 -0.33,1.65 1.65,0 0 0,-1 1.51 V21 a2,2 0,0 1,-2 2,2 2,0 0 1,-2 -2v-0.09 A1.65,1.65 0,0 0,9 19.4a1.65,1.65 0,0 0,-1.82 0.33l-0.06,0.06 a2,2 0,0 1,-2.83 0,2 2,0 0 1,0 -2.83l0.06,-0.06 a1.65,1.65 0,0 0,0.33 -1.82,1.65 1.65,0 0 0,-1.51 -1H3 a2,2 0,0 1,-2 -2,2 2,0 0 1,2 -2h0.09 A1.65,1.65 0,0 0,4.6 9a1.65,1.65 0,0 0,-0.33 -1.82l-0.06,-0.06 a2,2 0,0 1,0 -2.83,2 2,0 0 1,2.83 0l0.06,0.06 a1.65,1.65 0,0 0,1.82 0.33H9 a1.65,1.65 0,0 0,1 -1.51V3 a2,2 0,0 1,2 -2,2 2,0 0 1,2 2v0.09 a1.65,1.65 0,0 0,1 1.51,1.65 1.65,0 0 0,1.82 -0.33l0.06,-0.06 a2,2 0,0 1,2.83 0,2 2,0 0 1,0 2.83l-0.06,0.06 a1.65,1.65 0,0 0,-0.33 1.82V9 a1.65,1.65 0,0 0,1.51 1H21 a2,2 0,0 1,2 2,2 2,0 0 1,-2 2h-0.09 a1.65,1.65 0,0 0,-1.51 1z" />
</vector>
 */
