# Migration plan: Minecraft 1.21.1 → 26.1.2

Canonical reference for migrating ImmersivePortalsMod from Minecraft 1.21.1 to 26.1.2.
This document reflects the **current state only** — update it in place as work
progresses; don't append historical/dated entries here (session history lives in
chat/commit history, not this file).

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

### Dependencies & build script — done, verified

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

Other build changes in place:
- [build.gradle](../build.gradle): no `mappings` block (none exists for 26.x), no
  Parchment repo, all `mod*` dependency configs converted to plain equivalents,
  `remapJar` merged into the `jar` task (the task doesn't exist under the
  non-obfuscated plugin), `publishMods.file` points at `jar.archiveFile`.
- [src/main/resources/imm_ptl.accesswidener](../src/main/resources/imm_ptl.accesswidener):
  header is `accessWidener v2 official`. 4 stale entries referencing
  moved/removed members (`GameRules.register`, `GameRules$BooleanValue.create`,
  `Program$Type.getGlType`, `RegistryDataLoader$Loader`) were removed — none were
  referenced by our own source.

### Source code — partially done

17 symbol categories resolved so far (see `migration_tools/renames.json` for the
authoritative, current list of applied/pending renames):

**Mechanical package moves / renames** (applied via `bulk_rename.py`):
`ResourceLocation`→`Identifier`, `Util`→`util.Util`, `GameRules`→`gamerules.GameRules`,
`AbstractMinecart`/`Arrow`/`AbstractArrow`→`.minecart`/`.arrow` subpackages,
`ThrownEnderpearl`→`.throwableitemprojectile`, `EndDragonFight`→`EnderDragonFight`,
`DimensionTransition`→`TeleportTransition`, `RelativeMovement`→`Relative`,
`ReceivingLevelScreen`→`GenericWaitingScreen`, `DimensionDataStorage`→`SavedDataStorage`,
`ClientCommandManager`→`ClientCommands` (Fabric API), `RenderType`→`renderer.rendertype.RenderType`,
`FogRenderer`→`renderer.fog.FogRenderer`.

**API-shape fixes** (applied directly, not pure renames):
- `PortalPlaceholderBlock.java`: `FabricBlockSettings.create()` → `BlockBehaviour.Properties.of()`
  (Fabric API merged this wrapper into vanilla; `noCollission()` typo also fixed to `noCollision()`).
- `ExampleGuiPortalRendering.java`: `Screen.render(GuiGraphics, ...)` →
  `Screen.extractRenderState(GuiGraphicsExtractor, ...)`, `drawCenteredString(...)` →
  `centeredText(...)` (part of the GUI render-state-extraction rework). Note:
  `DimEntryWidget.java` still uses `GuiGraphics` and needs the same treatment.

**Chunk ticket system redesigned** (`chunk_loading/ImmPtlChunkTickets.java` and its
supporting duck interfaces): rewritten to use vanilla's own high-level
`ServerChunkCache#addTicketWithRadius`/`removeTicketWithRadius` API (backed by the
new `TicketStorage`, throttled internally by vanilla's own chunk task dispatcher)
instead of replicating vanilla's old low-level ticket/mailbox internals, which no
longer exist. Deleted `IEChunkTaskPriorityQueueSorter.java`,
`mixin/common/chunk_sync/IEDistanceManager.java` (the accessor-based one), and
`ducks/IEDistanceManager.java` (all dead — targeted removed classes/fields).
`MixinDistanceManager.java` stripped down to just its one still-valid NPE-avoidance
injection. `PortalDebugCommands.java`'s two ticket-listing debug commands updated to
read `TicketStorage` directly instead of the deleted duck. Compile-verified: the
`TicketType`/`Ticket`/`ChunkTaskPriorityQueueSorter`/`ProcessorMailbox`/`IntRunnable`
error categories are all gone.

**Lightmap rendering redesigned** (`LightTexture` → `Lightmap`, affecting
`DimensionRenderHelper`, `IEGameRenderer`, `MixinGameRenderer`, `MyGameRenderer`,
`RenderStates`, `ClientTeleportationManager`, `ClientWorldLoader`): confirmed via
Distant Horizons' `MixinLightTexture.java` and decompiled `Lightmap.java`/
`LightmapRenderState.java`/`LightmapRenderStateExtractor.java`/`GameRenderer.java`
that vanilla's `lightmap` field on `GameRenderer` is now `private final` (was
mutable), and the render-state is now split into three pieces: `Lightmap` (GPU
texture holder, no-arg constructor), `LightmapRenderState` (plain data holder), and
`LightmapRenderStateExtractor` (reads `Minecraft.level`/`.player` to populate a
state, tied to one `GameRenderer`+`Minecraft` pair). `DimensionRenderHelper` now
holds all three (for non-current dimensions only — the current dimension still
reuses vanilla's own shared instance via a new `IEGameRenderer.ip_getLightmap()`
accessor), with a `forceUpdate()` convenience method replacing the old
`LightTexture#updateLightTexture(float)` call. `MixinGameRenderer`'s `lightmap` field
shadow uses `@Mutable` (matching the pre-existing pattern already used for the old
`lightTexture` field) so it can still be swapped to point at another dimension's
`Lightmap` while that dimension is being rendered through a portal.

Also partially handled in `MixinParticleEngine.java`: `ParticleEngine.render(LightTexture,
Camera, float)` → `ParticleEngine.extract(ParticlesRenderState, Frustum, Camera,
float)` (same render-state-extraction pattern) — the portal-count injection was
retargeted. However `Particle.render(VertexConsumer, Camera, float)` no longer
exists at all (particle geometry building moved elsewhere in the new pipeline) — the
corresponding `redirectBuildGeometry` mixin is commented out with a `TODO`, tracked
as part of item 2 below rather than guessed at.

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
same "draw arbitrary vertex data with a custom pipeline" problem).

