# Portal Rendering Pipeline & "Portal Content Invisible" Bug Trace

**Purpose of this document**: this mod's entire reason for existing is rendering
another dimension's content through a portal. That pipeline is large, spread across
many files, and has already needed to be re-looked-up multiple times per session
(burning context each time). This document is the persistent reference: section 1 is
the full expected pipeline flow (write-once, should stay accurate as the source of
truth), section 2 is the live bug-trace log for the current
"portal content invisible / bleeds outside its frame" issue.

**Status as of the end of the 2026-07-12 session (read this first)**: `
RendererUsingStencil` (section 1.1-1.4 below) is now **confirmed non-functional** --
MC 26.1's rendering pipeline rewrite removed stencil-buffer support entirely (no
combined depth+stencil `TextureFormat` exists anymore; see section 2.9). The mod has
been switched over to `RendererUsingFrameBuffer` (a render-to-texture + compositing
approach, section 1.7) as the default active renderer. That renderer's own occlusion-
query gate is *also* almost always false (section 2.12) -- current plan is to drop
that gate and switch to a Distant-Horizons-style manual depth-compare compositing
shader instead of relying on GPU hardware depth test/occlusion queries (section
2.13). **Nothing in this doc past section 2.8 has been implemented yet except where
explicitly marked "implemented".**

Keep this file up to date as the investigation progresses. Do not let findings live
only in chat/session memory -- session memory is cleared between conversations, this
file is not.

---

## 1. Expected rendering pipeline (how a portal's destination content is supposed to get drawn)

**This section (1.1-1.6) describes `RendererUsingStencil`, which is no longer the
active renderer** (see the status note above and section 2.9) -- kept here because
(a) the per-real-frame Mixin hook points in 1.1, the portal-selection logic in 1.2,
and the nested-render mechanics in 1.4/1.5 are all **shared** with the currently-
active `RendererUsingFrameBuffer` (only the masking technique in 1.3 differs -- see
1.7 for the replacement), and (b) `RendererUsingStencil`'s source is still in the
codebase (`IPCGlobal.rendererUsingStencil` still exists, just not selected by
default) in case stencil support ever returns to a future MC version. This was the
`IPGlobal.renderMode == normal` path without Iris shaders. There is also
`RendererUsingFrameBuffer` (now default, `compatibility` mode -- section 1.7),
`RendererDebug`, and Iris-specific renderers (`IrisPortalRenderer`,
`IrisCompatibilityPortalRenderer`, `ExperimentalIrisPortalRenderer`) that follow
similar but not identical flows -- not covered in detail here.


### 1.1 Per-real-frame entry points (Mixin hooks into vanilla `LevelRenderer`)

All hooks live in `src/main/java/qouteall/imm_ptl/core/mixin/client/render/MixinLevelRenderer.java`,
re-anchored onto MC 26.1's `FrameGraphBuilder`-based `renderLevel` (most of the actual
work happens inside synthetic lambda methods now, not `renderLevel`'s own body -- see
that file's own class-level comment for how each hook was re-anchored and why):

1. `redirectClearing` (`@Redirect` on `CommandEncoder.clearColorAndDepthTextures`, inside
   `lambda$renderLevel$0`) -- calls `IPCGlobal.renderer.replaceFrameBufferClearing()`.
   If it returns true, the normal GL clear is skipped (used so a nested portal-content
   render doesn't wipe the outer world's already-drawn pixels; see 1.4).
2. `onBeginRenderingEntitiesAndBlockEntities` (`@Inject HEAD` on `submitEntities`) --
   `CrossPortalEntityRenderer.onBeginRenderingEntitiesAndBlockEntities(...)`.
3. `onEndRenderingEntities` (`@Inject` after `FeatureRenderDispatcher.renderSolidFeatures()`
   inside `lambda$addMainPass$0`) -- `CrossPortalEntityRenderer.onEndRenderingEntitiesAndBlockEntities(...)`.
