import os
import re

files = [
    r"app\src\main\java\com\example\sharkmentor\ui\screens\HomeScreen.kt",
    r"app\src\main\java\com\example\sharkmentor\ui\screens\PlansScreen.kt",
    r"app\src\main\java\com\example\sharkmentor\ui\screens\HistoryScreen.kt",
    r"app\src\main\java\com\example\sharkmentor\ui\screens\AnalyticsScreen.kt"
]

replacements = {
    "CreamWhite": "MaterialTheme.colorScheme.onBackground",
    "DeepNavy": "MaterialTheme.colorScheme.background",
    "SurfaceNavy": "MaterialTheme.colorScheme.surface",
    "MutedSlate": "MaterialTheme.colorScheme.onSurfaceVariant",
    "DarkNavy": "MaterialTheme.colorScheme.surfaceVariant",
}

imports_to_remove = [
    "import com.example.sharkmentor.ui.theme.CreamWhite",
    "import com.example.sharkmentor.ui.theme.DeepNavy",
    "import com.example.sharkmentor.ui.theme.SurfaceNavy",
    "import com.example.sharkmentor.ui.theme.MutedSlate",
    "import com.example.sharkmentor.ui.theme.DarkNavy"
]

for filepath in files:
    if not os.path.exists(filepath):
        continue
    with open(filepath, "r", encoding="utf-8") as f:
        content = f.read()

    # Add missing MaterialTheme import if not present
    if "import androidx.compose.material3.MaterialTheme" not in content:
        content = content.replace("import androidx.compose.material3.*", "import androidx.compose.material3.*\nimport androidx.compose.material3.MaterialTheme")

    for k, v in replacements.items():
        content = re.sub(r'\b' + k + r'\b', v, content)
        
    lines = content.split('\n')
    new_lines = []
    for line in lines:
        if line.strip() not in imports_to_remove:
            new_lines.append(line)
            
    with open(filepath, "w", encoding="utf-8") as f:
        f.write('\n'.join(new_lines))

print("Color replacements done.")
