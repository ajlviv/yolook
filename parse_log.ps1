$ErrorActionPreference = "Stop"
$path = "logcat.log"
# logcat.log is UTF-16LE. Read it decoded, split into lines.
$text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::Unicode)
$lines = $text -split "`r`n"
Write-Host "Total lines: $($lines.Count)"
Write-Host "=== Crash/Exception lines ==="
$lines | Where-Object { $_ -match "FATAL|Exception|RuntimeException|Error|crash|Abort|device error" } | Select-Object -First 40 | ForEach-Object { Write-Host ($_ -replace '^\S+\s+', '') }
Write-Host "=== Driver/ViewMode/CameraDevice lines ==="
$lines | Where-Object { $_ -match "ViewMode|driverMode|DRIVER|CameraDevice-JV|ImageAnalysis|Analyzer|surface|Surface" } | Select-Object -First 40 | ForEach-Object { Write-Host ($_ -replace '^\S+\s+', '') }
