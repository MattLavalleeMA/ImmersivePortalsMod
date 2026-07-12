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
Confirmed against **real decompiled MC 26.1.2 source** (extracted directly from the
sibling `*-sources.jar` next to the merged jar in `.gradle/loom-cache/minecraftMaven` —
cheaper than `genSourcesWithVineflower` for single classes, see Tooling section) and
Distant Horizons' `common/.../render/blaze/` wrapper package (which had to solve the
same "draw arbitrary vertex data with a custom pipeline" problem). The mechanical
fixes needed to get this cluster compiling clean (package moves, ctor/field
renames, projection-matrix capture retargeting) are all done — see
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#rendering-pipeline--mechanical-fixes).

**Deliberately stubbed as no-ops (compiles, but non-functional — see inline `TODO MC
26.1` comments at each site), because they have no direct API translation and can't be
verified without an actual game launch:**
- The stencil-based portal view-area masking algorithm itself
  (`ViewAreaRenderer.renderPortalArea`/`buildPortalViewAreaTrianglesBuffer`,
  `MyRenderHelper`'s screen-triangle/framebuffer-blit helpers) — these drew custom
  triangles via `ShaderInstance`+`Tesselator`+`BufferUploader`, none of which exist
  anymore, and the new pipeline has no dynamic stencil state to increment/test against
  per nested portal layer in the first place. Needs a genuinely different algorithm
  (not just a new draw-call API), likely modeled on DH's `Blaze` wrapper pattern
  (`RenderPipelineBuilderWrapper`/`RenderPassWrapper`/`BlazeVertexBufferWrapper`/
  `BlazeUniformBufferWrapper` — see that repo for a complete, working reference
  implementation of custom-pipeline immediate-style drawing).
- The custom clip-plane shader-uniform injection system
  (`FrontClipping.updateClippingEquationUniformForCurrentShader`/`unsetClippingUniform`,
  and the vanilla-side `IEShader`/`MixinShaderInstance`/`MixinProgram`/
  `MixinGameRenderer_Shaders`/`MixinRenderSystem_Clipping`/`MixinShaderInstanceForIris`
  mixins — all deleted, fully dead since `ShaderInstance`/`Program`/`Shader`/`Uniform`
  are gone). The old trick (redirect a shader-source-read call to inject a GLSL
  uniform, then `.set()` its value whenever a shader became active) has no equivalent:
  shaders are compiled centrally by `ShaderManager` into `RenderPipeline`s with a fixed,
  build-time-declared uniform list, and there's no "currently active shader" global hook
  anymore (each `RenderPass` binds uniforms explicitly via
  `setUniform(name, GpuBuffer)`). A real fix needs a new GLSL-injection point (found:
  `ShaderManager.loadShader`'s `IOUtils.toString(Reader)` call, verified via bytecode)
  plus a UBO-backed uniform declared consistently across every affected pipeline, plus
  a new binding point (candidate: `RenderSystem.bindDefaultUniforms(RenderPass)`, which
  vanilla itself uses to bind common uniforms to every `RenderPass`).
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
- `MixinLevelRenderer.java`: `LevelRenderer.renderLevel` was completely restructured
  around a `FrameGraphBuilder` (named `FramePass`es executed later via lambdas — most
  of the actual solid/translucent/entity draw logic now lives inside a **synthetic
  lambda method** `lambda$addMainPass$0`, confirmed to exist via `javap`, not
  sequentially in `renderLevel`'s own body like before). ~8 old injection points
  (before/after cutout|translucent rendering, before/after a render layer, before/after
  weather, frame-buffer-clearing redirect) targeted call sites
  (`DimensionSpecialEffects.constantAmbientLight`, `Sheets.translucentCullBlockSheet`,
  `LevelRenderer.renderSectionLayer`, `LevelRenderer.renderSnowAndRain`,
  `RenderSystem.clear(int,boolean)`) that no longer exist in that form or aren't called
  from `renderLevel`'s own body anymore — removed with `TODO` comments rather than
  guessed at. **Important:** invalid Mixin `@At(INVOKE)` string targets are NOT caught
  by `compileJava` (Mixin weaving happens at a separate, later step) — only
  javac-visible type errors (e.g. referencing the now-fully-removed `LightTexture`
  class, or `VertexBuffer`-typed `@Shadow` fields for `starBuffer`/`skyBuffer`/
  `darkBuffer`/`cloudBuffer`, which don't exist on `LevelRenderer` anymore at all — sky/
  cloud rendering moved to dedicated `SkyRenderer`/`CloudRenderer` classes) show up as
  compile errors. **This means a clean `compileJava` run is necessary but not
  sufficient — these ~8 hooks will only reveal themselves as broken at actual Mixin
  weave time (game launch), which needs a real launch to iterate on.** Same treatment
  applied to `MixinLevelRenderer_BeforeIris.java` (emptied — its one hook targeted a
  `"translucent"` string CONSTANT inside the old `renderLevel` body) and
  `MixinLevelRenderer_Optional.java` (removed one `ShaderInstance.apply()`-targeting
  hook that drove the now-stubbed clip-plane uniform). The still-valid-looking hooks in
  these files (targeting `allChanged()`, `setupRender(...)` methods that individually
  still type-check even though `setupRender` itself was confirmed **fully removed** from
  `LevelRenderer`, `renderSky`, etc.) were left as-is — same weave-time-only-verifiable
  caveat applies; `setupRender`'s replacement (visibility/frustum-culling override, a
  real gameplay feature — `VisibleSectionDiscovery`) likely now lives inside
  `extractLevel`/`prepareChunkRenders` given the new CPU-extract/GPU-render split, but
  this needs dedicated research.
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
--run` to confirm. The project now compiles clean — full error-count progression
history is in
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#compile-error-count-progression-for-reference).

The `ValueInput`/`ValueOutput` entity save-data rewrite and the large wave of small
mechanical renames it (and other API changes) surfaced are both done — full
file-by-file detail is in
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#entity-save-data-rewrite-valueinputvalueoutput--done).

**DimLib is now an in-repo module, fully migrated to 26.1.2 — done.** Rather than
maintaining DimLib (`iPortalTeam/DimLib`, archived/dead upstream, same team as this
mod) as a separate forked repo + external Gradle dependency, its source was
imported directly into this repo (`src/main/java/qouteall/dimlib`, merged
`dimlib.mixins.json`, entrypoints/mixins folded into this mod's own
`fabric.mod.json`) and migrated to 26.1.2 in place, reusing this repo's own
already-working build pipeline instead of standing up a separate one. Full detail:
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#dimlib-merged-into-this-repo-as-an-in-repo-module--done).

**GravityChanger support is dropped entirely — done.** Upstream
(`com.github.qouteall/GravityChanger`) is archived (read-only since Apr 2026),
last release targets mc1.20.4; this mod will simply not support it going
forward. `GravityChangerInterface.java` had its real-API-bound
`OnGravityChangerPresent` implementation removed, leaving only its existing
no-op default `Invoker` (gravity always down); the Gradle dependency and its
conditional activation in `IPModEntry.java` were removed too. This resolved the
last 25 compile errors — **the project now compiles with 0 errors.** Full
detail:
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#gravitychanger-support-dropped-stubbed-out--done).

**`MixinFogRenderer.java`'s cross-dimension fog color swap is redesigned — done.**
The old static-field-shadowing mixin (weave-time-broken — `FogRenderer` no
longer has static color fields to shadow at all) was removed and replaced with
a `FogRenderer.setupFog(Camera, int, DeltaTracker, float, ClientLevel)`-based
redesign that needs no static state at all. Full detail:
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#mixinfogrendererjavas-cross-dimension-fog-color-swap-redesigned--done).

**`MixinFogRenderer_A_CVB.java`'s weave-time-broken Mixin targets are fixed —
done.** Found while investigating the above: this separate mixin (alternate
dimensions' void-darkness override) still targeted the removed
`FogRenderer.setupColor(...)`/`Camera.getPosition()`, which would also have
hard-crashed Mixin weaving at game launch. Retargeted to the real replacements
(`FogRenderer.computeFogColor(...)`/`Camera.position()`) — no behavior change,
its handler body already used the new method names. Full detail:
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#mixinfogrenderer_a_cvbjavas-weave-time-broken-targets-fixed--done).

## Blocking / external dependency issues

- `geckolib` test dependency (`enable_geckolib=false`, off by default) — not
  investigated, low priority. Not a compile error today.

## Outstanding work

The only work left is tracked below. (Numbering has gaps — items 1 and 3 from
earlier revisions of this doc were fully resolved and moved to
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md); the gaps are
kept so cross-references elsewhere in this section stay valid.)

### Priority order for next session(s) (established after a full-landscape review)

As of the latest run: **0 compile errors.** Every compile-error cluster is now
resolved, including the DimLib migration (now an in-repo module — see
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#dimlib-merged-into-this-repo-as-an-in-repo-module--done)),
the `net.minecraft.gizmos` debug-drawing system investigation (confirmed not a
fit for this mod's own needs — see
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#netminecraftgizmos-debug-drawing-system--investigated-not-adopted)),
and dropping GravityChanger support entirely (see
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#gravitychanger-support-dropped-stubbed-out--done)).
Full changelog of every completed round is in
[migration-26.1-plan-completed.md](migration-26.1-plan-completed.md). **What's left
now is exclusively genuinely-new design-and-test work (item 2, portal rendering
algorithm redesign below), not a known bug/breakage to fix** — every
weave-time-only issue that was previously tracked here has also now been
resolved (see below).

**Weave-time-only / runtime-only issues still open (not compile errors, so not in
the count above — `MixinCamera.java`'s equivalent issue is already fixed, see the
completed-work log):**

- **`MixinDebugRenderer.java` (`portal_wand` package) targeted
  `DebugRenderer.render(PoseStack, MultiBufferSource.BufferSource, double, double,
  double)`, which is now fully removed** (confirmed via `inspect_class.py` —
  `DebugRenderer`'s debug-overlay drawing was migrated wholesale to the new
  declarative `net.minecraft.gizmos` API; `DebugRenderer` itself now only exposes
  `emitGizmos(Frustum, double, double, double, float)`, called during the
  CPU-only `extractLevel(...)` phase with no `PoseStack`/`MultiBufferSource`
  available at all). Since `@Inject` is `require`d by default, this would have
  hard-crashed Mixin weaving at game launch (not just silently no-op'd) — found
  and neutralized while investigating the `net.minecraft.gizmos` system (see
  [migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#netminecraftgizmos-debug-drawing-system--investigated-not-adopted)).
  The real `PoseStack`+`MultiBufferSource`+camera-position call site now lives
  inside `LevelRenderer.addMainPass`'s captured `FramePass` lambda (confirmed via
  decompiled source) — the same synthetic-lambda-method re-anchoring problem
  already tracked for `MixinLevelRenderer.java`'s ~8 disabled hooks below.
  Disabled (emptied, same precedent as `MixinLevelRenderer_BeforeIris.java`)
  pending a real game launch to re-anchor against the actual lambda method; the
  portal wand's marker-drawing logic itself (`PortalWandItem.clientRender`,
  `ClientPortalWandPortalCreation`/`Drag`/`Copy.render(...)`) is untouched and
  ready to be reconnected once a hook is found.
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

Remaining items in this cluster:
- Redesign or drop the custom clip-plane shader-uniform injection
  (`FrontClipping`/`IPGlobal.enableClippingMechanism`) — candidate approach documented
  in Status above (`ShaderManager.loadShader`'s `IOUtils.toString(Reader)` +
  `RenderSystem.bindDefaultUniforms`).
- Re-verify/re-anchor `MixinLevelRenderer.java`'s ~8 disabled injection points against
  the real `lambda$addMainPass$0` synthetic method (found via `javap`, not yet used) —
  requires an actual game launch since invalid Mixin targets aren't compile errors.
- Rebuild the Iris-compatibility renderer stack (`ExperimentalIrisPortalRenderer`/
  `IrisPortalRenderer`/`IrisCompatibilityPortalRenderer`/`IPIrisHelper`), currently
  stubbed to no-ops.
- Re-verify `setupRender`-targeting hooks in `MixinLevelRenderer.java`/
  `MixinLevelRenderer_Optional.java` — `setupRender` itself is confirmed gone from
  `LevelRenderer`; its replacement (chunk visibility/frustum culling override) likely
  moved into `extractLevel`/`prepareChunkRenders` (the new CPU-extract phase) but this
  needs dedicated research.

### 4. Small leftover items

- `src/main/resources/fabric.mod.json`:
  - `"minecraft": ["1.21", "1.21.1"]` → needs to become `26.1.x`.
  - `"fabric-api": ">=0.109.0"` → bump floor.
  - `"iris"`/`"sodium"` `breaks` ranges reference stale version numbers.
- All 5 `*.mixins.json` files declare `"compatibilityLevel": "JAVA_17"` — needs
  bumping; check what the Mixin version bundled with Loom 1.17.13 supports.

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

1. **All compile errors are done, including the DimLib migration (now an
   in-repo module) and dropping GravityChanger support entirely.** The project
   compiles with **0 errors.** Re-run `parse_compile_errors.py --run` at the
   start of the next session to confirm this hasn't regressed.
2. **All known weave-time-only issues are now fixed**, including `MixinCamera.java`/
   `MixinGameRenderer.java` (an earlier round), `MixinFogRenderer.java`'s
   cross-dimension fog-color-swap redesign, and `MixinFogRenderer_A_CVB.java`'s
   stale Mixin retarget (both this round, see
   [migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#mixinfogrendererjavas-cross-dimension-fog-color-swap-redesigned--done)
   and
   [migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#mixinfogrenderer_a_cvbjavas-weave-time-broken-targets-fixed--done)).
   Still needs real in-game testing to confirm the redesign actually looks right
   through portals, once a dev environment launch is possible (item 3 below).
3. **Get the mod to actually launch in a dev environment** (`./gradlew runClient`)
   with portal rendering left in its current stubbed/no-op state, to establish a
   working baseline and start surfacing any remaining Mixin-weave-time-only
   issues that `compileJava` cannot catch.
4. Only after that baseline works should the stencil-masking algorithm (direction
   already chosen, see item 2 above) and clip-plane uniform system be redesigned,
   since both need real in-game visual feedback to get right — reference Distant
   Horizons' `common/.../render/blaze/`
   wrapper package throughout.


