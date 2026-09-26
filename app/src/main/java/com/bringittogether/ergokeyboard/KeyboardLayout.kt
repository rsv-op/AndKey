package com.bringittogether.ergokeyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun KeyboardLayout() {
    // Landscape split layout mapping
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.5f) // Occupies lower physical bounds
            .background(Color.Transparent),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left Cluster Envelope (24% Width)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.25f)
                .fillMaxHeight()
                .background(Color(0x2EFFFFFF)) // Frosted diffuse base
        ) {
            Text(
                text = "Left Cluster", 
                color = Color.White, 
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Center Void (Passive Corridor - 100% Transparent)
        Box(
            modifier = Modifier
                .fillMaxWidth(0.66f) // Relative to remaining space
                .fillMaxHeight()
                .background(Color.Transparent)
        )

        // Right Cluster Envelope (24% Width)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(Color(0x2EFFFFFF))
        ) {
            Text(
                text = "Right Cluster", 
                color = Color.White, 
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
