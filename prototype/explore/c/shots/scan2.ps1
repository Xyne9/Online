Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap("D:\毕设\agent oj\prototype\explore\c\shots\page.png")
for ($x = 1439; $x -ge 1420; $x--) {
  $ys = @()
  for ($y = 0; $y -lt 900; $y++) {
    $c = $bmp.GetPixel($x, $y)
    if ($c.R -lt 235 -or $c.G -lt 235 -or $c.B -lt 235) { $ys += $y }
  }
  if ($ys.Count -gt 0) { Write-Output "x=$x count=$($ys.Count) first=$($ys[0]) last=$($ys[-1]) sample=$((($ys | Select-Object -First 8) -join ','))" }
}
$bmp.Dispose()
