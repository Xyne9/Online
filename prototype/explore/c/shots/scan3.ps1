Add-Type -AssemblyName System.Drawing
$bmp = New-Object System.Drawing.Bitmap("D:\毕设\agent oj\prototype\explore\c\shots\page.png")
foreach ($y in 400, 100, 700) {
  $line = ""
  for ($x = 1416; $x -lt 1440; $x++) {
    $c = $bmp.GetPixel($x, $y)
    $line += ("{0}:{1},{2},{3}  " -f $x, $c.R, $c.G, $c.B)
  }
  Write-Output "y=$y  $line"
}
$bmp.Dispose()
