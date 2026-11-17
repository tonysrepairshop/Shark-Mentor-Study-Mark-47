import re

with open('app/src/main/java/com/example/sharkmentor/ui/screens/ProfileScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Remove the unconditional appointment card and active meetings list.
start_idx = content.find('            // === NEW UNCONDITIONAL APPOINTMENT & COACH CARD ===')
end_idx = content.find('            item {\\n            CloudBackupSection(')
if start_idx != -1 and end_idx != -1:
    content = content[:start_idx] + content[end_idx:]

# 2. Remove the activeMeetings parameter and variable
content = content.replace('    val activeMeetings by viewModel.upcomingMeetings.collectAsStateWithLifecycle(initialValue = emptyList())\\n', '')

# 3. Remove showBookingSheet variable
content = content.replace('    var showBookingSheet by remember { mutableStateOf(false) }\\n', '')

# 4. Remove the MeetingBookingBottomSheet block
bottom_sheet_start = content.find('    if (showBookingSheet) {')
bottom_sheet_end = content.find('    }\\n}', bottom_sheet_start)
if bottom_sheet_start != -1 and bottom_sheet_end != -1:
    content = content[:bottom_sheet_start] + content[bottom_sheet_end + 6:]

with open('app/src/main/java/com/example/sharkmentor/ui/screens/ProfileScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)

