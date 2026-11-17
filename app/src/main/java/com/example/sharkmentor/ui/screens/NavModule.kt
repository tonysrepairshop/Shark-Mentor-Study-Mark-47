package com.example.sharkmentor.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.StickyNote2
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.ui.graphics.vector.ImageVector

enum class NavModule(val title: String, val iconVector: ImageVector) {
    PLANS("Planlar", Icons.Default.CalendarMonth),
    GOALS_ROUTE("Hedefler & Rota", Icons.Default.TrackChanges),
    TOPIC_MASTERY("Konu Hakimiyeti", Icons.Default.Psychology),
    LIBRARY("Kütüphane", Icons.AutoMirrored.Filled.MenuBook)
}
