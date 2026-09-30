# Every finished take: replay, centre/size (origin + schematic's non-air box), stage ticks (from the log), music.
param([string[]] $Only = @(), [string[]] $Skip = @())
$R = Join-Path $PSScriptRoot 'render-take.ps1'
$takes = @(
  @('charmander_80',        'replay=2026-09-27T14_18_28.zip centerX=-21741 centerZ=-23884 baseY=64 width=79 depth=65 height=80 terraformEnd=21440 buildEnd=91468',   @('style=cinematic music=royalty','style=orbit music=heat_waves_slowed','style=cinematic music=biome_fest')),
  @('pikachu+detective_80', 'replay=2026-09-27T13_00_50.zip centerX=-24267 centerZ=-24756 baseY=67 width=71 depth=71 height=80 terraformEnd=43500 buildEnd=148740',  @('style=cinematic music=trap_royalty','style=orbit music=runaway_slowed','style=orbit music=biome_fest')),
  @('gengar_black_80',      'replay=2026-09-27T16_21_44.zip centerX=-22008 centerZ=-24758 baseY=92 width=83 depth=71 height=80 terraformEnd=55660 buildEnd=146171',  @('style=cinematic music=middle_of_the_night_slowed','style=orbit music=trap_royalty','style=cinematic music=royalty')),
  @('halloween+witch_80',  'replay=2026-09-27T07_26_44.zip centerX=-25462 centerZ=-20539 baseY=66 width=111 depth=77 height=80 terraformEnd=109300 buildEnd=230796', @('style=cinematic music=middle_of_the_night_slowed','style=orbit music=haunt_muskie','style=cinematic music=colossus')),
  @('halloween+cauldron_80','replay=2026-09-27T04_11_04.zip centerX=-24304 centerZ=-21758 baseY=65 width=91 depth=89 height=80 terraformEnd=43500 buildEnd=201999',  @('style=cinematic music=haunt_muskie','style=orbit music=middle_of_the_night_slowed')),
  @('pumpkin+house_80',     'replay=2026-09-27T01_21_21.zip centerX=-23804 centerZ=-22066 baseY=68 width=111 depth=95 height=80 terraformEnd=66800 buildEnd=204858', @('style=cinematic music=colossus','style=orbit music=haunt_muskie')),
  @('halloween+cottage_80', 'replay=2026-09-26T19_20_08.zip centerX=-20794 centerZ=-18193 baseY=65 width=71 depth=67 height=80 terraformEnd=37980 buildEnd=136686',  @('style=cinematic music=middle_of_the_night_slowed','style=orbit music=colossus')),
  @('haunted+cottage_80',   'replay=2026-09-26T17_24_29.zip centerX=-20791 centerZ=-17057 baseY=67 width=89 depth=77 height=80 terraformEnd=100440 buildEnd=206630', @('style=cinematic music=haunt_muskie','style=orbit music=middle_of_the_night_slowed')),
  @('pumpkin_house_80',     'replay=2026-09-26T22_24_49.zip centerX=-22743 centerZ=-22477 baseY=64 width=83 depth=87 height=80 terraformEnd=76720 buildEnd=216186',  @('style=cinematic music=trap_royalty','style=orbit music=haunt_muskie')),
  @('house_3_80',           'replay=2026-09-26T08_24_51.zip centerX=-21287 centerZ=-22427 baseY=79 width=99 depth=99 height=80 terraformEnd=196620 buildEnd=366256', @('style=cinematic music=aria_math','style=orbit music=runaway_slowed')),
  @('house_80_v2',          'replay=2026-09-25T22_30.zip centerX=-13466 centerZ=-22382 baseY=68 width=91 depth=75 height=80 terraformEnd=40900 buildEnd=152009',     @('style=cinematic music=royalty','style=orbit music=heat_waves_slowed')),
  @('house_6_80',           'replay=2026-09-26T03_17_41.zip centerX=-17313 centerZ=-20458 baseY=66 width=99 depth=105 height=80 terraformEnd=81200 buildEnd=330055', @('style=cinematic music=taswell','style=orbit music=runaway_slowed')),
  @('storybook_cottage',    'replay=2026-09-25T20_21_24.zip centerX=-13014 centerZ=-21976 baseY=110 width=22 depth=22 height=15 terraformEnd=14180 buildEnd=20278',   @('style=cinematic music=aria_math','style=orbit music=taswell')),
  @('greenhouse_80',        'replay=2026-09-25T19_51_25.zip centerX=-8559 centerZ=-18671 baseY=67 width=109 depth=101 height=80 terraformEnd=167140 buildEnd=385095', @('style=cinematic music=biome_fest','style=orbit music=heat_waves_slowed')),
  @('haunted_80',           'replay=2026-09-24T23_32_10.zip centerX=-4191 centerZ=-2165 baseY=68 width=73 depth=63 height=77 terraformEnd=86500 buildEnd=161193',    @('style=cinematic music=middle_of_the_night_slowed','style=orbit music=haunt_muskie')),
  @('pumpkin_castle_80',    'replay=2026-09-25T05_24_14.zip centerX=-4751 centerZ=-362 baseY=86 width=97 depth=79 height=80 terraformEnd=115200 buildEnd=339352',    @('style=cinematic music=colossus','style=orbit music=middle_of_the_night_slowed')),
  @('haunted_halloween_80', 'replay=2026-09-27T10_27_11.zip centerX=-25913 centerZ=-21431 baseY=69 width=83 depth=75 height=80 terraformEnd=114980 buildEnd=214191', @('style=cinematic music=haunt_muskie','style=orbit music=colossus')),
  @('halloween_80',         'replay=2026-09-25T09_23_25.zip centerX=-5399 centerZ=-1097 baseY=70 width=83 depth=79 height=80 terraformEnd=87600 buildEnd=269995',    @('style=cinematic music=trap_royalty','style=orbit music=middle_of_the_night_slowed')),
  @('treehouse_80',         'replay=2026-09-26T14_28_22.zip centerX=-21814 centerZ=-21858 baseY=70 width=91 depth=89 height=80 terraformEnd=166020 buildEnd=434233', @('style=cinematic music=aria_math','style=orbit music=runaway_slowed'))
)
foreach ($t in $takes) {
    if ($Only.Count -and $Only -notcontains $t[0]) { continue }
    if ($Skip -contains $t[0]) { continue }
    & $R -Build $t[0] -Common "$($t[1]) frontYaw=160" -Jobs $t[2]
}
