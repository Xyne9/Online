Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap("D:\毕设\agent oj\prototype\explore\c\shots\page.png")
$cols = @{ "left" = @(0, 500); "mid" = @(508, 1076); "right" = @(1080, 1440) }
foreach ($k in $cols.Keys) {
  $x0 = $cols[$k][0]; $x1 = $cols[$k][1]
  $last = -1; $lastx = -1
  for ($y = 899; $y -ge 44; $y--) {
    $hit = $false
    for ($x = $x0; $x -lt $x1; $x += 1) {
      $c = $bmp.GetPixel($x, $y)
      if ($c.R -lt 235 -or $c.G -lt 235 -or $c.B -lt 235) { $hit = $true; $lastx = $x; break }
    }
    if ($hit) { $last = $y; break }
  }
  Write-Output "$k last ink y=$last (x=$lastx)  gap-to-900=$(900-$last)"
}
# 检查最右墨迹
$maxx = -1
for ($x = 1439; $x -ge 1080; $x--) {
  for ($y = 0; $y -lt 900; $y++) {
    $c = $bmp.GetPixel($x, $y)
    if ($c.R -lt 235 -or $c.G -lt 235 -or $c.B -lt 235) { $maxx = $x; break }
  }
  if ($maxx -ge 0) { break }
}
Write-Output "rightmost ink x=$maxx gap-to-1440=$(1439-$maxx)"
$bmp.Dispose()
