# Desktop player audit

Date: 2026-10-26. Branch: `fix/player-surface-flash`.

Where a claim depends on library behaviour that the repository does not show, it is marked **(unverified)**.

## 1. What runs today

### Entry and backend selection

- `PlayerScreenRuntimeUi.kt` (common code) renders `PlatformPlayerSurface`. When `platformPlayerSurfaceOwnsOverlay()` is true (Windows with the native surface), the whole Compose overlay (controls, opening overlay, modals, party) is passed into the surface as `overlayContent`. The surface is keyed by `Unit`, so a single panel and a single mpv instance last for the whole player screen. Source changes inside the screen go through `loadfile replace`.
- `DesktopPlayerSurface.desktop.kt` picks a backend by `os.name`:
  - Windows: `WindowsMpvPlayerBackend` (libmpv through JNA).
  - macOS: `MacOSMpvPlayerBackend`. It drives `MPVKit/Sources/DesktopMPVBridge` (Swift). That bridge opens its own `NSWindow` with an `NSOpenGLView`, uses the mpv render API (`vo=libmpv`, OpenGL), and draws its own SwiftUI/AppKit controls (`NuvioControlsView.swift`, about 1.2k lines). None of the Compose player UI is used there. I did not check whether the dylib is built and shipped by the current Gradle and release pipeline **(unverified)**.
  - Linux and anything else: `UnsupportedDesktopPlaybackBackend`, which shows a black box and an error.
- Upstream NuvioMobile `cmp-rewrite` has no desktop player. Its `desktopMain` contains only four stub actuals (updater, server config, feature policy, palette image). The desktop player is Kino's own code.

### Windows: two presentation paths in one file

`WindowsMpvPlayerBackend.desktop.kt` is about 4,700 lines. It holds JNA bindings, the event pump, polling, fullscreen Win32 styling, audio diagnostics, subtitle styling, two complete panels, and the Swing-era controls.

| Path | Class | Selected by | Render path |
|---|---|---|---|
| Native (default) | `WindowsPlayerPanel` | `kino.windows.video-surface` unset, `native` or `d3d11` | An AWT heavyweight `Canvas` HWND is handed to mpv as `wid`. mpv uses `vo=gpu-next,gpu`, `gpu-api=d3d11`, `d3d11-flip=no` and presents straight into that child HWND. |
| Embedded (opt-in fallback) | `EmbeddedWindowsPlayerPanel` | `-Dkino.windows.video-surface=embedded`, `gl` or `opengl` | A JOGL `GLJPanel` runs the mpv render API (`vo=libmpv`, OpenGL FBO). `GLJPanel` reads pixels back into Swing, so this path is a GPU→CPU→GPU copy. |

The embedded path was the first attempt to keep video inside Swing and Compose. It still exists as an escape hatch. The JOGL jars (`jogl-all`, `gluegen-rt` 2.6.0, plus a repacked Windows jar in `build.gradle.kts`) ship only for it. Until this branch, the Windows prewarmer also preloaded JOGL even though the default path never uses it.

### Native path structure (default)

```
ComposeWindow (Skiko D3D12 swapchain on its own child HWND, compose.interop.blending=false)
 └─ SwingPanel  →  WindowsPlayerPanel (JPanel)
                     └─ videoLayer (JPanel, black)
                          └─ Canvas (heavyweight HWND, hidden until video is ready)  ← mpv wid, D3D11 blt-model swapchain
JWindow overlay (separate owned top-level window, bounds = owner content pane)
 └─ ComposePanel(RenderSettings.SwingGraphics)  ← all Compose player UI
```

- Threads:
  - The AWT EDT runs Compose, Swing, mpv init, `loadfile`, and most mpv property writes.
  - `Kino-mpv-events` (`MpvEventPump`) drains `mpv_wait_event`.
  - A Compose `LaunchedEffect` polls about 15 mpv properties on `Dispatchers.IO` under `mpvCallLock` on every wakeup.
  - A Swing `Timer` at 250 ms tracks the pointer.
  - URL resolution and redirects run in `urlResolutionScope` (IO).
