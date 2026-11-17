import re

with open('app/src/main/java/com/example/sharkmentor/ui/screens/InstitutionInfoScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Add imports
imports_to_add = '''
import androidx.compose.material.icons.filled.CalendarToday
import java.util.Date
import com.example.sharkmentor.ui.screens.coach.CoachAppointmentBookingSheet
import com.example.sharkmentor.ui.screens.profile.ProfileViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
'''
if 'import java.util.Date' not in content:
    content = content.replace('import kotlinx.coroutines.launch', 'import kotlinx.coroutines.launch' + imports_to_add)

# 2. Add states for coachId, booking sheet and booked dates
states_to_add = '''
    var activeCoachId by remember { mutableStateOf("") }
    var coachOffDay by remember { mutableStateOf<String?>(null) }
    var showAppointmentSheet by remember { mutableStateOf(false) }
    var activeBookedDatesList by remember { mutableStateOf<List<Date>>(emptyList()) }
    val profileViewModel: ProfileViewModel = viewModel()

    LaunchedEffect(activeCoachId) {
        if (activeCoachId.isNotBlank()) {
            val db = GoogleServicesHelper.getFirebaseFirestore()
            db?.collection("meetings")
                ?.whereEqualTo("coachId", activeCoachId)
                ?.addSnapshotListener { snap, _ ->
                    val dates = snap?.documents?.mapNotNull { it.getTimestamp("appointmentTimestamp")?.toDate() } ?: emptyList()
                    activeBookedDatesList = dates
                }
        }
    }
'''
if 'var activeCoachId' not in content:
    content = content.replace('var hasCoach by remember { mutableStateOf(false) }', 'var hasCoach by remember { mutableStateOf(false) }' + states_to_add)

# 3. Save activeCoachId in fetchStudentData
content = content.replace('''val coachId = studentDoc.getString("coachId")
                    if (!coachId.isNullOrBlank()) {''', '''val coachId = studentDoc.getString("coachId")
                    if (!coachId.isNullOrBlank()) {
                        activeCoachId = coachId''')

content = content.replace('''coachName = coachDoc.getString("coachName") ?: "Bilinmeyen Koç"''', '''coachName = coachDoc.getString("coachName") ?: "Bilinmeyen Koç"
                                coachOffDay = coachDoc.getString("offDay")''')

# 4. Save activeCoachId in redeemInstitutionCode
content = content.replace('''val cId = coachDoc.id
                    val instName = coachDoc.getString("institutionName") ?: "Kurum"''', '''val cId = coachDoc.id
                    activeCoachId = cId
                    val instName = coachDoc.getString("institutionName") ?: "Kurum"''')

# 5. Insert button in UI
button_to_insert = '''
                        Button(
                            onClick = { showAppointmentSheet = true },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(50.dp).padding(top = 8.dp)
                        ) {
                            Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Koçla Randevu Al (45 Dk Seans)")
                        }
'''
content = content.replace('Spacer(modifier = Modifier.height(16.dp))', 'Spacer(modifier = Modifier.height(16.dp))' + button_to_insert)

# 6. Insert Bottom Sheet at the end
sheet_to_insert = '''
    if (showAppointmentSheet) {
        CoachAppointmentBookingSheet(
            studentName = studentNameInput.ifBlank { "Öğrenci" },
            coachName = coachName.ifBlank { "Rehber Koç" },
            coachOffDayName = coachOffDay,
            bookedTimestamps = activeBookedDatesList,
            onDismiss = { showAppointmentSheet = false },
            onConfirmBooking = { appointmentDate, reason ->
                profileViewModel.bookMeeting(
                    coachId = activeCoachId,
                    institutionId = null,
                    studentName = studentNameInput.ifBlank { "Öğrenci" },
                    appointmentDate = appointmentDate,
                    requestNote = reason
                )
                showAppointmentSheet = false
            }
        )
    }
}
'''
content = content.replace('    }\n}\n', '    }\n' + sheet_to_insert)

with open('app/src/main/java/com/example/sharkmentor/ui/screens/InstitutionInfoScreen.kt', 'w', encoding='utf-8') as f:
    f.write(content)