Mechanical fixes applied (compile-clean, should work as-is): `GlStateManager`/`Uniform`/
`GlDebug` package move (`com.mojang.blaze3d.platform`→`.opengl`); `GraphicsStatus`→
`GraphicsPreset` (`Options.graphicsMode()`→`.graphicsPreset()`); `GlUtil.getVendor()`→
`RenderSystem.getDevice().getVendor()`; `TextureTarget`/`RenderTarget` ctor/field
changes (`viewWidth`/`viewHeight`→`width`/`height`, no more `Minecraft.ON_OSX` param);
`ShaderCodeTransformation`'s `Program.Type` param replaced with its own `ShaderType`
enum (this let the Iris/Sodium-side shader-source-transformation mixins
`MixinIrisTransformPatcher`/`MixinSodiumShaderLoader` keep working, since they patch
Iris's/Sodium's own shader classes, unrelated to vanilla's `Program` removal); a new
projection-matrix capture (`MixinGameRenderer.ip_captureBasicProjectionMatrix`, a
`@ModifyVariable` at `STORE` since the old capture point
`GameRenderer.getProjectionMatrix(double)` no longer exists at all — the real Matrix4f
now only exists transiently inside `GameRenderer.renderLevel` before being wrapped into
a `GpuBufferSlice`); the `IPCGlobal.renderer.onBeforeHandRendering(modelView)` hook
retargeted from a `LevelRenderer.renderLevel(...)` `@WrapOperation` (old signature
fully gone) to a new `@Inject` at `GameRenderer.renderItemInHand(...)`'s call site
(verified against real decompiled source).

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

**Current compile state:** run `python migration_tools/parse_compile_errors.py --run`
for the live count. **`build.gradle` now passes `-Xmaxerrs 5000` to javac** (added
in Round 13) so the reported count is the TRUE full error count, not capped at ~100
per invocation like earlier rounds believed — always re-baseline after this change,
don't compare against pre-Round-13 numbers. As of the latest run: **303 errors / 110
distinct symbols** — a **54% reduction** from the 665/152 true baseline established
when `-Xmaxerrs` was first added, all fixed in one continuous session (Rounds 13-15)
via mechanical/API-shape fixes with zero architecture redesigns needed. Progression:
665/152 → 589/150 (`getNormal()` cluster) → 543/141 (`method does not override`
batch) → 349/119 (large sweep: `server`/`getServer()`, `isClientSide`,
`displayClientMessage`, `registryOrThrow`, `playS2C`/`playC2S`, `cameraEntity`,
`RenderType.lines()`, `worldGenOptions()`, `hasPermissions` stragglers,
`Direction.fromDelta`, Sodium `getOrigin()`, packet record reshape) → **303/110**
(further sweep: `GameProfile.getId/getName`, `Entity.startRiding`, `ChunkPos` `x`/`z`
stragglers, `ClickEvent` sealed-record subtypes, `Entity.lerpTo`→`snapTo`,
`StringTag.getAsString()`→`.value()`, `ListTag.getElementType()` removal,
`StringWidget.alignCenter()` removal, `Entity.createCommandSourceStack()`→
`createCommandSourceStackForNameResolution(ServerLevel)`). The total error count is
a meaningful, shrinking progress signal — trust it. Full detail on every fix in
`/memories/session/mc26.1-migration.md` Round 15 (session memory, not duplicated
here to keep this doc from growing unbounded — this doc tracks current-state
summaries, the memory file tracks the full chronological narrative). Everything
else historically tracked in this doc (Fabric API package moves, `ChunkPos`
construction, `Level.getMinSection()`/`getMaxSection()` renames,
`InteractionResultHolder` removal, `ChunkProgressListener` rename,
`WeightedRandomList`/`Carving` moves, the `GuiGraphics` GUI rendering rewrite, Sodium's
`OcclusionCuller` redesign, `BakedQuad`/`BakedModel`/`BlockRenderDispatcher`,
`Minecraft.ON_OSX` removal, `ValueInput`/`ValueOutput`, `.location()`→`.identifier()`,
`getProfiler()`, `ChunkPos` record shape, `EntityType.create()`,
`Direction.getNormal()`/`getNearest()`, `Camera.getPosition()`, NBT `getAsNumber`/
`getAsByte`/`getAllKeys`, `getMinBuildHeight`/`getMaxBuildHeight`,
`Item.appendHoverText`, `ClientChunkCache.replaceWithPacketData`, `BlockBehaviour.
updateShape`/`propagatesSkylightDown`, `ViewArea.repositionCamera`,
`ChunkGenerator.createStructures`/`getTypeNameForDataFixer`, `Screen.keyPressed`/
`KeyMapping.matches`, `TextureTarget`'s label-param constructor) remains fixed — see
the Status subsections below.

**Newly-discovered architectural item (deferred, not mechanical)**: `SavedData`
(base class for `GlobalPortalStorage`) no longer has `save(CompoundTag,
HolderLookup.Provider)`/load-via-factory at all — persistence moved entirely to a
`Codec<T>` registered via a `SavedDataType<T>` record, with `SavedDataStorage`
(renamed from `DimensionDataStorage`) handling serialization automatically. This
doesn't fit the "pure data" Codec model cleanly since `GlobalPortalStorage` holds
live `Portal` entities that need a specific `ServerLevel` to deserialize into —
needs research into how vanilla's own entity-holding `SavedData` subclasses (if
any) handle this before attempting a fix. Tracked as a new item alongside the
`EntityRenderer<T,S>` redesign (item 3) — see "Remaining work" below.

### Entity save-data rewrite (`ValueInput`/`ValueOutput`) — done

Minecraft rewrote entity/block-entity NBT persistence from raw `CompoundTag`
read/write calls to a `ValueInput`/`ValueOutput` abstraction
(`net.minecraft.world.level.storage` package) whose getters return `Optional<T>` (or
have an explicit `...Or(key, default)` primitive-returning variant) instead of
nullable/zero-defaulted raw values. **`CompoundTag` itself gained the exact same
`Optional<T>`/`...Or(key, default)` getter split** (confirmed via `javap` — e.g.
`getDouble(String)` now returns `Optional<Double>`, `getDoubleOr(String, double)`
returns the primitive directly), and lost `getCompound(String)` (→
`Optional<CompoundTag>`, use `getCompoundOrEmpty(String)`), `getList(String, int)`'s
type-filtering second arg (→ single-arg `getList(String)` returning
`Optional<ListTag>`, use `getListOrEmpty(String)`), and `hasUUID`/`getUUID`/`putUUID`
entirely (no direct UUID convenience methods left on `CompoundTag`/`ValueInput`/
`ValueOutput` at all — use `store(key, net.minecraft.core.UUIDUtil.CODEC, uuid)` /
`read(key, UUIDUtil.CODEC)` instead, since `Codec<UUID> UUIDUtil.CODEC` still exists).
`ListTag` also gained the identical `Optional`/`...Or` split for its own index-based
getters (`getDouble(int)` → `Optional<Double>` + `getDoubleOr(int, double)`, etc.) —
this is NOT unique to `CompoundTag`, both NBT container types changed together.
`StringTag.getAsString()` was renamed to `.value()`.

**Key design decision:** rather than converting this codebase's entire nested
`CompoundTag`-based serialization tree (`PortalState`/`UnilateralPortalState`/
`DeltaUnilateralPortalState`/`DQuaternion`/`Mesh2D`/`BlockPortalShape`/
`PortalAnimation`/animation drivers/`PortalExtension`'s signal handlers — all of which
serialize as nested nested `CompoundTag` sub-trees via a `toTag()`/`fromTag(CompoundTag)`
convention) over to the new `ValueInput`/`ValueOutput` types, **only the two outermost
override points changed shape**; everything else stayed `CompoundTag`-based internally
and just needed its *own* API calls fixed for `CompoundTag`'s new Optional-returning
getters (mechanical, low-risk). Concretely, in `Portal.java`:
- `protected void readAdditionalSaveData(CompoundTag compoundTag)` /
  `protected void addAdditionalSaveData(CompoundTag compoundTag)` **keep their exact
  original name and signature** (no longer annotated `@Override` since they no longer
  match anything on `Entity` directly — they're now just Portal's own polymorphic
  helper methods, which subclasses like `BreakablePortalEntity`/`BreakableMirror`
  still correctly `@Override` since Java only requires overriding *some* reachable
  superclass method, not one from `Entity` specifically). All of their internal
  `compoundTag.getX(...)` calls were fixed to `getXOr(...)`/`getCompoundOrEmpty(...)`/
  `getListOrEmpty(...)` throughout.
- Two **new** methods were added with the real `@Override` matching `Entity`'s
  actual current abstract signature, acting as a thin bridge:
  ```java
  @Override
  protected void addAdditionalSaveData(ValueOutput output) {
      CompoundTag compoundTag = new CompoundTag();
      addAdditionalSaveData(compoundTag); // virtual dispatch — reaches subclass overrides too
      output.store("data", CompoundTag.CODEC, compoundTag);
  }

  @Override
  protected void readAdditionalSaveData(ValueInput input) {
      CompoundTag compoundTag = input.read("data", CompoundTag.CODEC).orElseGet(CompoundTag::new);
      readAdditionalSaveData(compoundTag);
  }
  ```
  This relies on `CompoundTag.CODEC` (a `static final Codec<CompoundTag>`, confirmed to
  still exist) to embed the entire existing `CompoundTag` blob as one nested value
  under a `ValueOutput`/`ValueInput`. It preserves the *entire* existing network-sync
  code path unchanged (`createSyncPacket()`/`writePortalDataToNbt()`/
  `readPortalDataFromNbt()`/`acceptDataSync()`/`updatePortalFromNbt()` all still work
  directly with `CompoundTag`, calling the CompoundTag-shaped methods by static-type
  overload resolution) while satisfying the new `Entity` abstract-method contract.
  Chosen deliberately over converting the whole nested-serialization tree to avoid a
  much larger, riskier rewrite for no functional benefit — the data format on disk/
  network is unaffected either way.
- `Entity.makeBoundingBox()` is now `final`; the overridable hook moved to
  `makeBoundingBox(Vec3 position)` (Portal's own bounding-box logic doesn't depend on
  the input position, so the param is accepted but unused, matching prior behavior
  exactly). `Entity.hurtServer(ServerLevel, DamageSource, float)` became a **newly
  abstract** method (previously had a default via `hurt(DamageSource, float)`, which no
  longer exists at all) — Portal never overrode damage handling before, so it now
  implements `hurtServer(...)` returning `false` (portals cannot be hurt, matching
  prior de-facto behavior).
- `Direction.getNormal()` (returned `Vec3i`) is fully removed — replaced with
  `new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ())`
  (`Vec3.atLowerCornerOf(direction.getNormal())` pattern → equivalent inline
  construction). `Direction.getNearest(double,double,double)` renamed to
  `Direction.getApproximateNearest(...)`. Fixed in `Portal.java`, `PortalAPI.java`,
  `IntBox.java` (this round); **not yet swept everywhere** — see item 1 under
  "Remaining work", several more call sites turned up in `BlockManipulationClient.java`/
  `BlockManipulationServer.java`.
- `FabricEntityTypeBuilder<T>.build()` (no-arg) → `build(ResourceKey<EntityType<?>>)` —
  now requires the entity's registry key **at construction time** instead of at
  `Registry.register(...)` time. `Portal.createPortalEntityType(...)` gained a leading
  `String id` parameter (builds `ResourceKey.create(Registries.ENTITY_TYPE,
  McHelper.newResourceLocation("immersive_portals", id))` internally); every subclass's
  `ENTITY_TYPE` field init was updated to pass its own id (matching the strings already
  used in `IPModMain.registerEntityTypes`'s separate `Registry.register(...)` call, so
  the two must be kept in sync manually — same namespace/path each). Also affected
  `LoadingIndicatorEntity.java` (constructs its `EntityType` directly via
  `FabricEntityTypeBuilder`, not through `Portal.createPortalEntityType`).
- `ServerPlayNetworking.createS2CPacket(...)` (Fabric API) renamed to
  `createClientboundPacket(...)`; same rename applies to
  `ServerConfigurationNetworking.createS2CPacket(...)`. Fixed in `Portal.java`,
  `GlobalPortalStorage.java`, `ImplRemoteProcedureCall.java`, `MiscNetworking.java`,
  `ImmPtlNetworkConfig.java`. (`ClientPlayNetworking.createC2SPacket(...)` was checked
  and appears unaffected — left as-is.)
- `Entity.getServer()` removed entirely (not just moved) — `ServerLevel.getServer()`/
  `CommandSourceStack.getServer()` are **unaffected** (different classes, still have
  their own `getServer()`), only `Entity`-typed (and by extension `Player`/
  `LivingEntity`/`ServerPlayer`-typed) receivers broke. Fixed by casting to
  `((ServerLevel) entity.level()).getServer()` at the ~4 confirmed call sites in
  `Portal.java`/`PortalAPI.java` this round — **a broader `grep_search` turned up 100+
  more `.getServer()` call sites across the codebase, but the overwhelming majority are
  on `ServerLevel`/`MiscHelper`/`CommandSourceStack` receivers which are unaffected; only
  2 more `Entity`-typed call sites remain (in `ImmPtlChunkTracking.java`, part of the new
  wave — see item 1 under "Remaining work")**.
- `ResourceKey<T>.location()` → `.identifier()` swept again in this round's touched
  files (`UnilateralPortalState.java`, `MiscNetworking.java`'s `dimId`/
  `BuiltinDimensionTypes.OVERWORLD` call sites) — **not fully complete**, a much larger
  fresh batch (46 errors) turned up in files not yet touched — see item 1 under
  "Remaining work".
- Files touched this round (mechanical `CompoundTag`-API fixes plus the above):
  `Portal.java`, `PortalAPI.java`, `Helper.java`, `DQuaternion.java`, `IntBox.java`,
  `PortalExtension.java`, `PortalState.java`, `UnilateralPortalState.java`,
  `DeltaUnilateralPortalState.java`, `PortalAnimation.java`, `NormalAnimation.java`,
  `RotationAnimation.java`, `PortalAnimationDriver.java`, `DefaultPortalAnimation.java`,
  `BreakableMirror.java`, `BreakablePortalEntity.java`, `BlockPortalShape.java`,
  `FastBlockPortalShape.java`, `SpecialFlatPortalShape.java`, `Mesh2D.java`,
  `GeometryPortalShape.java`, `PortalShapeSerialization.java`, `Mirror.java`,
  `EndPortalEntity.java`, `GlobalTrackedPortal.java`, `VerticalConnectingPortal.java`,
  `WorldWrappingPortal.java`, `GeneralBreakablePortal.java`, `NetherPortalEntity.java`,
  `LoadingIndicatorEntity.java`, `GlobalPortalStorage.java`, `ImplRemoteProcedureCall.java`,
  `MiscNetworking.java`, `ImmPtlNetworkConfig.java`.
- Verified via `parse_compile_errors.py --run`: `Portal.java`/`PortalAPI.java`
  individually now have **zero** compile errors of their own (confirmed by filtering
  the JSON report by filename) — the whole cluster is resolved, not just partially
  masked.

### Small mechanical fixes (done)

- **`ChunkPos`** is a `record` with private `x`/`z` fields — use `.x()`/`.z()`
  accessors. Its constructor is now **`ChunkPos(int, int)` only** — the old
  `ChunkPos(BlockPos)`/`ChunkPos(long)` convenience constructors are gone; use the new
  static factories `ChunkPos.containing(BlockPos)` / `ChunkPos.unpack(long)` instead
  (applied across ~14 files).
- **`LevelHeightAccessor`** (implemented by `Level`): `getMinSection()`/
  `getMaxSection()` renamed to `getMinSectionY()`/`getMaxSectionY()`.
- **`ResourceKey<T>.location()`** renamed to **`ResourceKey<T>.identifier()`**.
- **`Item.use(Level, Player, InteractionHand)`** now returns plain
  `InteractionResult` — `InteractionResultHolder<ItemStack>` no longer exists at all;
  mutate the `ItemStack` in place instead of threading it through the return value.
- **Fabric API restructuring**: `FabricItemGroup`/`ItemGroupEvents`
  (`net.fabricmc.fabric.api.itemgroup.v1`) → `FabricCreativeModeTab`/
  `CreativeModeTabEvents` (`net.fabricmc.fabric.api.creativetab.v1`).
  `FabricBlockSettings` fully removed — use vanilla
  `BlockBehaviour.Properties.of()` directly. `net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap`
  has **no confirmed replacement** after checking every relevant Fabric API submodule
  jar (`fabric-renderer-api-v1`, `fabric-rendering-v1`, `fabric-model-loading-api-v1`,
  `fabric-content-registries-v0`, `fabric-renderer-registries-v1`); likely superseded
  by the new `BlockStateModel`/`FabricBlockStateModel` model-part system (see below),
  but not confirmed — stubbed to a no-op with a `TODO` in
  `PeripheralModEntryClient.registerBlockRenderLayers()` (cosmetic-only regression:
  affected blocks render solid instead of cutout).
  Note: the Fabric API `.jar` itself is a **jar of jars** — real classes live in
  nested jars under `META-INF/jars/*.jar` inside the outer artifact; `find_candidates.py`
  only indexes the vanilla merged jar, so Fabric API package moves must be
  investigated by opening the outer jar with Python `zipfile` and then opening the
  relevant nested jar the same way.
- **`net.minecraft.server.level.progress.ChunkProgressListener`/
  `ChunkProgressListenerFactory`** → renamed to
  **`net.minecraft.server.level.progress.LevelLoadListener`** (with a nested
  `LevelLoadListener.Stage` enum replacing whatever staged-loading shape the old type
  had) — there is **no separate factory type anymore**; `MinecraftServer`'s
  constructor now takes a `LevelLoadListener` instance **directly** (stored as a
  `private final` field, exposed via `getLevelLoadListener()`), and
  `MinecraftServer.createLevels()` is now **no-arg** (it reads
  `this.levelLoadListener` internally instead of taking a parameter). This changed the
  shape of 3 mixins: `NormalSkylandGenerator.java` (just an import used in a Javadoc
  `{@link}`), `MixinMinecraftServer_DimStack_CVB.java` (`@Inject` targets on
  `createLevels` had to drop the now-removed parameter from their handler method
  signatures), and `MixinMinecraftServer_Misc.java` (its `<init>` `@Inject` handler had
  to be updated to match the *real* current constructor param list — which also gained
  a new `Optional<GameRules> gameRules` param (5th) and a trailing `boolean
  propagatesCrashes` param neither of which the old mixin body accounted for at all;
  Mixin handler methods for `method = "<init>"` must match the target constructor's
  full parameter list in order, so all of these had to be added, not just the
  `ChunkProgressListenerFactory`→`LevelLoadListener` type swap).
- **`net.minecraft.util.random.WeightedRandomList`** → renamed to
  **`net.minecraft.util.random.WeightedList`** (same shape: `.getRandom(RandomSource)`
  now returns `Optional<E>` instead of a nullable `E`). Separately,
  `ChunkGenerator.applyCarvers(...)` **dropped its trailing `GenerationStep.Carving`
  parameter entirely** (no longer takes a carving-step argument at all) — fixed in
  `DelegatedChunkGenerator.java`.
- **`BakedQuad`/`BakedModel`/`BlockRenderDispatcher`** (used by
  `OverlayRendering.java` to draw a breakable-portal's block overlay) — this is a
  **genuine redesign, not a rename**: block models are now built from a
  `BlockStateModel`/`BlockStateModelPart`/`BlockStateModelDispatcher` model-part system
  (`net.minecraft.client.renderer.block.dispatch` package;
  `BlockStateModelPart.getQuads(Direction)` replaces
  `BakedModel.getQuads(BlockState, Direction, RandomSource)`), and `BakedQuad` itself
  (`net.minecraft.client.resources.model.geometry.BakedQuad`) is now a `record` of
  packed vertex/material data with **no `.getSprite()`** — `VertexConsumer
  .putBulkData(pose, BakedQuad, ...)` no longer matches this shape at all. Stubbed
  `OverlayRendering.renderBreakablePortalOverlay(...)` to a no-op with a `TODO` (the
  portal breakable-overlay block just won't render) rather than guess at the new
  quad-consuming API — needs the same kind of dedicated redesign work as item 1 below.
- **GUI rendering rewrite (`GuiGraphics` → `GuiGraphicsExtractor`) — done across all
  affected files** (`DimEntryWidget.java`, `DimListWidget.java`,
  `DimStackEntryEditScreen.java`, `DimStackScreen.java`, `SelectDimensionScreen.java`,
  `CustomTextOverlay.java`/`MixinGui_Overlay.java`, `GuiHelper.java`). Key API shape
  facts (verified via real decompiled source, not guessed):
  - `GuiGraphics` **class itself is gone** — replaced everywhere by
    `GuiGraphicsExtractor`, obtained by the framework and passed into a renamed
    `extractRenderState(GuiGraphicsExtractor, mouseX, mouseY, partialTick)` method
    (replaces `render(GuiGraphics, ...)`) on `Screen`/`Renderable`/`AbstractWidget`
    (the latter's `extractRenderState` is `final`).
  - `Screen.renderBackground(...)` is **gone** — background rendering
    (panorama/blur/menu-background) is now handled **automatically** by an outer
    `extractRenderStateWithTooltipAndSubtitles` wrapper that calls a `extractBackground`
    hook before calling into the screen's own `extractRenderState` override; subclasses
    should **not** call anything background-related themselves anymore.
  - List-widget rendering: `AbstractSelectionList.Entry`'s abstract render method is
    now `extractContent(GuiGraphicsExtractor, int mouseX, int mouseY, boolean hovered,
    float a)` — it **no longer receives `index`/`x`/`y`/`rowWidth`/`itemHeight`** as
    params; use the entry's own `getX()`/`getY()`/`getWidth()`/`getHeight()` getters
    instead (position/size are now tracked on the entry itself and set by the owning
    list). `AbstractSelectionList.renderListBackground(GuiGraphics)` renamed to
    `extractListBackground(GuiGraphicsExtractor)`; `getScrollbarPosition()` renamed to
    `scrollBarX()`.
  - `GuiGraphics.pose()` used to return a 3D `PoseStack`; `GuiGraphicsExtractor.pose()`
    returns a 2D **`org.joml.Matrix3x2fStack`** instead (JOML library, not a Mojang
    type) — `pushPose()`/`popPose()` → `pushMatrix()`/`popMatrix()`; `translate(x,y,z)`/
    `scale(x,y,z)` (3-arg) → `translate(x,y)`/`scale(x,y)` (2-arg, no z); rotating
    around a pivot point (`rotateAround(Quaternionfc, cx, cy, cz)`) → `rotateAbout(float
    angleRadians, cx, cy)` (2D, angle instead of quaternion — a 180° flip becomes
    `rotateAbout((float) Math.PI, cx, cy)`).
  - Method renames: `drawString(...)` → `text(...)`; `drawCenteredString(...)` →
    `centeredText(...)`. `blit(Identifier, x, y, u, v, w, h, texW, texH)` (9-arg, no
    pipeline) is gone — the closest equivalent now requires an explicit
    `RenderPipeline` first argument (use `RenderPipelines.GUI_TEXTURED` for a plain
    textured icon blit): `blit(RenderPipeline, Identifier, x, y, u, v, w, h, texW,
    texH)`.
  - Multi-line label rendering: `MultiLineLabel.renderCentered(...)`/
    `.renderLeftAligned(...)` are **gone** — replaced by a "collector" pattern:
    `label.visitLines(TextAlignment.CENTER/LEFT/RIGHT, x, y, lineHeight,
    graphics.textRenderer())`, where `graphics.textRenderer()` returns an
    `ActiveTextCollector` bound to the current `GuiGraphicsExtractor`.
  - `Gui.render(GuiGraphics, DeltaTracker)` (the HUD-overlay render hook,
    mixed into by `MixinGui_Overlay.java`) → `Gui.extractRenderState(GuiGraphicsExtractor,
    DeltaTracker)`.
- **Input event API rewrite**: mouse/keyboard callbacks no longer take raw
  primitives — `mouseClicked(double x, double y, int button)` →
  `mouseClicked(MouseButtonEvent event, boolean doubleClick)`
  (`event.x()`/`.y()`/`.button()`/`.modifiers()`); `mouseDragged(double,double,int,double,double)`
  → `mouseDragged(MouseButtonEvent event, double dragX, double dragY)`; key/char
  callbacks similarly take `KeyEvent`/`CharacterEvent` records
  (`net.minecraft.client.input` package: `KeyEvent.key()`/`.scancode()`/`.modifiers()`,
  `CharacterEvent.codepointAsString()`). Applied to `DimEntryWidget.java`/
  `DimListWidget.java`; **`ExampleGuiPortalRendering.java` still has 2 leftover
  `keyPressed(int,int,int)`/`KeyMapping.matches(int,int)`-shaped call sites that were
  not part of this pass** (see "Not yet investigated" below).
- **Sodium 0.9.1's `OcclusionCuller` API fully redesigned** — `findVisible(...)` used
  to take a single `OcclusionCuller.Visitor` + a `useOcclusionCulling` boolean + a
  `frame` int; it now takes **three** separate visitor types (`GraphOcclusionVisitor`
  ×2, `VisibilityTestingVisitor`) plus a `CancellationToken`, with no single
  "useOcclusionCulling" override flag left at all; `isWithinFrustum(...)` was also
  renamed to `isWithinNearbySectionFrustum(...)`. `MixinSodiumOcclusionCuller.java`'s
  portal cave-culling override (redirect the culling iteration start point to the
  portal's visible-section origin, tolerate an initial out-of-frustum start point) has
  **no straightforward mapping onto the new 3-visitor shape** — stubbed to an empty
  mixin shell with a `TODO` (a performance-only regression: cave culling through
  portals now behaves like vanilla Sodium's own culling, nothing is functionally
  broken).
- **`Minecraft.ON_OSX`** field removed entirely — replaced with
  `Util.getPlatform() == Util.OS.OSX` (`net.minecraft.util.Util`/`Util.OS` enum:
  `LINUX`/`SOLARIS`/`WINDOWS`/`OSX`/`UNKNOWN`). Also fixed 2 leftover `RenderTarget`
  API mismatches from the earlier GL/shader pipeline cluster that this surfaced:
  `renderTarget.viewWidth`/`.viewHeight` → `.width`/`.height`, and
  `RenderTarget.resize(w, h, boolean)` → `resize(w, h)` (2-arg, no OS-specific flag
  anymore) in `IPPortingLibCompat.java`/`MixinRenderTarget.java`.
- **`Camera.getPosition()`** renamed to **`Camera.position()`** (`BlockManipulationClient.java`).

## Blocking / external dependency issues

- **DimLib** (`com.github.iPortalTeam:DimLib`) — unconditional dependency
  (`api` + `include`). Latest release is `v1.1.0-mc1.21.1` (Sep 2024); **no 26.1
  build exists**. This is our own library (iPortalTeam), so it needs to be migrated
  in parallel — blocks a fully working build until done. Pinned to the old version in
  `gradle.properties` with a `TODO BLOCKING` comment.
- **GravityChanger** (`com.github.qouteall/GravityChanger`) — upstream repo is
  **archived** (read-only since Apr 2026), last release targets mc1.20.4. Already
  disabled by default (`enable_gravity_changer=false`), non-blocking but permanently
  dead unless forked/replaced.
- `geckolib` test dependency (`enable_geckolib=false`, off by default) — not
  investigated, low priority.

## Remaining work

### 1. Post-`ValueInput`/`ValueOutput` cleanup wave — large batch DONE, tail remains

Item 1 from the previous revision of this doc (the `ValueInput`/`ValueOutput`
save-data rewrite) is **DONE**. The large wave of newly-surfaced errors it unmasked
has now also mostly been fixed, in order:

- **`ResourceKey<T>.location()` → `.identifier()`**: DONE, full-codebase sweep (not
  just the 8 files `javac` initially reported — a follow-up `grep_search` found ~15
  more files where the same rename was needed but hadn't been recompiled yet).
  Confirmed via `javap` that `ResourceKey<T>` has *only* `.identifier()` now, no
  `.location()` at all, so this was safe to blanket-replace everywhere.
- **`getProfiler()` removed from `Level`/`MinecraftServer`/`Minecraft`**: DONE.
  Replacement is a static accessor: `net.minecraft.util.profiling.Profiler.get()`
  returns the current thread's `ProfilerFiller` (no receiver needed at all — it's a
  static/thread-local-style holder now, not a per-instance getter). Fixed via a
  Python regex pass replacing `<any-receiver-expr>.getProfiler()` with
  `Profiler.get()` and auto-inserting the import, across 19 files (50 call sites).
  Also had to fix `MixinMinecraft.java`'s `@Shadow public abstract ProfilerFiller
  getProfiler();` (a shadow of a now-nonexistent method) and `ClientWorldLoader.java`'s
  `CLIENT::getProfiler` method reference (→ `Profiler::get`, matches
  `Supplier<ProfilerFiller>` for the `ClientLevel` constructor param).
- **`ChunkPos` reshaped to a record**: DONE. `ChunkPos` is now `public final class
  ChunkPos extends Record` with private fields and `x()`/`z()` accessor methods
  (not the old public `x`/`z` fields). `ChunkPos.asLong(int,int)` renamed to
  `ChunkPos.pack(int,int)`; instance `.toLong()` renamed to `.pack()`. Fixed via
  regex across all files, then a manual sweep for remaining `.x`/`.z` field access
  (found extra spots in `ImmPtlChunkTracking.java`'s `lowPos`/`highPos` loop bounds
  and `ErrorTerrainGenerator.java` that weren't in the original error list).
- **`hasPermission(int)` on `CommandSourceStack` / `hasPermissions(int)` on `Player`
  removed** (new permission system): DONE. Command permission checking moved to a
  `Permission`/`PermissionSet`/`PermissionCheck` object model
  (`net.minecraft.server.permissions` package) instead of raw integer op-levels.
  `Commands.LEVEL_ALL`/`LEVEL_MODERATORS`/`LEVEL_GAMEMASTERS`/`LEVEL_ADMINS`/
  `LEVEL_OWNERS` are `PermissionCheck` constants matching the old levels 0-4
  respectively. For `.requires(x -> x.hasPermission(N))` command-tree predicates,
  replaced with `.requires(Commands.hasPermission(Commands.LEVEL_X))` (returns a
  `Predicate<CommandSourceStack>` directly). For inline boolean checks
  (`expr.hasPermission(N)`), replaced with `Commands.LEVEL_X.check(expr.permissions())`
  (`PermissionCheck.check(PermissionSet)` — `CommandSourceStack`/`ServerPlayer` both
  have a `.permissions()` accessor returning `PermissionSet`). For
  `CommandSourceStack.withPermission(int)` (used to elevate permissions for
  programmatic command execution), replaced with
  `.withPermission(LevelBasedPermissionSet.GAMEMASTER)` (`LevelBasedPermissionSet` in
  the same package has `ALL`/`MODERATOR`/`GAMEMASTER`/`ADMIN`/`OWNER` constants that
  implement `PermissionSet` directly). Fixed across `PortalCommand.java` (30
  call sites), `PortalDebugCommands.java` (20), `PortalWandInteraction.java`,
  `McHelper.java`, `CommandStickItem.java`.
- **`ServerPlayer.server` field now private / `Entity.getServer()` still doesn't
  exist**: DONE. Both replaced with `<entityOrPlayer>.level().getServer()` (or
  `((ServerLevel) entity.level()).getServer()` when the static type is base `Entity`/
  `Level` rather than already `ServerLevel`/`ServerPlayer`) — same pattern as the
  `Entity.getServer()` fix from the `ValueInput` round, just needed a wider sweep
  across `BlockManipulationServer.java`, `ImmPtlChunkTracking.java`,
  `PlayerChunkLoading.java`, `ClientDebugCommand.java`, `CustomPortalGenManager.java`,
  `ServerTeleportationManager.java`, `PortalWandInteraction.java`,
  `MixinCardinalCompComponentKey.java`.
- **`EntityType<T>.create(Level)`/`.create(ServerLevel)` (1-arg) removed**: DONE.
  New signatures are `create(Level, EntitySpawnReason)` and `create(ServerLevel,
  Consumer<T>, BlockPos, EntitySpawnReason, boolean, boolean)`; since `ServerLevel
  extends Level`, just appending `, EntitySpawnReason.TRIGGERED` to every 1-arg
  `create(...)` call satisfies the simpler 2-arg overload for both. Fixed via a
  balanced-paren Python script (regex alone can't handle nested-paren call
  arguments like `create(McHelper.getServerWorld(x))`) across 13 files, all
  `X.ENTITY_TYPE.create(...)`/`entityType.create(...)`/`getType().create(...)` call
  sites (26 total).
- **`Direction.getNormal()` (returned `Vec3i`) / `Direction.getNearest(double,double,
  double)` remaining stragglers**: DONE — a few call sites in `PortalCommand.java`
  and `BlockManipulationServer.java`/`BlockManipulationClient.java`/`IPMcHelper.java`
  weren't caught by earlier rounds' sweeps (hadn't been recompiled yet). Same fixes
  as before: `getNormal()` → `new Vec3(d.getStepX(), d.getStepY(), d.getStepZ())` (or
  `new Vec3i(...)` when a `Vec3i` is needed, e.g. `IntBox.getMoved(Vec3i)`);
  `getNearest(double,double,double)` → `getApproximateNearest(double,double,double)`,
  and where the 3 args were literally `vec.x, vec.y, vec.z`, simplified to the
  `getApproximateNearest(Vec3)` overload directly.
- **`Camera.getPosition()` removed**: DONE — renamed to `Camera.position()` (a
  `Vec3`-returning record-style accessor). Swept across 15 files (mostly
  `client.gameRenderer.getMainCamera().getPosition()` call sites in rendering code).
- **NBT `getAsNumber()`/`getAsByte()`/`CompoundTag.getAllKeys()`**: DONE. `ByteTag`/
  `ShortTag`/`IntTag`/`LongTag` implement `NumericTag` with `.byteValue()`/
  `.intValue()`/etc and `.box()` (returns `Number` directly — replaces
  `getAsNumber()`). `getAsByte()` → `.byteValue()`. `CompoundTag.getAllKeys()` →
  `.keySet()`. Also swept a few more leftover `getCompound(...)`/`getList(...,int)`
  Optional-unwrap spots in `GlobalPortalStorage.java` found via a broader grep.
- **`LevelHeightAccessor.getMinBuildHeight()`/`getMaxBuildHeight()` → `getMinY()`/
  `getMaxY()`**: DONE — **not a plain rename, a semantic shift**: confirmed via the
  real decompiled source (extracted from the `-sources.jar` with `zipfile`) that
  `getMaxY() = getMinY() + getHeight() - 1`, i.e. **inclusive** top Y, whereas old
  `getMaxBuildHeight()` was **exclusive** (one past the top). `getMinY()` is
  equivalent to old `getMinBuildHeight()` (no shift). Each call site was fixed
  according to its actual usage (loop bounds needed `<=` instead of `<`; comparisons
  needed re-deriving; one helper explicitly named `getMaxYExclusive` kept its
  exclusive contract via `+ 1`) — verified the `handleBlockBreakAction` case
  specifically against vanilla's own updated call site in
  `ServerGamePacketListenerImpl.java` (confirmed it now passes `getMaxY()` directly,
  no `+1`, meaning that method's internal contract shifted in lockstep).
  **General lesson: height/bound-accessor renames are not always safe as blind
  mechanical renames — always verify inclusive/exclusive semantics via decompiled
  source before applying.**

- **`Direction.getNormal()` (returned `Vec3i`) — full cluster (all 10 files) and
  `Direction.fromDelta(int,int,int)`**: DONE. `getNormal()` → `d.getUnitVec3i()`
  (returns `Vec3i` directly) or `d.getUnitVec3()` (returns `Vec3` directly,
  replaces the old `Vec3.atLowerCornerOf(d.getNormal())` idiom in one call) — both
  confirmed via `inspect_class.py` as clean drop-in convenience methods on
  `Direction`. `fromDelta(int,int,int)` fully removed (no replacement with the same
  exact-match semantics) — since call sites here always transform an already-exact
  unit vector, replaced with `Direction.getNearest(int,int,int, Direction
  fallback)` (still exists) using `Direction.UP` as a never-actually-used fallback.
  `Direction.getNearest(double,double,double)` (3-arg, no fallback) → renamed to
  `Direction.getApproximateNearest(...)`.
  **Watch out**: this codebase also has an unrelated, completely valid
  `Portal.getNormal()`/`PortalState.getNormal()` custom method with the exact same
  name (the portal's own facing normal, nothing to do with `Direction`) — a bulk
  regex rename by method-name-only will blindly break these too. Always diff
  changed lines against the compiler's error-location list afterward when doing
  name-based bulk renames where the name could collide across unrelated types.
- **`Camera.getPosition()` removed**: DONE — renamed to `Camera.position()` (a
  `Vec3`-returning record-style accessor). Swept across 15 files (mostly
  `client.gameRenderer.getMainCamera().getPosition()` call sites in rendering code).
- **NBT `getAsNumber()`/`getAsByte()`/`CompoundTag.getAllKeys()`**: DONE. `ByteTag`/
  `ShortTag`/`IntTag`/`LongTag` implement `NumericTag` with `.byteValue()`/
  `.intValue()`/etc and `.box()` (returns `Number` directly — replaces
  `getAsNumber()`). `getAsByte()` → `.byteValue()`. `CompoundTag.getAllKeys()` →
  `.keySet()`. Also swept a few more leftover `getCompound(...)`/`getList(...,int)`
  Optional-unwrap spots in `GlobalPortalStorage.java` found via a broader grep.
- **`LevelHeightAccessor.getMinBuildHeight()`/`getMaxBuildHeight()` → `getMinY()`/
  `getMaxY()`**: DONE — **not a plain rename, a semantic shift**: confirmed via the
  real decompiled source (extracted from the `-sources.jar` with `zipfile`) that
  `getMaxY() = getMinY() + getHeight() - 1`, i.e. **inclusive** top Y, whereas old
  `getMaxBuildHeight()` was **exclusive** (one past the top). `getMinY()` is
  equivalent to old `getMinBuildHeight()` (no shift). Each call site was fixed
  according to its actual usage (loop bounds needed `<=` instead of `<`; comparisons
  needed re-deriving; one helper explicitly named `getMaxYExclusive` kept its
  exclusive contract via `+ 1`) — verified the `handleBlockBreakAction` case
  specifically against vanilla's own updated call site in
  `ServerGamePacketListenerImpl.java` (confirmed it now passes `getMaxY()` directly,
  no `+1`, meaning that method's internal contract shifted in lockstep).
  **General lesson: height/bound-accessor renames are not always safe as blind
  mechanical renames — always verify inclusive/exclusive semantics via decompiled
  source before applying.**
- **`server has private access in ServerPlayer` / `Entity.getServer()` — wider
  sweep DONE**: `Entity.getServer()` confirmed fully removed (not on `Entity` at
  all via javap); `ServerPlayer.server` field confirmed `private final`. Fixed
  with `<x>.level().getServer()` — note **`Level.getServer()` itself exists**, so
  no `ServerLevel` cast is actually required at any of these call sites (a
  `((ServerLevel) x.level())` cast still compiles too, just redundant). Swept
  ~16 files including `MixinChunkMap_E`, `MixinTrackedEntity`, `MixinPlayerList`
  (+`_Misc`), `MixinItemEntity_P`, `MixinServerGamePacketListenerImpl`,
  `ImmPtlNetworking`, `PacketRedirection`, `MixinServerPlayerEntity_MA`,
  `RequiemCompat`, `BreakablePortalEntity`, `EndPortalEntity`, `CommandStickItem`,
  `DimStackManagement`.
- **`isClientSide has private access in Level`**: DONE — `Level.isClientSide`
  field is now private, use the `.isClientSide()` method call instead (still
  exists, confirmed via javap). Fixed across 8 files (`IPMcHelper`, `MixinEntity`,
  `BreakablePortalEntity` ×2, `BreakableMirror`, `ScaleUtils` ×2,
  `MixinEnderEyeItem_CVB`).
- **`displayClientMessage(MutableComponent/Component,boolean)`**: DONE — method
  fully renamed to `sendSystemMessage(Component,boolean)` on `ServerPlayer`
  (confirmed via javap: both 1-arg and 2-arg `sendSystemMessage` overloads exist on
  `ServerPlayer`; base `Player` only has the 1-arg one, so verify the receiver is
  actually `ServerPlayer`-typed before a blind rename). Fixed across 7 files.
- **`registryOrThrow(...)`**: DONE — renamed to `lookupOrThrow` on `RegistryAccess`
  (confirmed via javap; old name fully gone). Fixed across 5 files. This also
  unmasked `.asLookup()` (14 errors) — no longer needed at all since `Registry<T>`
  now directly `extends HolderLookup.RegistryLookup<T>` (implements `HolderGetter`
  transitively), so `rm.lookupOrThrow(key)`'s result already satisfies
  `HolderGetter<E>`-typed parameters without any conversion call. Fixed in
  `AlternateDimensions.java`.
- **`playS2C()`/`playC2S()`** (Fabric API's `PayloadTypeRegistry`): DONE — renamed
  to `clientboundPlay()`/`serverboundPlay()` (confirmed via `javap -cp` directly
  against the `fabric-networking-api-v1` jar located under
  `~/.gradle/caches/modules-2/files-2.1/net.fabricmc.fabric-api/
  fabric-networking-api-v1/` — used the highest cached version since multiple were
  present. `inspect_class.py` only indexes the Minecraft jar, not Fabric API jars —
  use `javap -cp <located-jar>` directly for library classes). Fixed 4 files.
- **`cameraEntity`**: DONE — `Minecraft.cameraEntity` field removed, replaced by
  `getCameraEntity()`/`setCameraEntity(Entity)` methods. Fixed 3 files (all were
  reads, no writes needed).
- **`lines()`** (debug-rendering `RenderType.lines()`): DONE — `RenderType` moved
  package (already fixed in an earlier round) but its static factory methods like
  `lines()` moved to a **separate new class `RenderTypes`** (plural) in the same
  package — confirmed via listing `.class` entries in the merged jar. Fixed 4
  files (`PortalEntityRenderer`, `ClientPortalWandPortalCopy`/`Creation`/`Drag`).
- **`worldGenOptions()`**: DONE — `WorldData.worldGenOptions()` fully removed (no
  `WorldOptions` accessor on `WorldData`/`PrimaryLevelData` at all anymore).
  Real replacement found by grepping `MinecraftServer.java`'s decompiled source
  (from the `-sources.jar`): `MinecraftServer` now holds `WorldGenSettings`
  directly via a public `getWorldGenSettings()` getter; `.options()` on that
  returns the `WorldOptions` (unchanged shape, has `.seed()` etc). Fix:
  `server.getWorldData().worldGenOptions()` → `server.getWorldGenSettings()
  .options()`. Fixed 2 files.
- **`hasPermissions(int)` stragglers** (3 files Round 11's sweep missed): DONE —
  confirmed via javap that `Player`/`Entity` have no permission-level method at all
  anymore (`hasPermissions`/`hasPermission` fully gone, not renamed). Added a
  small helper `Helper.hasPermissionLevel(Player, int)` in `q_misc_util/Helper.java`
  (`player.permissions() instanceof LevelBasedPermissionSet lbps && lbps.level()
  .isEqualOrHigherThan(PermissionLevel.byId(level))`) since there's no direct
  1-line replacement for an arbitrary int-level check anymore. Not confirmed as
  the exact idiomatic vanilla pattern (no decompiled-source example found quickly)
  but compiles and is behaviorally equivalent for the normal case (ops.json-backed
  `LevelBasedPermissionSet` players).
- **`getOrigin()`** (Sodium `SectionRenderDispatcher.RenderSection`): DONE —
  renamed to `getRenderOrigin()` (confirmed via `javap` on the nested class,
  needs `Outer\`$Inner` FQN escaping in PowerShell). Fixed 3 call sites in
  `VisibleSectionDiscovery.java`.
- **`MixinClientPacketListener.java`'s `getX()`/`getY()`/`getZ()`**: DONE —
  `ClientboundPlayerPositionPacket` reshaped into a `record` with a `.change()`
  accessor returning a `PositionMoveRotation` record (`.position()` → `Vec3`,
  `.deltaMovement()` → `Vec3`, `.yRot()`/`.xRot()` floats) instead of flat x/y/z/
  yaw/pitch fields. Fixed via `packet.change().position().x/.y/.z`.

**Still remaining** (re-run `python migration_tools/parse_compile_errors.py --run`
for the live list; last full run: **303 errors / 110 distinct symbols**, down from
the 665 true baseline established in Round 13 — a 54% reduction):

- **`method does not override or implement a method from a supertype`** (12 errors,
  unchanged from before — `CommandStickItem.java`, `GlobalPortalStorage.java`,
  `LoadingIndicatorRenderer.java`) — `GlobalPortalStorage.java` is the deferred
  `SavedData`→Codec architectural item (see above); `LoadingIndicatorRenderer.java`/
  `PortalEntityRenderer.java` are the deferred `EntityRenderer<T,S>` redesign (item
  3 below, also responsible for the separate `wrong number of type arguments;
  required 2` cluster, 5 errors); `CommandStickItem.java` has a second, different
  override issue beyond the already-fixed `appendHoverText` — not yet investigated.
- **`incompatible types: Entity cannot be converted to class_1297` / `Direction
  cannot be converted to class_2350`** (10+4 errors, `GravityChangerInterface.java`)
  — confirmed genuinely stale external mod dependency, not an in-repo fix. Leave
  for last.
- **`incompatible types: CompoundTag cannot be converted to ValueOutput/ValueInput`
  / `Optional<String> cannot be converted to String`** (8+4+6 errors,
  `GlobalPortalStorage.java`, `McHelper.java`, `PortalCommand.java`,
  `MiscNetworking.java`, `PortalWandItem.java`) — the `GlobalPortalStorage.java`
  instances are part of the deferred SavedData item; the others may be independent
  leftover `ValueInput`/`ValueOutput`-family spots not yet investigated.
- **`incompatible types: ResourceKey<DimensionType> cannot be converted to
  class_5321<class_2874>` / `Identifier cannot be converted to class_2960`** (8+8
  errors, `AlternateDimensions.java`) — obfuscated-name leaks in this mod's OWN
  code (not an external dependency like GravityChanger) — **still not checked**
  whether this is a stale Mixin `@Shadow`/mapping mismatch fixable in-repo; flagged
  across 2 rounds now as needing this check.
- **`getId()`/`getName()`** (6+6 errors, `CHelper.java`, `ImmPtlNetworkConfig.java`,
  `O_O.java`) — not yet investigated.
- **`no suitable method found for startRiding(Entity,boolean)`** (6 errors,
  `ClientTeleportationManager.java`, `MixinServerPlayer.java`,
  `ServerTeleportationManager.java`) — mount API signature change, not yet
  investigated.
- **`depthMask(boolean)`** (6 errors, `RendererUsingStencil.java`) — GL state
  management API change, related to item 2's rendering-pipeline rewrite.
- **`incompatible types: ClientboundPlayerPositionPacket cannot be converted to
  IEPlayerPositionLookS2CPacket`** (4 errors, `MixinClientPacketListener.java`,
  `MixinServerGamePacketListenerImpl.java`) — likely the duck-interface cast for
  the packet record needs updating alongside the `getX/getY/getZ` fix just applied.
- **`x`/`z` has private access in `ChunkPos`** (4+4 errors, `ErrorTerrainGenerator.java`,
  `MixinTrackedEntity.java`) — leftover `ChunkPos` record-shape stragglers (same
  fix as the earlier `ChunkPos` cluster, `.x`/`.z` fields → `.x()`/`.z()` methods,
  just missed in these 2 files).
- **`constructor ReentrantBlockableEventLoop ... cannot be applied`** (4 errors,
  `MixinMinecraftServer_Misc.java`, `MixinMinecraft_RedirectedPacket.java`) — ctor
  signature change, not yet investigated.
- Smaller remaining items (2-5 errors each, not yet investigated): `wrong number of
  type arguments; required 2` (the `EntityRenderer<T,S>` item, item 3 below),
  `ChatComponent.addMessage`, `invalid method reference`, `getProjectionMatrix()`,
  `Optional<Integer>`/`Optional<Long> cannot be converted to int/long`,
  `ClickEvent is abstract` (still not investigated since Round 12),
  `lerpTo(double,double,double,float,float,int)`, `createCommandSourceStack()`,
  `getAsString()`, `getElementType()`, `alignCenter()`.

Recommended approach next session: the mechanical/API-rename-shaped items above
(`getId()`/`getName()`, `startRiding`, `ChunkPos` `x`/`z` stragglers,
`ReentrantBlockableEventLoop` ctor, `ClickEvent`) are likely all quick wins similar
to this round's sweep — investigate each via `inspect_class.py`/`javap` on the
relevant class first, don't guess. Leave the confirmed-external-dependency items
(`GravityChangerInterface.java`) for last. Still need to determine whether
`AlternateDimensions.java`'s obfuscated-name leaks are in-repo-fixable (flagged
twice now, never actually checked). The `SavedData`→Codec redesign
(`GlobalPortalStorage.java`) and `EntityRenderer<T,S>` redesign
(`LoadingIndicatorRenderer.java`/`PortalEntityRenderer.java`) both need dedicated
research/design sessions, not quick mechanical fixes — track them as their own
items alongside item 2/3 below. Re-run `parse_compile_errors.py --run` after each
batch.

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

### 3. Entity render-state pattern

`EntityRenderer<T>` → `EntityRenderer<T, S>` (same render-state-object family as
`Screen.extractRenderState` and `Lightmap.render(LightmapRenderState)`). Affects
`LoadingIndicatorRenderer.java` and `PortalEntityRenderer.java`. Needs the same kind
of render-state redesign as item 2 above — not yet investigated in depth. (Also now
listed as one of the error categories under item 1 above since it was part of the same
compile-error batch — investigate together.)

### 4. Small leftover items

- `ExampleGuiPortalRendering.java`: 2 leftover call sites using the old
  `keyPressed(int,int,int)`/`KeyMapping.matches(int,int)` shapes that weren't covered
  by the input-event-rewrite pass done elsewhere this round — needs the same
  `KeyEvent`-based treatment applied to `DimEntryWidget.java`/`DimListWidget.java`
  (small, isolated fix — just 1 file, ~6 errors).


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

1. **Tackle item 1 (post-`ValueInput`/`ValueOutput` cleanup wave)** — the newly
   surfaced 201-error/34-symbol batch. Start with the `.location()`→`.identifier()`
   sweep (46 errors, purely mechanical, proven-safe rename), then the `ChunkPos`
   private-field/`asLong`/`toLong` cluster (40 errors) and `getProfiler()` removal (28
   errors) — those three account for well over half the remaining errors. Full
   category breakdown is in "Remaining work" item 1 above.
2. Re-run `parse_compile_errors.py --run` after each sub-batch (fixing smaller/more
   mechanical categories first shrinks and clarifies what's left, same pattern that
   worked for every previous cluster in this migration).
3. Fix the small leftover item 4 (`ExampleGuiPortalRendering.java`'s 2 remaining
   `keyPressed`/`KeyMapping.matches` call sites) — quick, isolated.
4. Re-run `fabric.mod.json` / `*.mixins.json` metadata cleanup once the rest of the
   code compiles.
5. **Before attempting item 2 (portal rendering algorithm redesign):** get the mod to
   actually launch in a dev environment (`./gradlew runClient`) with portal rendering
   left in its current stubbed/no-op state, to establish a working baseline and start
   surfacing the Mixin-weave-time-only-verifiable issues (the ~8 disabled
   `MixinLevelRenderer` hooks, the `setupRender`-targeting hooks that reference a
   confirmed-removed method) that `compileJava` cannot catch. Only after that baseline
   works should the stencil-masking algorithm (direction already chosen, see item 2
   above) and clip-plane uniform system be redesigned, since both need real in-game
   visual feedback to get right — reference Distant Horizons' `common/.../render/blaze/`
   wrapper package throughout.