- Overlay transparency:
  - The overlay window starts opaque `#0C0C0C` (`setOverlayWindowTransparency(false)`).
  - `primeOverlayWindow` switches it to per-pixel transparent once the window is showing and the Compose render API is known. The prime runs again on every focus gain.
  - `setVideoSurfaceVisible(true)` forces it transparent.
  - The `ComposePanel` uses the `SwingGraphics` render settings, so the overlay is software-rasterized and pushed through Swing. That is what makes per-pixel alpha possible on a top-level window.
- Lifecycle:
  - `canvas.addNotify` initializes mpv on the EDT.
  - `canvas.removeNotify` and `dispose` shut mpv down before the HWND dies (commit `6d0383aeb`).
  - The overlay window is disposed in `removeNotify` and `dispose`.
- Other platform glue:
  - Fullscreen restyles the owner frame through `SetWindowLongPtrW`/`SetWindowPos` (`FullscreenWindowState`).
  - SMTC and media keys use `KinoMediaSession.cpp` through `WindowsMediaSession.desktop.kt` and `DesktopMediaKeySession`.
  - HDR and Dolby Vision use `vf=libplacebo=…apply_dolbyvision=1…`, chosen per file by `configureWindowsVideoPipeline`.

### Feature coverage today (Windows native path)

| Feature | How it works | Status |
|---|---|---|
| HDR | `target-colorspace-hint`, `hdr-compute-peak`, `tone-mapping=auto`, `gpu-next` | Works |
| Dolby Vision | libplacebo `vf` (`apply_dolbyvision=1`) | Works (P5/P8 reshaping through libplacebo) |
| Subtitles (embedded, external, ASS) | libass in mpv; `sub-add`; `sub-ass-override=force` plus Kino style mapping | Works |
| Audio tracks | `aid`, `track-list`; separate audio URL through `audio-add` | Works |
| HTTP headers | `http-header-fields` per load, swapped around `audio-add` | Works |
| Torrent and debrid URLs | `DesktopPlaybackUrlResolver` resolves to HTTP before `loadfile` | Works |
| Hardware decode | `hwdec=auto-copy-safe` | Works. Copy-back means extra PCIe traffic at 4K. |
| Seek, speed, volume | mpv commands | Works |
| Audio delay | Read for diagnostics only. No setter exposed. | Partial |
| Seek thumbnails | None on desktop | Missing |
| PiP | `ManagePlayerPictureInPicture` is a no-op on desktop | Missing |
| SMTC and media keys | Native DLL | Works |

## 2. Bugs investigated on this branch

### 2.1 White window on the first playback

**Evidence** (`%LOCALAPPDATA%\Kino\logs\kino.log.1`, first playback after launch, 18:11Z):

```
18:11:41.965 native panel addNotify peerReady
18:11:41.966 native overlay window transparency=false windowVisible=false ...   (overlay about to be shown)
18:11:43.165 native panel canvas displayable=true ... hwnd=…                   (+1.2 s, first line of initializeMpv after WindowsMpvLibrary.INSTANCE)
```

The second playback in the same session (18:13:43) takes about 120 ms for the same step.

**Root cause.** `initializeMpv()` runs on the EDT, and its first statement is `WindowsMpvLibrary.INSTANCE`. That is a lazy `Native.load` of the roughly 100 MB `libmpv-2.dll`, plus a resource-extraction check. On Windows, `prewarmDesktopPlaybackBackend()` only preloaded JOGL, so the first playback paid the full load on the EDT. For about 1.2 s nothing in the app could paint:

- The Compose player route could not render.
- The overlay `JWindow` had just been shown as a new top-level window covering the entire content pane, and it had not painted yet.

Swing top-level windows rely on the EDT to paint, and an unpainted new top-level window shows as white. **(unverified)**: whether AWT's native background erase is disabled for `JRootPane` windows. Either way, the timing matches the report: white on the first playback only, because later playbacks already have the library loaded.

**Fix** (commit `24d658188`):
- The prewarmer loads libmpv on IO at startup. It loads JOGL only when the embedded surface is selected.
- `canvas.addNotify` resolves the library on IO and then initializes mpv on the EDT. It is guarded by `playerDisposed` and `isDisplayable`.

### 2.2 Stale frame from the previous stream

**Evidence** (`kino.log.1`, source change inside one player screen, 13:38Z):

