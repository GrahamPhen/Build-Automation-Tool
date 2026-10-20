# Shared by finish-short.ps1 and package-all.ps1 (dot-source it): what kind of build a take is, and the
# description (title, server details, hashtags for that kind) posted with its Short.

function Get-ShortKind([string] $Build) {
    if ($Build -match '(?i)charmander|pikachu|gengar|ninetales|squirtle|vaporeon|rayquaza|pokemon|bulbasaur|mimikyu|rowlet|alakazam|mew|blastoise|articuno|eevee') { return 'pokemon' }
    if ($Build -match '(?i)haunt|halloween|pumpkin|witch|ghost|skull|coffin|bat_|cauldron|reaper|necro|grim') { return 'spooky' }
    if ($Build -match '(?i)statue|golem|knight|dragon|pharaoh|bust|monster|teddy') { return 'statue' }
    if ($Build -match '(?i)ship|liner|boat|kapal|vehicle|construction|truck|car|plane|train') { return 'vehicle' }
    return 'building'
}

function Get-ShortHashtags([string] $Build) {
    $tags = [System.Collections.Generic.List[string]]::new()
    foreach ($t in '#minecraft', '#shorts', '#minecraftbuilds', '#minecrafttimelapse') { $tags.Add($t) }
    switch (Get-ShortKind $Build) {
        'pokemon' { '#pokemon', '#minecraftpokemon', '#pokemonbuild' | ForEach-Object { $tags.Add($_) } }
        'spooky'  { '#halloween', '#minecrafthalloween', '#spooky' | ForEach-Object { $tags.Add($_) } }
        'statue'  { '#minecraftstatue', '#minecraftart' | ForEach-Object { $tags.Add($_) } }
        'vehicle' { if ($Build -match '(?i)ship|liner|boat|kapal') { '#minecraftship', '#minecraftboat' | ForEach-Object { $tags.Add($_) } } else { $tags.Add('#minecraftvehicle') } }
        default {
            if ($Build -match '(?i)castle|fort|keep|citadel') { $tags.Add('#minecraftcastle') }
            elseif ($Build -match '(?i)tower|spire|lighthouse') { $tags.Add('#minecrafttower') }
            elseif ($Build -match '(?i)windmill|mill|farm') { $tags.Add('#minecraftfarm') }
            else { $tags.Add('#minecrafthouse') }
            $tags.Add('#minecraftarchitecture')
        }
    }
    $tags.Add('#minesurvive')
    return ($tags -join ' ')
}

function Get-ShortDescription([string] $Build, [string] $Display, [string] $Title) {
    if (-not $Display) { $Display = ($Build -replace '_TIER_\d+_?$', '' -replace '_\d+$', '' -replace '_', ' ').Trim() }
    if (-not $Title) { $Title = "I Built a $Display in Minecraft! #minecraft #shorts" }
    @"
$Title

Built block by block by hand, then a full orbit of the finished $Display. Rate it 1-10 in the comments!

Play with us on MineSurvive!
Java (PC): play.minesurvive.com
Bedrock (Console / Mobile): bedrock.minesurvive.com  Port 19132
Discord: discord.gg/minesurvive

$(Get-ShortHashtags $Build)
"@
}
