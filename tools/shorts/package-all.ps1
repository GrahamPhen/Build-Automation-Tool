# Upload kit for every finished Short: makes any missing thumbnail, rewrites each "<build> - description.txt"
# (title, server details, hashtags for that kind of build) and writes one Word doc listing every Short with its
# video, thumbnail, title and full description, ready to copy-paste.
#   package-all.ps1
$ErrorActionPreference = 'Continue'
. (Join-Path $PSScriptRoot 'shorts-meta.ps1')
$shorts = 'C:\Users\Graham\Desktop\Shorts'
$kit = 'C:\Users\Graham\Desktop\Shorts Thumbnails and Titles'
$csvPath = Join-Path $kit 'titles.csv'

$rows = @()
$olderRows = @()
foreach ($dir in Get-ChildItem $shorts -Directory | Where-Object { $_.Name -notmatch '^_' }) {   # _NOT-USABLE_ etc.
    $build = $dir.Name
    $f = Get-ChildItem -LiteralPath $dir.FullName -Filter '*final*.mp4' | Where-Object { $_.Name -notmatch 'phone size' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $titles = @(Import-Csv $csvPath | Where-Object { $_.Video -like "$build-*" })
    if (-not $f) {
        # older format (before the hook/banner finals): each B/C cut, the with-outro copy being the one to post
        foreach ($v in Get-ChildItem -LiteralPath $dir.FullName -Filter "$build-*.mp4") {
            $post = Join-Path $dir.FullName "with-outro\$($v.Name)"
            if (-not (Test-Path -LiteralPath $post)) { $post = $v.FullName }
            $row = $titles | Where-Object { $_.Video -eq $v.Name } | Select-Object -First 1
            $thumb = $v.Name -replace '\.mp4$', '.jpeg'
            if (-not (Test-Path -LiteralPath (Join-Path $kit $thumb))) { $thumb = "(missing) $thumb" }
            $desc = (Get-ShortDescription $build "$($row.Build)" "$($row.Title)").Trim()
            $olderRows += [pscustomobject]@{ build = $(if ($row.Build) { "$($row.Build) (older edit)" } else { "$build (older edit)" }); video = $post; thumb = $thumb
                title = $(if ($row.Title) { $row.Title } else { ($desc -split "`n")[0].Trim() }); description = $desc }
        }
        continue
    }
    if (-not ($titles | Where-Object { Test-Path -LiteralPath (Join-Path $kit ($_.Video -replace '\.mp4$', '.jpeg')) })) {
        try { & (Join-Path $PSScriptRoot 'make-thumbnails.ps1') -Only $build | Select-Object -Last 1 } catch { "thumbnail error for ${build}: $_" }
        $titles = @(Import-Csv $csvPath | Where-Object { $_.Video -like "$build-*" })
    }
    # the orbit cut (C) is what the finals are made from; else whatever this build has
    $row = ($titles | Where-Object { $_.Video -match '-C-orbit-' } | Select-Object -First 1)
    if (-not $row) { $row = $titles | Select-Object -First 1 }
    $thumb = if ($row) { $row.Video -replace '\.mp4$', '.jpeg' } else { '' }
    if ($thumb -and -not (Test-Path -LiteralPath (Join-Path $kit $thumb))) { $thumb = "(missing) $thumb" }
    $desc = Get-ShortDescription $build "$($row.Build)" "$($row.Title)"
    Set-Content -LiteralPath (Join-Path $f.DirectoryName "$build - description.txt") -Value $desc -Encoding utf8
    $rows += [pscustomobject]@{ build = $(if ($row.Build) { $row.Build } else { $build }); video = $f.FullName; thumb = $thumb
        title = $(if ($row.Title) { $row.Title } else { ($desc -split "`n")[0].Trim() }); description = $desc.Trim() }
}
$json = Join-Path $env:TEMP 'shorts-upload.json'
$missingOld = @($olderRows | Where-Object { $_.thumb -like '(missing)*' } | ForEach-Object { ($_.thumb -replace '^\(missing\) ', '') -replace '-[ABC]-.*$', '' } | Sort-Object -Unique)
foreach ($b in $missingOld) { try { & (Join-Path $PSScriptRoot 'make-thumbnails.ps1') -Only $b | Select-Object -Last 1 } catch { "thumbnail error for ${b}: $_" } }
foreach ($r in $olderRows) { $t = $r.thumb -replace '^\(missing\) ', ''; if (Test-Path -LiteralPath (Join-Path $kit $t)) { $r.thumb = $t } }
"$($rows.Count) current Shorts, $($olderRows.Count) older edits"
$rows = @($rows) + @($olderRows)
[IO.File]::WriteAllText($json, ($rows | ConvertTo-Json -Depth 3))
$env:NODE_PATH = "$env:TEMP\docxlib\node_modules"
node (Join-Path $PSScriptRoot 'upload-doc.js') $json (Join-Path $kit 'Shorts Upload Sheet.docx')
"$($rows.Count) Shorts packaged; missing thumbnails: $(@($rows | Where-Object { $_.thumb -like '(missing)*' -or -not $_.thumb }).Count)"