```
13:38:42.609 source load effect started ... playerReady=true          (same panel and mpv, new source)
13:38:42.611 native video canvas visible=false                         (hideVideoSurfaceForLoad)
13:38:42.651 native video canvas visible=true                          (+40 ms, re-shown while the new URL is still resolving)
13:38:51.936 native panel loadFile ...                                 (+9.3 s, URL resolution finished)
13:38:52.192 mpv event id=7 ... / id=6 playlistEntryId=2 generation=2
13:38:52.211 native video canvas visible=false
13:38:53.247 native video canvas visible=true                          (new stream's first video output)
```

**Root cause.** The poll computed `videoOutputReady` only from `vo-configured`, `video-params/w` and `video-params/h`. While the new URL resolves, and until mpv's `start-file` for the new `loadfile`, those properties still describe the old file. The first poll after `hideVideoSurfaceForLoad()` therefore re-showed the canvas with the old stream on it. The UI also shows the opening overlay with a fade-in, so the old frames are visible through it.

**Fix** (commit `16e543475`): the new `shouldRevealWindowsVideoSurface` (in `WindowsVideoSurfacePolicy.desktop.kt`) also requires:
- no pending load request, and
- `activePlaylistEntryGeneration == sourceGeneration`. That value is set only by the current generation's `start-file` in `handleWindowsMpvStartFileEvent`.

Unit tests are in `WindowsVideoSurfacePolicyTest`.

**Residual risk.** Some may remain for brand-new player screens. Older Windows builds of mpv present into the child HWND in the blt model (`d3d11-flip=no`), which writes into the top-level window's DWM redirection surface. Skiko's own flip-model swapchain never overwrites that surface. If any region of the redirection surface is exposed before Swing repaints it, it can still show the last video frame of an earlier session **(unverified, needs a runtime check)**. Section 4 explains why I would not keep spending on this class of bug.

### 2.3 Discord window share shows only the video

**Code facts:**
- The player UI lives in the overlay `JWindow`. That is a separate owned top-level window, not a child of the Kino frame.
- The video is mpv's D3D11 swapchain on a child HWND of the frame.
- Nothing in `desktopMain` calls `SetWindowDisplayAffinity` or sets `WDA_EXCLUDEFROMCAPTURE`, `WS_EX_TOOLWINDOW` or `Window.Type`. The only capture-related code is `WindowsCaptureCompatibility` (`DISABLE_VULKAN_OBS_CAPTURE`).

**Cause.** Window capture (Windows.Graphics.Capture and the BitBlt/PrintWindow fallbacks Discord uses) captures one top-level HWND, including its child windows. It does not include other top-level windows, even owned ones **(general WGC behaviour, not tested against Discord specifically)**. So the shared stream gets the frame (Compose content plus the mpv child HWND) and misses the controls.

**Mitigations within the current architecture:**
- Cheap, user-side: share the screen or monitor instead of the window. Screen capture composites every window.
- Not cheap: turn the overlay into a child window of the frame. Layered child windows exist on Windows 8+, but AWT cannot create a `JWindow` as `WS_CHILD`. It would need native `SetParent` and style surgery on a per-pixel-alpha AWT window, and that Java2D path is unsupported **(unverified, high risk)**.
- If the overlay were a lightweight Swing component instead of a window, it would sit under the heavyweight mpv canvas (airspace) and could not show over the video.

Conclusion: no cheap code fix. A single-window composition (option A below) fixes this by construction.

## 3. Problems with the current design

1. **Airspace.** A heavyweight D3D11 child HWND sits under Compose. Compose cannot draw over it, which is why the UI had to move into a second top-level window. Every flash fix so far (`85660014a`, `f23a31e9d`, this branch) works around that split.
2. **Two-window choreography.**
   - The overlay must track owner moves, resizes, focus, minimize and fullscreen by hand (`windowBoundsListener`, `windowFocusListener`, `FullscreenWindowState`, `isPlayerWindowActive`).
   - It toggles opaque and transparent states and re-primes on every focus gain.
   - It breaks window capture (2.3). It is also the likely source of focus, z-order and alt-tab quirks (`1969552cf`).
