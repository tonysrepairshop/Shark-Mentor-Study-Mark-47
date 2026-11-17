package com.example.sharkmentor.data.model

fun generateDynamicSlots(startHour: Int = 9, endHour: Int = 18): List<TimeSlotDefinition> {
    val slots = mutableListOf<TimeSlotDefinition>()
    var currentHour = startHour
    var currentMinute = 0

    while (true) {
        // 12:00 Öğle Molası Kuralı
        if (currentHour == 12 && currentMinute < 30) {
            currentHour = 12
            currentMinute = 30
        }

        // 45 dk seans hesapla
        var sessionEndMinute = currentMinute + 45
        var sessionEndHour = currentHour

        if (sessionEndMinute >= 60) {
            sessionEndHour += sessionEndMinute / 60
            sessionEndMinute %= 60
        }

        // Mesai sınırını aşıyor mu kontrol et
        if (sessionEndHour > endHour || (sessionEndHour == endHour && sessionEndMinute > 0)) {
            break
        }

        slots.add(TimeSlotDefinition(currentHour, currentMinute, sessionEndHour, sessionEndMinute))

        // 15 Dk koç dinlenme molası ekle
        var nextMinute = sessionEndMinute + 15
        var nextHour = sessionEndHour
        if (nextMinute >= 60) {
            nextHour += nextMinute / 60
            nextMinute %= 60
        }

        currentHour = nextHour
        currentMinute = nextMinute
    }

    return slots
}
