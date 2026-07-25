package com.framex.app.ui.screens.thermal.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framex.app.ui.components.WovenNetBackground
import com.framex.app.ui.screens.thermal.iconColorForLabel
import com.framex.app.ui.screens.thermal.iconForLabel
import com.framex.app.ui.screens.thermal.plateBgForLabel
import com.framex.app.ui.screens.thermal.plateBorderForLabel
import java.util.Locale

@Composable
fun ReadingCard(
    label: String,
    value: String,
    delta30s: Float = 0f,
    peakVal: Float = 0f,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            WovenNetBackground(modifier = Modifier.matchParentSize())

            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(plateBgForLabel(label))
                                .border(1.dp, plateBorderForLabel(label), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(iconForLabel(label), contentDescription = null, tint = iconColorForLabel(label), modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(label, color = Color.Gray, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }

                    if (delta30s != 0f) {
                        val (arrow, deltaColor) = when {
                            delta30s > 0.3f -> "↑" to Color(0xFFEF4444)
                            delta30s < -0.3f -> "↓" to Color(0xFF34D399)
                            else -> "→" to Color.Gray
                        }
                        Text(
                            text = "$arrow ${String.format(Locale.US, "%+.1f°", delta30s)}",
                            color = deltaColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    value,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (peakVal > 0f) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Peak: ${String.format(Locale.US, "%.1f°C", peakVal)}",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
fun SensorDetailRow(label: String, source: String, active: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (active) Color(0xFF34D399) else Color.Gray)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(source, color = if (active) Color.LightGray else Color.Gray, fontSize = 11.sp)
        }
    }
}