3. **Software-rendered UI over 4K video.** The overlay uses `RenderSettings.SwingGraphics`: Skia renders on the CPU, then a full-window per-pixel-alpha `UpdateLayeredWindow` runs on every Compose frame. Animations (opening overlay pulse, seek bar) cost CPU in proportion to window size.
4. **Size and duplication.**
   - The backend file is about 4,700 lines.
   - Two panels repeat the mpv option set, `loadFile` (about 160 lines each), event reading, volume and seek.
   - The native panel still builds a full set of unused Swing controls (`DarkButton`, `ModernSliderUI`, popup menus) behind `showNativeControls=false`.
   - macOS has a third, separate player UI written in SwiftUI.
5. **EDT coupling.** mpv init, `loadfile`, header swaps and seeks run on the EDT under `mpvCallLock`, and the IO poll takes the same lock. Any slow mpv call stalls the UI, as 2.1 showed.
6. **Crash and lifecycle history.** Several commits deal with ordering problems: mpv shutdown before the HWND is destroyed (`6d0383aeb`), media-key callback lifetime (`84ff44a5e`), repeated next-episode clicks (`a85de0f9c`), and JVM crash dumps added to triage native crashes (`0012bad72`). Each is a symptom of JNA callbacks and native HWND lifetimes spread across Swing, Compose and mpv threads.
7. **Platform coverage.** Windows uses Compose UI in an overlay window. macOS uses a separate native window with SwiftUI controls and no shared Kino UI. Linux has nothing.

## 4. Options

Every option must keep HDR and DV, ASS and external subtitles, audio track selection, HTTP headers, torrent and debrid HTTP URLs, hardware decode, fullscreen, SMTC, seek, speed and audio delay.

### A. libmpv render API → texture composited by Compose/Skia (single window)

mpv renders through `mpv_render_context_render` into a GPU texture that Skia owns, and Compose draws it as a normal layer. The overlay window, the airspace problem and the macOS SwiftUI fork all go away.

- **Features.** Everything mpv does today stays: demux, decode, hwdec, libass, headers, `audio-add`, the libplacebo DV `vf`.
- **HDR.**
  - Tone-mapping to SDR works.
  - True HDR output (HDR10 or scRGB swapchain) would need Skiko to present an HDR swapchain, which it does not expose today **(unverified, likely a regression for HDR-display users)**. The current `wid` path gets HDR passthrough through `target-colorspace-hint`.
  - Mainline libmpv offers the render API for OpenGL and software. `vo=libmpv` uses the `gpu` renderer, not `gpu-next` **(unverified for current mpv versions; check `render.h` and release notes for D3D11 or Vulkan render API support)**.
