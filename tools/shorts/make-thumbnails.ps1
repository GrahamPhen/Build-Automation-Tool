# One thumbnail + one title per edited Short. The thumbnail is a frame of the finished build (just before the
# end of the cut), with the build's name in big outlined letters and a "MINECRAFT BUILD" pill; saved as
# "<video name>.jpeg" in the Desktop folder "Shorts Thumbnails and Titles", with titles.csv beside them.
#   make-thumbnails.ps1 [-Only build1,build2] [-Force]
param([string] $Root = 'C:\Users\Graham\Desktop\Shorts', [string] $Out = 'C:\Users\Graham\Desktop\Shorts Thumbnails and Titles',
      [string[]] $Only = @(), [switch] $Force)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$bin = Split-Path (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName
$ff = Join-Path $bin 'ffmpeg.exe'; $fp = Join-Path $bin 'ffprobe.exe'
$inv = [Globalization.CultureInfo]::InvariantCulture
$work = Join-Path $env:TEMP 'startbuild-thumbs'; New-Item -ItemType Directory -Force $work, $Out | Out-Null

# Display names: what a viewer should read (the schematic file names are not that).
$names = @{
    'whimsical_halloween_80' = 'Whimsical Halloween House'; 'haunted_80' = 'Haunted House'; 'pumpkin_castle_80' = 'Pumpkin Castle'
    'halloween_80' = 'Halloween House'; 'greenhouse_80' = 'Greenhouse'; 'storybook_cottage' = 'Storybook Cottage'
    'house_80_v2' = 'Classic House'; 'house_6_80' = 'Family House'; 'house_3_80' = 'Dream House'; 'house_10_80' = 'Big House'
    'treehouse_80' = 'Treehouse'; 'haunted+cottage_80' = 'Haunted Cottage'; 'halloween+cottage_80' = 'Halloween Cottage'
    'pumpkin_house_80' = 'Pumpkin House'; 'pumpkin+house_80' = 'Pumpkin Cottage'; 'halloween+cauldron_80' = "Witch's Cauldron"
    'halloween+witch_80' = 'Giant Witch'; 'haunted_halloween_80' = 'Haunted Mansion'; 'pikachu+detective_80' = 'Detective Pikachu'
    'charmander_80' = 'Charmander'; 'gengar_black_80' = 'Gengar'; 'ninetales' = 'Ninetales'; 'pikachu_painter_80' = 'Painter Pikachu'
    'squirtle_falling' = 'Squirtle'; 'skull+mask_80' = 'Skull Mask'; 'coffin_80' = 'Spooky Coffin'; 'pokemon_center_80' = 'Pokemon Center'
    'bat_house_80' = 'Bat House'; 'ship_magma_80' = 'Lava Pirate Ship'; 'construction_vehicle_80' = 'Construction Truck'
    'Bulbasaur_house' = 'Bulbasaur House'; 'ghost_cat_80' = 'Ghost Cat'; 'Mimikyu_House' = 'Mimikyu House'; 'vaporeon_80' = 'Vaporeon'
    'rayquaza1' = 'Rayquaza'; 'Aether_Cliff_Outpost_tier_1_' = 'Cliff Outpost'; 'Aether_Lighthouse_Vol.1' = 'Lighthouse'
    'Brackenhollow_House_TIER_1_' = 'Fantasy Cottage'; 'Classic_European_Windmill_TIER_1_' = 'Windmill'; 'Clock_Tower_Vol.1' = 'Clock Tower'
    'Enchanting_Tower_TIER_1_' = 'Enchanting Tower'; 'enchanting_tower' = 'Wizard Tower'; 'Fisherman_s_Refuge_TIER_1_' = "Fisherman's Hut"
    'Dragon_s_Gate_TIER_1_' = "Dragon's Gate"; 'house_enchanted' = 'Enchanted House'; 'Grim_Reaper' = 'Grim Reaper'
    'Farmer_House_TIER_1_' = "Farmer's House"
}
function Display($b) {
    if ($names.ContainsKey($b)) { return $names[$b] }
    $t = ($b -replace '_(TIER|tier)_\d+_?$', '' -replace '_\d+$', '' -replace '[_+]', ' ').Trim()
    return (Get-Culture).TextInfo.ToTitleCase($t)
}
function Kind($b) {
    if ($b -match '(?i)charmander|pikachu|gengar|ninetales|squirtle|vaporeon|rayquaza|pokemon|bulbasaur|mimikyu') { return 'pokemon' }
    if ($b -match '(?i)haunt|halloween|pumpkin|witch|ghost|skull|coffin|bat_|cauldron|reaper|dragon') { return 'spooky' }
    if ($b -match '(?i)ship|construction|vehicle') { return 'vehicle' }
    return 'building'
}
function Title($b, $cut) {
    $n = Display $b
    $t = switch ("$(Kind $b)-$cut") {
        'pokemon-B'  { "I Built a Giant $n in Minecraft!" }
        'pokemon-C'  { "$n Statue Timelapse in Minecraft" }
        'pokemon-A'  { "Watch a $n Come to Life in Minecraft" }
        'spooky-B'   { "Building a Spooky $n in Minecraft" }
        'spooky-C'   { "$n Halloween Build Timelapse | Minecraft" }
        'spooky-A'   { "Spooky Season: $n Minecraft Build" }
        'vehicle-B'  { "I Built a Huge $n in Minecraft!" }
        'vehicle-C'  { "$n Build Timelapse | Minecraft" }
        'building-B' { "Building a $n in Minecraft" }
        'building-C' { "This Minecraft $n Turned Out Amazing" }
        default      { "$n Minecraft Build Timelapse" }
    }
    return "$t #minecraft #shorts"
}

# Graphics helpers.
$fc = New-Object System.Drawing.Text.PrivateFontCollection
$fc.AddFontFile('C:\Windows\Fonts\seguibl.ttf')
$black = $fc.Families[0]
function RoundRect($x, $y, $w, $h, $r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($x, $y, 2 * $r, 2 * $r, 180, 90); $p.AddArc($x + $w - 2 * $r, $y, 2 * $r, 2 * $r, 270, 90)
    $p.AddArc($x + $w - 2 * $r, $y + $h - 2 * $r, 2 * $r, 2 * $r, 0, 90); $p.AddArc($x, $y + $h - 2 * $r, 2 * $r, 2 * $r, 90, 90)
    $p.CloseFigure(); return $p
}
function C($hex, $a = 255) { [System.Drawing.Color]::FromArgb($a, [System.Drawing.ColorTranslator]::FromHtml($hex)) }
$accent = @{ pokemon = '#FFD23F'; spooky = '#FF7A1A'; vehicle = '#4FC3F7'; building = '#7CDB5A' }

function Thumb($frame, $out, $label, $kind) {
    $src = [System.Drawing.Image]::FromFile($frame)
    $bmp = New-Object System.Drawing.Bitmap 1080, 1920
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.InterpolationMode = 'HighQualityBicubic'; $g.TextRenderingHint = 'AntiAlias'
    $g.DrawImage($src, 0, 0, 1080, 1920); $src.Dispose()
    # darken the top for the title, and a little at the bottom
    $top = New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Point 0, 0), (New-Object System.Drawing.Point 0, 760), (C '#000000' 200), (C '#000000' 0)
    $g.FillRectangle($top, 0, 0, 1080, 760)
    # pill
    $pill = 'MINECRAFT BUILD'
    $pf = New-Object System.Drawing.Font $black, 46, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
    $pw = [int]$g.MeasureString($pill, $pf).Width + 70
    $pp = RoundRect ((1080 - $pw) / 2) 150 $pw 88 44
    $g.FillPath((New-Object System.Drawing.SolidBrush (C $accent[$kind])), $pp)
    $sf = New-Object System.Drawing.StringFormat; $sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
    $g.DrawString($pill, $pf, (New-Object System.Drawing.SolidBrush (C '#141414')), (New-Object System.Drawing.RectangleF ((1080 - $pw) / 2), 152, $pw, 88), $sf)
    # title: big outlined letters, wrapped to the width, shrunk until it fits in 2 lines
    # one line if it fits, else two lines split at the space that balances them; never break inside a word
    $words = $label.ToUpper() -split ' '
    $options = @(, @($words -join ' '))
    for ($k = 1; $k -lt $words.Count; $k++) { $options += , @((($words[0..($k - 1)]) -join ' '), (($words[$k..($words.Count - 1)]) -join ' ')) }
    $best = $null; $bestSize = 0
    foreach ($lines in $options) {
        for ($size = 190; $size -ge 70; $size -= 6) {
            $wmax = 0
            foreach ($ln in $lines) {
                $q = New-Object System.Drawing.Drawing2D.GraphicsPath
                $q.AddString($ln, $black, 0, $size, (New-Object System.Drawing.PointF 0, 0), [System.Drawing.StringFormat]::GenericTypographic)
                $wmax = [math]::Max($wmax, $q.GetBounds().Width)
            }
            if ($wmax -le 960 -and $size * 1.05 * $lines.Count -le 460) { break }
        }
        if ($size -gt $bestSize) { $bestSize = $size; $best = $lines }
    }
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $lh = $bestSize * 1.05; $y0 = 300 + (460 - $lh * $best.Count) / 2
    for ($k = 0; $k -lt $best.Count; $k++) {
        $path.AddString($best[$k], $black, 0, $bestSize, (New-Object System.Drawing.RectangleF 0, ($y0 + $k * $lh), 1080, $lh), $sf)
    }
    $g.DrawPath((New-Object System.Drawing.Pen ([System.Drawing.Color]::Black), 22), $path)
    $g.FillPath([System.Drawing.Brushes]::White, $path)
    $enc = [System.Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
    $ep = New-Object System.Drawing.Imaging.EncoderParameters 1
    $ep.Param[0] = New-Object System.Drawing.Imaging.EncoderParameter ([System.Drawing.Imaging.Encoder]::Quality), 92L
    $bmp.Save($out, $enc, $ep); $g.Dispose(); $bmp.Dispose()
}

$rows = New-Object System.Collections.Generic.List[object]
foreach ($d in Get-ChildItem $Root -Directory | Where-Object { $_.Name -notlike '_*' }) {
    if ($Only.Count -and $Only -notcontains $d.Name) { continue }
    foreach ($v in Get-ChildItem $d.FullName -Filter *.mp4 -File | Sort-Object Name) {
        $base = [IO.Path]::GetFileNameWithoutExtension($v.Name)
        $cut = if ($base -match '-([ABC])-') { $Matches[1] } else { 'B' }
        $title = Title $d.Name $cut
        $rows.Add([pscustomobject]@{ Build = (Display $d.Name); Video = $v.Name; Title = $title })
        $jpg = Join-Path $Out "$base.jpeg"
        if ((Test-Path $jpg) -and -not $Force) { continue }
        $dur = [double]::Parse((& $fp -v error -show_entries format=duration -of csv=p=0 $v.FullName).Trim(), $inv)
        # orbit cuts end close in (their wide view is earlier); the others end on the reveal
        $sec = if ($cut -eq 'C') { $dur * 0.72 } else { [math]::Max(0, $dur - 2.5) }
        $at = $sec.ToString('0.##', $inv)
        $frame = Join-Path $work 'frame.png'
        & $ff -v error -y -ss $at -i $v.FullName -frames:v 1 -update 1 $frame
        Thumb $frame $jpg (Display $d.Name) (Kind $d.Name)
        "thumb $($d.Name)\$base.jpeg"
    }
}
$csv = Join-Path $Out 'titles.csv'
$rows | Export-Csv -NoTypeInformation -Encoding UTF8 $csv
"titles: $($rows.Count) -> $csv"
