# Shared by finish-short.ps1, make-thumbnails.ps1 and package-all.ps1 (dot-source it): what kind of build a
# take is, and the description (a keyword sentence, server details, hashtags for that kind) posted with its Short.
# Hashtags follow the Oct 2026 playbook: at most one in the title, 4-5 in the description from a bucket that
# fits the build (houses / fantasy and towers / pop culture, statues and seasonal), never #minecraftmemes or
# #minecraftreels.

function Get-ShortKind([string] $Build) {
    if ($Build -match '(?i)charmander|pikachu|gengar|ninetales|squirtle|vaporeon|rayquaza|pokemon|bulbasaur|mimikyu|rowlet|alakazam|mew|blastoise|articuno|eevee') { return 'pokemon' }
    if ($Build -match '(?i)haunt|halloween|pumpkin|witch|ghost|skull|coffin|bat_|cauldron|reaper|necro|grim') { return 'spooky' }
    if ($Build -match '(?i)statue|golem|knight|dragon|pharaoh|bust|monster|teddy') { return 'statue' }
    if ($Build -match '(?i)ship|liner|boat|kapal|vehicle|construction|truck|car|plane|train') { return 'vehicle' }
    return 'building'
}

# winter / cozy builds get the seasonal search words (Nov-Dec: "cozy winter cabin", "snowy survival base")
# (and only if the site really was snowy when run-queue logged its wish: "Frostwood Mill" on plains got "From a
# snowy hill to THIS cozy Frostwood Mill")
function Test-ShortWinter([string] $Build) {
    if ($Build -notmatch '(?i)snow|winter|frost|(^|[_ +])ice|alpine|christmas|xmas|igloo|cabin|cozy') { return $false }
    $log = Join-Path $PSScriptRoot 'run-queue.log'
    $wish = if (Test-Path $log) { Select-String -Path $log -Pattern ("=== " + [regex]::Escape($Build) + " \((.*)\)") | Select-Object -Last 1 | ForEach-Object { $_.Matches[0].Groups[1].Value } }
    return (-not $wish) -or ($wish -match '(?i)snow|frozen|ice|winter|taiga|grove')
}

# Blocks the character placed (the last "progress" count run-queue logged for the take), as "9,000+" - for the
# scale titles ("Turning an Empty Hillside Into a Tavern (9,000+ Blocks)"). '' when unknown.
function Get-ShortBlocks([string] $Build) {
    $log = Join-Path $PSScriptRoot 'run-queue.log'
    if (-not (Test-Path $log)) { return '' }
    $cur = $null; $n = 0
    foreach ($l in Get-Content $log) {
        if ($l -match '=== (\S+) ') { $cur = $Matches[1] }
        elseif ($cur -eq $Build -and $l -match 'progress=\S*?:(\d+)(\s+dusk=\d+)?\s+music=') { $n = [int]$Matches[1] }
    }
    if ($n -lt 3000) { return '' }                                    # "(1,000+ Blocks)" undersells it - no count then
    $step = if ($n -ge 10000) { 1000 } else { 500 }
    return ('{0:N0}+' -f ([math]::Floor($n / $step) * $step))
}

# what to call it in "Stop Building Boring ___" titles
function Get-ShortNoun([string] $Build) {
    switch -Regex ($Build) {
        '(?i)castle|fort|keep' { 'Castles' } '(?i)tower|spire|lighthouse' { 'Towers' } '(?i)windmill|mill' { 'Windmills' }
        '(?i)church|chapel|cathedral|sanctuary|temple' { 'Churches' } '(?i)barn|farm' { 'Barns' } '(?i)ship|liner|boat' { 'Boats' }
        '(?i)cafe|caf_|shop|bakery|bakehouse|tavern|inn' { 'Shops' } '(?i)portal|gate' { 'Portals' } default { 'Houses' }
    }
}

function Get-ShortSeed([string] $Build) { $s = 0; foreach ($ch in $Build.ToCharArray()) { $s = ($s * 31 + [int]$ch) % 1000003 }; $s }

