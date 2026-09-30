# Animated MineSurvive outro appended to a vertical Short. The last frame keeps playing as a slow push-in;
# the logo drops in with a bounce, a "JOIN THE SERVER" tag pops up, and the Java / Bedrock / Discord cards
# slide in from alternating sides. The music fades out under it. Graphics come from outro-assets.ps1.
param([Parameter(Mandatory)][string]$In, [Parameter(Mandatory)][string]$Out, [double]$Len = 5.0)
$ErrorActionPreference = 'Stop'
$bin = Split-Path (Get-ChildItem "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Recurse -Filter ffmpeg.exe | Select-Object -First 1).FullName
$ff = Join-Path $bin 'ffmpeg.exe'; $fp = Join-Path $bin 'ffprobe.exe'
$assets = Join-Path $PSScriptRoot 'outro-assets'
$logo = Join-Path $assets 'minesurvive-logo.png'   # copied from MineSurvive web\leaderboards\assets
$inv = [Globalization.CultureInfo]::InvariantCulture

$dur = [double]::Parse((& $fp -v error -show_entries format=duration -of csv=p=0 $In).Trim(), $inv)
$rate = (& $fp -v error -select_streams v:0 -show_entries stream=r_frame_rate -of csv=p=0 $In).Trim()
$fade = 0.4
$off = ($dur - $fade).ToString('0.###', $inv)

function N($x) { ([double]$x).ToString('0.###', $inv) }
# ease-out-cubic slide from a to b, starting at T for D seconds
function Slide($T, $D, $a, $b) { "if(lt(t,$(N $T)),$a,if(gt(t,$(N ($T+$D))),$b,$a+($b-($a))*(1-pow(1-(t-$(N $T))/$(N $D),3))))" }
# ease-out-back (overshoots a little, then settles)
function Back($T, $D, $a, $b) { "if(lt(t,$(N $T)),$a,if(gt(t,$(N ($T+$D))),$b,$a+($b-($a))*(1+2.70158*pow((t-$(N $T))/$(N $D)-1,3)+1.70158*pow((t-$(N $T))/$(N $D)-1,2))))" }

$frames = [math]::Ceiling($Len * 60)
$g = @(
    # background: last frame, slow push-in (upscaled first so the zoom does not jitter)
    "[1:v]scale=2160:3840,zoompan=z='1+0.06*on/$frames':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s=1080x1920:fps=$rate,setsar=1[bg]",
    "[2:v]format=rgba,fade=t=in:st=0:d=0.6:alpha=1[shade]",
    "[3:v]scale=860:-1,format=rgba,fade=t=in:st=0.15:d=0.25:alpha=1[logo]",
    "[4:v]format=rgba,fade=t=in:st=0.75:d=0.25:alpha=1[tag]",
    "[5:v]format=rgba,fade=t=in:st=1.05:d=0.3:alpha=1[java]",
    "[6:v]format=rgba,fade=t=in:st=1.25:d=0.3:alpha=1[bed]",
    "[7:v]format=rgba,fade=t=in:st=1.45:d=0.3:alpha=1[disc]",
    "[bg][shade]overlay=0:0[o1]",
    "[o1][logo]overlay=x=(W-w)/2:y='$(Back 0.15 0.75 -700 150)'[o2]",
    "[o2][tag]overlay=x=(W-w)/2+4:y='$(Back 0.75 0.4 760 680)'[o3]",
    "[o3][java]overlay=x='$(Slide 1.05 0.55 -1000 68)':y=850[o4]",
    "[o4][bed]overlay=x='$(Slide 1.25 0.55 1100 68)':y=1060[o5]",
    "[o5][disc]overlay=x='$(Slide 1.45 0.55 -1000 68)':y=1310,format=yuv420p,fps=$rate,settb=AVTB[card]",
    "[0:v]fps=$rate,format=yuv420p,settb=AVTB,setsar=1[main]",
    "[main][card]xfade=transition=fade:duration=${fade}:offset=$off[v]",
    "[0:a]apad=pad_dur=$(N $Len),atrim=0:$(N ($dur + $Len - $fade)),afade=t=out:st=$(N ($dur - 0.8)):d=$(N ($Len + 0.4))[a]"
) -join ';'

$work = Join-Path $env:TEMP 'startbuild-outro'; New-Item -ItemType Directory -Force $work | Out-Null
$last = Join-Path $work 'last.png'
& $ff -v error -y -sseof -0.1 -i $In -frames:v 1 -update 1 $last
$loop = @('-loop', '1', '-framerate', $rate, '-t', (N $Len))
& $ff -v error -y -i $In @loop -i $last @loop -i (Join-Path $assets 'shade.png') @loop -i $logo `
    @loop -i (Join-Path $assets 'tag.png') @loop -i (Join-Path $assets 'java.png') `
    @loop -i (Join-Path $assets 'bedrock.png') @loop -i (Join-Path $assets 'discord.png') `
    -filter_complex $g -map '[v]' -map '[a]' -c:v libx264 -preset medium -crf 18 -pix_fmt yuv420p `
    -c:a aac -b:a 192k -movflags +faststart $Out
if ($LASTEXITCODE -ne 0) { throw "ffmpeg failed on $In" }
