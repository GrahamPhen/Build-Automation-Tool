# Renders the outro's graphics as transparent PNGs (anti-aliased, rounded cards) into outro-assets\ (committed; re-run after changing text or colours).
Add-Type -AssemblyName System.Drawing
$out = Join-Path $PSScriptRoot 'outro-assets'
New-Item -ItemType Directory -Force $out | Out-Null
$fc = New-Object System.Drawing.Text.PrivateFontCollection
$fc.AddFontFile('C:\Windows\Fonts\seguibl.ttf')   # Segoe UI Black
$fc.AddFontFile('C:\Windows\Fonts\segoeuib.ttf')  # Segoe UI Bold
function Fam($name) { $fc.Families | Where-Object { $_.Name -eq $name } | Select-Object -First 1 }
$black = Fam 'Segoe UI Black'; if (-not $black) { $black = $fc.Families[0] }
$bold = Fam 'Segoe UI'; if (-not $bold) { $bold = $fc.Families[-1] }

function New-Canvas($w, $h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'; $g.InterpolationMode = 'HighQualityBicubic'
    $g.Clear([System.Drawing.Color]::Transparent)
    return @($bmp, $g)
}
function RoundRect($x, $y, $w, $h, $r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($x, $y, 2 * $r, 2 * $r, 180, 90); $p.AddArc($x + $w - 2 * $r, $y, 2 * $r, 2 * $r, 270, 90)
    $p.AddArc($x + $w - 2 * $r, $y + $h - 2 * $r, 2 * $r, 2 * $r, 0, 90); $p.AddArc($x, $y + $h - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $p.CloseFigure(); return $p
}
function C($hex, $a = 255) { $c = [System.Drawing.ColorTranslator]::FromHtml($hex); [System.Drawing.Color]::FromArgb($a, $c) }

# A platform card: dark glass panel, coloured edge and label, big address, optional small line.
function Card($file, $accent, $label, $address, $small) {
    $W = 960; $H = if ($small) { 230 } else { 190 }
    $bmp, $g = New-Canvas $W $H
    # soft shadow
    $sh = RoundRect 8 12 ($W - 16) ($H - 16) 34
    $g.FillPath((New-Object System.Drawing.SolidBrush (C '#000000' 110)), $sh)
    $p = RoundRect 0 0 ($W - 16) ($H - 16) 34
    $lg = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Point 0, 0), (New-Object System.Drawing.Point 0, $H), (C '#1B2330' 235), (C '#0D1117' 235)
    $g.FillPath($lg, $p)
    $g.DrawPath((New-Object System.Drawing.Pen (C $accent 200), 4), $p)
    # accent stripe on the left
    $g.SetClip($p); $g.FillRectangle((New-Object System.Drawing.SolidBrush (C $accent)), 0, 0, 22, $H); $g.ResetClip()
    $fLabel = New-Object System.Drawing.Font $black, 34, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
    $fAddr = New-Object System.Drawing.Font $bold, 66, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $fSmall = New-Object System.Drawing.Font $bold, 36, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)
    $g.DrawString($label, $fLabel, (New-Object System.Drawing.SolidBrush (C $accent)), 56, 22)
    # shrink the address to fit
    $size = 66
    while ($g.MeasureString($address, $fAddr).Width -gt ($W - 100) -and $size -gt 40) { $size -= 2; $fAddr = New-Object System.Drawing.Font $bold, $size, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel) }
    $g.DrawString($address, $fAddr, [System.Drawing.Brushes]::White, 50, 64)
    if ($small) { $g.DrawString($small, $fSmall, (New-Object System.Drawing.SolidBrush (C '#B8C4D6')), 56, 150) }
    $bmp.Save((Join-Path $out $file), [System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $bmp.Dispose()
}
Card 'java.png' '#FFB02E' ('JAVA EDITION  ' + [char]0x2022 + '  PC') 'play.minesurvive.com' $null
Card 'bedrock.png' '#38D9F5' ('BEDROCK  ' + [char]0x2022 + '  CONSOLE & MOBILE') 'bedrock.minesurvive.com' 'Port 19132'
Card 'discord.png' '#8C8CFF' 'DISCORD' 'discord.gg/minesurvive' $null

# "JOIN THE SERVER" tag: pill with a warm gradient.
$tagText = 'CHECK OUT MY SERVER'
$f = New-Object System.Drawing.Font $black, 50, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
$probe = [System.Drawing.Graphics]::FromImage((New-Object System.Drawing.Bitmap 1, 1))
$tw = [int]([math]::Ceiling($probe.MeasureString($tagText, $f).Width) + 90)   # text plus padding
$bmp, $g = New-Canvas ($tw + 8) 110
$p = RoundRect 0 0 $tw 100 50
$lg = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Point 0, 0), (New-Object System.Drawing.Point $tw, 0), (C '#FF8A1F'), (C '#FFC93C')
$g.FillPath($lg, $p)
$sf = New-Object System.Drawing.StringFormat; $sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
$g.DrawString($tagText, $f, (New-Object System.Drawing.SolidBrush (C '#1A1206')), (New-Object System.Drawing.RectangleF 0, 2, $tw, 100), $sf)
$bmp.Save((Join-Path $out 'tag.png'), [System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $bmp.Dispose()

# Shade: darkens the lower part of the frame so the cards read well, keeps the build visible up top.
$bmp, $g = New-Canvas 1080 1920
$lg = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Point 0, 0), (New-Object System.Drawing.Point 0, 1920), (C '#000000' 40), (C '#000000' 215)
$blend = New-Object System.Drawing.Drawing2D.Blend 3
$blend.Positions = [single[]](0, 0.45, 1); $blend.Factors = [single[]](0, 0.55, 1)
$lg.Blend = $blend
$g.FillRectangle($lg, 0, 0, 1080, 1920)
$bmp.Save((Join-Path $out 'shade.png'), [System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $bmp.Dispose()
"assets in $out"