function Get-ShortBucket([string] $Build) {
    $kind = Get-ShortKind $Build
    if ($kind -in 'pokemon', 'statue', 'spooky' -or (Test-ShortWinter $Build)) { return 'C' }
    if ($kind -eq 'vehicle' -or $Build -match '(?i)castle|fort|keep|citadel|tower|spire|lighthouse|windmill|mill|wizard|enchant|portal|sanctuary|chapel|church|temple|fantasy|gate') { return 'B' }
    return 'A'
}

function Get-ShortHashtags([string] $Build) {
    $seed = Get-ShortSeed $Build
    $kind = Get-ShortKind $Build
    switch (Get-ShortBucket $Build) {
        'A' { $core = '#minecraft', '#minecraftbuilds'; $niche = '#minecraftideas', '#minecrafthouse', '#minecraftsurvival', '#minecrafttimelapse' }
        'B' { $core = '#minecraft', '#minecraftbuilding'; $niche = '#minecraftaesthetic', '#minecrafttimelapse', '#minecraftdesign', '#minecraftart' }
        default {
            $core = '#minecraft', '#minecraftshorts'
            $niche = switch ($kind) {
                'pokemon' { '#pokemon', '#minecraftpixelart', '#minecraftpokemon', '#gaming' }
                'spooky'  { '#minecrafthalloween', '#spooky', '#minecraftbuilds', '#minecraftart' }
                'statue'  { '#minecraftstatue', '#minecraftpixelart', '#minecraftart', '#gaming' }
                default   { '#winterbuilds', '#minecraftwinter', '#minecraftcabin', '#minecraftbuilds' }
            }
        }
    }
    # 2-3 niche tags, rotated per build (the same block pasted on every upload is what the playbook warns about)
    $count = 2 + ($seed % 2)
    $pick = for ($k = 0; $k -lt $count; $k++) { $niche[($seed + $k) % $niche.Count] }
    return (@($core) + @($pick) | Select-Object -Unique) -join ' '
}

# One natural sentence with search words, varied per build - not the same template on every video.
function Get-ShortKeywordLine([string] $Build, [string] $Display) {
    $seed = Get-ShortSeed $Build
    $n = $Display
    $lines = switch (Get-ShortKind $Build) {
        'pokemon' { @("A giant Minecraft $n build, placed block by block - a fun Pokemon build idea for your world.",
                      "How to build $n in Minecraft: a full timelapse of the build from the first block to the last.",
                      "Minecraft $n pixel art in 3D, built by hand in survival style.") }
        'spooky'  { @("A spooky Minecraft $n build idea, built block by block by hand.",
                      "Minecraft $n timelapse - a creepy build for your survival world.") }
        'statue'  { @("A giant Minecraft $n statue, built block by block - a big build idea for your world.",
                      "Minecraft $n build timelapse, from the first block to the finished statue.") }
        'vehicle' { @("A huge Minecraft $n build, placed block by block by hand.",
                      "Minecraft $n build timelapse - a giant build idea for your world.") }
        default {
            if (Test-ShortWinter $Build) {
                @("A cozy winter Minecraft $n - a snowy survival base idea, built block by block.",
                  "Cozy Minecraft $n build idea for winter: a full survival-friendly timelapse.")
            } else {
                @("A Minecraft $n build idea for your survival world, built block by block by hand.",
                  "Easy-to-follow Minecraft $n build timelapse: a survival house idea from the first block to the roof.",
                  "Minecraft $n - a cozy build idea with a full timelapse, placed block by block.",
                  "Looking for a Minecraft $n idea? Here is the whole build, block by block, on a natural hillside.")
            }
        }
    }
    return $lines[$seed % $lines.Count]
}

function Get-ShortDescription([string] $Build, [string] $Display, [string] $Title) {
    if (-not $Display) { $Display = ($Build -replace '_TIER_\d+_?$', '' -replace '_\d+$', '' -replace '_', ' ').Trim() }
    if (-not $Title) { $Title = "I Built a $Display in Minecraft! #minecraft" }
    @"
$Title

$(Get-ShortKeywordLine $Build $Display) Rate it 1-10 in the comments - and tell me what to build next!

Play with us on MineSurvive!
Java (PC): play.minesurvive.com
Bedrock (Console / Mobile): bedrock.minesurvive.com  Port 19132
Discord: discord.gg/minesurvive

$(Get-ShortHashtags $Build)
"@
}
