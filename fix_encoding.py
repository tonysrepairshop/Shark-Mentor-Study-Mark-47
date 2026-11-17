import os
def fix_encoding_and_labels():
    files = [
        'app/src/main/java/com/example/sharkmentor/ui/screens/plan/PlanScreen.kt',
        'app/src/main/java/com/example/sharkmentor/ui/screens/plan/PlanViewModel.kt',
        'app/src/main/java/com/example/sharkmentor/ui/aichat/AiChatHubScreen.kt',
        'app/src/main/java/com/example/sharkmentor/ui/aichat/AiChatHubViewModel.kt'
    ]

    replacements = {
        'Ã§': 'ç', 'Ã‡': 'Ç',
        'Ã¶': 'ö', 'Ã–': 'Ö',
        'Ã¼': 'ü', 'Ãœ': 'Ü',
        'ÅŸ': 'ş', 'Åž': 'Ş',
        'Ä±': 'ı', 'Ä°': 'İ',
        'ÄŸ': 'ğ', 'Äž': 'Ğ',
        # specific fixes from task 1
        '"Pzt", "Sal", "Crs", "Per", "Cum", "Cmt", "Paz"': '"Pzt", "Sal", "Çar", "Per", "Cum", "Cmt", "Paz"',
        '"Yeni Gorev Ekle"': '"Yeni Görev Ekle"',
        'placeholder = { Text("Turev") }': 'placeholder = { Text("Türev / Paragraf") }',
        '"Gun"': '"Gün"',
        'placeholder = { Text("09:00") }': 'placeholder = { Text("09:00 - 10:30") }',
        '"Hedef Soru Sayisi (min 30)"': '"Hedef Soru Sayısı (Min. 30)"',
        'Text("Ekle")': 'Text("Görevi Ekle")',
        'Text("Iptal"': 'Text("İptal"',
        '"Koc Plani"': '"Koç Çalışma Programı"',
        '"Tarama: ${sdf.format(Date(now))} Plani"': '"Tarama: ${sdf.format(Date(now))} Planı"',
        '"Tarama basarisiz oldu."': '"Tarama başarısız oldu. Lütfen tekrar deneyin."',
        '"Gorev"': '"Görev"',
        # specific fixes from task 2
        'Akilli Piantayıcı': 'Akıllı Planlayıcı',
        'Akilli Piantayici': 'Akıllı Planlayıcı',
        'Akilli PiantayÄ±cÄ±': 'Akıllı Planlayıcı',
        'Shark Al Koç': 'Shark AI Koç',
        'Shark Al KoÃ§': 'Shark AI Koç',
        'Shark Al Koc': 'Shark AI Koç',
        '✔ 7 GÃ¼nlÃ¼k Plan Takvime Eklendi': '✔ 7 Günlük Plan Takvime Eklendi',
        'Planlar Sekmesine Git': 'Planlar Sekmesine Git'
    }

    for filepath in files:
        full_path = os.path.join('C:/Users/hp/AndroidStudioProjects/SharkMentor', filepath)
        if not os.path.exists(full_path): continue
        with open(full_path, 'r', encoding='utf-8') as f:
            content = f.read()

        for k, v in replacements.items():
            content = content.replace(k, v)

        with open(full_path, 'w', encoding='utf-8') as f:
            f.write(content)

fix_encoding_and_labels()
