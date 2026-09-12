$ErrorActionPreference = "Stop"
$path = "logcat.log"
$text = [System.IO.File]::ReadAllText($path, [System.Text.Encoding]::Unicode)
$lines = $text -split "`r`n"
Write-Host "=== am_crash lines (full) ==="
$lines | Where-Object { $_ -match "am_crash|JavaExceptionHandler|Can't |recycled|hardware|getPixels|IllegalState" } | Select-Object -First 40 | ForEach-Object { Write-Host $_ }
Write-Host "=== ViewMode applyViewMode lines ==="
$lines | Where-Object { $_ -match "ViewMode|applyViewMode|driverMode|collectDetect|buildDriver" } | Select-Object -First 30 | ForEach-Object { Write-Host $_ }