4. `onMyBeforeTranslucentRendering` (`@Inject` before the 2nd
   `ChunkSectionsToRender.renderGroup(...)` call = the TRANSLUCENT layer, inside
   `lambda$addMainPass$0`) -- **this is the actual portal-rendering entry point**:
   calls `IPCGlobal.renderer.onBeforeTranslucentRendering(modelView)`, i.e.
   `RendererUsingStencil.onBeforeTranslucentRendering` -> `doPortalRendering(modelView)`.
   Portals are drawn after opaque/cutout terrain+entities but before translucent
   content, so translucent stuff (water, glass, portal's own particles) composites
   correctly on top.
5. `onBeforeRenderingLayer` / `onAfterRenderingLayer` (`@Inject` around every
   `ChunkSectionsToRender.renderGroup` call) -- sets up/tears down `FrontClipping`
   (the near-plane clip-plane trick so terrain in front of the portal plane doesn't
   draw over the portal's contents) and mirror face-culling, only when
   `PortalRendering.isRendering()`.
6. `beforeRenderingWeather` / `afterRenderingWeather` -- same clipping idea for weather.

### 1.2 `PortalRenderer` / `RendererUsingStencil` -- deciding which portals to draw

`PortalRenderer.getPortalsToRender(modelView)` (abstract base, in
`src/main/java/qouteall/imm_ptl/core/render/renderer/PortalRenderer.java`):
- Collects all `GlobalPortalStorage` global portals + all `Portal` entities in
  `client.level.entitiesForRendering()`.
- Filters each one through `shouldSkipRenderingPortal(portal, frustumSupplier)`, which
  checks (in order): `isPortalValid()`, `isVisible()` (unless `maxPortalLayer == 0`),
  `RenderStates.getRenderedPortalNum() >= portalRenderLimit`, `isRoughlyVisibleTo`,
  `outerPortal.cannotRenderInMe` (when already inside a portal), distance vs
  `getRenderRange()`, frustum culling (`IPCGlobal.earlyFrustumCullingPortal`),
  `PortalRendering.isInvalidRecursionRendering`, and the
  `PORTAL_RENDERING_PREDICATE` event. **If a portal fails any of these it is silently
  excluded from the renderables list and nothing further in this pipeline runs for
  it at all this frame** -- see section 2, this is one of two places a portal's
  content can silently fail to render.
- Sorts survivors by distance to camera (closest first).

`RendererUsingStencil.renderPortals(modelView)` iterates that sorted list and calls
`doRenderPortal(portal, modelView)` for each.

### 1.3 `doRenderPortal` -- stencil masking + occlusion query + recursing into content

In `src/main/java/qouteall/imm_ptl/core/render/renderer/RendererUsingStencil.java`:

1. `shouldSkipRenderingInsideFuseViewPortal` early-out (fused/mirror-adjacent portal
   looking at its own reverse).
2. **Occlusion query gate**: `PortalRenderInfo.renderAndDecideVisibility(portal, () ->
   renderPortalViewAreaToStencil(portal, modelView))`. The lambda renders the portal's
   own quad/shape geometry (`ViewAreaRenderer.renderPortalArea`) into the stencil
   buffer (`GL_INCR` on stencil+depth pass), wrapped in a GPU occlusion query
   (`GL_ANY_SAMPLES_PASSED` via `GlQueryObject`/`QueryManager`). If the query says
   **no pixels of the portal shape actually passed the depth test** (i.e. it's fully
   behind other geometry from the camera's point of view), `anySamplePassed` is
   false and the function returns immediately **without ever rendering the portal's
   destination content** -- this is the *second* place content can silently fail to
   render (see section 2). Note `IPGlobal.offsetOcclusionQuery = true` by default,
   which makes this **predictive**: it can reuse *last frame's* query result instead
   of blocking on a fresh GPU readback every frame (see `PortalRenderInfo.
   renderAndDecideVisibility`'s two branches) -- this caching is a plausible source of
   "stuck invisible" bugs if a portal's very first evaluation is wrong.
3. If the query passed: `PortalRendering.pushPortalLayer(portal)` (marks
   "we are now inside this portal" -- `PortalRendering.isRendering()` becomes true,
   `getRenderingPortal()` returns this portal, for the remainder of this call).
4. `clearDepthOfThePortalViewArea` -- clears just the depth buffer (not color) within
   the portal's stencil-masked area to the far plane, so the destination content
   about to be drawn isn't depth-tested against the *current* dimension's geometry
   that happened to be behind the portal.
5. `setStencilStateForWorldRendering()` -- from here on, only draw where
   `stencil == thisPortalStencilValue` (i.e. inside this portal's mask, and not
   inside any nested portal drawn so far).
6. **`renderPortalContent(portal)`** (defined in the abstract `PortalRenderer` base
   class) -- this is where the actual nested-dimension render happens (section 1.4).
7. `PortalRendering.popPortalLayer()`, then (unless fuse-view)
   `restoreDepthOfPortalViewArea` (restores real depth values in the portal's area so
   later opaque/translucent draws depth-test against it correctly), then
   `clampStencilValue(outerPortalStencilValue)` (clamps the stencil buffer back down
   so an outer/sibling portal's later drawing isn't confused by this portal's stencil
   increments).

### 1.4 `renderPortalContent` -> `MyGameRenderer.renderWorldNew` -> `switchAndRenderTheWorld`

`PortalRenderer.renderPortalContent(portal)`:
- Looks up/loads the destination `ClientLevel` via `ClientWorldLoader.getWorld(portal.getDestDim())`.
- `PortalRendering.onBeginPortalWorldRendering()`.
- Builds a `WorldRenderInfo` (destination world, portal-transformed camera pos via
  `PortalRendering.getRenderingCameraPos()`, `doRenderHand=false`,
  `doRenderSky=!portal.isFuseView()`, computed render distance via
  `getPortalRenderDistance`) and calls `invokeWorldRendering(...)` ->
  `MyGameRenderer.renderWorldNew(worldRenderInfo, Runnable::run)`.
- `PortalRendering.onEndPortalWorldRendering()` afterwards.

`MyGameRenderer.renderWorldNew` pushes the `WorldRenderInfo` onto
`WorldRenderInfo`'s own stack, then calls the real workhorse,
`switchAndRenderTheWorld(...)` (in
`src/main/java/qouteall/imm_ptl/core/render/MyGameRenderer.java`), which:

1. Looks up the **persistent, per-dimension** `LevelRenderer` (`ClientWorldLoader.
   getWorldRenderer(newDimension)`) and `DimensionRenderHelper` (lightmap etc,
   `ClientWorldLoader.getDimensionRenderHelper`). These are cached/reused across
   frames per dimension (not recreated every portal render) -- see
   `ClientWorldLoader.java`'s `WORLD_RENDERER_MAP`/`getWorld`.
2. Saves off a large pile of outer-world state that's about to be swapped
   (old `ClientLevel`, old `LevelRenderer`, old `Lightmap`, old camera, old
   `RenderBuffers`, old `Frustum`, old chunk-info list, old transparency shader, old
   `SectionBufferBuilderPack`, the projection matrix via `RenderSystem.
   backupProjectionMatrix()`, the model-view matrix stack).
3. Creates a **brand new `Camera` object every call** (`new Camera()`), sets its level/
   focused-entity/fov-modifier, calls `camera.update(deltaTracker)` (computes
   rotation/fov/perspective from the focused entity -- wrong dimension at this point!)
   then **overrides the position** with the portal-transformed `thisTickCameraPos` via
   `portal_setPos` (a duck-typed Mixin accessor).
4. Swaps `client.level`, `client.levelRenderer`, lightmap, `noPhysics`, `doRenderHand`,
   particle engine's world, hit result.
5. Creates a **fresh `CameraRenderState`** and installs it into
   `client.gameRenderer.getGameRenderState().levelRenderState.cameraRenderState` (MC
   26.1 restructured `GameRenderer` into separate `update()`/`extract()`/`render()`
   phases -- this mod's nested render has to manually redo what the removed-for-us
   `extractCamera()` would have done, since `renderLevel()` itself just *reads* an
   already-populated `CameraRenderState`, it doesn't compute one).
6. `worldRenderer.update(newCamera)` -- **this is vanilla's own terrain-visibility
   pipeline**: `LevelRenderer.update(Camera)` -> `cullTerrain(...)` ->
   `SectionOcclusionGraph.update(...)` (BFS-based chunk visibility, partly async/
   cached) -> `compileSections(...)`. Also what triggers Sodium's own per-frame
   terrain-uniform setup (`SodiumWorldRenderer.setupTerrain`), which is why this call
   has to happen here at all (originally missing -- see
   `docs/migration-26.1-plan-completed.md`'s "Sodium setupTerrain crash" entry).
7. `ieGameRenderer.ip_setCamera(newCamera)`, then
   `newCamera.extractRenderState(cameraRenderState, partialTicks)` (populates
   position/rotation/projection/cull-frustum), then computes
   `cameraRenderState.fogType`/`fogData` for the destination dimension.
8. Optionally swaps in a secondary `RenderBuffers` (`useSecondaryEntityVertexConsumer`)
   so nested-portal vertex data doesn't collide with the outer world's in-flight
   `BufferSource`.
9. Switches Sodium's world-renderer context (`SodiumInterface.invoker.
   switchContextWithCurrentWorldRenderer`), resets the transparency shader, installs a
   fresh empty model-view `Matrix4fStack`, clears the Iris pipeline.
10. **`invokeWrapper.accept(() -> client.gameRenderer.renderLevel(deltaTracker))`** --
    this is the actual recursive re-entry into vanilla's full level-rendering routine
    (which is exactly what triggered all of section 1.1's Mixin hooks again, this
    time with `PortalRendering.isRendering()==true` and depth+1 -- so a portal visible
    from within another portal's content recurses through this whole pipeline again,
    up to `PortalRendering.getMaxPortalLayer()`).
11. After `renderLevel` returns: swaps everything back (reverse of steps 2-9), restores
    the projection matrix (`RenderSystem.restoreProjectionMatrix()`) and model-view
    stack, restores the Iris pipeline, re-`prepare`s the entity render dispatcher for
    the outer camera.

### 1.5 Chunk/section visibility inside the nested render (why distant destination terrain can vanish)

- **Vanilla path** (no Sodium, or Sodium's own culling disabled): `LevelRenderer.
  cullTerrain` -> `SectionOcclusionGraph.update(...)`. A **full** BFS-based
  recompute (`scheduleFullUpdate`) is scheduled onto `Util.backgroundExecutor()`
  (an actual background thread, not the render thread) whenever `invalidate()` was
  called (view-area reload, or camera crossing an 8-block grid cell -- see
  `cullTerrain`'s `camX/camY/camZ` check). Its BFS seed point comes from
  `initializeQueueForFullUpdate(Camera, Queue<Node>)`'s own
  `BlockPos cameraPosition = camera.blockPosition()` local.
  `MixinSectionOcclusionGraph.ip_redirectFullUpdateSeed` (`@ModifyVariable` on that
  local) tries to override this seed to a portal-shape-adjusted position via
  `PortalShape.getModifiedVisibleSectionIterationOrigin`, **but reads
  `PortalRendering.getRenderingPortal()`/`isRendering()` from inside the async
  background-thread closure** -- a real race: by the time the background task
  actually runs, the synchronous per-frame portal-render window that pushed that
  static `Stack<Portal>` state may already be over (or worse, could reflect a
  different/later portal's context). **This is a known, not-yet-fixed correctness
  bug** for the vanilla (non-Sodium) chunk-visibility path -- see section 2 for
  whether it's actually relevant to any currently-observed symptom.
- **Sodium path**: `OcclusionCuller.findVisible` (via `MixinSodiumOcclusionCuller`)
  has the same kind of origin-override hook, but critically it's **gated by
  `PortalRendering.shouldEnableSodiumCaveCulling()`**, which only returns true when
  camera distance to the portal < 5 blocks AND the portal's shape isn't
  `BoxPortalShape` (see the javadoc on that method in `PortalRendering.java` -- the
  original design intent was cave-culling-through-a-portal being ONLY a close-range
  FPS optimization, on the assumption vanilla's own bespoke BFS
  (`VisibleSectionDiscovery`) was doing the "real" job always). **Important, already
  confirmed**: `VisibleSectionDiscovery.discoverVisibleSections` is currently **dead
  code, never called anywhere** in the post-migration codebase (confirmed via
  full-repo search) -- `MyGameRenderer.switchAndRenderTheWorld` just lets vanilla's/
  Sodium's own real algorithms run unmodified beyond 5 blocks. Also,
  `getModifiedVisibleSectionIterationOrigin` is a **default-null** method on
  `PortalShape`, only overridden by `BoxPortalShape` -- **a normal rectangular nether
  portal never gets any origin adjustment at all**, at any distance.
- `IPGlobal.reducedPortalRendering` / `RenderStates.isLaggy` / portal scale can also
  shrink the effective render distance passed to the nested world (see
  `PortalRenderer.getPortalRenderDistance`/`getRenderRange`).

### 1.6 Key state-management classes (quick reference)

| Class | Role |
|---|---|
| `PortalRendering` | Tracks the current portal-nesting stack (`Stack<Portal> portalLayers`), `isRendering()`/`getRenderingPortal()`/`getPortalLayer()`. **Main-thread-only, not thread-safe.** |
| `WorldRenderInfo` | Stack of `WorldRenderInfo` (per-nested-render camera pos/dimension/render distance/flags), pushed/popped once per `renderWorldNew` call. |
| `RenderStates` | Misc per-frame globals: `basicProjectionMatrix` (captured every real `LevelRenderer.renderLevel` via `MixinGameRenderer.ip_captureBasicProjectionMatrix`, since MC 26.1 removed `RenderSystem.getProjectionMatrix()`), `frameIndex`, `isLaggy`, `portalRenderInfos`, `renderedDimensions`, etc. |
| `ClientWorldLoader` | Owns the per-dimension `ClientLevel`/`LevelRenderer`/`DimensionRenderHelper` maps so destination dimensions have persistent renderer state across frames. |
| `PortalRenderInfo` | Per-`Portal` occlusion-query visibility prediction/caching (`Visibility` inner class, keyed by `WorldRenderInfo.getRenderingDescription()`). |
| `VisibleSectionDiscovery` | **Currently dead code** -- pre-26.1 bespoke BFS chunk-visibility override, never called post-migration. |
| `FrontClipping` | Near-plane clip-plane management so outer-world geometry in front of the portal plane doesn't overdraw portal content. |
| `MyGameRenderer` | `switchAndRenderTheWorld` -- the nested-render swap/recurse/restore workhorse (section 1.4). |

### 1.7 `RendererUsingFrameBuffer` -- the currently-active renderer (render-to-texture + compositing)

Implemented this session as the replacement for the now-dead stencil masking (see
section 2.9 for why stencil had to be abandoned). Source:
`src/main/java/qouteall/imm_ptl/core/render/renderer/RendererUsingFrameBuffer.java`,
plus new helper classes `SecondaryFrameBuffer` (already existed, pre-migration
scaffolding) and `PositionTexturedGlProgram` (new this session). Selected by setting
`IPConfig.compatibilityRenderMode = true` (now the default), which makes
`PortalRenderer.switchToCorrectRenderer()` pick `IPCGlobal.rendererUsingFrameBuffer`.

**Core idea**: instead of masking the portal's content into the *same* framebuffer
the outer world already drew into (stencil's approach), render the portal's nested
content into a **separate offscreen `RenderTarget`** (`SecondaryFrameBuffer`, same
size as the main window), then **composite** it onto the main framebuffer by drawing
the portal's own screen-facing quad geometry with that offscreen texture sampled
per-fragment. The quad's own rasterized shape is the mask -- no stencil buffer or
clip planes needed. This works with Sodium/Iris/Distant Horizons with **zero
awareness on their part** -- they just render an ordinary scene into whatever
`Minecraft.getInstance().getMainRenderTarget()` currently is; swapping that field
(`ip_setFrameBuffer`, a duck-typed Mixin on `Minecraft`) transparently redirects them
into the offscreen buffer.

1. `prepareRendering()` -- `secondaryFrameBuffer.prepare()` (allocates/resizes the
   offscreen `TextureTarget` to match the main window), disables the (now-inert)
   stencil test, and explicitly downgrades the main render target's stencil flag
   (`IPPortingLibCompat.setIsStencilEnabled(..., false)` -- harmless no-op today
   since the underlying mixin is dead, kept in case that ever changes).
2. `doRenderPortal(portal, modelView)`:
   a. **Occlusion pre-check gate**: `testShouldRenderPortal(portal, modelView)` --
      draws the portal's own quad (`ViewAreaRenderer.renderPortalArea`, same mesh-
      generation code as the old stencil path, just without any stencil op) wrapped
      in a `GL_ANY_SAMPLES_PASSED` occlusion query (`QueryManager.
      renderAndGetDoesAnySamplePass`). **This is functionally the exact same gate
      that was implicated in section 2.3-2.6's unresolved "anySamplePassed always
      false" mystery for the stencil renderer** -- and empirically shows the same
      near-always-false behavior here too (section 2.12). If false, the portal is
      skipped entirely, same as before.
   b. If passed: `PortalRendering.pushPortalLayer(portal)`, swap `client.
      getMainRenderTarget()` to `secondaryFrameBuffer.fb` (`ip_setFrameBuffer`),
      clear its color+depth via `RenderSystem.getDevice().createCommandEncoder()
      .clearColorAndDepthTextures(...)` (the sanctioned MC 26.1 replacement for the
      removed `RenderTarget.bindWrite`/`GlStateManager._clearColor`/`_clearDepth`),
      then `renderPortalContent(portal)` -- **identical nested-render mechanics to
      section 1.4** (same `MyGameRenderer.switchAndRenderTheWorld`), just now
      drawing into the secondary buffer instead of the main one.
   c. Swap the main render target back, pop the portal layer, then
      `renderSecondBufferIntoMainBuffer(portal, modelView)` -- calls
      `MyRenderHelper.drawPortalAreaWithFramebuffer(portal, secondaryFrameBuffer.fb,
      modelView, RenderStates.basicProjectionMatrix)`, which is where the actual
      compositing draw happens (below). "Only support one-layer portal" is an
      explicit, pre-existing limitation of this renderer (`PortalRendering.
      isRendering()` early-out at the top of `doRenderPortal`) -- no recursive
      portal-in-portal support yet, unlike the stencil renderer.
3. **Compositing draw** (`PositionTexturedGlProgram`, new class, sibling to the
   existing `PositionColorGlProgram`): a minimal raw-GL (LWJGL) program that bypasses
   Blaze3D's `RenderPipeline` entirely (same rationale as `PositionColorGlProgram` --
   see that class's own javadoc). Draws the portal's quad geometry transformed by the
   **same outer-camera `modelView`/`projection`** used for the rest of the frame (so
   it lands in the same screen position as if it were solid geometry, and is subject
   to normal hardware depth test against whatever's already in the main depth
   buffer), and in the fragment shader samples `secondaryFrameBuffer`'s color texture
   at `gl_FragCoord.xy / ViewportSize` (i.e. "whatever color ended up at this exact
   screen pixel in the offscreen render, use that"). Relies on the GPU's **hardware**
   depth test to decide where to actually draw -- this is the piece section 2.13
   proposes replacing with a manual depth-compare (Distant-Horizons-style) instead.

**Known limitations of the current implementation** (all still open, see section 2's
end for prioritized next steps):
- Occlusion pre-check (`testShouldRenderPortal`) almost always returns false in
  practice (section 2.12) -- the portal fails to render on nearly every frame.
- No recursive portal-in-portal support (single `secondaryFrameBuffer`, reused
  sequentially, not a pool).
- Compositing relies on hardware depth test, which per section 2.6's still-relevant
  findings may be the actual root cause of the occlusion-query failures too.

---

## 2. Live bug trace: portal content invisible / bleeds outside its frame (multi-round investigation)

### 2.1 Symptom

User report + 3 screenshots (`run/screenshots/2026-07-12_12.19.{16,20,23}.png`),
nether -> overworld portal:
- Screenshot 1 (far): portal area shows the **current dimension (nether) bleeding
  through** -- fully transparent, no destination content at all.
- Screenshots 2-3 (progressively closer): sky and some nearby terrain show, but
  **chunks in the distance are missing** (only sky visible beyond a short range).

An earlier, separate bug ("sky rendering in front of the portal") was already fixed
before this trace started.

### 2.2 Confirmed from `run/logs/latest.log` (2026-07-12 session)

Grepped `[SODIUM-DIAG] enter switchAndRenderTheWorld` / `exit switchAndRenderTheWorld`
around the exact screenshot timestamps (found via `Saved screenshot as ...` log lines):

- At **all 3** screenshot timestamps, there is **no `enter switchAndRenderTheWorld`
  log line at all** -- only repeating `[SODIUM-DIAG] ... dim=minecraft:the_nether
  depth=0` frames (`setupTerrain`/`renderLayer`/`endFrame`, i.e. normal outer-world
  nether rendering, no nested render happening).
- ~13 seconds later (12:19:29), the exact same portal **does** start working:
  `enter switchAndRenderTheWorld from=minecraft:the_nether to=minecraft:overworld
  depth=0` / matching `exit` lines appear repeatedly.

**Conclusion**: this rules out the section-1.5 chunk-visibility/occlusion-culling
theories as the primary cause for this specific symptom -- `switchAndRenderTheWorld`
(the nested content render) is **never being invoked at all** during the 3
screenshots, i.e. the portal is being rejected by one of the two gates in section 1.2
(`shouldSkipRenderingPortal`) or 1.3 (`anySamplePassed` occlusion query) before
content rendering ever starts. Chunk visibility inside the nested render is a
real, separately-confirmed correctness concern (section 1.5) but is not what's
producing this particular symptom.

### 2.3 First diagnostic round (2026-07-12) -- CONFIRMED: occlusion query gate is the cause

Added logging (still in place):
1. `PortalRenderer.shouldSkipRenderingPortal` -- logs
   `[PORTAL-SKIP-DIAG] <discriminator> skip: <reason>` / `NOT skipped, distance=...`.
2. `RendererUsingStencil.doRenderPortal` -- logs
   `[PORTAL-SKIP-DIAG] <discriminator> anySamplePassed=<bool>` right after the
   occlusion-query decision.

User reproduced from **both sides** of the same portal pair (3 screenshots standing in
the overworld looking at the portal, 3 more standing in the nether looking at its
twin) and shared `run/logs/latest.log`. Correlated via `Saved screenshot as ...` line
numbers:

- **Overworld side** (portal `4b8aac4d-e2a6-...`, distance ~4.0 blocks, clearly
  filling a large part of the screen): `PORTAL-SKIP-DIAG ... NOT skipped, distance=
  4.024...` immediately followed every single frame by `PORTAL-SKIP-DIAG ...
  anySamplePassed=false`.
- **Nether side** (portal `02c25aab-6d60-...`, distance ~5.7 blocks): identical
  pattern -- `NOT skipped` then `anySamplePassed=false`, every frame.
- A second, unrelated portal entity is present in both dimensions' entity lists too
  (presumably the linked-pair's own additional portal-entity bookkeeping) but is
  correctly excluded earlier via `skip: not roughly visible to cameraPos=...` /
  `skip: frustum culled` -- not a symptom, working as intended.

**Confirmed root cause location**: `shouldSkipRenderingPortal` passes every check (the
portal *is* being correctly identified as one that should render) -- the failure is
100% inside the occlusion-query gate (section 1.3, step 2): `PortalRenderInfo.
renderAndDecideVisibility`'s wrapped draw (`renderPortalViewAreaToStencil` ->
`ViewAreaRenderer.renderPortalArea` -> `PositionColorGlProgram`) is producing **zero
passing samples** deterministically, for both portals in the pair, in both
dimensions, every single frame, regardless of camera being stationary or moving. This
is NOT the caching/staleness theory from section 2.5 (that would be intermittent/
one-sided) -- it's a hard, reproducible 100% failure, so the actual GPU draw itself
must not be producing any depth/stencil-test-passing fragments at all.

Ruled out by direct source inspection (not just theorizing):
- **Winding/back-face-culling mismatch, mathematically checked and ruled out**: the
  view-area quad is built from `AxisW`/`AxisH` via `RectangularPortalShape.
  renderViewAreaMesh` -> `ViewAreaRenderer.outputFullQuad`/`outputTriangle`. Portal.java
  defines `normal = axisW.cross(axisH)` (`Portal.java` ~line 503). Triangle winding
  analysis of `outputTriangle`'s parameter order confirms the quad's front face (the
  side that survives default `GL_BACK`/`GL_CCW` culling) is on the `+(AxisW x AxisH)
  == +normal` side. `RectangularPortalShape.roughTestVisibility` gates on
  `transformGlobalToLocal(cameraPos).z() > 0`, and `UnilateralPortalState.getNormal()`
  is confirmed (`animation/UnilateralPortalState.java`) to be exactly the local +Z
  axis. So "roughly visible" (already passed, per the logs) and "front-face survives
  culling" are the **same side** -- winding is actually correct, not the bug.
- No GL errors are logged anywhere near these frames (`PositionColorGlProgram.end()`
  already unconditionally calls `CHelper.doCheckGlError()` -- a pre-existing temp
  diagnostic -- and grepping the log for GL error text returns nothing), ruling out a
  shader-compile/link/invalid-operation failure.

### 2.4 Second diagnostic round added (2026-07-12), compiles clean, **awaiting a new repro**

Since winding was mathematically ruled out and there's no GL error, the remaining
candidates are: (a) zero geometry actually generated (e.g. width/height reading as 0
for this portal shape/instance), or (b) geometry generated correctly but rejected by
some *other* fixed-function GL state active at draw time (wrong depth func/depth
test disabled/depth write mask off in a way that fails `GL_ANY_SAMPLES_PASSED`,
stencil func/ref mismatch, etc). Added:

- `PositionColorGlProgram`: new `public static int lastVertexCount` and
  `public static String lastDrawStateDiag`, populated at the end of `end()` (right
  after the real `glDrawArrays` call, before unbinding) -- captures vertex count plus
  `GL_CULL_FACE`/`GL_CULL_FACE_MODE`/`GL_FRONT_FACE`/`GL_DEPTH_TEST`/`GL_DEPTH_FUNC`/
  `GL_DEPTH_WRITEMASK`/`GL_STENCIL_TEST`/`GL_STENCIL_FUNC`/`GL_STENCIL_REF`/
  `GL_STENCIL_VALUE_MASK`/`GL_COLOR_WRITEMASK` at the moment the mask draw executed.
- `RendererUsingStencil.doRenderPortal`'s existing `anySamplePassed` log line now also
  appends `vertexCount=<n> <lastDrawStateDiag>`.

Reading these values from the next repro will directly distinguish (a) vs (b):
`vertexCount=0` means the mesh-generation side is broken (look at
`RectangularPortalShape.renderViewAreaMesh`/portal width-height/`getThisSideState()`);
a nonzero count with e.g. `depthTest=false` or an unexpected `depthFunc`/`stencilFunc`
value means the fixed-function GL state at draw time is wrong (look at what runs
between `prepareRendering()`'s stencil setup and this draw -- possibly something
Sodium-side clobbering GL state that vanilla's own draws don't normally touch, since
this is the first hand-rolled raw-GL draw call added this migration to run in that
exact spot in the frame).

### 2.5 Ruled-out / deprioritized (from the first investigation pass, kept for
posterity so it isn't re-litigated)

If a future repro somehow shows `shouldSkipRenderingPortal` rejecting a portal that
should render, or shows the occlusion-query gate passing but content still wrong,
these are the previously-explored (and currently deprioritized) theories:

- **Async `SectionOcclusionGraph` seed-origin race** (section 1.5) -- a real
  thread-safety bug in `MixinSectionOcclusionGraph.ip_redirectFullUpdateSeed` (reads
  the non-thread-safe `PortalRendering` static state from a background-executor
  thread), but only matters for the non-Sodium vanilla chunk-visibility path, and
  doesn't explain "content never renders at all" (section 2.3 shows the bug is
  upstream of any chunk-visibility code running at all).
- **`PortalRenderInfo` occlusion-query prediction/caching staleness** (`IPGlobal.
  offsetOcclusionQuery = true`'s `decision = lastFrameVisible` reuse path) -- would
  produce *intermittent or one-sided* failures depending on history, not the clean
  100%-repro-rate, both-sides failure actually observed; deprioritized in favor of
  2.3's finding that the underlying per-frame query itself always reports zero
  samples.
- `PortalShape.getModifiedVisibleSectionIterationOrigin` origin-adjustment (only
  overridden by `BoxPortalShape`, default-null otherwise) -- confirmed irrelevant to
  a normal rectangular nether portal.

### 2.6 Rounds 3-7: deeper diagnostics on the occlusion-query draw (2026-07-12)

Chased the "zero passing samples" mystery from 2.3/2.4 much further, each round
adding a new targeted diagnostic and re-testing live. Summary of what was measured
and ruled out, in order:

- **Round 3-4 (FBO binding + stencil-buffer readback)**: added logging of
  `GL_FRAMEBUFFER_BINDING` and a `glReadPixels(GL_STENCIL_INDEX)` readback both in
  `RendererUsingStencil.prepareRendering()` (right after the once-per-frame
  `glClear(GL_STENCIL_BUFFER_BIT)`) and in `renderPortalViewAreaToStencil` (right
  before the actual masking draw). Result: **identical FBO (id 0) in both places**,
  and the stencil buffer correctly reads back `0` matching `outerPortalStencilValue=0`
  right before the draw. **Ruled out**: a framebuffer-binding mismatch leaving the
  real target's stencil buffer stale/uncleared (a theory raised by the still-open
  `// TODO MC 26.1: RenderTarget.bindWrite no longer exists` comment in
  `prepareRendering()` -- that TODO is still real/unresolved as a code-quality issue,
  but it is NOT the cause of this particular symptom).
- **Round 5 (GL error + immediate query result at the query boundary)**: added
  `glGetError()` checks around `glBeginQuery`/`glEndQuery` in
  `GlQueryObject.performQuery()` (previously had zero error checking) plus an
  immediate blocking peek at `GL_QUERY_RESULT` right there. Result: **no GL errors
  anywhere, and the immediate result is genuinely `0`** -- ruled out a silently-failed
  query call (e.g. from illegal concurrent same-target queries) and ruled out a bug
  in `PortalRenderInfo`'s later caching/prediction logic (the raw query itself, at the
  earliest possible read point, already says zero).
- **Round 6-7 (manual clip-space re-transform of the mask geometry)**: added logic in
  `PositionColorGlProgram.end()` to manually re-transform every vertex of the just-
  drawn batch through the exact `modelView`/`projection` matrices used (mirroring the
  vertex shader), logging NDC x/y/z and an `onScreen` flag per vertex. Result across
  ~1767 samples: **`anyOnScreen=true` in 1595 (90%) of frames** -- e.g. a representative
  logged sample has 2 corners of the quad legitimately on-screen (`ndc=(-0.92,-0.69,
  0.93)`, `onScreen=true`) with valid clip-space `w`, while the other 2 corners are
  off the top of the screen (`ndc y ~2.3`, expected/normal for a large close-up quad).
  **This rules out "the geometry never touches the visible viewport"** as the (sole)
  explanation -- a meaningful portion of the quad, with valid non-degenerate clip
  coordinates, does land on-screen, yet the occlusion query still reports zero
  passing samples every time.
- Also double-checked and **ruled out a reversed-Z depth-convention mismatch**: read
  vanilla's own `RenderPipelines.java` (via the decompiled source at
  `migration_tools/reports/decompiled_mc_lr/...`) and confirmed vanilla consistently
  uses `CompareOp.LESS_THAN_OR_EQUAL` (standard, non-reversed depth) everywhere,
  matching what this code already sets (`GL_LEQUAL`/515) -- not a convention mismatch.
  Also manually verified via the standard perspective-projection depth formula that an
  NDC Z of ~0.93 for an object only 5-6 blocks from the camera is completely normal
  (with a typical near=0.05/far=large matrix, depth precision is heavily compressed
  near the far plane -- even close objects map to NDC Z values near +1) -- NOT itself
  evidence of a wrong projection matrix, a theory that looked suspicious at first
  glance but doesn't hold up under the actual math.

**Remaining unconfirmed leading theory**: a genuine depth-test failure specifically
at the on-screen portion of the quad -- i.e. whatever is already in the depth buffer
directly behind the portal in the *current* dimension (e.g. nearby solid terrain
just past the portal opening) may legitimately be closer than the mask quad's own
computed depth, OR the quad's camera-relative world position
(`portal.getOriginPos().subtract(cameraPos)` in `ViewAreaRenderer.
buildPortalViewAreaTrianglesBuffer`) doesn't precisely coincide with the portal's
actual rendered location, causing a depth mismatch against the correctly-rendered
portal frame around it. **Not yet confirmed** -- needs a depth-buffer
readback at one of the *actually on-screen* vertices (not just the screen-center
pixel used in earlier rounds) compared directly against that vertex's own NDC Z.

### 2.7 Round 8: a REAL, CONFIRMED bug found and fixed (crash, not the invisibility issue)

While chasing the above, a repeated-portal-traversal **crash** occurred:
`IllegalStateException: Global terrain uniforms have not been updated` (Sodium,
`UniformBufferManager.getUniformBuffer` <- `SodiumWorldRenderer.renderLayer`), during
normal **non-nested** outer-world rendering -- the same crash class as an earlier,
previously-"fixed" issue (see `docs/migration-26.1-plan-completed.md`'s "Sodium
setupTerrain crash" entry), regressed.

**Root cause (confirmed via direct source reading, not speculation)**: in
`MyGameRenderer.switchAndRenderTheWorld`, the Sodium render-section-manager context
creation+switch (`SodiumInterface.invoker.createNewContext(renderDistance)` +
`switchContextWithCurrentWorldRenderer(newSodiumContext)`) was happening **after**
`worldRenderer.update(newCamera)` -- but `.update()` is exactly what triggers
Sodium's own per-frame terrain-uniform population for whatever context is *currently*
active. Since `client.levelRenderer` was already switched to the destination
dimension earlier in the function, `.update()` was populating uniforms for the
*previous* (stale) Sodium context, and only immediately afterward did the code swap
in a **brand-new, never-populated** context (`scheduleTerrainUpdate()` only marks it
dirty for a future update, it does not synchronously run one) right before
`renderLevel()` tried to draw with it.

(Also verified, to avoid a false fix: `MixinSodiumRenderSectionManager.
ip_swapContext` is a genuine bidirectional swap of `renderLists`/`renderDistance`
between the manager and the passed context object using temp variables -- so calling
`switchContextWithCurrentWorldRenderer(newSodiumContext)` twice with the same
variable, once to swap the outer context out and once to swap it back in after
rendering, is itself a valid, correct push/pop pattern, not a bug.)

**Fix applied** (compiles clean; not yet verified live -- awaiting the next repro):
moved the context-creation-and-switch block to immediately *before*
`worldRenderer.update(newCamera)`, so `.update()`'s `cullTerrain()`/`setupTerrain()`
call now runs against the same freshly-swapped-in context that's about to actually be
rendered with. The restore call at the end of the function is unchanged.

### 2.8 Round 9: crash fix verified live; portal content renders for the first time (but unmasked/bleeding)

Relaunched and repeatedly traversed the same portal pair. The 2.7 crash fix held (no
repeat of that specific crash during this round). More importantly: **for the first
time, actual destination-dimension content was visually confirmed on screen** through
the portal (screenshots showed real terrain/sky from the other dimension, not just
transparent/current-dimension bleed-through) -- meaning the occlusion query
(section 2.3-2.6) is not in fact *always* false, just false the overwhelming
majority of the time (consistent with the ~90% "onScreen" but 0% "anySamplePassed"
disconnect already measured in 2.6).

However, the content that did render was **not correctly masked to the portal's
shape** -- screenshots showed destination-dimension terrain bleeding out past the
portal frame onto the surrounding current-dimension blocks (e.g. overworld birch
trees visible outside a nether portal's obsidian frame). This became the new primary
symptom to chase.

### 2.9 Root cause CONFIRMED: MC 26.1 removed stencil-buffer support entirely from the rendering pipeline

Traced the mask-bleeding bug all the way to its true source using the exact-version
Sodium source (`C:\repos\Sodium`, confirmed 26.1.2-matching) plus decompiling the
real `RenderTarget` class:

1. Added a live `GL_STENCIL_BITS` readback directly in `RendererUsingStencil.
   prepareRendering()` (runs every frame, no portal needed). **Result:
   `stencilBitsAtClear=0` on every single frame of an entire play session** -- the
   main render target has zero stencil bits, always, on both the mask-writing side
   (FBO where our own code drew) and the terrain-drawing side (a different FBO,
   confirmed via a separate per-pass diagnostic added to `MixinSodiumWorldRenderer.
   onRenderLayerReturn` logging `pass.isTranslucent()`/`GL_FRAMEBUFFER_BINDING`/
   `GL_STENCIL_BITS` -- both solid *and* translucent terrain passes showed
   `stencilBits=0` on their respective FBOs too).
2. Read `MixinRenderTarget.java`'s own code comments: its two `@ModifyArgs` mixins
   (`modifyTexImage2D`, `modifyFrameBufferTexture2D`) that used to swap the
   depth-only texture format for a combined depth+stencil one are **both marked
   `require = 0`** (silently disabled) with an explicit comment: *"MC 26.1:
   RenderTarget.createBuffers no longer calls GlStateManager._texImage2D at all
   (confirmed at weave time -- 0 matching targets found)... this whole mechanism is
   already tracked as needing a full redesign, not a rename."*
3. Decompiled the real `RenderTarget.createBuffers()` (via the vineflower workflow,
   see `/memories/repo/reference-jar-decompiling.md`): it now creates its depth
   texture via `device.createTexture(..., TextureFormat.DEPTH32, ...)` -- and
   `TextureFormat`'s only depth-related enum value, confirmed via `javap`, is
   `DEPTH32`. **There is no combined depth+stencil `TextureFormat` constant anywhere
   in this MC version's texture API at all.**
4. Cross-checked against Iris's own source (also present in the resolved dependency
   jar): Iris ships (but doesn't enable) `MixinRenderTarget_StencilBufferTest`, an
   experiment at exactly the same idea (swap the depth `TextureFormat` for a
   stencil-having one via `IrisPlatformHelpers.mojangDepthFormat(DepthBufferFormat.
   DEPTH_STENCIL)`) -- decompiling `IrisFabricHelpers.mojangDepthFormat` shows it
   returns `null` for every `*_STENCIL*` case, i.e. **Iris's own developers already
   hit this exact same wall and their prototype can't run either.**

**Conclusion**: this is not a "wrong mixin target" bug fixable with a re-anchor --
Mojang's new `GpuDevice`/`GpuTexture` texture abstraction has no sanctioned way to
attach a stencil buffer to a render target at all in this MC version. The entire
stencil-based masking algorithm (`RendererUsingStencil`) has been silently drawing
against a stencil-less framebuffer since the 26.1 migration -- every
`GL_STENCIL_TEST`/`glStencilFunc` call in that code path has been a no-op (a
framebuffer with 0 stencil bits makes the stencil test always pass, per the OpenGL
spec), which also retroactively explains section 2.6's mystery: the "occlusion
query" was never testing a real mask, it was purely riding on hardware depth test
results, which is a much weaker/less reliable signal than a true stencil-shaped test
(explaining the ~90%-onscreen-but-mostly-0%-passing disconnect -- depth ordering
alone rarely produces a clean pass/fail signal the way a real stencil mask would).

A theoretical fix bolting a raw LWJGL renderbuffer (`glRenderbufferStorage(
GL_DEPTH24_STENCIL8)` + `glFramebufferRenderbuffer`) directly onto the FBO obtained
via `GlTexture.getFbo(...)` (bypassing `GpuTexture` entirely) was considered but not
attempted -- see section 2.10 for why a different architecture was chosen instead.

**Full research trail preserved in repo memory**:
`/memories/repo/portal-stencil-fbo3-bleed-finding.md`.

### 2.10 Decision: abandon stencil masking, switch to render-to-texture + compositing (`RendererUsingFrameBuffer`)

Given (a) stencil support is architecturally gone, not just misconfigured, and (b)
the user explicitly said compatibility with the old stencil-based architecture can
be sacrificed as long as through-portal rendering works correctly and Sodium/Distant
Horizons compatibility (Iris eventually) is preserved -- the chosen replacement is
render-to-texture + compositing, implemented as `RendererUsingFrameBuffer` (full
description in section 1.7). This was **not built from scratch**: `RendererUsingFrameBuffer`/
`SecondaryFrameBuffer`/`ip_setFrameBuffer` already existed as unused pre-migration
scaffolding; the missing piece was `MyRenderHelper.drawPortalAreaWithFramebuffer`
(previously a stubbed no-op) and a way to actually sample another `RenderTarget`'s
color texture from a raw-GL draw call.

Implementation work this session:
1. **`PositionTexturedGlProgram`** (new class, `src/main/java/qouteall/imm_ptl/core/
   render/PositionTexturedGlProgram.java`) -- a raw-GL (LWJGL) triangle program,
   sibling to the existing `PositionColorGlProgram`, that samples an arbitrary raw GL
   texture id (via `GlTexture.glId()`, cast down from `RenderTarget.getColorTexture()`)
   at `gl_FragCoord.xy / ViewportSize` per-fragment.
2. **`MyRenderHelper.drawPortalAreaWithFramebuffer`** implemented (was a stub) --
   binds the secondary buffer's color texture, draws the portal's quad geometry with
   the outer camera's real `modelView`/`RenderStates.basicProjectionMatrix`.
3. **`RendererUsingFrameBuffer.doRenderPortal`**'s clearing/projection-matrix TODOs
   fixed: secondary-buffer clear now goes through `RenderSystem.getDevice()
   .createCommandEncoder().clearColorAndDepthTextures(colorTex, 0, depthTex, 1.0)`
   (the sanctioned MC 26.1 replacement for the removed `RenderTarget.bindWrite`/
   `GlStateManager._clearColor`/`_clearDepth`); `new Matrix4f()` identity stubs for
   the projection matrix replaced with `RenderStates.basicProjectionMatrix`.
4. **Switched the active renderer**: `IPConfig.compatibilityRenderMode` default
   changed from `false` to `true` (both the Java field default and the persisted
   `run/config/immersive_portals.json`), which makes `PortalRenderer.
   switchToCorrectRenderer()` select `IPCGlobal.rendererUsingFrameBuffer` via
   `IPGlobal.renderMode == RenderMode.compatibility`.
5. Added a one-time diagnostic log (`PortalRenderer.switchToCorrectRenderer`'s first
   call, plus a one-time stack-trace log in `RendererUsingStencil.prepareRendering()`)
   to **definitively confirm** (per explicit user request -- "don't guess about
   switch outcomes, log them") which renderer is actually active at runtime, rather
   than inferring it from log-line presence/absence. Confirmed: `RendererUsingFrameBuffer`
   is the sole active renderer for the entire session once the config change took
   effect; an earlier apparent "stencil renderer still running" sighting was traced
   to misreading a stale log file across a terminal-restart, not a real bug.

Removed the now-obsolete `GL_STENCIL_BITS`-querying diagnostic added in 2.9 from
`MixinSodiumWorldRenderer.onRenderLayerReturn` (it's invalid on the core GL profile
and was spamming `GL_INVALID_ENUM` every frame with no further diagnostic value once
the stencil-is-dead conclusion was confirmed).

### 2.11 New crash found + fixed: real-teleport Sodium "uniforms not updated" (distinct from 2.7)

While testing the new renderer, a **different** instance of the same crash class as
2.7 occurred -- this time on an actual dimension teleport (`ClientTeleportationManager
.changePlayerDimension`), not the nested-portal-preview render path 2.7's fix
targeted. Log evidence: `renderLayer dim=minecraft:the_nether` fires with **no**
preceding `setupTerrain dim=minecraft:the_nether` line that frame, immediately after
`Client Changed Dimension from ... to ...` -- then
`IllegalStateException: Global terrain uniforms have not been updated` in
`UniformBufferManager.getUniformBuffer`.

**Root cause**: per `MyGameRenderer.switchAndRenderTheWorld`'s own existing code
comment (confirmed accurate), `GameRenderer.update()` (a separate per-real-frame
lifecycle phase from `render()`/`renderLevel()`) is the **only** place vanilla calls
`LevelRenderer.update(Camera)` -> `cullTerrain(...)` -> `compileSections(...)`, and it
only ever does so for whichever `LevelRenderer` was active at the **start** of the
frame. `ClientTeleportationManager.manageTeleportation` runs at `GameRenderer.render
()`'s literal `@At("HEAD")` -- i.e. *after* that frame's `GameRenderer.update()`
phase already ran against the *old* dimension's `LevelRenderer`. A real teleport
swaps `client.levelRenderer` to the destination dimension's (persistent, per-
dimension) `LevelRenderer` right there, but nothing then forces *that* instance's
`update(Camera)` to run this same frame -- so when `renderLevel()` proceeds to draw
it moments later, Sodium's per-frame terrain-uniform buffer was never populated for
it, and `renderLayer` crashes immediately. (The pre-existing `MyGameRenderer.
vanillaTerrainSetupOverride` flag set in `teleportPlayer`/`forceTeleportPlayer` looks
like it was meant to cover this, but its only consumer,
`MixinLevelRenderer_old.java`'s `onSetupTerrainEnd`, targets a since-fully-removed
vanilla method and was never carried over to the current `MixinLevelRenderer.java` --
confirmed dead, same as `VisibleSectionDiscovery.discoverVisibleSections` in 1.5.)

**Fix applied** (`ClientTeleportationManager.changePlayerDimension`, compiles clean,
verified live -- no repeat of this crash after the fix): immediately after
`ip_setWorldRenderer(...)`, explicitly call `mainCamera.setLevel(toWorld);
newLevelRenderer.update(mainCamera);` -- mirroring the exact pattern
`MyGameRenderer.switchAndRenderTheWorld` already uses for the nested-render case,
just applied to the real top-level teleport path instead.

### 2.12 Confirmed: the occlusion-query gate is (still) the primary blocker, now proven common to both renderers

Added real diagnostics to `RendererUsingFrameBuffer.doRenderPortal` (previously had
*zero* logging, unlike the stencil renderer's extensive `PORTAL-SKIP-DIAG` coverage)
-- logs `testShouldRenderPortal=<bool>` per attempt, and `composited into main
buffer, glError=<n>` whenever the gate does pass. Result from a full test session:
**`testShouldRenderPortal=false` on 251 of 253 logged attempts (~99.2%)** -- only 2
frames in the entire session passed the gate, and both of those 2 successfully
composited with `glError=0` (confirming the *compositing draw itself* is correct
when it actually gets the chance to run -- the bug is entirely in the gate, not the
draw).

This is **the same occlusion-query mechanism** described in section 1.3 step 2 /
section 2.3-2.6 for the old stencil renderer (`ViewAreaRenderer.renderPortalArea` +
`QueryManager.renderAndGetDoesAnySamplePass`, relying on GPU hardware depth test) --
`RendererUsingFrameBuffer.testShouldRenderPortal` calls the exact same function.
**This proves the "anySamplePassed almost always false" bug is not specific to
either renderer or to stencil at all -- it's inherent to relying on a hardware
occlusion query/depth test for this particular draw**, strengthening section 2.6's
"remaining unconfirmed leading theory" (a genuine depth-test failure at the portal
quad's on-screen fragments) as the root cause, now observed identically across two
independently-implemented renderer code paths.

Correlating with the existing `allVertexTransforms` diagnostic (still active in
`PositionColorGlProgram.end()`, shared by both renderers' occlusion pre-check) at
matching timestamps: `anyOnScreen=false` (i.e. literally every vertex of the quad
computed as outside the -1..1 NDC cube) was observed on a `testShouldRenderPortal=
false` frame at `distance=1.03` blocks -- ambiguous evidence, since at extreme
close range a large quad's *corners* legitimately leaving the NDC cube doesn't by
itself prove nothing renders (a per-pixel occlusion query and a per-vertex NDC check
answer different questions) -- a direct depth-buffer readback at a confirmed
on-screen vertex (planned since section 2.6, still not done) would be the
conclusive test, but see 2.13 for why that plan has been superseded.

### 2.13 New lead: how Distant Horizons solves the same "merge separately-rendered content" problem

Per user request, checked how Distant Horizons (LOD terrain renderer; source
confirmed present and MC-26.1.2-compatible at `C:\repos\distant-horizons\
distant-horizons`) merges its own separately-rendered content with vanilla/Sodium's
main scene -- a structurally similar problem to compositing a portal's offscreen
render onto the main framebuffer.

**DH does not use GPU occlusion queries at all** (confirmed via a full source search
for `OcclusionQuery`/`glBeginQuery`/`ANY_SAMPLES_PASSED`/`GL_SAMPLES_PASSED` -- zero
matches). Instead (`GlDhApplyShader.renderToMcTexture()`, the branch actually used
for `MC_VER > MC_1_21_5` including our exact version, confirmed via
`MinecraftRenderWrapper.mcRendersToFrameBuffer()`):
- DH renders its LOD terrain into its own **separate offscreen color + depth
  textures** (same high-level shape as our `SecondaryFrameBuffer`).
- The merge/"apply" pass **explicitly disables both depth test and blending**
  (`GLMC.disableDepthTest()`, `GLMC.disableBlend()`) -- it does **not** rely on the
  GPU's hardware depth test at all.
- The merge fragment shader (`quad_apply.vert`/`apply.frag`) is given **two**
  sampler inputs -- `gDhColorTexture` and `gDhDepthTexture` -- and manually compares
  DH's own rendered depth against whatever's already in the target per-pixel,
  deciding itself whether to write DH's color or leave the existing pixel alone.

**Why this is a better fit for us than hardware depth test/occlusion queries**: our
current compositing draw (`PositionTexturedGlProgram`, section 1.7 step 3) and our
occlusion pre-check (section 1.3 step 2 / 2.12) both lean on the GPU's hardware
depth-test machinery to decide "is this on-screen/visible", and that mechanism is
empirically unreliable for our case (2.12). DH's technique sidesteps hardware depth
test entirely by doing the comparison manually in a fragment shader that samples a
real depth texture directly -- a strictly more deterministic, driver-independent
approach.

**Proposed new approach (not yet implemented)**:
1. Drop the `testShouldRenderPortal` GPU occlusion-query gate entirely (keep only
   the existing cheap CPU-side `shouldSkipRenderingPortal` checks from section 1.2 --
   distance/frustum/roughly-visible -- as the sole gate).
2. Always render the portal's nested content into `SecondaryFrameBuffer` once those
   cheap checks pass (no GPU readback/query needed to decide *whether* to render).
3. Rework the compositing draw (`PositionTexturedGlProgram` or a new sibling class)
   to **disable hardware depth test** for the compositing quad and instead sample the
   **main render target's own depth texture** as a second input, comparing it
   manually in the fragment shader against the portal quad's own rasterized depth
   (available for free as `gl_FragCoord.z`, computed from the same outer-camera
   `modelView`/`projection` already used) -- write the secondary buffer's color only
   where the portal quad's own depth is nearer than the real scene's depth at that
   pixel; otherwise discard (leaving the real scene untouched), matching DH's
   `renderToMcTexture()` pattern.
4. Known wrinkle to solve during implementation: the main target's depth texture is
   simultaneously the depth *attachment* of the framebuffer being drawn into during
   compositing -- sampling and writing to the same underlying texture in the same
   draw is a feedback hazard. DH avoids this by disabling depth test entirely for
   its merge draw and only touching the color attachment
   (`GL33.glFramebufferTexture(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, ...)`) --
   our merge draw should do the same (bind a framebuffer targeting *only* the main
   color texture, no depth attachment, while separately sampling the depth texture
   as a plain input).

### 2.14 Current state / next steps (end of the 2026-07-12 session, second pass)

1. **Fixed, verified live**: the 2.7 nested-render Sodium crash, and the 2.11
   real-teleport Sodium crash (both confirmed no longer reproducing).
2. **Root-caused (2.9)**: stencil buffer support is architecturally removed from MC
   26.1's rendering pipeline -- `RendererUsingStencil` cannot be fixed as designed,
   only replaced.
3. **Implemented (2.10)**: `RendererUsingFrameBuffer` (render-to-texture +
   compositing) is now the default active renderer, confirmed correctly selected via
   explicit logging (not inference).
4. **Implemented (2.15/2.16, was previously "still unresolved" here)**: the
   occlusion-query gate (`testShouldRenderPortal`/`anySamplePassed`) has been
   removed and replaced with always-render + a manual per-pixel depth compare at
   composite time (portal quad's own depth vs the real scene's depth texture,
   combined with an alpha "was anything drawn" sentinel). Two variants were tried
   in between (2.15's initial version, then a depth-priming variant) before landing
   on this one -- see 2.16 for the full story, including why Distant Horizons
   itself turned out not to have a directly-applicable technique for this specific
   problem (occlusion against arbitrarily-close real geometry).
5. **Live-tested and looking good (2.16's last paragraph)**: no crashes, `glError=0`
   throughout, and screenshots show clean/coherent nested content through portals in
   both directions -- a marked improvement over earlier attempts. Not yet
   exhaustively tested across all scenarios (e.g. a wall directly in front of a
   portal, the original 2.6 motivating case).
6. **Next**: continue interactive play-testing across more scenarios (occlusion by
   nearby real geometry specifically, recursive/nested portals, hand doubling) to
   confirm the fix holds up broadly, not just in the scenarios already screenshotted.
7. Once portal rendering is confirmed reliably correct: remove all temporary
   diagnostics accumulated across every round in this document (`PORTAL-SKIP-DIAG`
   logging throughout `PortalRenderer`/`RendererUsingStencil`/`RendererUsingFrameBuffer`/
   `GlQueryObject`/`PositionColorGlProgram`; `SODIUM-DIAG`/`HAND-DIAG` in
   `MyGameRenderer.java`; the one-time stack-trace/renderer-resolution logs in
   `RendererUsingStencil.prepareRendering()`/`PortalRenderer.switchToCorrectRenderer()`;
   `PositionColorGlProgram`'s `lastVertexCount`/`lastDrawStateDiag`/`lastModelView`/
   `lastProjection`/the `allVertexTransforms` block/unconditional
   `doCheckGlError()`; `MyRenderHelper.debugReadDepthPixel`/`debugReadColorPixel`
   and their call sites in `RendererUsingFrameBuffer.doRenderPortal`), then fold a
   final summary into `docs/migration-26.1-plan-completed.md`.
8. Also still outstanding: re-verify the earlier-fixed issues (sky-in-front-of-portal,
   hand doubling) and recursive/nested portal support (explicitly dropped by
   `RendererUsingFrameBuffer`'s "one-layer only" limitation, section 1.7) once the
   above is resolved.

### 2.15 Implemented: Distant-Horizons-style manual depth-compare compositing (2.13's plan)

Implemented per user go-ahead, cross-checking Distant Horizons' actual Fabric/mixin
source (`C:\repos\distant-horizons\distant-horizons`, `GlDhApplyShader
.renderToMcTexture()` + `IMinecraftRenderWrapper`/`MinecraftRenderWrapper` for our
exact MC-version branch) rather than guessing at the GL technique:

1. **`RendererUsingFrameBuffer.doRenderPortal`**: deleted `testShouldRenderPortal`
   (the GPU occlusion-query gate) entirely -- the portal's nested content is now
   always rendered into `secondaryFrameBuffer` once the existing cheap CPU-side
   `shouldSkipRenderingPortal` checks pass (nothing else gates it). `FrontClipping
   .updateInnerClipping(modelView)` (previously only called inside the deleted gate)
   is now called unconditionally before rendering.
2. **New raw-GL wrinkle found and fixed while implementing** (not previously
   diagnosed): `IEMinecraftClient.ip_setFrameBuffer` (`MixinMinecraft
   .ip_setFrameBuffer`) only reassigns the Java-side `mainRenderTarget` field -- it
   never itself rebinds anything at the raw GL level. This means the compositing
   draw (a raw-GL `glDrawArrays` call that bypasses Blaze3D's `RenderPass`
   machinery, same as `PositionColorGlProgram`) had **no guarantee** it was actually
   drawing into the main render target's real framebuffer, rather than whatever raw
   FBO the secondary buffer's nested-content render had last bound. Fixed by
   capturing the real raw FBO id in use for the main target (`GlStateManager
   .getFrameBuffer(GL30.GL_FRAMEBUFFER)`) at the very start of `doRenderPortal`,
   *before* swapping to the secondary buffer, and using that captured id as the
   explicit restore target after compositing.
3. **`MyRenderHelper.drawPortalAreaWithFramebuffer`** reworked to mirror
   `GlDhApplyShader.renderToMcTexture()` (the exact branch DH itself uses for
   `MC_VER > MC_1_21_5`, i.e. our version) instead of relying on hardware depth
   test/write:
   - A single persistent, depth-attachment-less raw FBO (`compositeFboId`, created
     once via `GlStateManager.glGenFramebuffers()`) is reused every compositing
     draw -- each call reattaches the main render target's *current* raw color
     texture id to its `GL_COLOR_ATTACHMENT0` (`GlStateManager
     ._glFramebufferTexture2D`), exactly like DH's `renderToMcTexture()` re-attaches
     `MC_RENDER.getGlColorTextureId()` onto its own pre-existing FBO for the same
     reason: this avoids ever needing to sample-and-write the same depth texture in
     one draw (the "feedback hazard" flagged as an open wrinkle in 2.13's plan) by
     simply never attaching a depth texture to this FBO at all.
   - Hardware depth test is explicitly disabled (`GlStateManager._disableDepthTest()`)
     for this draw, matching DH's `GLMC.disableDepthTest()`.
   - After the draw, the raw FBO binding is explicitly restored to the id captured
     in step 2 (`GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER,
     restoreToFbo)`) and hardware depth test is re-enabled for the rest of the
     frame's normal rendering, matching DH's own explicit restore-after-drawing
     (`GLMC.glBindFramebuffer(GL33.GL_FRAMEBUFFER, mcFrameBufferId)`).
4. **`PositionTexturedGlProgram`**'s fragment shader now takes a second sampler,
   `SceneDepthTex` (the main render target's own depth texture, sampled as a plain
   input, never as this draw's own attachment -- see point 3), and does the
   occlusion decision itself: `if (gl_FragCoord.z >= sceneDepth) discard;` else
   output the secondary buffer's color (`gl_FragCoord.z` is already, for free, the
   portal quad's own physical depth at this pixel, computed via the same outer-
   camera modelView/projection matrices used for the rest of the frame -- exactly
   what a hardware depth test would have compared, just done manually and
   deterministically instead of via an occlusion query). Note this compares against
   the *real* MC scene depth (unlike DH's own `apply.frag`, which only checks its
   own offscreen depth against a sentinel "not drawn" value of `1.0` -- DH's LOD
   terrain never needs to be occluded by nearby real geometry since it only ever
   fills in *beyond* the vanilla render distance, a fundamentally different problem
   shape than a portal quad that can be arbitrarily close to the camera and behind
   real blocks).

**Verified**: compiles clean (`./gradlew compileJava`); a live `runClient` test
showed the compositing draw running on every attempted portal render (not gated to
~2/253 frames like the old occlusion-query approach), `glError=0` on every attempt,
and no exceptions/crashes of any kind (including no repeat of the 2.7/2.11 Sodium
crashes). Visual/interactive confirmation that portals now render correctly on
screen (as opposed to just "the draw call succeeded with no GL error") is still
outstanding -- needs an interactive play-test.

### 2.16 Live test of 2.15: mixed results, then a depth-priming variant tried and disproven

Live testing of 2.15's manual-depth-compare approach showed **mixed results** (user
report + screenshots): from the overworld side, portions of the nether were
correctly visible, but mixed with patches of the overworld's own sky/background
bleeding through (i.e. the compositing quad discarding in places it shouldn't); from
the nether side, the portal area rendered as solid black.

**A depth-priming variant was tried next**: instead of comparing depths only at
composite time, prime the secondary buffer's depth attachment with a copy of the
real scene's depth (`RenderTarget.copyDepthFrom`) *before* rendering the nested
content into it, so the nested content would be naturally occluded by real nearby
geometry via ordinary hardware depth test during that render (reasoning: mirrors
"as if you removed the portal surface and could see straight through, but real
nearby geometry still blocks the view"). Compositing was changed to a simple
alpha-sentinel check (was anything drawn at this pixel at all) instead of a second
depth compare, since the depth channel was no longer available as a "nothing here"
sentinel (it now legitimately contained copied real depth).

**This was root-caused as broken using real pixel-readback diagnostics, not
guessing** (`MyRenderHelper.debugReadDepthPixel`/`debugReadColorPixel`, new
generic single-texel GL readback helpers added this round, safe to call without
disturbing the currently-bound framebuffer): logged the screen-center texel's depth
immediately after `copyDepthFrom` (`realDepthBeforeCopy`/`depthAfterCopy` --
correctly matched, e.g. both `0.98279583`, confirming the copy itself works) and
again immediately after `renderPortalContent` (`afterNestedRender`). Real captured
log lines:
```
depthAfterCopy(center)=0.98279583
afterNestedRender(center) depth=1.0 color=(0.0,0.0,0.0,0.0)
afterNestedRender(center) depth=0.99963135 color=(0.023529414,0.007843138,0.007843138,1.0)
```
Depth went from a near value (`0.98`) *up* to ~`1.0` (far plane) after nested
rendering -- under normal `GL_LEQUAL` depth test this is only possible if something
overwrote the primed depth *without* a passing depth test. **Conclusion: vanilla's
own sky-rendering pass (drawn first in any normal world render, including the
nested "as if standing at the destination" render `renderPortalContent` triggers)
evidently renders with depth test disabled/`GL_ALWAYS`-like semantics** (a
reasonable vanilla optimization -- safe in a normal frame, since sky is always
drawn first into a freshly-cleared depth buffer there, so ignoring depth test costs
nothing) -- **this unconditionally clobbers any pre-primed depth with the far-plane
value, for the entire screen, not just edge cases.** Priming a secondary/offscreen
buffer's depth before triggering a full normal vanilla world-render into it is
therefore fundamentally incompatible with vanilla's sky pass.

**Distant Horizons re-investigated in depth a second time** (per repeated user
request) to check whether DH has a real technique for this: confirmed it does
**not**. DH's own LOD render pass also just clears its depth to far every render
(`GlDhMetaRenderer.java`, `GL33.glClearDepth(1.0)` -- no `copyDepthFrom`-equivalent
anywhere in DH's source). DH never needs real occlusion against nearby real
geometry because its LOD content is constructed to only ever exist in world-space
regions *beyond* vanilla's own render distance -- structurally non-overlapping by
design. DH's merge shaders (`apply.frag`, fully read) only check "was anything
drawn here" (`depth != 1.0`); its `vanilla_fade.frag` (also fully read, the one
shader that takes *both* a vanilla depth texture and DH's own depth texture) does
**not** decide occlusion either -- it only computes a pure camera-distance-based
fade blend for a smoother visual transition at the LOD boundary. A portal's nested
content, unlike DH's LOD terrain, structurally *can* be directly behind/occluded by
arbitrarily-close real geometry (e.g. a wall built in front of a portal) -- DH's
architecture sidesteps a problem this mod cannot sidestep the same way.

**Fix applied**: reverted the depth-priming entirely -- the secondary buffer gets a
completely normal clear (transparent color, far depth) and nested rendering
proceeds untouched. Reinstated 2.15's manual depth compare at composite time
(portal quad's own `gl_FragCoord.z` vs the *main* target's real depth texture, both
using the identical `RenderStates.basicProjectionMatrix`) as the sole occlusion
decision, combined with the alpha-sentinel check as a defense-in-depth fallback
(`PositionTexturedGlProgram`'s fragment shader now discards if either `color.a <
0.5` *or* `gl_FragCoord.z >= sceneDepth`). This doesn't depend on the secondary
buffer's own depth at all, so it's immune to the sky-clobbering issue found above.

**Live-tested after this revert**: no exceptions/crashes, `glError=0` throughout,
and screenshots now show clean, spatially coherent nested content through portals
in both directions (correct sky/clouds/terrain visible through a nether-side
portal showing the overworld destination, and vice versa) -- a marked improvement
over both earlier attempts' black/glitchy/mixed results. Not yet exhaustively
tested (e.g. the specific "wall directly in front of a portal" occlusion case from
2.6's original motivating scenario) -- next session should verify that case
specifically, plus re-check the previously-known sky-in-front-of-portal/hand-
doubling issues and recursive/nested portal support once basic correctness is
confirmed across more scenarios.