- **Performance.**
  - Skiko on Windows defaults to Direct3D 12 (the logs show `renderApi=DIRECT3D`). An OpenGL FBO from mpv cannot be sampled by a D3D12 Skia context without interop (`WGL_NV_DX_interop2` targets D3D11) or a copy.
  - Two realistic variants:
    - **A1.** Force Skiko to OpenGL on Windows and share the GL context or texture with mpv (zero-copy). Cost: Skiko's GL backend on Windows is less battle-tested, and GL is weaker than D3D for vsync and VRR.
    - **A2.** mpv renders on its own GL context into a D3D11 shared texture through `WGL_NV_DX_interop2`; Skia imports it as a D3D texture. This needs native glue (a small C++ DLL, like `KinoMediaSession.cpp`) and Skiko APIs for wrapping external D3D textures **(unverified that Skiko exposes this)**.
  - 4K60 HDR→SDR through the render API is routine for mpv-based apps (IINA, Celluloid, mpv.net's renderer variants). The cost is mainly one extra full-screen composite.
- **Effort.**
  - High for the Windows interop. Medium for the Kotlin side: one `DesktopVideoLayer` composable plus one engine class replacing both panels.
  - The existing `EmbeddedWindowsPlayerPanel` already proves the render API, update callback and FBO plumbing. It only lacks a zero-copy handoff.
- **Platforms.** macOS: mpv render API OpenGL (deprecated but working) or Metal through MoltenVK patches already in `MPVKit`. Skiko on macOS uses Metal, so the same interop question applies **(unverified)**. Linux: Skiko OpenGL plus mpv GL is the easiest platform of the three.
- **Licensing.** Unchanged. libmpv is GPL/LGPL and the app is GPLv3.
- **Fixes.** White flash, stale frame, Discord capture, overlay focus and z-order bugs, and the duplicated macOS UI.

### B. Keep `wid` embedding, simplify the overlay

Keep the D3D11 child HWND, but cut down the overlay:
- move `loadFile` and init off the EDT,
- delete the Swing controls and the embedded panel,
- split the backend file,
- paint the canvas black (`sun.awt.noerasebackground=false`, explicit `FillRect` on `WM_ERASEBKGND`),
- keep the canvas hidden until mpv's first `playback-restart`.

- **Features.** Everything, including HDR passthrough and `gpu-next`. This is the best HDR quality available today.
- **Effort.** Low to medium.
- **Does not fix.** Airspace, Discord window capture, two-window focus quirks, software overlay rendering, or macOS divergence.

### C. VLC (vlcj, libVLC 3 or 4)

- **Licensing.** vlcj is GPLv3 and libVLC is LGPL, so it is compatible.
- **Features.**
  - ASS through libass: yes. Track selection: yes.
  - HTTP headers: libVLC 3 only supports `:http-user-agent` and `:http-referrer`, not arbitrary headers **(blocker for debrid and addon streams that need headers)**.
  - DV: no. HDR on Windows: D3D11 output with tone-mapping, weaker than libplacebo.
- **Embedding.** Either `wid` (same airspace problem as today) or `CallbackVideoSurface` (CPU copy of every frame; 4K60 HDR is not viable).
- **Verdict.** A regression on headers and DV with no structural gain.

### D. JavaFX Media

No MKV, HEVC, ASS or headers. Rejected.

### E. GStreamer (gst1-java-core)

- **Licensing.** LGPL plus plugins; the GPL plugins are acceptable.
- **Features.** Headers through `souphttpsrc extra-headers`, ASS through `assrender`, D3D11 decode and sinks. Dolby Vision: no.
- **Embedding.** `VideoOverlay` (a `wid`, same airspace problem) or appsink and GL sharing (similar effort to A, with a weaker subtitle and HDR stack).
- **Other costs.** Very large Windows packaging. Pipeline complexity moves into Kino.
- **Verdict.** More work than A for fewer features.

### F. FFmpeg-based custom player (JavaCV or bespoke)

You own A/V sync, buffering, seeking, the libass integration, HDR tone-mapping (you would reimplement libplacebo) and hwdec interop. This is years of work to reach mpv parity. Rejected.

### G. Native per-platform players (Media Foundation, AVPlayer)

- Media Foundation: no ASS, patchy MKV, HEVC needs a Store extension, no DV profile handling, header support is awkward.
- AVPlayer: no MKV and no ASS.
- Two codebases instead of one. Rejected.

### Summary

| | HDR/DV | ASS | Headers | 4K perf | Airspace and capture fixed | Effort | Platforms |
|---|---|---|---|---|---|---|---|
| A render API → Skia | DV yes; HDR tone-mapped (passthrough unverified) | yes | yes | good with zero-copy | **yes** | high | W/M/L |
| B wid + simpler overlay | **best** | yes | yes | best | no | low-med | W (M separate) |
| C vlcj | no DV | yes | **no** | wid ok / callback poor | only with callback | med | W/M/L |
| E GStreamer | no DV | yes | yes | ok | only with GL share | high | W/M/L |
| F FFmpeg custom | rebuild | rebuild | yes | unknown | yes | very high | W/M/L |
| G MF/AVPlayer | partial | no | partial | good | per-OS | very high | per-OS |

## 5. Recommendation

Stay on libmpv. The engine has no realistic competitor for DV, ASS and headers. The work is in the presentation layer:
1. Do **B now** to stop the bleeding.
2. Prototype **A** behind the existing `kino.windows.video-surface` switch.
3. Make A the default once it matches B on 4K HDR→SDR frame pacing.
4. Keep B as a selectable "HDR passthrough" surface until Skiko can present HDR.

A is the only option that removes the overlay window. That fixes window capture and the flash and focus bug class by construction, and it lets macOS and Linux share the Compose player UI.

### Phased plan

1. **Stabilize B** (days):
   - Split `WindowsMpvPlayerBackend.desktop.kt` into engine (JNA, options, events, poll), surface (canvas and overlay window), fullscreen, and diagnostics files.
   - Delete the Swing controls behind `showNativeControls`.
   - Move `loadfile` and header writes off the EDT.
   - Reveal the canvas on mpv `playback-restart` (event 21) rather than polling.
   - Add an audio-delay setter.
2. **Extract a platform-neutral `MpvEngine`** (about a week). One Kotlin class owns the mpv handle, options, event thread, property observation (replacing the 15-property poll with `mpv_observe_property`), load, header and audio-add sequencing, and the DV pipeline. Both panels and a future Compose surface consume it.
3. **Prototype A on Windows** (1–3 weeks):
   - Spike A1 first (`SKIKO_RENDER_API=OPENGL`, mpv render API into a GL texture wrapped as a Skia `Image`) to measure 4K60 pacing.
   - If that is not good enough, build A2 with a small D3D11/GL interop DLL.
   - Gate it behind `kino.windows.video-surface=compose`.
4. **Port A to Linux** (GL, simplest), then **macOS** (replace the SwiftUI window, reuse Compose controls). Retire `MPVKit/Sources/DesktopMPVBridge` and JOGL.
5. **Make A the default.** Keep B as an opt-in HDR passthrough mode.

### Biggest risks

- **HDR passthrough regression** with A. Skiko does not present HDR swapchains **(unverified)**. Mitigation: keep B as a mode.
- **Zero-copy texture interop between mpv and Skiko** on D3D12 or Metal may need native glue and unstable Skiko APIs **(unverified)**.
- **`gpu-next` not being available through the render API** (the render API may only expose the older `gpu` renderer) **(unverified)**. DV still works because it is a `vf`, but some libplacebo output-stage quality is lost.
- **Frame pacing.** Compose's frame clock would drive video presentation. That needs `mpv_render_context_report_swap` and careful vsync handling to avoid judder at 23.976 fps.

## 6. Owner verification for this branch

Discord window capture is not fixed by this branch (see 2.3).

1. Build and install the branch, then fully quit and relaunch Kino so the prewarm runs.
2. In `kino.log`, expect `windows mpv preload complete` within a few seconds of launch.
3. **First playback.** Start any stream.
   - Expected: no white window. The opening overlay with the backdrop appears straight away.
   - Log: the gap between `native panel addNotify peerReady` and `native panel canvas displayable=…` should be about 100 ms, not about 1.2 s.
4. **Stream change.** While playing, switch source (Sources panel), or use Next Episode.
   - Expected: no old-stream frames while the new one resolves.
   - Log: after `native video canvas visible=false`, the next `native video canvas visible=true` must come **after** `native panel loadFile …` and `mpv event id=6 … generation=N` for the new generation, not about 40 ms after the hide.
5. **New playback after exiting.** Back out to details, then start a different title. Expected: black or the opening overlay, then the new video, with no frame from the previous title. If a stale frame still appears here, note the timestamps of `native panel factory start` and `native video canvas visible=true` and report them. That would be the residual redirection-surface case in 2.2.

## 7. Performance pass

Date: 2026-10-27. Branch: `t3/optimize-video-playback`.

### Measurement setup

- `./gradlew :composeApp:runPlayerHarness` (`composeApp/src/desktopTest/.../player/harness/PlayerHarness.kt`) plays local files through `WindowsMpvPlayerBackend` without the app shell, account or single-instance lock. Properties: `kino.harness.surface` (`compose` or `native`), `kino.harness.media` (`;`-separated paths), `kino.harness.script` (`wait<s>`, `reset`, `cpu`, `seek<ms>`, `seekby<ms>`, `next`, `pause`, `play`, `shot<name>`, `exit`). `-Pkino.harness.jvmArgs=...` passes JVM flags. It enables `kino.player.perf-log` at 1 s.
- CPU is process CPU time over wall time, as % of one core, over a 40 s window after a 10 s warm-up. The window is 1280x800 and topmost.
- Test media were synthetic (testsrc2 plus noise): 1080p23.976 H.264, 4K23.976 HEVC SDR, 4K60 HEVC Main10 PQ (HDR10), 4K60 Dolby Vision Profile 8.1 (dovi_tool RPU, x265), 1080p Dolby Vision Profile 5, and a 1080p file with 3 audio and 20 subtitle tracks.
- The machine was busy with other work during the runs, so single runs vary by about ±10%.

### Results

Before is commit `44d7e7d98` with only the harness applied. After is this branch. Each pair of runs alternated between the two builds.

CPU, % of one core:

| Case | Before | After |
|---|---|---|
| Compose 1080p24 | 26.9, 24.8 | 23.6, 24.7 |
| Compose 4K24 SDR | 25.6, 24.3 | 22.4, 22.7 |
| Compose 4K60 Dolby Vision 8.1 | 126.3, 117.0 (5 and 0 drops) | 84.4, 81.6 (0 drops) |
| Compose 4K60 HDR10 | 122.3, 126.7 | 37.1, 37.6 |
| Compose 1080p24, 3 audio + 20 subtitle tracks | 23.1, 25.7 | 28.3, 23.3 |
| Same file, only the track refresh changed (1 s vs 10 s plus change-driven) | 27.7, 26.2, 25.0 | 23.2, 25.7, 22.0 |
| Native 1080p24 | 29.3 | 27.8 |
| Native 4K24 SDR | 46.9 | 45.4 |
| Native 4K60 Dolby Vision 8.1 (5 runs each) | 83.0 average, drops 27/33/54/57/85 | 82.7 average, drops 55/56/58/63/286 |

Native 4K60 Dolby Vision drops mostly at startup in both builds. The 286 came from one 15 s burst in an otherwise clean run.

Latency on the compose surface, one sequential run per build (load, then Next to the next file):

| Step | Before | After |
|---|---|---|
| Seek in 1080p24 | 32 ms, 67 ms | 26 ms, 67 ms |
| Next to 4K24 | 291 ms | 265 ms |
| Seek in 4K24 (long GOP) | 667 ms | 657 ms |
| Next to 4K60 Dolby Vision | 741 ms | 866 ms |
| Seek in Dolby Vision | 1186 ms | 1113 ms |
| Next from Dolby Vision to HDR10 | 2533 ms | 323 ms |
| Seek in HDR10 | 1126 ms | 141 ms |
| Next from HDR10 to 1080p24 | 1583 ms | 183 ms |
| First `loadFile` to first frame | 103 ms | 182 ms |

The first-load row is a single noisy sample; earlier runs of the new build measured 96-141 ms. Before, the HDR10 file inherited the previous file's Dolby Vision filter and copy decoder, which is why its seek and the following Next were slow.

### Dolby Vision and HDR on the compose surface

- The render API renderer (`vo=libmpv`, which is `vo_gpu`) does not apply Dolby Vision RPU reshaping. A Profile 5 sample rendered without the libplacebo filter shows completely wrong colors (IPT treated as YCbCr). This mpv build has no `gpu-next` backend for the render API. So the libplacebo `vf` is still required for Dolby Vision.
- A GPU-only Dolby Vision chain does not work with the bundled mpv/FFmpeg:
  - `lavfi=[hwupload=derive_device=vulkan,libplacebo=...]:hwdec_interop=cuda` fails with "A hardware device reference is required". mpv parses the graph before it attaches the interop device, and FFmpeg 8 `hwupload` checks for the device during parsing.
  - `hwmap=derive_device=vulkan` (with or without `reverse=1`) derives the device but fails with ENOSYS, because FFmpeg cannot map CUDA frames into Vulkan.
  - Both would need a patched mpv or FFmpeg runtime.
- What changed instead:
  - Copy decoding for the filter prefers `nvdec-copy` (CUDA DMA) over `auto-copy-safe`, which picked `d3d11va-copy` and its CPU readback.
  - On SDR texture surfaces (compose), libplacebo tone-maps Dolby Vision straight to 8-bit BT.709 `yuv420p`. That halves the download and the GL upload. Tone mapping moves from `vo_gpu` to libplacebo, so very bright saturated highlights look more saturated than before.
  - HDR10 no longer goes through libplacebo on SDR texture surfaces. `vo_gpu` already tone-maps it, so it stays on zero-copy `nvdec`. The output matches the old path to within about 9% brightness (peak detection differs).
  - The native surface keeps the previous filters, because it can pass HDR through to the display.
- Pipeline correctness:
  - `video-params/dolby-vision` does not exist in mpv, so Dolby Vision used to be detected only from `colormatrix=dolbyvision` after the first decoded frame. The poll only reconfigured at position 0 or while loading, so a Dolby Vision file could play its whole run on zero-copy `nvdec` with no reshaping. Detection now reads `current-tracks/video/dolby-vision-profile` from the container at `file-loaded`, so the filter and the copy decoder are in place before the first frame.
  - `hwdec` and `vf` are now set through `file-local-options/`. mpv restores them when the file ends. Previously the reset re-initialized the outgoing file's decoder, and the next file started with the previous file's filter. Next Episode from Dolby Vision to HDR10 took 1.6-2.5 s; it now takes about 0.3 s.
  - An unchanged filter is not written again, so SDR loads no longer rebuild an empty filter chain.

### About 330-470 ms present stalls about once a minute

Not caused by Kino. Evidence from harness runs with GC and safepoint logging and JFR (10 ms Java samples, 20 ms native samples):

- During a gap, mpv keeps rendering. The render thread publishes 8-12 frames that are never shown (`unshown`), and `frame-drop-count` stays 0.
- The EDT is idle (parked in `EventQueue`). The Skiko present thread `skiko-dispatcher-to-block-on` sits in `DwmFlush()` for the whole gap. The desktop compositor is not composing.
- No JVM safepoint near a gap is longer than 1 ms.
- Gaps cluster 1-3 s after each wall-clock minute (for example 08:36:01, 08:37:02, 08:38:01). Two separate harness JVMs that happened to run at the same time showed gaps at the same moments (08:43:45, 08:44:01, 08:45:01-08:45:08).
- The native path stalls too because its D3D11 presents also go through DWM.
- A likely trigger on the test machine is NVIDIA Overlay (5 processes running), which is known to hitch DWM periodically. To confirm, turn off the NVIDIA overlay or Instant Replay, then run the harness for 3 minutes and look for `maxPresentGapMs` above 100.

A separate, smaller per-minute hitch was in Kino's process. Skiko's `FrameWatcher` calls `System.gc()` every 30 s once more than 1000 frames have rendered (once a minute at 24 fps), and each call was a G1 Full GC: 28-35 ms in the harness, 64-93 ms in earlier app recordings. The app now runs with `-XX:+ExplicitGCInvokesConcurrent`, which turns that call into a concurrent cycle with 1-2 ms pauses. `-Dskiko.gc.auto=false` also removes the pauses, but it would stop Skiko from reclaiming native Skia memory, so it was not used.

### Startup and switching

- The source-load, play-state and resize effects wait for the engine through a state flow instead of polling every 50 ms. In one run, loading started 97 ms after the engine became ready.
- Dolby Vision first frame takes about 1.2-1.3 s because libplacebo creates a Vulkan device per file and the copy decoder starts. The old path showed the first frame sooner only because it skipped Dolby Vision processing.
- Rejected: turning off mpv's built-in scripts (`ytdl`, `load-stats-overlay`, `load-console`, `load-auto-profiles`, `load-select`, `load-positioning`, `load-commands`, `osc`, ...). Over 5 runs each, Next Episode averaged 188.8 ms stock and 195.2 ms lean.

### Cleanup

- Removed the embedded JOGL surface (`EmbeddedWindowsPlayerPanel`, `WindowsJoglNativeLoader`, the JOGL and gluegen dependencies, the repacked JOGL jar task and its ProGuard rules). `-Dkino.windows.video-surface=embedded` now falls back to the native surface.
- `mpv.dll` was a byte-identical copy of `libmpv-2.dll`. It is no longer downloaded, packaged (45 MB in the app jar) or extracted. The extraction cache also kept a 240 MB directory per build or update (115 directories, 26 GB, on the test machine). The loader now prunes directories unused for 7 days and skips any directory another running copy still has loaded.
- Track and audio diagnostics are re-read when `track-list/count`, `aid` or `sid` change, with a 10 s safety refresh, instead of every second. `sid` is now observed.
- Removed unused bindings and EDT hops for engine-only calls.

### Open items

- On 60 fps content, about a third of the frames mpv renders are replaced before Compose draws them (`unshown` about 20 per second). mpv renders with `BLOCK_FOR_TARGET_TIME` on its own clock, and Compose presents on vsync, so two frames can land inside one vsync. A small FIFO (two pending frames, one more ring slot) would trade one frame of latency for even pacing. 24 fps content is not affected.
- Dolby Vision cost is now dominated by the CUDA copy and the GL upload of the 8-bit result. Removing them needs a render API with libplacebo (`gpu-next`) or an FFmpeg that can import CUDA frames into Vulkan inside mpv's filter graph.
