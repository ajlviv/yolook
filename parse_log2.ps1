$ErrorActionPreference = "Stop"
$path = "logcat.log"
$text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::Unicode)
$lines = $text -split "`r`n"
Write-Host "Total lines: $($lines.Count)"
Write-Host "=== FATAL EXCEPTION / crash stacklines ==="
$lines | Where-Object { $_ -match "FATAL EXCEPTION|com\.yolo|\.kt:|Caused by" } | Select-Object -First 50 | ForEach-Object { Write-Host $_ }
Write-Host "=== lines mentioning yolo tag (ViewMode, applyViewMode, TrafficLight, DriverScene, DriverHud, DriverObject) ==="
$lines | Where-Object { $_ -match "yolo\.detector" } | Select-Object -First 60 | ForEach-Object { Write-Host $_ }
Write-Host "=== last 25 lines ==="
$lines | Select-Object -Last 25 | ForEach-Object { Write-Host $_ }
