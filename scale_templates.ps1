Add-Type -AssemblyName System.Drawing
$names = @("yanxun","kaishi_xunlian","donggubi","sutong","jiahao","duigou","wancheng","zhuye")
foreach ($n in $names) {
    $srcPath = "E:\MWA\shots_login\$n.png"
    $dstPath = "E:\MWA\whmx\image\$n.png"
    $src = [System.Drawing.Bitmap]::FromFile($srcPath)
    $w = [int]($src.Width * 2 / 3)
    $h = [int]($src.Height * 2 / 3)
    $dst = New-Object System.Drawing.Bitmap($w, $h)
    $g = [System.Drawing.Graphics]::FromImage($dst)
    $g.InterpolationMode = 'Bilinear'
    $g.DrawImage($src, 0, 0, $w, $h)
    $g.Dispose()
    $dst.Save($dstPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $dst.Dispose()
    $src.Dispose()
    Write-Host ("$n -> " + $w + "x" + $h)
}
