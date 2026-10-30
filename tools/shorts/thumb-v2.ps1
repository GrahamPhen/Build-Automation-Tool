# Thumbnail redesign (v2 pilot): a real frame of the finished build, cropped in so the build fills the cover (less
# empty sky), a gentle grade (contrast, saturation, a soft vignette to lift the build off the background), at most
# a few words placed clear of the build, and a small MineSurvive logo instead of the big "MINECRAFT BUILD" pill.
# Writes the 1080x1920 cover plus crop tests (square, 3:4, 4:5) and a phone-grid preview for review.
#   thumb-v2.ps1 -Video <mp4> -At <seconds | -seconds from end> -Out <cover.jpeg> [-Zoom 1.3] [-FocusX 0.5] [-FocusY 0.55]
#                [-Text "OCEAN LINER"] [-TextY 0.12] [-Logo] [-ReviewDir <dir>]
param([Parameter(Mandatory)][string] $Video, [Parameter(Mandatory)][double] $At, [Parameter(Mandatory)][string] $Out,
      [double] $Zoom = 1.0, [double] $FocusX = 0.5, [double] $FocusY = 0.5, [string] $Text = '', [double] $TextY = 0.1,
      [double] $TextSize = 120, [switch] $Logo, [string] $ReviewDir = '', [double] $Shrink = 1.0)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$bin = Split-Path (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName
$ff = Join-Path $bin 'ffmpeg.exe'; $fp = Join-Path $bin 'ffprobe.exe'
$inv = [Globalization.CultureInfo]::InvariantCulture
function N($x) { ([double]$x).ToString('0.###', $inv) }
$work = Join-Path $env:TEMP ('thumbv2-' + [guid]::NewGuid().ToString('N').Substring(0, 8)); New-Item -ItemType Directory $work | Out-Null

if ($At -lt 0) { $At = [double]::Parse((& $fp -v error -show_entries format=duration -of csv=p=0 $Video).Trim(), $inv) + $At }
# crop window (in source pixels) of 1080/zoom x 1920/zoom around the focus point, kept inside the frame
$cw = 1080 / $Zoom; $ch = 1920 / $Zoom
$cx = [math]::Min([math]::Max(0, 1080 * $FocusX - $cw / 2), 1080 - $cw); $cy = [math]::Min([math]::Max(0, 1920 * $FocusY - $ch / 2), 1920 - $ch)
$graded = Join-Path $work 'graded.png'
$vf = "crop={0}:{1}:{2}:{3},scale=1080:1920:flags=lanczos,eq=contrast=1.07:saturation=1.15:gamma=0.98,unsharp=5:5:0.6" -f (N $cw), (N $ch), (N $cx), (N $cy)
if ($Shrink -lt 1) {
    # a subject that fills the whole height loses its top in square / 3:4 previews: shrink the frame, sit it on the
    # bottom edge and fill the strip above with a soft blur of the same frame (sky), so the top moves into the middle
    $sw = [int](1080 * $Shrink / 2) * 2; $sh = [int](1920 * $Shrink / 2) * 2
    # (the frame's edges are feathered into the blur - a hard edge read as a picture pasted on a picture)
    $vf += ",split[bg][fg];[bg]boxblur=40:2,eq=brightness=-0.04[bgb];[fg]scale=$($sw):$($sh):flags=lanczos,format=rgba[fgs];" +
           "color=c=black:s=$($sw)x$($sh),format=gray,drawbox=x=70:y=90:w=$($sw - 140):h=$($sh):color=white:t=fill,boxblur=45:2[mask];" +
           "[fgs][mask]alphamerge[fga];[bgb][fga]overlay=x=(W-w)/2:y=H-h"
}
$vf += ",vignette=angle=PI/5:mode=forward"
& $ff -v error -y -ss (N $At) -i $Video -frames:v 1 -filter_complex "[0:v]$vf" -update 1 $graded
if ($LASTEXITCODE -ne 0) { throw "frame grab failed" }

$bmp = [System.Drawing.Bitmap]::FromFile($graded)
if ($Text -and $TextY -lt 0) {
    # auto: the name goes in whichever band (top or bottom) has the least detail - sky, not the build
    $small = New-Object System.Drawing.Bitmap $bmp, 108, 192
    function Busy($y0, $y1) { $s = 0.0; $n = 0; for ($y = $y0; $y -lt $y1; $y++) { for ($x = 0; $x -lt 107; $x++) { $a = $small.GetPixel($x, $y); $b = $small.GetPixel($x + 1, $y); $s += [math]::Abs($a.GetBrightness() - $b.GetBrightness()); $n++ } }; $s / $n }
    $TextY = if ((Busy 8 30) -le (Busy 160 182) * 1.3) { 0.085 } else { 0.88 }   # (top preferred: the feed's title sits on the bottom)
    $small.Dispose()
}
$canvas = New-Object System.Drawing.Bitmap 1080, 1920
$g = [System.Drawing.Graphics]::FromImage($canvas); $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'
$g.DrawImage($bmp, 0, 0, 1080, 1920); $bmp.Dispose()
if ($Text) {
    $fc = New-Object System.Drawing.Text.PrivateFontCollection; $fc.AddFontFile('C:\Windows\Fonts\seguibl.ttf'); $black = $fc.Families[0]
    $sf = New-Object System.Drawing.StringFormat; $sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
    $size = $TextSize
    do { $q = New-Object System.Drawing.Drawing2D.GraphicsPath; $q.AddString($Text.ToUpper(), $black, 0, $size, (New-Object System.Drawing.PointF 0, 0), [System.Drawing.StringFormat]::GenericTypographic); $w = $q.GetBounds().Width; $size -= 4 } while ($w -gt 900 -and $size -gt 50)
    $size += 4
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddString($Text.ToUpper(), $black, 0, $size, (New-Object System.Drawing.RectangleF 0, (1920 * $TextY - $size * 0.7), 1080, ($size * 1.4)), $sf)
    # soft shadow, then a dark outline and white fill (reads on sky and on blocks alike)
    $shadow = $path.Clone(); $m = New-Object System.Drawing.Drawing2D.Matrix; $m.Translate(0, 8); $shadow.Transform($m)
    $g.FillPath((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(110, 0, 0, 0))), $shadow)
    $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(235, 15, 15, 20)), ([math]::Round($size * 0.16)); $pen.LineJoin = 'Round'
    $g.DrawPath($pen, $path); $g.FillPath([System.Drawing.Brushes]::White, $path)
}
if ($Logo) {
    $lg = [System.Drawing.Image]::FromFile((Join-Path $PSScriptRoot 'outro-assets\minesurvive-logo.png'))
    $lw = 190; $lh = [int]($lg.Height * $lw / $lg.Width)
    $ia = New-Object System.Drawing.Imaging.ImageAttributes
    $cm = New-Object System.Drawing.Imaging.ColorMatrix; $cm.Matrix33 = 0.85; $ia.SetColorMatrix($cm)
    $g.DrawImage($lg, (New-Object System.Drawing.Rectangle 44, (1920 - $lh - 330), $lw, $lh), 0, 0, $lg.Width, $lg.Height, 'Pixel', $ia); $lg.Dispose()
}
$enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
$ep = New-Object System.Drawing.Imaging.EncoderParameters 1; $ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), 94L
$canvas.Save($Out, $enc, $ep); $g.Dispose(); $canvas.Dispose()

if ($ReviewDir) {
    New-Item -ItemType Directory -Force $ReviewDir | Out-Null
    $base = [IO.Path]::GetFileNameWithoutExtension($Out)
    # centred crop tests as the platforms show them, and the cover at phone-grid size (~ a third of a phone width)
    & $ff -v error -y -i $Out -filter_complex ("[0]split=4[a][b][c][d];[a]crop=1080:1080:0:420,scale=300:300[sq];[b]crop=1080:1440:0:240,scale=300:400[t34];" +
        "[c]crop=1080:1350:0:285,scale=300:375[t45];[d]scale=124:220[grid];[sq]pad=300:400:0:50:white[sqp];[t45]pad=300:400:0:12:white[t45p];" +
        "[grid]pad=300:400:88:90:white[gridp];[sqp][t34][t45p][gridp]hstack=4") -frames:v 1 -update 1 (Join-Path $ReviewDir "$base-croptests.png")
}
Remove-Item -Recurse -Force $work
"cover: $Out"
