import os

files = ['BrowserTopBar.kt', 'SettingsBottomSheet.kt', 'TabsManagerContent.kt', 'HomeDashboard.kt']
for f in files:
    path = f'app/src/main/java/com/example/ui/{f}'
    if not os.path.exists(path): continue
    with open(path, 'r') as file:
        content = file.read()
    
    idx = content.find('import androidx.activity.compose.BackHandler')
    if idx != -1:
        content = content[:idx].strip() + '\n'
        with open(path, 'w') as file:
            file.write(content)
