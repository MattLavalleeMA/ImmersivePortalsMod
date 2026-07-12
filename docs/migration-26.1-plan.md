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
- `MixinSodiumOcclusionCuller.java`'s portal cave-culling override, re-anchored against Sodium 0.9.1's redesigned `OcclusionCuller` — [implemented, weave-time unverified](migration-26.1-plan-completed.md#mixinsodiumocclusioncullerjavas-portal-cave-culling-override-re-anchored--implemented-weave-time-unverified).
- Vanilla terrain-visibility override for portal rendering, re-anchored against `SectionOcclusionGraph`'s redesigned algorithm — [implemented, weave-time unverified](migration-26.1-plan-completed.md#vanilla-terrain-visibility-override-re-anchored-against-sectionocclusiongraph--implemented-weave-time-unverified).
- Clip-plane shader-source injection (`ShaderCodeTransformation`), re-anchored onto vanilla's `ShaderManager.loadShader` — [implemented (source-injection half only), weave-time unverified](migration-26.1-plan-completed.md#clip-plane-shader-source-injection-re-anchored-onto-shadermanagerloadshader--implemented-source-injection-half-only-weave-time-unverified).
- `./gradlew runClient` weave-time crash-fixing pass, round 1 (~24 Mixin fixes: renames, signature changes, and a few genuine-redesign items disabled with `require = 0`) — [implemented, launch still in progress](migration-26.1-plan-completed.md#gradlew-runclient-weave-time-crash-fixing-pass-round-1-24-mixin-fixes--implemented-launch-still-in-progress).
- `./gradlew runClient` weave-time crash-fixing pass, round 2 (~19 more Mixin fixes, including 2 deferred/lazy-loaded ones only surfacing on manual click-through) — [done, client reaches a working main menu](migration-26.1-plan-completed.md#gradlew-runclient-weave-time-crash-fixing-pass-round-2--client-now-reaches-the-main-menu).
- `./gradlew runClient` weave-time crash-fixing pass, round 3 (~10 more Mixin/data fixes surfacing from the Create-World-with-dim_stack and actual world-join workflow) — [fixes applied, 0 compile errors, NOT YET launch-verified — the last fix in this round postdates the last captured launch log](migration-26.1-plan-completed.md#gradlew-runclient-weave-time-crash-fixing-pass-round-3--player-joinworld-creation-workflow-fixes-applied-not-yet-launch-verified).

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

**Local Sodium/Iris jars are a real, exact-version-matching reference source, not
just GitHub's `main` branch.** The dependency jars Loom already resolved into
`~/.gradle/caches/modules-2/files-2.1/maven.modrinth/{sodium,iris}/...` are the
*exact* `mc26.1.2-0.9.1`/`1.11.2+26.1` builds this project depends on — decompilable
with the same Vineflower jar Loom itself uses
(`~/.gradle/caches/modules-2/files-2.1/org.vineflower/vineflower/*/vineflower-*.jar`,
`java -jar <vineflower.jar> <target.jar> <outDir>`), or inspectable class-by-class
with `inspect_class.py --jar <path-to-jar>` without a full decompile. This found real
leads GitHub's `main`-branch source search missed (`main` isn't guaranteed to match
what an exact pinned dependency version actually shipped):
- Iris ships (but doesn't enable by default) `MixinRenderTarget_StencilBufferTest`,
  an `@ModifyArgs` on `RenderTarget.createBuffers`'s call to
  `GpuDevice.createTexture(...)` that swaps the depth `TextureFormat` argument for
  `IrisPlatformHelpers.getInstance().mojangDepthFormat(DepthBufferFormat.DEPTH_STENCIL)`
  — i.e. Iris's own developers already prototyped "attach a combined depth+stencil
  texture to a vanilla `RenderTarget`" as an experiment. However, decompiling
  `IrisFabricHelpers.mojangDepthFormat` shows it currently returns `null` for every
  `*_STENCIL*` case — confirming there is **no sanctioned `TextureFormat` enum
  constant for a combined depth+stencil texture in this MC version at all**, so this
  particular path is a dead end as-is (Iris's own test can't actually run). Doesn't
  change the raw-LWJGL-stencil plan above, but does confirm attaching stencil via the
  *sanctioned* `GpuTexture`/`TextureFormat` layer isn't an option — a real stencil
  attachment would need a raw GL renderbuffer (`glRenderbufferStorage` with
  `GL_DEPTH24_STENCIL8`, attached via `glFramebufferRenderbuffer`) bolted directly
  onto the existing FBO obtained through `GlTexture.getFbo(...)`, bypassing
  `GpuTexture` entirely rather than trying to get one through it.
- Iris's own `IrisRenderSystem.blitFramebuffer(int source, int dest, ...)` is a thin
  wrapper around raw `glBlitFramebuffer` taking plain integer FBO ids (used by
  `DepthCopyStrategy` for depth-buffer copies) — i.e. Iris solves its own
  framebuffer-copy problem (the same one `IPIrisHelper`/`RendererUsingFrameBuffer`
  had) not through any high-level `CommandEncoder.copyTextureToTexture(...)`
  replacement API, but by dropping to the exact same raw-GL-against-raw-FBO-ids
  technique already planned for the stencil work above. This *is* a usable lead for
  the Iris-compatibility renderer stack rebuild below (previously assessed as having
  none).
- Sodium 0.9.1 confirms the async/tree-based occlusion pipeline (`CullTask`,
  `RayOcclusionSectionTree`, `SectionOcclusionGraph`-equivalent) described below, but
  also disproves the "no hook left" conclusion previously reached about it —
  `MixinSodiumOcclusionCuller.java`'s portal cave-culling override is now resolved,
  see "Completed work" above.

Remaining items in this cluster, in priority order (items with a concrete,
externally-sourced lead first; genuinely-open design work last):

1. Redesign or drop the custom clip-plane shader-uniform injection
   (`FrontClipping`/`IPGlobal.enableClippingMechanism`). **Why this can't just reuse the
   raw-OpenGL-bypass trick planned for stencil masking above:** stencil testing is a
   pure fixed-function per-fragment test — a raw `glStencilFunc`/`glEnable` call around
   a draw affects it regardless of what shader ran, no shader source involved. Clip
   planes are fundamentally different: `gl_ClipDistance[0]` is a **per-vertex shader
   output** — a raw `glEnable(GL_CLIP_DISTANCE0)` call does nothing unless the bound
   vertex shader itself is compiled to write to `gl_ClipDistance[0]`, which none of
   vanilla's shaders do. So this genuinely needs shader-source cooperation, not just a
   raw GL state call; the two items aren't solvable by the same technique.
   
   The GLSL-injection half is done — see "Completed work" above (`ShaderCodeTransformation`
   re-anchored onto `ShaderManager.loadShader` via `MixinShaderManager.java`).
   
   **Still open — confirmed via decompiled `RenderPipeline`/`RenderPipelines` source to
   be more than "just set the uniform value":** `RenderPipeline.Builder.withUniform(String,
   UniformType)` shows every vanilla pipeline explicitly declares its own uniform list
   in Java at registration time (`net.minecraft.client.renderer.RenderPipelines`), not
   just via GLSL text — so `iportal_ClippingEquation` also needs a matching
   `.withUniform(...)` declaration added to every affected pipeline (or to a shared
   `Builder`/"snippet" they all derive from, if one exists — not yet checked), before
   `RenderSystem.bindDefaultUniforms(RenderPass)` can actually bind a per-frame value
   to it. `FrontClipping.updateClippingEquationUniformForCurrentShader`/
   `unsetClippingUniform` remain stubbed no-ops pending this. Checked Distant Horizons'
   decompiled source for a clip-plane lead — no `ClipDistance`/`GL_CLIP`/clip-plane code
   anywhere in its tree, and its `render/blaze/` (post-rewrite) package has zero
   stencil-related calls either — no externally-sourced lead for the
   uniform-declaration piece specifically; still needs a real game launch to validate
   once implemented.
2. Rebuild the Iris-compatibility renderer stack (`ExperimentalIrisPortalRenderer`/
   `IrisPortalRenderer`/`IrisCompatibilityPortalRenderer`/`IPIrisHelper`), currently
   stubbed to no-ops. Previously checked IrisShaders/Iris's GitHub `main` branch for a
   `copyTextureToTexture` replacement and found nothing; decompiling the actual pinned
   `1.11.2+26.1` jar (see above) found a real lead instead —
   `IrisRenderSystem.blitFramebuffer(int, int, ...)`, a raw `glBlitFramebuffer` call
   against plain FBO ids, is Iris's own real solution to the same framebuffer-copy
   problem. The remaining work is obtaining `IPIrisHelper`'s own raw FBO ids the same
   way the stencil-masking plan above does
   (`GlTexture.getFbo(DirectStateAccess, GpuTexture)`, cast down from
   `RenderTarget.getColorTexture()`/`.getDepthTexture()`), then issuing the same raw
   blit call directly (no Iris API dependency needed, since the technique itself is
   just raw LWJGL/OpenGL, not an Iris-specific mechanism). Optional-dependency compat
   code (only matters with Iris installed), no crash risk since it's already fully
   stubbed — still needs a real game launch to verify, but no longer a "no lead"
   item.

Two further items in this cluster — the vanilla terrain-visibility override
(`VisibleSectionDiscovery`/`SectionOcclusionGraph`) and `MixinSodiumOcclusionCuller
.java`'s portal cave-culling override — are now done; see "Completed work" above.

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
- **Before decompiling vanilla source to figure out a renamed/removed Mixin
  target, search GitHub first (via the GitHub MCP tools, e.g. `search_code`) for
  real Fabric mods that already ship a Mixin against the same class/method on
  MC 26.1+** (Distant Horizons, Sodium, Iris, and other actively-maintained
  ecosystem mods are good candidates). A real mod's working Mixin against the
  same target is a faster, higher-confidence lead than reading raw decompiled
  source cold and guessing at the right re-anchor point — reserve decompiling
  (or `inspect_class.py`/`javap`) for confirming the exact signature once a
  candidate lead is found this way, or for when no cross-mod lead turns up at
  all.
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

1. **All compile errors are fixed.** The project compiles with **0 errors**
   (full changelog in
   [migration-26.1-plan-completed.md](migration-26.1-plan-completed.md)).
   Re-run `parse_compile_errors.py --run` at the start of the next session to
   confirm this hasn't regressed.
2. **Get the mod to actually launch in a dev environment** (`./gradlew
   runClient`) — **main menu reached successfully; a full create-world →
   join-world attempt is mid-progress, next fix unverified.** Since
   `imm_ptl.mixins.json` requires every mixin to apply, each launch attempt
   used to abort on the first weave-time crash and surface exactly one new
   issue (a rename/signature-change/removal invisible to `compileJava`); three
   rounds so far (~24 + ~19 + ~10 fixes) got the client from nothing all the
   way to a fully working, clickable title screen and partway through actually
   creating/joining a world — full detail in
   [migration-26.1-plan-completed.md](migration-26.1-plan-completed.md#gradlew-runclient-weave-time-crash-fixing-pass-round-1-24-mixin-fixes--implemented-launch-still-in-progress),
   [round 2](migration-26.1-plan-completed.md#gradlew-runclient-weave-time-crash-fixing-pass-round-2--client-now-reaches-the-main-menu),
   and
   [round 3](migration-26.1-plan-completed.md#gradlew-runclient-weave-time-crash-fixing-pass-round-3--player-joinworld-creation-workflow-fixes-applied-not-yet-launch-verified).

   **Important:** a clean main-menu launch does **not** mean every Mixin is
   fixed. Many mixin targets (any per-button `Screen` subclass, event-factory
   lambdas triggered on first fire, etc.) are only classloaded — and thus only
   weave-time-validated — when a specific menu/feature is actually used. Round
   2 already found two such **deferred** issues this way (`MixinSplashManager_CVB`'s
   immutable-list crash, which was actually the root cause of a fully
   black/blank-but-responsive title screen with **no logged Mixin error at
   all**; and `MixinCreateWorldScreen_CVB`'s constructor signature change,
   which only threw when clicking Singleplayer → Create New World). Round 3
   found several more of the same shape while clicking through Create World's
   dim_stack "More" tab and actually creating/joining a world (see the round 3
   changelog link above for full detail) — including a data-pack-level crash
   with **no Mixin-apply error at all** (`DimensionType`'s new required
   `has_ender_dragon_fight` field, causing world creation to hang silently at
   "Preparing for world creation...").

   **Immediate next action — pick up exactly here:** round 3's last fix
   (`MixinServerGamePacketListenerImpl`'s `isEntityCollidingWithAnythingNew`
   retarget, plus the `MixinServerEntity` dead-code removal made right after
   it) was made **after** the most recent captured `runClient` crash log, so it
   has not been launch-tested yet even though `parse_compile_errors.py --run`
   confirms 0 compile errors. Before doing anything else: re-run
   `./gradlew runClient`, retry Singleplayer → Create New World → actually
   entering the world, and check `run/logs/latest.log` for either a clean
   world-join or the next weave-time/runtime crash. Only once that's confirmed
   should click-through continue to other menus/features (Multiplayer,
   Options/video settings, Mod Menu's config screens, portal creation/use,
   etc.), fixing each new deferred crash as it's hit using the same established
   workflow — read the crash log for the failing mixin class/target symbol,
   extract the real current class shape from the sources jar
   (`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.1.2/*-sources.jar`,
   via `zipfile.ZipFile(jar).extract('path/To/Class.java', path='migration_tools/reports/decompiled_src2')`
   — check that directory first, many classes already extracted from prior
   fixes; Sodium/Iris jars decompile similarly from
   `~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/.../*.jar` or
   the relevant Sodium/Iris module jar under `~/.gradle/caches/modules-2/`,
   with Vineflower, or use `migration_tools/inspect_class.py --private
   [--jar <path>]` for a fast `javap`-based signature check first — and per the
   Tooling section above, check GitHub via the GitHub MCP tools for a real
   Fabric mod's equivalent Mixin *before* decompiling vanilla cold), fix/
   retarget the mixin (or disable with `require = 0` + a comment if it needs
   genuine redesign), confirm `python migration_tools/parse_compile_errors.py
   --run` still shows 0 errors, then relaunch and keep clicking through.

   Also worth a blind pre-emptive check for a black/blank screen with **no**
   Mixin-apply error logged: grep the log broadly for `Caught error`/`ERROR`
   (not just Mixin-specific patterns), since a swallowed exception in an
   unrelated subsystem (like the `SplashManager`/`has_ender_dragon_fight` cases
   above) can silently break something without ever showing as a Mixin crash.

   **Known remaining risk areas not yet reached by a launch/click-through
   attempt:** `qouteall.dimlib.*` (merged into this repo as a module, not
   touched at all yet); anything gated behind `enable_sodium`/`enable_iris`
   (both `true` in `gradle.properties`, so those mixins are being woven and
   could still crash, coverage incomplete); in-world/portal-specific mixins
   (world creation/join is the very thing currently blocked, see "Immediate
   next action" above — portal creation/use itself hasn't been reached yet);
   the server-side-only path (only ever launched via `runClient`, which loads
   an integrated server too — a dedicated `runServer` launch might surface
   different mixins in a different order).
3. **Once the client actually launches successfully:** follow the in-game
   testing checklist already written up below (fog, debug renderer, portal
   creation, chunk loading/ticket behavior, watch for stutter near portals from
   the Sodium/`SectionOcclusionGraph` fixes, etc.), and update this document's
   "Status"/compile-state sections plus
   [migration-26.1-plan-completed.md](migration-26.1-plan-completed.md)'s
   "weave-time unverified" annotations once each corresponding fix is confirmed
   actually working in-game, not just non-crashing.
4. Only after that baseline works should the remaining "Outstanding work" items
   be tackled, in the priority order listed there — they all need real in-game
   visual feedback to get right, not just static analysis.



