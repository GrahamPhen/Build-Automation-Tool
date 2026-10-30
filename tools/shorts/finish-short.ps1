# Finishes a rendered Short for posting (research: reports\Minecraft build Shorts engagement.md, items 1-4):
#  1. flash-forward hook - it OPENS on the finished build: the video's own final frame, held 1.2 s with a slow
#     push-in, then a hard cut to the empty site;
#  2. one line of hook text over that opening ("Rate this castle 1-10", or the build's premise from hooks.json);
#  3. the server plug as a compact banner over the last 2.2 s of the reveal, instead of a separate end card;
#  4. a seamless loop - the first frame IS the last frame, so a replay runs straight on.
# Also writes "<build> - description.txt" (title, server details for the description / pinned comment).
#   finish-short.ps1 -In <rendered.mp4> -Out <final.mp4> -Build <name> [-Display "Castle"] [-Title "..."]
param([Parameter(Mandatory)][string] $In, [Parameter(Mandatory)][string] $Out, [Parameter(Mandatory)][string] $Build,
      [string] $Display = '', [string] $Title = '')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$bin = Split-Path (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName
$ff = Join-Path $bin 'ffmpeg.exe'; $fp = Join-Path $bin 'ffprobe.exe'
$inv = [Globalization.CultureInfo]::InvariantCulture
$work = Join-Path $env:TEMP ('finish-' + [guid]::NewGuid().ToString('N').Substring(0, 8)); New-Item -ItemType Directory $work | Out-Null
$assets = Join-Path $PSScriptRoot 'outro-assets'

if (-not $Display) { $Display = (Get-Culture).TextInfo.ToTitleCase((($Build -replace '_(TIER|tier)_\d+_?$', '' -replace '_\d+$', '' -replace '[_+]', ' ').Trim())) }
$hooks = @{}
$hf = Join-Path $PSScriptRoot 'hooks.json'
if (Test-Path $hf) { (Get-Content $hf -Raw | ConvertFrom-Json).PSObject.Properties | ForEach-Object { $hooks[$_.Name] = $_.Value } }
# Default hooks rotate per build (the same "Rate this X 1-10" on every video would go stale); hooks.json overrides.
. (Join-Path $PSScriptRoot 'shorts-meta.ps1')
$kind = Get-ShortKind $Build
# The opening shows the FINISHED build with a line that sets up the snap back to block 1 (owner's formula, Oct
# 2026), with search words in it (YouTube reads the text in the first frames). True claims only: every take
# starts on a wild hillside and the character places every block by hand.
$blk = Get-ShortBlocks $Build
$byHand = if ($blk) { "$blk blocks, all placed by hand" } else { "Every block placed by hand" }
$pool = @{
    building = if (Test-ShortWinter $Build) { @("From a snowy hill to THIS cozy $Display", $byHand, "Minecraft $($Display): watch it go up from block 1") }
               else { @("From an empty hill to THIS Minecraft $Display", $byHand, "Minecraft $($Display): watch it go up from block 1") }
    pokemon  = @("From an empty hill to a GIANT $Display", $byHand, "Giant Minecraft $($Display), from block 1")
    spooky   = @("From an empty hill to THIS haunted $Display", $byHand, "Minecraft $($Display): watch it rise from block 1")
    vehicle  = @("From an empty hill to a HUGE $Display", $byHand, "Minecraft $($Display), from block 1")
    statue   = @("From an empty hill to a GIANT $Display", $byHand, "Giant Minecraft $($Display), from block 1")
}
$seed = 0; foreach ($ch in $Build.ToCharArray()) { $seed = ($seed * 31 + [int]$ch) % 1000003 }
$hookText = if ($hooks.ContainsKey($Build)) { $hooks[$Build] } else { $pool[$kind][$seed % $pool[$kind].Count] -creplace '\ba ([AEIOU])', 'an $1' }

# --- graphics: hook text and server banner (System.Drawing, outlined Segoe UI Black like the thumbnails)
$fc = New-Object System.Drawing.Text.PrivateFontCollection
$fc.AddFontFile('C:\Windows\Fonts\seguibl.ttf'); $fc.AddFontFile('C:\Windows\Fonts\segoeuib.ttf')
$black = $fc.Families | Where-Object { $_.Name -eq 'Segoe UI Black' } | Select-Object -First 1; if (-not $black) { $black = $fc.Families[0] }
$bold = $fc.Families | Where-Object { $_.Name -eq 'Segoe UI' } | Select-Object -First 1; if (-not $bold) { $bold = $fc.Families[-1] }
function C($hex, $a = 255) { [System.Drawing.Color]::FromArgb($a, [System.Drawing.ColorTranslator]::FromHtml($hex)) }
function Canvas($w, $h) { $b = New-Object System.Drawing.Bitmap $w, $h; $g = [System.Drawing.Graphics]::FromImage($b); $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAlias'; $g.Clear([System.Drawing.Color]::Transparent); return @($b, $g) }
function RoundRect($x, $y, $w, $h, $r) { $p = New-Object System.Drawing.Drawing2D.GraphicsPath; $p.AddArc($x, $y, 2 * $r, 2 * $r, 180, 90); $p.AddArc($x + $w - 2 * $r, $y, 2 * $r, 2 * $r, 270, 90); $p.AddArc($x + $w - 2 * $r, $y + $h - 2 * $r, 2 * $r, 2 * $r, 0, 90); $p.AddArc($x, $y + $h - 2 * $r, 2 * $r, 2 * $r, 90, 90); $p.CloseFigure(); return $p }

# hook: up to 3 balanced lines, as big as fits 880 px wide - clear of the like/comment buttons down the right
# side (and of a TV's Shorts panel), with a thicker outline so it reads from across a room
$bmp, $g = Canvas 1080 520
$sf = New-Object System.Drawing.StringFormat; $sf.Alignment = 'Center'; $sf.LineAlignment = 'Center'
# never wrap or clip a line: 'GRAND OCEAN LINER?' just overflowed, wrapped onto a hidden row and vanished
$sf.FormatFlags = [System.Drawing.StringFormatFlags]::NoWrap -bor [System.Drawing.StringFormatFlags]::NoClip
$words = $hookText.ToUpper() -split '\s+'
$best = $null; $bestSize = 0
# every 1-line and 2-line split (and 3-line ones only for long hooks); the split giving the biggest text wins
$options = @(, @($words -join ' '))
for ($a = 1; $a -lt $words.Count; $a++) { $options += , @(($words[0..($a - 1)] -join ' '), ($words[$a..($words.Count - 1)] -join ' ')) }
if ($words.Count -ge 7) {
    for ($a = 1; $a -lt $words.Count - 1; $a++) { for ($b = $a + 1; $b -lt $words.Count; $b++) {
        $options += , @(($words[0..($a - 1)] -join ' '), ($words[$a..($b - 1)] -join ' '), ($words[$b..($words.Count - 1)] -join ' ')) } }
}
foreach ($lines in $options) {
    for ($size = 170; $size -ge 50; $size -= 6) {
        $wmax = 0
        foreach ($ln in $lines) { $q = New-Object System.Drawing.Drawing2D.GraphicsPath; $q.AddString($ln, $black, 0, $size, (New-Object System.Drawing.PointF 0, 0), [System.Drawing.StringFormat]::GenericTypographic); $wmax = [math]::Max($wmax, $q.GetBounds().Width) }
        if ($wmax -le 880 -and $size * 1.08 * $lines.Count -le 500) { break }
    }
    if ($size -gt $bestSize) { $bestSize = $size; $best = $lines }
}
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$lh = $bestSize * 1.08; $y0 = (520 - $lh * $best.Count) / 2
for ($k = 0; $k -lt $best.Count; $k++) { $path.AddString($best[$k], $black, 0, $bestSize, (New-Object System.Drawing.RectangleF 0, ($y0 + $k * $lh), 1080, $lh), $sf) }
$pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::Black), 30; $pen.LineJoin = 'Round'
$g.DrawPath($pen, $path)
$g.FillPath([System.Drawing.Brushes]::White, $path)
$hookPng = Join-Path $work 'hook.png'; $bmp.Save($hookPng, [System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $bmp.Dispose()

# server banner: logo + address on one dark glass pill
$bmp, $g = Canvas 1000 230
$pill = RoundRect 0 0 992 220 46
$g.FillPath((New-Object System.Drawing.Drawing2D.LinearGradientBrush (New-Object System.Drawing.Point 0, 0), (New-Object System.Drawing.Point 0, 220), (C '#1B2330' 235), (C '#0D1117' 235)), $pill)
$g.DrawPath((New-Object System.Drawing.Pen (C '#FFB02E' 230), 5), $pill)
$logo = [System.Drawing.Image]::FromFile((Join-Path $assets 'minesurvive-logo.png'))
$lw = 300; $lh2 = [int]($logo.Height * $lw / $logo.Width); $g.DrawImage($logo, 22, [int]((220 - $lh2) / 2), $lw, $lh2); $logo.Dispose()
$g.DrawString('play.minesurvive.com', (New-Object System.Drawing.Font $bold, 56, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)), [System.Drawing.Brushes]::White, 335, 38)
$g.DrawString(('Java & Bedrock  ' + [char]0x2022 + '  discord.gg/minesurvive'), (New-Object System.Drawing.Font $bold, 28, ([System.Drawing.FontStyle]::Bold), ([System.Drawing.GraphicsUnit]::Pixel)), (New-Object System.Drawing.SolidBrush (C '#FFC93C')), 340, 126)
$bannerPng = Join-Path $work 'banner.png'; $bmp.Save($bannerPng, [System.Drawing.Imaging.ImageFormat]::Png); $g.Dispose(); $bmp.Dispose()

# --- video
$dur = [double]::Parse((& $fp -v error -show_entries format=duration -of csv=p=0 $In).Trim(), $inv)
$rate = (& $fp -v error -select_streams v:0 -show_entries stream=r_frame_rate -of csv=p=0 $In).Trim()
$H = 1.2
$last = Join-Path $work 'last.png'
& $ff -v error -y -sseof -0.05 -i $In -frames:v 1 -update 1 $last
function N($x) { ([double]$x).ToString('0.###', $inv) }
$total = $dur + $H
$bannerFrom = $total - 2.2

# --- sound effects (Minecraft's own sounds, from the launcher's asset store): a level-up chime on the very first
# frame, quiet block-placing under the music while it builds (pure music let viewers zone out), and a sweep on
# each camera cut. Where it builds / cuts comes from the renderer's "<video>.timing.txt".
$timing = @{}
$tf = [IO.Path]::ChangeExtension($In, '.timing.txt')
if (Test-Path -LiteralPath $tf) { foreach ($l in Get-Content -LiteralPath $tf) { if ($l -match '^(\w+)=(.+)$') { $timing[$Matches[1]] = @($Matches[2] -split ',' | ForEach-Object { [double]::Parse($_, $inv) }) } } }
if (-not $timing.build) { $timing.build = @(($dur * 0.14), ($dur - 8)) }                 # (older renders: the orbit's shape)
$assetsDir = "$env:APPDATA\PrismLauncher\assets"
$index = Get-ChildItem "$assetsDir\indexes" -Filter *.json | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$objects = (Get-Content $index.FullName -Raw | ConvertFrom-Json).objects
function Sfx($name) {
    $o = $objects."minecraft/sounds/$name.ogg"; if (-not $o) { return $null }
    $src = Join-Path "$assetsDir\objects" ($o.hash.Substring(0, 2) + '\' + $o.hash)
    $dst = Join-Path $work (($name -replace '/', '_') + '.ogg'); Copy-Item -LiteralPath $src $dst; return $dst
}
# a 2.4 s loop of hand-placed blocks (wood, stone, grass at uneven gaps and loudness), looped under the build
$hits = @(@('dig/wood1', 0, 1.0), @('dig/stone2', 260, 0.8), @('dig/wood3', 470, 0.9), @('dig/grass1', 790, 0.7), @('dig/stone4', 1010, 0.9),
          @('dig/wood2', 1330, 1.0), @('dig/stone1', 1560, 0.75), @('dig/wood4', 1900, 0.9))
$hitIn = @(); $hitF = @(); $k = 0
foreach ($hit in $hits) { $p = Sfx $hit[0]; if ($p) { $hitIn += '-i', $p; $hitF += "[$k]aresample=48000,aformat=channel_layouts=stereo,adelay=delays=$($hit[1]):all=1,volume=$($hit[2])[h$k]"; $k++ } }   # ($h would be $H, the hook length)
$blocksWav = $null
if ($k -gt 2) {
    $blocksWav = Join-Path $work 'blocks.wav'
    $mixIn = (0..($k - 1) | ForEach-Object { "[h$_]" }) -join ''
    & $ff -v error -y @hitIn -filter_complex (($hitF -join ';') + ";$($mixIn)amix=inputs=$($k):normalize=0,apad=whole_dur=2.4,atrim=0:2.4") $blocksWav
    if ($LASTEXITCODE -ne 0) { $blocksWav = $null }
}
$chime = Sfx 'random/levelup'
$sweep = Sfx 'entity/player/attack/sweep1'
$sfxIn = @(); $sfxF = @(); $mixLabels = @('[mus]'); $n = 4
if ($blocksWav) {
    $b0 = $timing.build[0] + $H; $b1 = $timing.build[1] + $H; $len = [math]::Max(1, $b1 - $b0)
    $sfxIn += '-stream_loop', '-1', '-i', $blocksWav
    $sfxF += "[$($n):a]atrim=duration=$(N $len),afade=t=in:d=0.6,afade=t=out:st=$(N ($len - 0.8)):d=0.8,volume=0.4,adelay=delays=$([int]($b0 * 1000)):all=1[blk]"
    $mixLabels += '[blk]'; $n++
}
if ($chime) { $sfxIn += '-i', $chime; $sfxF += "[$($n):a]aresample=48000,aformat=channel_layouts=stereo,volume=0.5[chm]"; $mixLabels += '[chm]'; $n++ }
if ($sweep -and $timing.cuts) {
    $sfxIn += '-i', $sweep
    $c = $timing.cuts.Count
    $sfxF += "[$($n):a]aresample=48000,aformat=channel_layouts=stereo,volume=0.35,asplit=$c" + ((0..($c - 1) | ForEach-Object { "[sw$_]" }) -join '')
    for ($i = 0; $i -lt $c; $i++) { $sfxF += "[sw$i]adelay=delays=$([int](([math]::Max(0, $timing.cuts[$i] + $H - 0.12)) * 1000)):all=1[swd$i]"; $mixLabels += "[swd$i]" }
    $n++
}
$g2 = @(
    # 1+4: the final frame held, slow push-in that ENDS at zoom 1 is not wanted - it STARTS at 1 so frame 0 == last frame
    "[1:v]scale=2160:3840,zoompan=z='1+0.035*on/$([math]::Ceiling($H * 60))':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=1080x1920:fps=$rate,trim=duration=$(N $H),setsar=1,format=yuv420p[hookv]",
    "[0:v]fps=$rate,setsar=1,format=yuv420p[mainv]",
    "[hookv][mainv]concat=n=2:v=1:a=0[cat]",
    # 2: hook text over the opening, fading out
    "[2:v]format=rgba,fade=t=out:st=2.3:d=0.4:alpha=1[hk]",
    # (above the build but below the top ~12%, and off the right edge: those sit under YouTube's buttons)
    "[cat][hk]overlay=x=-30:y=260:enable='lt(t,2.7)'[v1]",
    # 3: the server banner over the end of the reveal, smaller and left of the right-hand buttons
    "[3:v]format=rgba,scale=880:-1,fade=t=in:st=$(N $bannerFrom):d=0.35:alpha=1[bn]",
    "[v1][bn]overlay=x=40:y=1180:enable='gte(t,$(N $bannerFrom))',format=yuv420p[v]",
    # audio: ONE continuous song - it starts as the opening cuts to the site, with the level-up chime alone on the
    # flash-forward (a 1.2 s clip from the song's end used to play first, so the song ended and started again)
    "[0:a]adelay=delays=$([int]($H * 1000)):all=1,aresample=48000,aformat=channel_layouts=stereo[mus]"
) + $sfxF + @("$($mixLabels -join '')amix=inputs=$($mixLabels.Count):normalize=0:duration=first,alimiter=limit=0.95[a]") -join ';'
& $ff -v error -y -i $In -loop 1 -framerate $rate -t (N $H) -i $last -loop 1 -framerate $rate -t (N $total) -i $hookPng -loop 1 -framerate $rate -t (N $total) -i $bannerPng @sfxIn `
    -filter_complex $g2 -map '[v]' -map '[a]' -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p -c:a aac -b:a 192k -movflags +faststart $Out
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed on $In" }

$desc = Get-ShortDescription $Build $Display $Title
Set-Content -Path ([IO.Path]::Combine((Split-Path $Out), "$Build - description.txt")) -Value $desc -Encoding utf8
"finished: $Out ($(N $total) s)"
