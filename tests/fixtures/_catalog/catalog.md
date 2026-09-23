# Schematic catalog

7 file(s): 6 readable, 1 unreadable, 2 duplicate group(s), 1 same-build group(s), 1 same-content group(s).

"Blocks" is the real non-air block count - a decent proxy for how much work each build is.
"Baritone #build" = can be fed straight to Baritone from the file; where it says no, load and
place the schematic in Litematica and use `/startbuild` instead.

| # | Blocks | Size (x,y,z) | Name | Author | Format | Baritone #build | File |
|---|--------|--------------|------|--------|--------|-----------------|------|
| 1 | 27 | 3x3x3 | old_build | Fixture Author | litematic v6 | no - litematic v6 - Baritone calls this "too old"; use the Litematica placement path | old_build.litematic |
| 2 | 20 | 5x1x5 | bundle__flat | - | schematic (MCEdit) v0 | yes | bundle__flat.schematic |
| 3 | 20 | 5x1x5 | flat | - | schematic (MCEdit) v0 | yes | flat.schematic |
| 4 | 18 | 4x3x2 | copy_of_tree_house | Fixture Author | litematic v7 | yes | copy_of_tree_house.litematic |
| 5 | 18 | 4x3x2 | tree_house_test | Fixture Author | litematic v7 | yes | tree_house_test.litematic |
| 6 | 18 | 4x3x2 | tree_house_test | Fixture Author | schem (Sponge) v2 | yes | tree_house_test.schem |

## Same build, more than one format

* `tree_house_test` (4x3x2)
  * tree_house_test.litematic - litematic, 18 blocks
  * tree_house_test.schem - schem (Sponge), 18 blocks

## Duplicate files (byte-identical)

* `774512dc33d5`
  * C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922\tests\fixtures\flat.schematic
  * C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922\tests\fixtures\_catalog\extracted\bundle\bundle__flat.schematic
* `85a916dade5b`
  * C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922\tests\fixtures\tree_house_test.litematic
  * C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922\tests\fixtures\copy_of_tree_house.litematic

## Same content under different filenames

Identical block tally at identical dimensions - almost certainly the same build saved twice.
(A hint, not proof: two different builds with identical materials and size would also match.)

* content `771f64d13eebfc45`
  * copy_of_tree_house.litematic - 18 blocks, first seen
  * tree_house_test.litematic - 18 blocks, duplicate content
  * tree_house_test.schem - 18 blocks, duplicate content

## Unreadable files

* C:\Users\Graham\Codex\MineSurvive\staging\flashback-startbuild-20260922\tests\fixtures\broken.litematic - NbtError: root tag is not a compound (tag 116)
