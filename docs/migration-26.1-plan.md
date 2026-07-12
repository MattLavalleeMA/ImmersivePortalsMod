# Migration plan: Minecraft 1.21.1 → 26.1.2

Canonical reference for migrating ImmersivePortalsMod from Minecraft 1.21.1 to 26.1.2.
This document reflects **current status and outstanding work only** — update it in
place as work progresses; don't append historical/dated entries here (session
history lives in chat/commit history, not this file). Every fix/rename/redesign
that's already been applied is logged in the companion document,
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md) — move an item
there (don't just mark it "done" here) as soon as it's finished, so this file stays a
short, current-only picture of what's left.

## Background

Minecraft switched to a new `year.release[.patch]` version scheme, and starting with
**26.1**, the game ships **unobfuscated** (see
[wiki.fabricmc.net/tutorial:mappings](https://wiki.fabricmc.net/tutorial:mappings)).
This is a bigger change than a normal version bump:

- Yarn and Parchment mappings both stopped being published after `1.21.11` — there is
  nothing to layer on top of an unobfuscated jar.
- Fabric Loom 1.14+ ships a **new plugin id** for non-obfuscated Minecraft versions:
  `net.fabricmc.fabric-loom` (the old `fabric-loom` / `net.fabricmc.fabric-loom-remap`
  id is only for legacy obfuscated versions, `<= 1.21.11`).
  Loom skips all remapping for the new id, which also means `mod*` Gradle
  configurations (`modImplementation`, `modApi`, ...) are unnecessary — plain
  `implementation`/`api`/`compileOnly`/`localRuntime` are used instead.
- Access wideners now declare `accessWidener v2 official` instead of `... named`
  (there's only one namespace now, no more named/intermediary/official split).
- Minecraft's rendering internals (`GlStateManager`, `VertexBuffer`, `RenderType`,
  `LightTexture`, `ShaderInstance`/`Program`/`Uniform`, ...) and several server-side
  internals (chunk ticket system, GUI rendering, entity rendering) were reworked
  and/or moved as part of ongoing engine rewrites across the many snapshots between
  1.21.1 and 26.1.

**Reference material available:**
- [Distant Horizons](https://gitlab.com/distant-horizons-team/distant-horizons)
  (local checkout: `C:\repos\distant-horizons\distant-horizons`) already ships a
  working 26.2 client with version-conditional (`#if MC_VER ...`) mixins/wrappers
  covering most of the same rendering-pipeline classes this mod needs. Its
  `MixinLightTexture.java`, `MixinLevelRenderer.java`, `DhScreen.java`/
  `MinecraftScreen.java` are good references for the GUI and lightmap rewrites.
  (Note: `grep_search` doesn't search outside the current workspace — use
  `Get-ChildItem -Recurse -Include *.java <path> | Select-String -Pattern ...` in a
  terminal instead.)
- `./gradlew genSourcesWithVineflower` works and produces a real decompiled 26.1.2
  source jar (`.gradle/loom-cache/minecraftMaven/net/minecraft/*/26.1.2/*-sources.jar`)
  — the best source of ground truth for anything not covered by Distant Horizons.

## Status

### Dependencies & build script

`./gradlew help` configures and resolves successfully against 26.1.2.

| Component | Version |
|---|---|
| Minecraft | **26.1.2** |
| Fabric Loader | **0.19.3** |
| Fabric API | **0.154.2+26.1.2** |
| Sodium | **0.9.1** |
| Iris | **1.11.2** |
| Cloth Config | **26.1.154** |
| Mod Menu | **18.0.0** |
| Fabric Loom | **1.17.13**, plugin id `net.fabricmc.fabric-loom` |

No `mappings` block, no Parchment repo, no `mod*` dependency configs (plain
`implementation`/`api`/`compileOnly` throughout), access widener uses `accessWidener
v2 official`. Full detail on every build-script change: see
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#build--dependencies--done-verified).

### Source code migration

Source-level renames and API-shape fixes have been applied across the codebase in
many rounds (package moves, chunk-ticket-system redesign, lightmap-rendering
redesign, `ValueInput`/`ValueOutput` save-data rewrite, and dozens of smaller
mechanical renames). Full detail on every one of them, file-by-file, is in
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md); the current
authoritative list of applied/pending renames is `migration_tools/renames.json`.

**Rendering pipeline (`GlStateManager`/`VertexBuffer`/`ShaderInstance`/`Program`/
`Uniform`/`GraphicsStatus`/`GlUtil`/`GlDebug`) — compiles clean, core portal-render
algorithm intentionally stubbed pending in-game-tested follow-up.** This is MC 26.1's
real "new rendering pipeline" rewrite: immediate-mode `GlStateManager`/`VertexBuffer`/
`ShaderInstance`/`Program`/`Uniform`/`Tesselator`+`BufferBuilder`+`BufferUploader` are
replaced by a `RenderPipeline`/`GpuBuffer`/`RenderPass`/`CommandEncoder` abstraction
(`com.mojang.blaze3d.pipeline`/`buffers`/`systems`) with **no dynamic stencil test
state at all** (`DepthStencilState` is baked into a `RenderPipeline` at build time) and
**no "currently bound framebuffer"** for implicit draws (`RenderTarget.bindWrite()`/
`.clear()`/`.frameBufferId`/`.getColorTextureId()` are all gone — only
`GpuTexture`/`GpuTextureView` objects remain, cleared via
`RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(...)`).
The mechanical fixes needed to get this cluster compiling clean (package moves,
ctor/field renames, projection-matrix capture retargeting) are all done — see
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#rendering-pipeline--mechanical-fixes).

**Still stubbed as no-ops (compiles, but non-functional), because they need
genuinely new design work and can't be verified without an actual game launch —
full detail on each in "Outstanding work" below:**
- The stencil-based portal view-area masking algorithm itself
  (`ViewAreaRenderer.renderPortalArea`/`buildPortalViewAreaTrianglesBuffer`,
  `MyRenderHelper`'s screen-triangle/framebuffer-blit helpers) — these drew custom
  triangles via `ShaderInstance`+`Tesselator`+`BufferUploader`, none of which exist
  anymore, and the new pipeline has no dynamic stencil state to increment/test
  against per nested portal layer in the first place.
- The custom clip-plane shader-uniform injection system
  (`FrontClipping.updateClippingEquationUniformForCurrentShader`/
  `unsetClippingUniform`) — the control-flow half (when/where to enable/disable
  clipping) is wired back up (see "Completed work" below), but the actual GPU-side
  effect depends entirely on this still-stubbed uniform mechanism. The legacy
  `GL_CLIP_PLANE0` path the control-flow half currently drives is likely *already*
  non-functional on the core GL profile Minecraft uses (`GL_CLIP_PLANE0` is
  compatibility-profile-only; the core-profile equivalent is
  `gl_ClipDistance[]`/`GL_CLIP_DISTANCE0`, an unrelated mechanism).
- The entire Iris-compatibility renderer stack (`ExperimentalIrisPortalRenderer`,
  `IrisPortalRenderer`, `IrisCompatibilityPortalRenderer`, `IPIrisHelper`) — these did
  raw multi-framebuffer stencil compositing via `RenderTarget.frameBufferId`/
  `bindWrite`/`unbindWrite`/`checkStatus`/`getColorTextureId`/`getDepthTextureId`, all
  gone. Reduced to no-op stubs preserving their public class/method contracts (so
  `PortalRenderer.switchToCorrectRenderer()`'s dispatch logic still compiles).
- `RendererUsingStencil`/`RendererUsingFrameBuffer`/`RendererDebug`/`GuiPortalRendering`
  — kept their control-flow structure (these aren't inherently untranslatable — the
  *bind/clear* calls were fixed or stubbed narrowly) but ultimately call into the
  stubbed drawing helpers above, so portal content won't actually render yet.
- The terrain-visibility override for portal rendering
  (`VisibleSectionDiscovery.discoverVisibleSections`, formerly hooked into the
  now-removed `LevelRenderer.setupRender`) — its structural replacement,
  `cullTerrain(Camera, Frustum, boolean)`, is built around a fundamentally different
  `SectionOcclusionGraph`-based algorithm with persistent per-frame traversal state,
  not the one-shot linear setup the old override can be ported onto by a simple
  re-anchor.
- `MixinSodiumOcclusionCuller.java`'s portal cave-culling override — Sodium's own
  occlusion culling pipeline is now asynchronous/tree-based (confirmed via Sodium's
  own exact-version-matching source); no single synchronous call site is left to
  redirect the culling start point on.
- Cloud-rendering optimization (`MixinLevelRenderer_Clouds.java`, `CloudContext.java`)
  — deleted outright rather than stubbed: `LevelRenderer` no longer has
  `cloudBuffer`/`starBuffer`/`skyBuffer`/`darkBuffer` fields at all (moved to a new
  dedicated `net.minecraft.client.renderer.CloudRenderer` class), so there's nothing
  left to shadow/optimize against. A non-critical, purely-performance optimization; can
  be reintroduced later against the new `CloudRenderer` if worthwhile.

**Also discovered (useful for any future work in this area):**
`RenderSystem.outputColorTextureOverride`/`outputDepthTextureOverride` (public static
mutable `GpuTextureView` fields) are vanilla's own sanctioned mechanism for redirecting
a render sub-pass into arbitrary texture views without an explicit `RenderPass` of its
own — confirmed via vanilla's own `LevelRenderer.renderLevel` using exactly this to
draw "gizmos" onto the main target after post-processing. This is the correct
replacement for the old "bind a different framebuffer, then call into vanilla's
rendering code and let it implicitly target whatever's bound" trick used throughout
`RendererUsingFrameBuffer`/the Iris renderers, and should be the starting point for
redesigning those once the stencil-masking algorithm itself is redesigned. Also: MC
now renders solid/translucent/particles/item-entities/weather into **separate render
targets** composited later (`LevelRenderer`'s `targets.translucent`/`.itemEntity`/
`.particles`/`.weather`, a `FrameGraphBuilder`/`GraphicsResourceAllocator` dependency
graph) — any redesigned masking algorithm needs to account for this multi-target
compositing, not assume a single shared framebuffer+stencil-buffer for the whole frame.

**Current compile state: 0 errors.** Run `python migration_tools/parse_compile_errors.py
--run` to confirm. Full error-count progression history is in
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#compile-error-count-progression-for-reference).

### Completed work

Every item below is finished; only a short pointer is kept here per this file's own
policy (see the top of this document) — follow the links for the full story.

- Build & dependencies — [done, verified](migration-26.1-plan-completed.md#build--dependencies--done-verified).
- Entity save-data rewrite (`ValueInput`/`ValueOutput`) + the mechanical-rename wave
  it surfaced — [done](migration-26.1-plan-completed.md#entity-save-data-rewrite-valueinputvalueoutput--done).
- DimLib merged into this repo as an in-repo module — [done](migration-26.1-plan-completed.md#dimlib-merged-into-this-repo-as-an-in-repo-module--done).
- GravityChanger support dropped entirely — [done](migration-26.1-plan-completed.md#gravitychanger-support-dropped-stubbed-out--done).
- `net.minecraft.gizmos` debug-drawing system — [investigated, not a fit, not adopted](migration-26.1-plan-completed.md#netminecraftgizmos-debug-drawing-system--investigated-not-adopted).
- `MixinFogRenderer.java`'s cross-dimension fog color swap, redesigned — [done](migration-26.1-plan-completed.md#mixinfogrendererjavas-cross-dimension-fog-color-swap-redesigned--done).
- `MixinFogRenderer_A_CVB.java`'s weave-time-broken targets, fixed — [done](migration-26.1-plan-completed.md#mixinfogrenderer_a_cvbjavas-weave-time-broken-targets-fixed--done).
- `MixinDebugRenderer.java`'s portal wand marker rendering, reconnected — [implemented, weave-time unverified](migration-26.1-plan-completed.md#mixindebugrendererjavas-portal-wand-marker-rendering-reconnected--implemented-weave-time-unverified).
- `MixinLevelRenderer.java`'s `redirectRenderEntity` weave-crash risk, fixed — [implemented, weave-time unverified](migration-26.1-plan-completed.md#mixinlevelrendererjavas-redirectrenderentity-weave-crash-risk-fixed--implemented-weave-time-unverified).
- `setupRender`-targeting hooks, re-verified and fixed — [done](migration-26.1-plan-completed.md#setuprender-targeting-hooks-re-verified-and-fixed--done).
- `MixinLevelRenderer.java`'s ~8 disabled hooks, re-anchored — [implemented, weave-time unverified](migration-26.1-plan-completed.md#mixinlevelrendererjavas-8-disabled-hooks-re-anchored--implemented-weave-time-unverified).
- Small leftover items (`fabric.mod.json`/`*.mixins.json` stale version metadata) — [done](migration-26.1-plan-completed.md#small-leftover-items-fixed--done).

## Blocking / external dependency issues

- `geckolib` test dependency (`enable_geckolib=false`, off by default) — not
  investigated, low priority. Not a compile error today.

## Outstanding work

The only work left is tracked below. (Numbering has gaps — items 1 and 3 from
earlier revisions of this doc were fully resolved and moved to
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md); the gaps are
kept so cross-references elsewhere in this section stay valid.)

### Priority order for next session(s)

**0 compile errors, every known weave-time-crash risk fixed** (full changelog in
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md)). What's left is
genuinely new design-and-test work (item 2, portal rendering algorithm redesign
below) plus one remaining weave-time/runtime issue (not a compile error) listed
immediately below — neither is a known bug left to "fix" so much as testing work
that needs a real game launch to complete.

**Weave-time-only / runtime-only issue still open (not a compile error):**

- **`SectionRenderDispatcher.uploadAllPendingUploads()` removed with no
  replacement found** (confirmed via javap — the whole per-section async-upload-
  future-pumping concept from the old chunk pipeline doesn't appear to exist in
  the new `RenderRegionCache`/`SectionCompiler`/`SectionMesh`-based one).
  `MyRenderHelper.earlyRemoteUpload()` (a workaround for non-actively-rendered
  dimensions' chunk-section uploads potentially stalling, gated behind the
  `IPCGlobal.earlyRemoteUpload` debug toggle) stubbed to a no-op for now — needs
  real in-game testing across dimensions to see whether the new pipeline still
  has the original problem at all.

### 2. Portal rendering algorithm redesign (runtime work, needs a real game launch)

Not a compile-error-driven item anymore — the whole cluster compiles clean (see
Status above). What's left is genuinely new design-and-test work that can't be done
by reading source alone.

**Chosen direction for the stencil-masking algorithm: bypass Blaze3D, go straight to
raw OpenGL via LWJGL.** Considered and rejected: mimicking Distant Horizons'
`render/openGl/` vs `render/blaze/` package split as a literal "OpenGL fallback mode."
That split is **not** a runtime Vulkan/OpenGL compatibility choice — it's a
compile-time, per-Minecraft-version selection in DH's multi-version codebase
(`#if MC_VER <= MC_1_21_10` picks their old-style raw-GL wrappers for pre-rewrite MC,
`#else` picks their `blaze/` `RenderPipeline`/`GpuBuffer`/`RenderPass` wrappers for
post-rewrite MC — confirmed by reading the actual `#if` guards in
`BlazeDhFogRenderer.java` etc.). For MC 26.x, DH itself only ever uses the `blaze/`
path; there is no live "OpenGL mode" a user can pick on this version, and there is
**no Vulkan backend to fall back from in the first place** — confirmed via `javap` on
the real 26.1.2 jar: `com.mojang.blaze3d.opengl.GlDevice implements
com.mojang.blaze3d.systems.GpuDevice`, and no `VulkanDevice`/alternate backend class
exists anywhere in the jar. Blaze3D's new pipeline abstraction (`RenderPipeline`/
`GpuBuffer`/`RenderPass`/`CommandEncoder`) is a **declarative API redesign that still
runs on the exact same OpenGL context underneath** — it removes ad-hoc immediate-mode
GL state manipulation, it does not swap graphics APIs.

The real, useful discovery: **stencil testing isn't merely relocated in the new
pipeline, it's gone entirely.** `com.mojang.blaze3d.pipeline.DepthStencilState` (the
record that configures a `RenderPipeline`'s depth/stencil behavior) now has **only**
`depthTest`/`writeDepth`/`depthBiasScaleFactor`/`depthBiasConstant` fields — zero
stencil-related fields, confirmed via `javap`. There is no sanctioned way to do
stencil testing through Blaze3D at all anymore in this version. Since `GlDevice`/
`GlCommandEncoder` still issue real, immediate OpenGL calls against a real GL context
(not deferred to another thread/backend), and since Blaze3D's own texture wrapper
exposes real handles (`GlTexture.glId()` for the raw GL texture id,
`GlTexture.getFbo(DirectStateAccess, GpuTexture)` for the raw FBO id), the plan is to:

- Obtain the raw GL texture/FBO ids for the relevant `RenderTarget`s via
  `GlTexture.glId()`/`.getFbo(...)` (cast down from the `GpuTexture`/`GpuTextureView`
  objects `RenderTarget.getColorTexture()`/`.getDepthTexture()` expose — same cast
  pattern already used for `Lightmap`'s GPU texture in the lightmap redesign).
- Issue raw `org.lwjgl.opengl.GL11`/`GL20` stencil calls directly
  (`glEnable(GL_STENCIL_TEST)`, `glStencilFunc`, `glStencilOp`, `glStencilMask`,
  `glClear(GL_STENCIL_BUFFER_BIT)`) around the portal-content draw calls, via Mixin
  injections at the appropriate points in `LevelRenderer.renderLevel`'s new
  `FrameGraphBuilder`-based structure (see the `lambda$addMainPass$0` re-anchoring
  work below) — bypassing `RenderPipeline`/`RenderPass` for this one feature only,
  the same "drop below the vanilla abstraction to do a raw GL trick vanilla itself
  doesn't expose" move Distant Horizons' `openGl/` wrappers make, just applied to
  route around a *removed* feature rather than to support an *old* MC version.
  `RenderSystem.outputColorTextureOverride`/`outputDepthTextureOverride` remain the
  right mechanism for redirecting which texture views a render sub-pass targets, per
  the existing plan; the raw GL stencil calls are additive to that, not a
  replacement for it.
- Known trade-offs, accepted deliberately rather than overlooked:
  - This **hard-locks portal stencil masking to the OpenGL backend** specifically.
    Acceptable today since OpenGL is the only backend that exists at all in this MC
    version; would need revisiting if Mojang ever ships an alternate backend behind
    `GpuDevice`.
  - Lower state-desync risk than it might first appear for stencil specifically,
    since `GlStateManager` no longer tracks any stencil-related cached state to
    conflict with (it was removed along with everything else stencil-shaped) — but
    depth/blend/color-mask state touched incidentally by the same raw calls still
    needs care not to desync from whatever a `RenderPipeline` assumes is currently
    bound.
  - Does **not** by itself solve the separate multi-target-compositing complication
    (solid/translucent/particle/item-entity/weather now render into separate
    `RenderTarget`s composited later via `FrameGraphBuilder`) — that remains an
    orthogonal design problem regardless of which draw-call layer performs the
    stencil test, and still needs to be accounted for so nested-portal masking stays
    consistent across all of those separately-composited layers.
  - Needs a real game launch to validate at all — LWJGL calls interleaved with
    Blaze3D's own immediate GL calls are unverifiable by `compileJava`/static
    analysis; this is exploratory/prototype work, not a confirmed-safe design yet.

Remaining items in this cluster, in priority order (items with a concrete,
externally-sourced lead first; genuinely-open design work last):

1. Redesign or drop the custom clip-plane shader-uniform injection
   (`FrontClipping`/`IPGlobal.enableClippingMechanism`) — candidate approach documented
   in Status above (`ShaderManager.loadShader`'s `IOUtils.toString(Reader)` +
   `RenderSystem.bindDefaultUniforms`). Has a specific technical plan already, not yet
   implemented.
2. Rebuild the Iris-compatibility renderer stack (`ExperimentalIrisPortalRenderer`/
   `IrisPortalRenderer`/`IrisCompatibilityPortalRenderer`/`IPIrisHelper`), currently
   stubbed to no-ops. Checked whether
   [IrisShaders/Iris](https://github.com/IrisShaders/Iris)'s own `26.1` branch (exact
   version match) already solves `IPIrisHelper.java`'s framebuffer-copy problem
   (`RenderTarget.frameBufferId`/`getColorTextureId()`/`getDepthTextureId()` all
   removed, real replacement is `CommandEncoder.copyTextureToTexture(...)`) — no match
   found for `copyTextureToTexture` anywhere in Iris's source, so unlike items above,
   there's no quick externally-sourced lead here. Optional-dependency compat code (only
   matters with Iris installed), no crash risk since it's already fully stubbed — lower
   priority than the two items above.
3. Redesign the terrain-visibility override for portal rendering
   (`VisibleSectionDiscovery.discoverVisibleSections`, formerly hooked into the
   now-removed `setupRender`) against `cullTerrain(Camera, Frustum, boolean)`'s
   `SectionOcclusionGraph`-based algorithm — a fundamentally different,
   persistent-per-frame-state design that the old one-shot override can't be
   ported onto by a simple re-anchor. Needs real design work, not a rename.
4. Redesign `MixinSodiumOcclusionCuller.java`'s portal cave-culling override against
   Sodium's new **asynchronous, tree-based** occlusion culling pipeline (confirmed via
   Sodium's own exact-version-matching source, tag `mc26.1.2-0.9.1` —
   `RenderSectionManager` now schedules `CullTask`s on a background thread; there's no
   single synchronous call site left to redirect the culling start point on). Not a
   quick rename fix like `MixinDebugRenderer.java`'s was — full detail in
   [migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#mixinsodiumocclusioncullerjava-investigated--not-a-quick-fix-real-redesign-needed).
   Also an optional-dependency compat item, already stubbed to vanilla-equivalent
   behavior (performance-only regression, not a crash) — same priority tier as Iris
   above.

## Tooling

Scripts live in `migration_tools/` (pure Python stdlib, no pip packages needed):

- **`parse_compile_errors.py`** — `--run` (invokes `gradlew compileJava`) or `--log
  <file>`. Groups errors by missing symbol, writes
  `migration_tools/reports/compile_errors.json`.
- **`find_candidates.py <symbol> [<symbol> ...]`** — searches the real 26.1.2
  Minecraft jar (auto-discovered from `.gradle/loom-cache`) for exact/substring/fuzzy
  name matches. `--jar <path>` to point at a different jar (e.g. a Fabric API
  submodule jar in `~/.gradle/caches/modules-2`).
- **`inspect_class.py <fqn> [--jar <path>] [--private]`** — runs `javap -s` on a
  single class extracted from a jar, to verify method/field signatures (generics
  included) without a full decompile.
- **Extracting single real decompiled source files** (cheaper than
  `genSourcesWithVineflower` for full method bodies/control flow, e.g. to check what a
  restructured method actually does): the merged-jar's *sibling* `-sources.jar` under
  the same `.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.1.2/`
  directory contains real per-class decompiled `.java` files — pull just what you need
  with `zipfile.ZipFile(jar).extract("net/minecraft/.../Foo.java", path="migration_tools/reports/decompiled_src")`.
  Much faster than running the Gradle task for a whole-project decompile.
- **`bulk_rename.py [--mapping renames.json] [--apply]`** — applies confirmed
  `renames.json` mappings across `src/`: rewrites the import line, and (if the
  simple class name also changed) bare usages in files that had that import, plus
  any fully-qualified inline usages. Dry-run by default.
- **`renames.json`** — the mapping file; `"TODO"` values are skipped by
  `bulk_rename.py`. This is the authoritative record of which renames are confirmed
  vs. still pending.
- `migration_tools/reports/` (including `decompiled/`) is gitignored — regenerate as
  needed rather than treating it as persistent state.

## Next steps

1. **All compile errors and known weave-time-crash risks are fixed.** The
   project compiles with **0 errors** (full changelog in
   [migration-26.1-plan-completed.md](migration-26.1-plan-completed.md)).
   Re-run `parse_compile_errors.py --run` at the start of the next session to
   confirm this hasn't regressed.
2. **Get the mod to actually launch in a dev environment** (`./gradlew
   runClient`) with portal rendering left in its current stubbed/no-op state —
   this is the single most valuable next step, since it's the prerequisite for
   verifying every Mixin re-anchor/retarget fixed so far (none of them are
   checkable by `compileJava`) and for surfacing anything still broken.
3. Only after that baseline works should the remaining "Outstanding work" items
   be tackled, in the priority order listed there — they all need real in-game
   visual feedback to get right, not just static analysis.


