package com.glassremote.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glassremote.ui.theme.GlassBackground
import com.glassremote.ui.theme.GlassBorder

/**
 * Module 4: Dedicated Jio Set-Top Box Remote Card.
 * Rendered on the Dashboard with HDMI 2 routing, Channel Up/Down, Guide/EPG, and Numeric Keypad.
 */
@Composable
fun JioStbRemoteCard(
    onSelectHdmi2: () -> Unit,
    onChannelUp: () -> Unit,
    onChannelDown: () -> Unit,
    onOpenGuide: () -> Unit,
    onDigitPress: (Int) -> Unit,
    onPowerToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNumPad by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, GlassBorder, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = GlassBackground),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header: Title & HDMI 2 Active Chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(Color(0xFF0056D2), Color(0xFF0084FF))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = "Jio STB",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "Jio Set-Top Box",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Connected via HDMI 2 (CEC Passthrough)",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                    }
                }

                // Switch to HDMI 2 button
                Button(
                    onClick = onSelectHdmi2,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0056D2).copy(alpha = 0.3f)),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "HDMI 2",
                        color = Color(0xFF64B5F6),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }

            // Quick STB Navigation Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StbActionButton(
                    icon = Icons.Default.KeyboardArrowUp,
                    label = "CH +",
                    onClick = onChannelUp
                )
                StbActionButton(
                    icon = Icons.Default.KeyboardArrowDown,
                    label = "CH -",
                    onClick = onChannelDown
                )
                StbActionButton(
                    icon = Icons.Default.Menu,
                    label = "Guide / EPG",
                    onClick = onOpenGuide
                )
                StbActionButton(
                    icon = Icons.Default.Dialpad,
                    label = if (showNumPad) "Hide 123" else "123 Pad",
                    onClick = { showNumPad = !showNumPad },
                    isActive = showNumPad
                )
            }

            // Expandable Numeric Channel Pad
            if (showNumPad) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterVertically
                ) {
                    val rows = listOf(
                        listOf(1, 2, 3),
                        listOf(4, 5, 6),
                        listOf(7, 8, 9),
                        listOf(-1, 0, -2) // -1 is spacer/info, -2 is enter
                    )

                    for (row in rows) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            for (item in row) {
                                when (item) {
                                    in 0..9 -> {
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.08f))
                                                .clickable { onDigitPress(item) },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = item.toString(),
                                                color = Color.White,
                                                fontSize = 18.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                    -1 -> {
                                        Box(modifier = Modifier.size(52.dp))
                                    }
                                    -2 -> {
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF0056D2).copy(alpha = 0.4f))
                                                .clickable { onSelectHdmi2() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Tune",
                                                tint = Color.White,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StbActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    isActive: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (isActive) Color(0xFF0056D2).copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (isActive) Color.White else Color(0xFFCFD8DC),
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = label,
            fontSize = 10.sp,
            color = Color.Gray,
            fontWeight = FontWeight.Medium
        )
    }
}
