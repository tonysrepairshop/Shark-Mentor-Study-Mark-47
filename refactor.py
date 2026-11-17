import re

with open('app/src/main/java/com/example/sharkmentor/ui/screens/ProfileScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Add new imports
if 'import androidx.compose.foundation.lazy.LazyColumn' not in content:
    content = content.replace('import androidx.compose.foundation.layout.*', 'import androidx.compose.foundation.layout.*\nimport androidx.compose.foundation.lazy.LazyColumn\nimport androidx.compose.foundation.lazy.items\nimport androidx.compose.foundation.layout.PaddingValues\nimport androidx.compose.material.icons.filled.AddCircleOutline')

# Replace Column with LazyColumn
content = content.replace('''        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {''', '''        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {''')

# Wrap User Info Card in item
content = content.replace('''            // User Info Card
            Card(''', '''            // User Info Card
            item {
            Card(''')

content = content.replace('''                    }
                }
            }

            // ── Kurum & Koç Bağlantısı Form Section ──────────────────────────────────''', '''                    }
                }
            }
            } // end item

            // ── Kurum & Koç Bağlantısı Form Section ──────────────────────────────────''')

# Wrap Kurum & Koç blocks in item {}
content = content.replace('''                if (!hasCoach) {
                    Card(''', '''                if (!hasCoach) {
                    item {
                    Card(''')

content = content.replace('''                            }
                        }
                    }
                } else {
                    Card(''', '''                            }
                        }
                    }
                    } // end item
                } else {
                    item {
                    Card(''')

# Remove old Booking Button from Kurum Card
old_btn_block = '''                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))

                            Button(
                                onClick = { showBookingSheet = true },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Koçla Görüşme Talebi Başlat (45 Dk)",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }'''

content = content.replace(old_btn_block, '')

# Close the item for the active link card
content = content.replace('''                        }
                    }
                }
            }
            
            if (activeMeetings.isNotEmpty()) {''', '''                        }
                    }
                    } // end item
                }
            }
            
            // REMOVE OLD ACTIVE MEETINGS
            if (False) {''')

# Find the end of active meetings block and insert the new UNCONDITIONAL Booking Card + List
old_meetings_end = '''                        }
                    }
                }
            }

            CloudBackupSection('''

new_cards = '''                        }
                    }
                }
            }
            // === NEW UNCONDITIONAL APPOINTMENT & COACH CARD ===
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Koç Görüşme & Randevu Masası",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Haftalık 45 dakikalık birebir görüşme randevusu",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

                        Button(
                            onClick = { showBookingSheet = true },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Görüşme Talebi Başlat (45 Dk)",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            // === UPCOMING MEETINGS LIST ===
            item {
                Text(
                    text = "PLANLANAN GÖRÜŞMELERİM",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    ),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (activeMeetings.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(modifier = Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Henüz planlanmış randevunuz yok. Yukarıdaki butondan dilediğiniz saate talep oluşturabilirsiniz.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(activeMeetings, key = { it.id }) { meeting ->
                    val dateText = remember(meeting.appointmentTimestamp) {
                        java.text.SimpleDateFormat("d MMMM yyyy, EEEE - HH:mm", java.util.Locale("tr"))
                            .format(meeting.appointmentTimestamp.toDate())
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.EventAvailable, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = dateText,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "45 Dk • " + if (meeting.status == "COMPLETED") "Tamamlandı" else "Otomatik Onaylandı",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (meeting.status == "COMPLETED") Color(0xFF10B981) else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            item {
                CloudBackupSection('''

content = content.replace(old_meetings_end, new_cards)

# Wrap CloudBackup in item
content = content.replace('''            item {
                CloudBackupSection(
                onBackupClick = { /* TODO */ },
                onRestoreClick = { /* TODO */ },
                isSyncing = false,
                lastBackupDate = null
            )''', '''            item {
                CloudBackupSection(
                    onBackupClick = { /* TODO */ },
                    onRestoreClick = { /* TODO */ },
                    isSyncing = false,
                    lastBackupDate = null
                )
            }''')


# Wrap Spacer and Login/Logout in item
content = content.replace('''            Spacer(modifier = Modifier.weight(1f))

            if (isGuest) {''', '''            item { Spacer(modifier = Modifier.weight(1f)) }

            item {
            if (isGuest) {''')

content = content.replace('''                }
            }
        }
    }

    if (showUnlinkConfirm) {''', '''                }
            }
            } // end item
        }
    }

    if (showUnlinkConfirm) {''')

with open('app/src/main/java/com/example/sharkmentor/ui/screens/ProfileScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)
