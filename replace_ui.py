import re

path = 'app/src/main/java/com/example/ui/BrowserMainScreen.kt'
with open(path, 'r') as f:
    text = f.read()

old_block = """// Intercept Confirmation Dialog for Downloads (Anti-Malware & Ghost Blocker)
                pendingDownload?.let { req ->
"""
new_block = """// Intercept Confirmation Dialog for Downloads
                pendingDownloadPayload?.let { req ->
                    DownloadApprovalDialog(
                        payload = req,
                        onConfirm = { ctx ->
                            performDownloadAction {
                                viewModel.confirmDownload(ctx, req)
                            }
                        },
                        onCancel = { viewModel.cancelPendingDownload() }
                    )
                }
"""

start_idx = text.find("// Intercept Confirmation Dialog for Downloads")
if start_idx != -1:
    end_idx = text.find("AnimatedVisibility(", start_idx)
    # the end is just before the AnimatedVisibility comment
    end_idx = text.rfind("// Temporary Focus notifications (snackbar)", start_idx, end_idx)
    
    if end_idx != -1:
        text = text[:start_idx] + new_block + "\n                " + text[end_idx:]

with open(path, 'w') as f:
    f.write(text)
