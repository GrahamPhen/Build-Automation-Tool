# Editing and cinematography craft for build-timelapse Shorts (Minecraft + real-world), and replay-tool usage

Scope note: Web sources on this topic are thin and dominated by vendor/SEO blogs. Primary documentation for Flashback (the replay mod the pipeline uses) is not published in a crawlable form (Modrinth page, GitHub README carry no feature list; docs live on Discord). Claims below are tagged with their source quality. Searches run 2026-10-15.

## 1. Pacing: constant speed vs speed ramps, slowing at key moments, where to cut

### Takeaway
Sources converge on: compress the process hard, keep only segments where visible change happens, and slow down (or drop to real time) for the finished result; ramps should be eased, not stepped. Mark three anchor moments (first state, biggest change, final result) and build the edit around them.

### Cited Findings
- Edit workflow advice: mark the key moments first: the first visible state, the biggest change, and the final result; if those are weak, a normal-speed shot of the finished result helps more than extra speed — [nemovideo.com (vendor blog)](https://www.nemovideo.com/blog/ai-video-workflows/time-lapse-maker-short-video-workflow)
- Start just before the action and end after the result has settled — [nemovideo.com](https://www.nemovideo.com/blog/ai-video-workflows/time-lapse-maker-short-video-workflow)
- Sped-up footage with little visible change "looks like noise"; common in construction when the camera is wide and work happens in one corner — [nemovideo.com](https://www.nemovideo.com/blog/ai-video-workflows/time-lapse-maker-short-video-workflow)
- Use day stamps ("Day 3") sparingly — too many turn the edit into a log — [nemovideo.com](https://www.nemovideo.com/blog/ai-video-workflows/time-lapse-maker-short-video-workflow)
- Speed ramping (borrowed from slow-motion work) listed among five ways to improve timelapses, alongside stabilization, faked camera moves and faked motion blur — [DIY Photography](https://www.diyphotography.net/?p=194999)
- Easing in and out of speed changes makes the sequence feel smoother — [softhandtech.com (low quality)](https://softhandtech.com/?p=4255)
- Long-form Minecraft timelapses are structured as a timelapse section followed by a slower showcase section (Replay Mod for camera, Premiere for edit); a viewer comment singled out the slow cinematic shots as what made it stand out — [The Echelon - A Minecraft Timelapse](https://yt.d0.cx/v/yt/6gwoINNADqo)
- Shots that do not advance the story make a video feel disjointed; cutting them is the fix — [Splice blog (promotional)](https://spliceapp.com/blog/timelapse-editing-mistakes-plane-spotting/)
- Flashback 0.33.0: non-time keyframe interpolation now runs on real time, so changing replay speed mid-move does not speed up/slow down the camera (old tick-based behaviour re-enableable in Preferences) — this decouples camera motion from build-speed ramps — [search summary of Modrinth version notes](https://modrinth.com/project/4das1Fjq/version/BoZkOz9E)
- Replay Mod model: position keyframes say where the camera is; time keyframes are timestamps that set the length and speed of the video; spacing between keyframes sets duration (two keyframes 5 s apart = 5 s interpolation) — [Replay Mod docs](https://www.replaymod.com/docs/1.0.8); [premiumminecraft tutorial](https://premiumminecraft.com/replay-mod-first-time-set-up-usage-in-depth-explanation-tutorial/)

### Inferences
- For an automated pipeline: compute speed per segment from "visible change per second" (e.g. blocks placed on the camera-facing side per replay second) rather than a constant multiplier; speed up through idle/site-prep/hidden interior work, slow where the silhouette changes (roof going on, last block).
- A usable 45 s pacing curve consistent with the sources: 0-2 s hook (finished build or striking frame), 2-35 s accelerating build timelapse with eased ramps, ~35-38 s slowdown to near real time on the final placements, 38-45 s slow reveal move of the finished build. Map ramps with ease-in/ease-out curves (e.g. smoothstep on the time-remap curve), never step changes.
- Because Flashback camera interpolation is now real-time, the pipeline can drive the replay timeline speed (time keyframes) independently and keep camera moves smooth during ramps.

### Gaps
- No quantitative data found on optimal ramp ratios or segment durations for build timelapses specifically.
- Could not access channel-specific breakdowns of real-world restoration/build channels' pacing.

## 2. Camera moves for 9:16 vertical: which moves, how many angles, how long per shot

### Takeaway
Vertical framing favours depth and vertical moves (push-in, pull-out, crane/tilt-up) over lateral pans; orbits work if slow and tight with the subject centred. Keep key action in the central ~40% because platform UI covers edges. Stable framing matters more than movement during the build phase.

### Cited Findings
- Push/pull work better than panning in 9:16 because the narrow frame lacks horizontal room for a pan; slow orbit around the subject listed as suitable — [nemovideo (Spanish)](https://www.nemovideo.com/es-ES/blog/seedance-2-vertical-video-tiktok-reels)
- Vertical frame favours tall subjects (buildings, trees, waterfalls) and up/down movement — [Artlist vertical video guide](https://artlist.io/blog/the-ultimate-vertical-video-guide-2020/)
- Vertical tilt-up reveal, parallax layers (foreground moves differently from background) recommended for vertical — [cliprise / frameo guides](https://www.cliprise.app/learn/aspect-ratios); [frameo.ai](https://frameo.ai/blog/create-vertical-videos-social-media/)
- "Lift the camera up and tilt it down" with a wide lens creates depth; vertical requires very precise camera moves to keep framing; shakiness is very noticeable — [Adam Sebire 9:16 tips](https://www.adamsebire.info/vertical-film-festival/9-16-tips-and-tricks/); [Canon @inyourfayes tips](https://canon.com.au/get-inspired/essential-vertical-video-shooting-tips-inyourfayes)
- Keep key action in the centre 40% — platform UI (profile icons, CTAs, captions) lives in corners and edges — [cliprise](https://www.cliprise.app/learn/aspect-ratios)
- Stable framing matters more once footage is sped up; every small jump becomes noticeable; use a fixed mount — [nemovideo](https://www.nemovideo.com/blog/ai-video-workflows/time-lapse-maker-short-video-workflow)
- Adding a foreground element or varying angles adds interest to static timelapse framing — [Splice blog](https://spliceapp.com/blog/timelapse-editing-mistakes-plane-spotting/)
- Short-form guides suggest alternating wide/medium/close shots every 1-2 s (generic talking-head advice, not timelapse-specific) — [opus.pro (vendor)](https://www.opus.pro/blog/ideal-youtube-shorts-length-format-retention)
- Flashback camera modes include FREE, POV and FOLLOW, with smooth and cut transitions between camera keys (from Flash Droid companion description) — [Flash Droid](https://www.curseforge.com/minecraft/mc-mods/flash-droid)

### Inferences
- Build phase: 2-3 fixed (or very slowly drifting) high three-quarter angles, elevated and tilted down, building centred, 3-8 s each, cutting between them on a beat or at a layer/milestone. A slow continuous drift/orbit (e.g. 30-60 degrees over the whole build) adds life without destabilising the progress read.
- Reveal: crane-up/tilt-up or push-in to the front, or a slow orbit ending on the front face (matches the repo's existing fronts.json "end on the front" rule). A final pull-out/rise to show the landscape suits 9:16 because it uses frame height.
- Avoid wide lateral pans and fast orbits: the narrow frame clips the building edges.
- 1-2 s cuts (the generic Shorts advice) would destroy progress readability in a timelapse; prefer longer holds with motion inside the shot.

### Gaps
- No source compared retention across camera styles for build timelapses; no data on optimal number of angles.
- First-person walk-in/interior tours: no sourced guidance found.

## 3. How Minecraft cinematic creators use replay tools, shaders, time of day, weather, DoF, motion blur

### Takeaway
Standard workflow: record without shaders, then in the replay editor set camera paths, time and weather, and enable shaders (Iris packs) plus depth of field only for the frame-by-frame render, which removes the performance penalty. Flashback is the modern successor to Replay Mod, but its feature documentation is not public on the web.

### Cited Findings
- Replay rendering is frame-by-frame, so shaders don't cause lag in the output; guides recommend enabling shaders for renders — [Badlion Replay Mod guide](https://www.badlion.net/minecraft-blog/minecraft-replay-mod)
- Disable shaders while recording for performance, enable before rendering — [Cinema Shots modpack (archived)](https://modrinth.com/mod/cinema-shots)
- Workflow: record without shaders; in the replay viewer add camera paths and adjust weather and time; turn shaders and DoF on before render — [Badlion](https://www.badlion.net/minecraft-blog/minecraft-replay-mod); [Cinema Shots](https://www.mado-hosting.com/minecraft-modpacks/cinema-shots)
- DoF options: shader-pack DoF (focus follows crosshair by default), Universal Depth of Field Fabric mod (DoF in vanilla pipeline, smooth focus transitions, no shaders needed), Vanilla Camera mod (simple on/off DoF) — [Universal DoF](https://moddex.gg/mod/mc2-udof); [sportskeeda cinematic shader settings](https://www.sportskeeda.com/minecraft/5-best-cinematic-shader-settings-minecraft)
- Many shader packs include motion blur, considered cinematic for recorded footage — [sportskeeda](https://www.sportskeeda.com/minecraft/5-best-cinematic-shader-settings-minecraft)
- Replay Mod camera paths use cubic spline interpolation by default; position keyframes store x,y,z,yaw,pitch,roll; timeline up to 10 min — [Replay Mod docs](https://www.replaymod.com/docs/1.0.8)
- Flashback describes itself as a way to record gameplay, play it back "and create stunning cinematic shots"; supports MC 1.21-26.3 on Fabric; support via Discord — [Modrinth Flashback](https://modrinth.com/mod/flashback); [GitHub](https://github.com/Moulberry/Flashback)
- Flashback 0.33.0 added a "Block Overrides" keyframe track for block edits made while paused; place them near export start or they may be skipped — [Modrinth version notes via search](https://modrinth.com/project/4das1Fjq/version/BoZkOz9E)
- Flashback Extras add-on fixes audio during timelapses/speed changes (audio plays normally while waveform scales with timeline speed) — [flashback-extras](https://modrinth.com/mod/flashback-extras)
- Day-to-night transitions/sunset timelapses used for drama in Minecraft build videos (SEO-quality source) — [search summary, low quality](https://playboard.co/en/tag/timelapse)

### Inferences
- Golden-hour (in-game ~12000-13000 ticks) for the reveal plus a dusk/night transition where placed lanterns/torches light up gives a free "second reveal" — an automated pipeline can keyframe time of day only during the reveal, keeping a constant mid-day during the build so progress reads clearly and no flicker from shifting light occurs (the real-world flicker problem in Section 6 is the analogue).
- Render-only shaders + DoF: apply mild DoF only on the reveal/push-in (focus on building), not during the wide timelapse where everything should be sharp.
- Motion blur should be modest; with huge speed-ups, motion blur on placed blocks/character can smear progress.

### Gaps
- Could not verify whether Flashback has native time-of-day, weather, FOV, or orbit keyframe tracks from public sources (docs are on Discord). The repo's existing Flashback bridge code would be the authoritative reference.
- No interviews with named top Minecraft cinematic creators (e.g. Mogswamp is featured on the Flashback page) were found with technique details.
- Axiom / Distant Horizons cinematic usage: no usable sources surfaced (Distant Horizons is relevant only for far-landscape pull-outs).

## 4. Sound design and music

### Takeaway
Use risers before the reveal and an impact/hit on the reveal frame; whooshes for fast cuts. Restoration content shows the process sounds themselves (ASMR) are part of the appeal, so block-placement SFX can be kept under the music. For copyright safety, music should come from the YouTube Audio Library (no Content ID claims) rather than arbitrary "royalty-free" sources.

### Cited Findings
- Riser: sound that gets higher/louder/busier, placed right before a big moment so the release lands at the peak; whoosh: short pass-by gesture under a second to bridge a fast cut; impacts add weight to a reveal or cut point — [morphic glossary](https://morphic.com/ai-glossary/riser); [jackrighteous guide](https://jackrighteous.com/en-gb/blogs/ai-art-visuals-creatives/cinematic-hits-and-transitions-suno-sounds-guide)
- Pitfalls: generic whooshes make moments feel the same; muddy impacts clog a reveal; badly timed risers make a scene feel off — [jackrighteous](https://jackrighteous.com/en-gb/blogs/ai-art-visuals-creatives/cinematic-hits-and-transitions-suno-sounds-guide)
- Restoration-video viewers come for technique and stay for the hypnotic sounds (clinking metal, sanding, lathe), linked to ASMR; process is described as relaxing — [Open Culture](https://www.openculture.com/snippet/courses_law/page/305)
- YouTube Audio Library (YouTube Studio): royalty-free production music and SFX safe for YouTube, won't be claimed by Content ID; some tracks need attribution in the description — [subscribr.ai](https://subscribr.ai/youtube-strategy/youtube-shorts-copyright-guide); [foximusic (music vendor)](https://www.foximusic.com/blog/youtube-shorts-copyright-music-library-guide/)
- Shorts in-app audio library (label tracks) works only when added via YouTube's own picker; it is not a portable license for externally edited files; Creator Music is for long-form, not Shorts — [foximusic](https://www.foximusic.com/blog/?p=592); [subscribr.ai](https://subscribr.ai/p/youtube-shorts-copyright-guide)
- Music added externally from outside libraries is highly likely to trigger Content ID claims; for cross-posted videos upload a music-free version and add music via native tools — [foximusic](https://www.foximusic.com/blog/?p=592)
- Unverified vendor claim: Shorts over 60 s with any Content ID claim are blocked globally — [foximusic](https://www.foximusic.com/blog/?p=592) (not confirmed by YouTube docs)
- Flashback timeline speed changes distort audio unless Flashback Extras is used — [flashback-extras](https://modrinth.com/mod/flashback-extras)

### Inferences
- Pipeline: render video silent; build audio track in ffmpeg = YouTube Audio Library track (auto-pick by BPM) + a sparse, rhythm-quantised layer of block-place SFX (not raw replay audio, which is garbled at 20-100x speed) + riser starting ~1-2 bars before the reveal + impact on reveal frame.
- Beat-sync: detect beats in the chosen track (e.g. librosa/aubio onset) and snap angle cuts and the reveal to downbeats; this is cheap to automate and aligns with the riser/impact model.
- Keep a licence log (track, artist, URL, date, licence) per Short as the sources advise.

### Gaps
- No sourced guidance on how to beat-match cuts precisely; the bar/downbeat convention is general practice, not cited.
- No data on whether original game SFX vs music-only performs better in Shorts.

## 5. Reveal techniques

### Takeaway
The payoff of a transformation video is the clear, complete before/after contrast; the pipeline should guarantee an unobstructed, well-lit final shot, ideally held at slower speed, and can use a loop structure (end frame flows into opening) to drive replays.

### Cited Findings
- Watching something regain its look is "uniquely satisfying"; the pleasure comes from a clear, complete change — [Open Culture](https://www.openculture.com/snippet/courses_law/page/305)
- Stark contrast creates a "what happened here?" curiosity factor that keeps viewers watching (SEO text duplicated across many domains; weak) — [thebrandindustry](https://docs.thebrandindustry.com/news/before-and-after-the-unreal-change-captured-on-video-4802436)
- Keep the reveal shot unobstructed and well lit; timelapse then slower final reveal is the standard Minecraft structure — [search synthesis; Echelon example](https://yt.d0.cx/v/yt/6gwoINNADqo)
- Loop: final frame should leave viewers wanting to re-experience the satisfaction; replays weighted heavily (unsourced "10% replay rate" claim) — [opus.pro (vendor)](https://www.opus.pro/blog/ideal-youtube-shorts-length-format-retention)
- Open a loop in the first seconds and close it last; never answer the hook early or bury the payoff — [skills.sh short-form guide](https://skills.sh/iart-ai/tiktok-video-skills/short-form-video)
- YouTube's creator story (Sydney Morgan): first 3 s must be "thumb-stopping"; telling viewers what they're about to watch retains those who want it; her loop technique ends mid-sentence into the start — [YouTube Official Blog](https://blog.youtube/creator-and-artist-stories/how-sydney-morgan-creates-the-smoothest-makeup-transformations)
- Transitions/effects should only signal location/time/mood changes; over-editing distracts — [Splice blog](https://spliceapp.com/blog/timelapse-editing-mistakes-plane-spotting/)

### Inferences
- Automatable reveal options: (a) match cut from the empty site to the finished build at the identical camera pose (same keyframe, two replay timestamps); (b) 2-3 frame white flash + impact on the cut; (c) final slow orbit or crane ending on the front; (d) pull-out/rise to show landscape (the owner's terraforming blend makes this attractive); (e) dusk transition with lights turning on as a second beat.
- Loop: open on a 1-1.5 s flash of the finished build (hook), cut to empty site, end on the same pose as the opening frame so the restart is seamless.

### Gaps
- No retention data comparing reveal styles (flash vs match cut vs orbit).
- No sourced examples of interior walkthroughs in build Shorts.

## 6. Common mistakes that make timelapses boring

### Takeaway
Boredom comes from footage with no visible change, wrong speed (jittery or sluggish), unstable or flickering image, over-editing, slow intros, and burying the payoff.

### Cited Findings
- "A smooth clip of nothing is worse than nothing"; some scenes have too little movement for timelapse to add value — [Splice blog](https://spliceapp.com/blog/timelapse-editing-mistakes-plane-spotting/); [timelapsenetwork](https://timelapsenetwork.com/tutorial/10-things-must-not-do-when-shooting-timelapse/)
- Wrong interval: too short = jittery, too long = sluggish — [Splice](https://spliceapp.com/blog/road-trip-timelapse-editing-mistakes-to-avoid-for-youtube-shorts/)
- Flicker from auto exposure/white balance and pulsing from focus drift are the most common technical problems — [photographytalk](https://www.photographytalk.com/photography-articles/5485-6-mistakes-photographers-make-when-getting-started-in-time-lapse-photography); [lrtimelapse](https://lrtimelapse.com/?p=66888)
- Wide framing where work happens in one corner makes sped-up footage look like noise — [nemovideo](https://www.nemovideo.com/blog/ai-video-workflows/time-lapse-maker-short-video-workflow)
- Silence, slow ramps and throat-clearing intros are where viewers swipe — [videobgremover (vendor)](https://videobgremover.com/blog/youtube-shorts-best-practices)
- Vendor benchmark: target 75%+ retention at 3 s in YouTube Studio; other unverified stats (65% drop in first 3 s) circulate without sources — [opus.pro](https://www.opus.pro/blog/ideal-youtube-shorts-length-format-retention); [aivideosensei](https://aivideosensei.com/guides/youtube-shorts-with-ai)

### Inferences
- Minecraft analogues: character walking/flying between placements, site-prep lulls, interior work hidden by walls, camera too far so blocks are sub-pixel in 9:16, in-game day/night cycle running during the build (lighting "flicker" analogue), character body occluding the build.
- Automated checks: measure per-segment visual change (frame-diff) and auto-cut or speed through low-change spans; flag shots where the build occupies under ~30-40% of frame height.

### Gaps
- No rigorous study on optimal runtime for build timelapse Shorts; 30-60 s guidance comes from the task constraint and vendor blogs, not data.
