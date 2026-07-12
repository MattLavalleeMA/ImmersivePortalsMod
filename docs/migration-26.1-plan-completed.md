# Migration plan: completed work log (1.21.1 → 26.1.2)

Companion to [migration-26.1-plan.md](migration-26.1-plan.md). That document tracks
**current status and outstanding work only**; this document is the append-friendly,
roughly-chronological historical changelog of every fix/rename/redesign already
applied during the migration. Consult this file for *how* an already-completed item
was fixed (exact API shapes, files touched, gotchas hit); consult the main plan for
*what's still left to do*. Nothing in this file is open work — if you're looking for
the next thing to work on, this is the wrong document.

## Compile-error count progression (for reference)

`build.gradle` passes `-Xmaxerrs 5000` to javac (added in Round 13) so error counts
from that point on are the TRUE full count, not capped at ~100 per invocation like
earlier rounds believed. Progression from the true baseline down to the current
count (see the main plan document for the current authoritative count):

665/152 (true baseline) → 589/150 (`getNormal()` cluster) → 543/141 (`method does
not override` batch) → 349/119 (large sweep: `server`/`getServer()`, `isClientSide`,
`displayClientMessage`, `registryOrThrow`, `playS2C`/`playC2S`, `cameraEntity`,
`RenderType.lines()`, `worldGenOptions()`, `hasPermissions` stragglers,
`Direction.fromDelta`, Sodium `getOrigin()`, packet record reshape) → 303/110
(further sweep: `GameProfile.getId/getName`, `Entity.startRiding`, `ChunkPos` `x`/`z`
stragglers, `ClickEvent` sealed-record subtypes, `Entity.lerpTo`→`snapTo`,
`StringTag.getAsString()`→`.value()`, `ListTag.getElementType()` removal,
`StringWidget.alignCenter()` removal, `Entity.createCommandSourceStack()`→
`createCommandSourceStackForNameResolution(ServerLevel)`) → 277/103 (full resolution
of the `method does not override or implement a method from a supertype` cluster,
including the two deferred architectural items, `SavedData`→Codec and
`EntityRenderer<T,S>`) → 221/84 → 167/61 (P1 medium-cluster round) → 131/49
(`MyGameRenderer.java` round) → 113/40 (`ClientWorldLoader`/`MixinLevelRenderer`
round) → 95/33 (`RendererUsingStencil`/`ImmPtlViewArea`/`RenderTarget` round) →
91/31 (`ClientboundSetTimePacket`/`WorldClock` round) → 61/18 (final mechanical
sweep — every remaining error confined to DimLib-/GravityChanger-blocked files).

Full detail on every fix is also in `/memories/session/mc26.1-migration.md` Round 15
(session memory — not duplicated there, that file tracks the full chronological
narrative; this file tracks fix-by-fix technical detail).

## Build & dependencies — done, verified

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

## Source code fixes

17 symbol categories resolved in the first pass (see `migration_tools/renames.json`
for the authoritative, current list of applied/pending renames):

### Mechanical package moves / renames

Applied via `bulk_rename.py`:
`ResourceLocation`→`Identifier`, `Util`→`util.Util`, `GameRules`→`gamerules.GameRules`,
`AbstractMinecart`/`Arrow`/`AbstractArrow`→`.minecart`/`.arrow` subpackages,
`ThrownEnderpearl`→`.throwableitemprojectile`, `EndDragonFight`→`EnderDragonFight`,
`DimensionTransition`→`TeleportTransition`, `RelativeMovement`→`Relative`,
`ReceivingLevelScreen`→`GenericWaitingScreen`, `DimensionDataStorage`→`SavedDataStorage`,
`ClientCommandManager`→`ClientCommands` (Fabric API), `RenderType`→`renderer.rendertype.RenderType`,
`FogRenderer`→`renderer.fog.FogRenderer`.

### API-shape fixes

Applied directly, not pure renames:
- `PortalPlaceholderBlock.java`: `FabricBlockSettings.create()` → `BlockBehaviour.Properties.of()`
  (Fabric API merged this wrapper into vanilla; `noCollission()` typo also fixed to `noCollision()`).
- `ExampleGuiPortalRendering.java`: `Screen.render(GuiGraphics, ...)` →
  `Screen.extractRenderState(GuiGraphicsExtractor, ...)`, `drawCenteredString(...)` →
  `centeredText(...)` (part of the GUI render-state-extraction rework).

### Chunk ticket system redesigned

`chunk_loading/ImmPtlChunkTickets.java` and its supporting duck interfaces: rewritten
to use vanilla's own high-level `ServerChunkCache#addTicketWithRadius`/
`removeTicketWithRadius` API (backed by the new `TicketStorage`, throttled internally
by vanilla's own chunk task dispatcher) instead of replicating vanilla's old low-level
ticket/mailbox internals, which no longer exist. Deleted
`IEChunkTaskPriorityQueueSorter.java`, `mixin/common/chunk_sync/IEDistanceManager.java`
(the accessor-based one), and `ducks/IEDistanceManager.java` (all dead — targeted
removed classes/fields). `MixinDistanceManager.java` stripped down to just its one
still-valid NPE-avoidance injection. `PortalDebugCommands.java`'s two ticket-listing
debug commands updated to read `TicketStorage` directly instead of the deleted duck.
Compile-verified: the `TicketType`/`Ticket`/`ChunkTaskPriorityQueueSorter`/
`ProcessorMailbox`/`IntRunnable` error categories are all gone.

### Lightmap rendering redesigned

`LightTexture` → `Lightmap`, affecting `DimensionRenderHelper`, `IEGameRenderer`,
`MixinGameRenderer`, `MyGameRenderer`, `RenderStates`, `ClientTeleportationManager`,
`ClientWorldLoader`: confirmed via Distant Horizons' `MixinLightTexture.java` and
decompiled `Lightmap.java`/`LightmapRenderState.java`/`LightmapRenderStateExtractor.java`/
`GameRenderer.java` that vanilla's `lightmap` field on `GameRenderer` is now `private
final` (was mutable), and the render-state is now split into three pieces: `Lightmap`
(GPU texture holder, no-arg constructor), `LightmapRenderState` (plain data holder), and
`LightmapRenderStateExtractor` (reads `Minecraft.level`/`.player` to populate a state,
tied to one `GameRenderer`+`Minecraft` pair). `DimensionRenderHelper` now holds all
three (for non-current dimensions only — the current dimension still reuses vanilla's
own shared instance via a new `IEGameRenderer.ip_getLightmap()` accessor), with a
`forceUpdate()` convenience method replacing the old `LightTexture#updateLightTexture(float)`
call. `MixinGameRenderer`'s `lightmap` field shadow uses `@Mutable` (matching the
pre-existing pattern already used for the old `lightTexture` field) so it can still be
swapped to point at another dimension's `Lightmap` while that dimension is being
rendered through a portal.

Also partially handled in `MixinParticleEngine.java`: `ParticleEngine.render(LightTexture,
Camera, float)` → `ParticleEngine.extract(ParticlesRenderState, Frustum, Camera,
float)` (same render-state-extraction pattern) — the portal-count injection was
retargeted. However `Particle.render(VertexConsumer, Camera, float)` no longer
exists at all (particle geometry building moved elsewhere in the new pipeline) — the
corresponding `redirectBuildGeometry` mixin is commented out with a `TODO`, tracked
as part of the portal-rendering-algorithm redesign in migration-26.1-plan.md rather
than guessed at.

### Rendering pipeline — mechanical fixes

Compile-clean, should work as-is: `GlStateManager`/`Uniform`/`GlDebug` package move
(`com.mojang.blaze3d.platform`→`.opengl`); `GraphicsStatus`→`GraphicsPreset`
(`Options.graphicsMode()`→`.graphicsPreset()`); `GlUtil.getVendor()`→
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

### `SavedData` → Codec architecture item (resolved)

**Newly-discovered architectural item, initially deferred, not mechanical**:
`SavedData` (base class for `GlobalPortalStorage`) no longer has `save(CompoundTag,
HolderLookup.Provider)`/load-via-factory at all — persistence moved entirely to a
`Codec<T>` registered via a `SavedDataType<T>` record, with `SavedDataStorage`
(renamed from `DimensionDataStorage`) handling serialization automatically. This
doesn't fit the "pure data" Codec model cleanly since `GlobalPortalStorage` holds
live `Portal` entities that need a specific `ServerLevel` to deserialize into.
**Resolved** — see the `GlobalPortalStorage.java` entry under "Post-`ValueInput`/
`ValueOutput` cleanup wave" below for the actual fix (a `CompoundTag.CODEC.xmap(...)`
bridge).

## Entity save-data rewrite (`ValueInput`/`ValueOutput`) — done

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
  `IntBox.java` (this round); more call sites turned up in `BlockManipulationClient.java`/
  `BlockManipulationServer.java` and were resolved in a later round — see the
  "full cluster (all 10 files)" entry below.
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
  `Portal.java`/`PortalAPI.java` this round — a broader `grep_search` turned up 100+
  more `.getServer()` call sites across the codebase, but the overwhelming majority
  were on `ServerLevel`/`MiscHelper`/`CommandSourceStack` receivers which are
  unaffected; the remaining 2 `Entity`-typed call sites (in `ImmPtlChunkTracking.java`)
  were resolved in a later round — see the "wider sweep DONE" entry below.
- `ResourceKey<T>.location()` → `.identifier()` swept again in this round's touched
  files (`UnilateralPortalState.java`, `MiscNetworking.java`'s `dimId`/
  `BuiltinDimensionTypes.OVERWORLD` call sites) — a much larger fresh batch (46
  errors) turned up in files not yet touched and was resolved in later rounds (see
  the "full-codebase sweep" entry below).
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

## Small mechanical fixes (done)

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
  quad-consuming API — needs the same kind of dedicated redesign work as the
  portal-rendering-pipeline items tracked in migration-26.1-plan.md.
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
  `DimListWidget.java`; `ExampleGuiPortalRendering.java`'s 2 leftover
  `keyPressed(int,int,int)`/`KeyMapping.matches(int,int)`-shaped call sites (not part
  of this pass) were confirmed gone in a later source inspection — no fix was needed.
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
- **`RenderType.debugLineStrip(int)`** removed — replaced with `RenderTypes.lines()`
  (the same pattern already used elsewhere for the primary vertex consumer) in
  `ClientPortalWandPortalCreation.java`/`ClientPortalWandPortalDrag.java`.
- **`CompoundTag.getString(String)`** now returns `Optional<String>` — use the
  direct default-value overload `getStringOr(String, String)` instead
  (`PortalWandItem.java`, `MiscNetworking.java`).
- **`InteractionResult.shouldSwing()`** removed — new idiom (confirmed via
  decompiled `ServerGamePacketListenerImpl.java`) is
  `result instanceof InteractionResult.Success success && success.swingSource() ==
  InteractionResult.SwingSource.SERVER` (`BlockManipulationServer.java`).
- **`RenderSystem.getProjectionMatrix()`** removed (now GPU-UBO-driven) — reused
  the mod's own mixin-captured `RenderStates.basicProjectionMatrix` static field
  with a null-safe fallback (`PortalRenderer.java`).
- **`Player` constructor** changed from `(Level, BlockPos, float, GameProfile)` to
  `(Level, GameProfile)` — spawn-position params dropped entirely from the ctor
  chain (`MixinServerPlayer.java`).
- **`BlockStateBase.isSolidRender(BlockGetter, BlockPos)`** → no-arg
  `isSolidRender()` (`FlippingFloorSquareForm.java`).
- **Cloth Config 26.1.154 API redesign**: `AutoConfig.getConfigScreen(Class,
  Screen)` fully removed; build the screen via `new ConfigScreenProvider<>(
  configManager, guiRegistryAccess, parentScreen).get()`, where `configManager`
  comes from casting `AutoConfig.getConfigHolder(Class)`'s result to
  `ConfigManager<T>` (safe — it's the only implementer), and `guiRegistryAccess`
  is `DefaultGuiProviders.apply(new GuiRegistry())`. `IPConfigGUI.java` rewritten.
- **`PortalShape.createPortalBlocks()`** → `createPortalBlocks(LevelAccessor)`
  (`IntrinsicPortalGeneration.java`).
- **`Camera.setup(Level, Entity, boolean, boolean, float)`** removed entirely —
  replaced with `camera.setLevel(level)` + `camera.setEntity(entity)` +
  `camera.update(DeltaTracker)`; the new `Camera.update()` internally derives
  detached/mirrored state from `Minecraft.options.getCameraType()`, which is
  exactly what the mod's own `isThirdPerson()`/`isFrontView()` helpers already
  compute, so this is behavior-preserving. Added a small
  `RenderStates.fixedDeltaTracker(float)` helper (wraps a fixed partial-tick
  float into a `DeltaTracker`, since no built-in factory exists for that) —
  applied in `CrossPortalViewRendering.java`/`TransformationManager.java`.
- **`GameRenderer.getDarkenWorldAmount(float)`** renamed to
  `getBossOverlayWorldDarkening(float)` (confirmed via
  `GameRenderer.extractCamera()`'s real call site), and
  **`FogRenderer.setupColor(Camera, float, ClientLevel, int, float)`** (static,
  void) removed — replaced by instance method `FogRenderer.setupFog(Camera, int,
  DeltaTracker, float, ClientLevel)`, which returns a `FogData` object with the
  color already computed as a `Vector4f` field. Added a lazily-created cached
  `FogRenderer` instance to avoid leaking GPU buffers on repeated calls
  (`FogRendererContext.java`). Note: this fixes only the cross-dimension
  fog-color *query* path — the separate *live current-world* fog-color-swap
  mechanism (`RendererUsingStencil.java`'s `getCurrentFogColor`) still depends on
  the now-weave-broken `MixinFogRenderer.java` and needs its own redesign (see
  migration-26.1-plan.md's outstanding-work section for the current redesign plan).
- **`sendSystemMessage(Component)`** only exists on `ServerPlayer`, not generic
  `Entity` — guarded with `instanceof ServerPlayer` in `ScaleUtils.java` (which
  operates on generic `Entity`, not always a player).
- **`SectionRenderDispatcher.uploadAllPendingUploads()`** removed with no
  replacement found (confirmed via javap — the whole per-section async-upload-
  future-pumping concept appears absent from the reworked chunk-render
  pipeline). Stubbed as a no-op with a `TODO` in `MyRenderHelper.java`'s
  `earlyRemoteUpload()` (gated behind the optional `IPCGlobal.earlyRemoteUpload`
  debug toggle, so low-risk).

**With this round, every genuinely mechanical/in-repo-fixable compile-error
cluster is done: 91 → 61 errors, and every one of the 61 remaining errors is
confined to the 7 known DimLib-/GravityChanger-blocked files** (confirmed by
listing distinct files across all remaining error groups).

## Post-`ValueInput`/`ValueOutput` cleanup wave — DONE

The large wave of newly-surfaced errors the `ValueInput`/`ValueOutput` save-data
rewrite unmasked has been fixed, in order:

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
- **`hasPermissions(int)` stragglers** (3 files a prior sweep missed): DONE —
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

**`method does not override or implement a method from a supertype`**: DONE (all
12 errors resolved, 3 unrelated root causes):
- `CommandStickItem.java`: `Item.getDescriptionId(ItemStack)` no longer exists —
  `Item.getDescriptionId()` is now `final` and no-arg (per-`Item`, not per-`ItemStack`).
  The per-stack customization point moved to overriding `Item.getName(ItemStack)`
  (returns `Component` directly, not a translation-key `String`) instead. Fixed by
  replacing the override with `getName(ItemStack)` returning
  `Component.translatable(data.nameTranslationKey)` (falls back to
  `super.getName(stack)` when the item has no `Data` component).
- `LoadingIndicatorEntity.java`: simply hadn't received the `hurtServer(ServerLevel,
  DamageSource, float)` override other entities got in the `ValueInput`/`ValueOutput`
  round (see above) — added the same `return false` stub.
- `GlobalPortalStorage.java` (the deferred `SavedData`→Codec item): **DONE**.
  Confirmed via decompiled source (`WeatherData.java`/`MapItemSavedData.java` as
  reference examples) that `SavedData` is now a bare marker base class (only
  `setDirty()`/`isDirty()`, no persistence methods at all) — a `SavedDataType<T>`
  record (`Identifier id, Supplier<T> constructor, Codec<T> codec, DataFixTypes`)
  is registered instead, and `SavedDataStorage.computeIfAbsent(SavedDataType<T>)`
  replaces the old `Factory`-based `computeIfAbsent(Factory<T>, String)`. The
  genuine design problem (codec decode has no `ServerLevel` to spawn live `Portal`
  entities into) was solved with a thin bridge, same pattern as the earlier
  `Portal`/`ValueInput`/`ValueOutput` fix: `CODEC = CompoundTag.CODEC.xmap(...)`
  wraps the *exact* pre-existing `CompoundTag`-shaped save format (renamed
  `save(CompoundTag, HolderLookup.Provider)` → `toSyncTag(CompoundTag)`, dropped
  the always-unused `registries` param) — decode just stashes the raw
  `CompoundTag` into a new no-arg-constructed instance's `pendingNbt` field; actual
  portal-entity spawning is deferred until `get(ServerLevel)` binds the instance to
  its real world (`bindToWorld`), which resolves `pendingNbt` via the existing
  `fromNbt(CompoundTag)` method unchanged. `world` field became non-final
  (previously set once in the constructor, now set by `bindToWorld` on first
  `get()`). Net effect: the entire nested `CompoundTag` portal-serialization tree
  (and the network-sync path, which reuses `toSyncTag`) needed zero changes — only
  the outer `SavedData` registration/lookup shape changed. Note: this does change
  the on-disk storage file's identity (now keyed by the `Identifier`
  `immersive_portals:global_portal` via `SavedDataType`, rather than the old
  bare-string `"global_portal"` key) — an unavoidable consequence of the API
  redesign, not preserved on purpose.
- `LoadingIndicatorRenderer.java`/`PortalEntityRenderer.java` (the deferred
  `EntityRenderer<T,S>` redesign): **DONE** (compiles; portal-content drawing
  itself remains gated on the still-open portal-rendering-algorithm redesign,
  tracked in migration-26.1-plan.md). Confirmed via decompiled source
  (`EntityRenderer.java`/`EntityRenderState.java`/`ArrowRenderer.java`) that
  `render(T, float, float, PoseStack, MultiBufferSource, int)` no longer exists at
  all — replaced by the same CPU-extract/GPU-submit split used everywhere else in
  this rewrite: `createRenderState()` (builds a fresh `S extends
  EntityRenderState`), `extractRenderState(T entity, S state, float partialTicks)`
  (reads live entity/world data into the state — still has the live entity), and
  `submit(S state, PoseStack, SubmitNodeCollector, CameraRenderState)` (the actual
  draw-call submission point, only has the extracted state, not the live entity).
  `getTextureLocation(T)` no longer exists on `EntityRenderer` at all (confirmed via
  decompiled source and grepping the base class's method list) — both overrides
  (which only ever returned `null`) were dead code, deleted outright.
  `LoadingIndicatorRenderer`: its old `render()` body was already fully commented
  out (dead code) — reduced to a minimal `EntityRenderState`-only renderer with no
  custom override needed.
  `PortalEntityRenderer`: `renderPortalInEntityRenderer(Portal)` (the actual portal
  -content draw hook, one of the rendering-pipeline stubs) only ever took the
  live `Portal`, not any of `render()`'s other params, so it needed to move to
  `submit()` (the timing-equivalent replacement for the old immediate `render()`
  call, still receiving a `PoseStack` matching the entity's transform) via a new
  `PortalRenderState` subclass that carries a `public Portal portal` field set in
  `extractRenderState`. The debug portal-shape-mesh wireframe (`WireRenderingHelper
  .renderPortalShapeMeshDebug`, unrelated to the rendering-pipeline stubs, still
  fully functional) needed a `MultiBufferSource`-shaped `VertexConsumer` at submit
  time — found `SubmitNodeCollector.submitCustomGeometry(PoseStack, RenderType,
  CustomGeometryRenderer)` (`CustomGeometryRenderer.render(PoseStack.Pose,
  VertexConsumer)` callback) as the new sanctioned mechanism for arbitrary
  immediate-mode geometry, and used it to keep calling the existing helper
  unchanged. `OverlayRendering.onRenderPortalEntity`/`renderBreakablePortalOverlay`
  had their `MultiBufferSource` param dropped entirely (confirmed unused — the only
  body, `renderBreakablePortalOverlay`, is already a stubbed no-op per the
  `BakedQuad` redesign item above) rather than inventing a fake one.

(The rest of the bullets formerly tracked as "still remaining" here — `getId()`/
`getName()`, `startRiding`, `ChunkPos` `x`/`z` stragglers, `ClickEvent`, the
`GlobalPortalStorage.java` `ValueInput`/`ValueOutput` stragglers — were also fixed
in subsequent rounds. `GravityChangerInterface.java` and `AlternateDimensions.java`
are confirmed external/blocked, not in-repo fixable — see migration-26.1-plan.md.
`ReentrantBlockableEventLoop` (`MixinMinecraftServer_Misc.java`,
`MixinMinecraft_RedirectedPacket.java`) was carried forward into the "P1
medium-cluster round" changelog below and is now fixed there.)

## `MixinCamera.java` weave-time-only fix — done

**`MixinCamera.java`'s `@Inject` targeting `Camera.setup(BlockGetter,Entity,
boolean,boolean,float)`** — **fixed**. That method overload no longer exists
(`Camera` was reworked around `update(DeltaTracker)`, which reads
detached/mirrored state directly from `Minecraft.options.getCameraType()`
instead of taking explicit booleans — confirmed via decompiled source). The 2
real call sites that used to call `Camera.setup(...)` directly
(`CrossPortalViewRendering.java`, `TransformationManager.java`) were fixed by
calling `.setLevel(...)`/`.setEntity(...)` then
`.update(RenderStates.fixedDeltaTracker(partialTick))` instead (a new small
helper added to `RenderStates` that wraps a fixed partial tick into a
`DeltaTracker`) — this produces identical behavior since the mod's own
`isThirdPerson()`/`isFrontView()` helpers already just read
`client.options.getCameraType()` the same way `Camera` does internally now, so
nothing was actually lost. `MixinCamera.java`'s injection target itself has
now also been updated to target `update(DeltaTracker)` at `RETURN` (confirmed
via decompiled `Camera.update()` that entity/level are already set by the time
it's called, and `WorldRenderInfo.adjustCameraPos(...)` only needs the
post-update camera state, same as before) — also corrected the `@Shadow level`
field's declared type from the stale `BlockGetter` to the real field's actual
type `Level` (was only compiling before because `Level extends BlockGetter`,
a widening that happened to be assignment-compatible but wasn't the real
shadowed type). Rebuilt and confirmed the error count is unchanged (this was
never a compile error) — no regressions.

## Changelog — P1 medium-cluster round (221 → 167 errors, 84 → 61 symbols)

All fixed, zero regressions (verified by diffing every touched file's error list
before/after):

- **Fabric API attachment-sync redesign**: `AttachmentChange.partitionAndSendPackets
  (List<AttachmentChange>, ServerPlayer)` (a Fabric-internal method, package
  `net.fabricmc.fabric.impl.attachment.sync`) no longer exists on `AttachmentChange`
  at all — moved to `AttachmentSync.trySync(List<AttachmentChange>, ServerPlayer)`
  (confirmed via `javap` on the nested `fabric-data-attachment-api-v1` jar, extracted
  from the outer Fabric API "jar of jars" the same way prior rounds did). Fixed in
  `PlayerChunkLoading.java`.
- **`Identifier.of(String,String)` removed** — renamed to
  `Identifier.fromNamespaceAndPath(String,String)` (confirmed via `javap`; `Identifier`
  gained several other named factories too — `parse`/`tryParse`/`withDefaultNamespace`/
  `bySeparator` — but this exact 2-arg shape maps 1:1 to `fromNamespaceAndPath`).
  Fixed in `ImmPtlChunkTickets.java`.
- **`Entity.canChangeDimensions(Level,Level)` removed, renamed to
  `Entity.canTeleport(Level,Level)`** (confirmed via decompiled source — same exact
  signature, just renamed as part of the new `TeleportTransition`-based
  cross-dimension teleport rewrite). Fixed in `ServerTeleportationManager.java`.
- **`Entity.moveTo(double,double,double,float,float)`/`Entity.moveTo(double,double,double)`
  removed, renamed to `Entity.snapTo(...)`** (same signatures, confirmed via
  decompiled source and `javap` — part of the same rename family as the
  already-completed `Entity.lerpTo`→`snapTo`). `Entity.absMoveTo(...)` similarly
  renamed to `Entity.absSnapTo(...)`. Fixed in `ServerTeleportationManager.java`,
  `ImmPtlNetworking.java`, `MixinServerGamePacketListenerImpl.java`.
- **`ServerLevel.getSharedSpawnPos()` removed** — spawn position moved into a new
  `LevelData.RespawnData` record (`dimension()`/`pos()`/`yaw()`/`pitch()` accessors,
  replacing several separate fields); fix is `level.getRespawnData().pos()`. Fixed in
  `ServerTeleportationManager.java`.
- **Fabric API `PayloadTypeRegistry`/networking renames** (same "S2C/C2S" →
  "clientbound/serverbound" rename family already applied to the play-phase
  registry in an earlier round, now swept for the configuration phase and for
  `ClientPlayNetworking` too): `PayloadTypeRegistry.configurationS2C()`/
  `.configurationC2S()` → `.clientboundConfiguration()`/`.serverboundConfiguration()`;
  `ClientPlayNetworking.createC2SPacket(...)` → `.createServerboundPacket(...)`;
  `ServerConfigurationNetworking.Context.networkHandler()` → `.packetListener()`.
  Fixed in `ImmPtlNetworkConfig.java`, `ClientTeleportationManager.java`,
  `ImplRemoteProcedureCall.java` (confirmed via `javap` on the nested
  `fabric-networking-api-v1` jar).
- **`FriendlyByteBuf.writeResourceLocation`/`readResourceLocation` renamed to
  `writeIdentifier`/`readIdentifier`** (matches the overall `ResourceLocation`→
  `Identifier` rename theme already applied everywhere else). Fixed in
  `ImplRemoteProcedureCall.java`.
- **`ChatComponent.addMessage(Component)` removed entirely** — split into
  `addClientSystemMessage(Component)` (client-generated diagnostic messages, used
  here) and `addServerSystemMessage(Component)`/`addPlayerMessage(...)` (confirmed
  via `javap`). Fixed in `ImplRemoteProcedureCall.java`, `CHelper.java`.
- **`ReentrantBlockableEventLoop(String)` ctor gained a required trailing `boolean`**
  (confirmed via decompiled `Minecraft`/`MinecraftServer` source: `super("Client",
  true)` / `super("Server", propagatesCrashes)`). Both of this mod's mixin classes
  that extend it purely to satisfy Mixin's bytecode-merging requirements (their
  constructors are never actually invoked — `MixinMinecraftServer_Misc`'s explicitly
  throws right after `super(...)`) just needed a filler boolean added: `true` for
  `MixinMinecraft_RedirectedPacket` (mirroring `Minecraft`'s own hardcoded `true`),
  `false` for `MixinMinecraftServer_Misc` (value is irrelevant, dead code).
- **`ClientboundPlayerPositionPacket` becoming a `record` breaks the classic Mixin
  duck-interface cast trick**: records are implicitly `final`, and per JLS 5.5 javac
  statically rejects casting a `final`-typed reference to an unrelated interface
  unless that class's own (visible-to-javac) declaration implements it — which a
  Mixin-added `implements` doesn't satisfy since Mixin's bytecode weaving happens
  after javac runs. **General fix for this exact situation**: cast through `Object`
  first (`(IEPlayerPositionLookS2CPacket) (Object) packet`) — legal because the
  final-class restriction doesn't apply transitively through an intermediate cast to
  `Object`. Fixed at both real cast sites (`MixinClientPacketListener.java`,
  `MixinServerGamePacketListenerImpl.java`) — the cast from `this` inside the actual
  mixin class implementing the interface didn't need this (non-final source type).
  Separately, `ClientboundPlayerPositionPacket`'s canonical constructor reshaped from
  `(double,double,double,float,float,Set<Relative>,int)` to `(int teleportId,
  PositionMoveRotation change, Set<Relative> relatives)` (position/rotation delta
  fields folded into a shared `PositionMoveRotation(Vec3 position, Vec3
  deltaMovement, float yRot, float xRot)` record, `deltaMovement` set to `Vec3.ZERO`
  since this mod's teleport packet never had a velocity-delta concept). Fixed in
  `MixinServerGamePacketListenerImpl.java`. **Not yet investigated at the time**: the
  packet's own `write(FriendlyByteBuf)`/`<init>(FriendlyByteBuf)` methods (targeted
  by 2 `@Inject`s in `MixinPlayerPositionLookS2CPacket.java`/
  `MixinClientboundPlayerPositionPacket.java` to smuggle the extra dimension field
  over the wire) no longer exist at all on the record — serialization is now
  entirely via the static `STREAM_CODEC` field. These 2 `@Inject`s target
  nonexistent methods, which (per the established pattern for Mixin `method=`
  targets) isn't caught by `compileJava`, only at weave time/game launch. Needs a
  genuine redesign (wrap/redirect the `STREAM_CODEC` itself) — flagged, not
  attempted, since it's weave-time-only-verifiable; still open (see
  migration-26.1-plan.md if this hasn't been picked up yet).
- **`ChunkMap.TrackedEntity.broadcastAndSend(Packet)` renamed to
  `sendToTrackingPlayersAndSelf(Packet<? super ClientGamePacketListener>)`**
  (confirmed via `javap` — the class also gained `sendToTrackingPlayers`/
  `sendToTrackingPlayersFiltered` variants; `...AndSelf` is the closest match to the
  old broadcast-and-send-to-owner semantics). This also required widening
  `McHelper.sendToTrackers(Entity, Packet<?>)`'s own parameter type to `Packet<?
  super ClientGamePacketListener>` to satisfy the new method's bound — verified all
  3 existing call sites already pass compatible packet types. Fixed in
  `McHelper.java`.
- **`Entity.saveWithoutId`/`.load` `CompoundTag`→`ValueOutput`/`ValueInput`
  stragglers**: same `TagValueInput`/`TagValueOutput` bridge established in the
  previous round, applied to one more call site (`McHelper.copyEntity`). Fixed in
  `McHelper.java`.
- **`ActiveProfiler.WARNING_TIME_NANOS`**: the field itself still exists unchanged —
  this was just a missing `import net.minecraft.util.profiling.ActiveProfiler;`
  (confirmed via `javap` that the field is present and public), not an API change at
  all. Fixed in `PortalDebugCommands.java`.

## Changelog — `MyGameRenderer.java` round (167 → 131 errors, 61 → 49 symbols)

All 36 errors in this one file resolved, zero regressions. `MyGameRenderer.java`
mirrors vanilla's own `GameRenderer`/`FogRenderer`/`Lighting`, and turned out to be
a genuine mechanical-fix cluster after all (not deferred runtime work), despite
superficially resembling the already-deferred rendering-pipeline item — every
symbol had a real, confirmable replacement:
- **`GameRenderer` gained a CPU-extract/GPU-render split**, same pattern as
  `EntityRenderer<T,S>`/GUI elsewhere in this migration: new `extract(DeltaTracker,
  boolean)`/`render(DeltaTracker, boolean)` methods, with `renderLevel(DeltaTracker)`
  (still directly callable, used unchanged by this mod) now internally reading from
  a pre-populated `GameRenderState` snapshot rather than fresh mutable globals. Not
  otherwise consequential for this file's fix (nothing here needed to call
  `extract`/`render` directly), but worth knowing this exists for the still-open
  portal-rendering-algorithm item, since it explains *why* so much of
  `GameRenderer`'s old imperative API surface disappeared.
- **`RenderSystem.getProjectionMatrix()`/`GameRenderer.resetProjectionMatrix(Matrix4f)`
  removed** — the projection matrix is now GPU-buffer-backed
  (`RenderSystem.getProjectionMatrixBuffer()`), not a plain CPU `Matrix4f`, so it can
  no longer be captured/restored by value at all. `RenderSystem` itself now provides
  a purpose-built replacement for exactly this save/restore use case:
  `RenderSystem.backupProjectionMatrix()`/`.restoreProjectionMatrix()` (confirmed via
  `javap`) — removed the local `oldProjectionMatrix` variable entirely in favor of
  this pair.
- **`RenderSystem.applyModelViewMatrix()` removed**, no replacement needed — the
  model-view matrix is read live from `RenderSystem.getModelViewStack()` at draw
  time now (confirmed via decompiled source), there's no separate "apply to shader
  state" step left to call. Removed both call sites with a `TODO` note. Also
  confirmed (but did not change, since it wasn't a compile error) that
  `RenderSystem`'s `modelViewStack` field became `private static final` — the
  existing `IERenderSystem` `@Mutable`-`@Accessor` swap-the-whole-stack-instance
  trick this mod uses to save/restore it still compiles (Mixin's `@Mutable` permits
  writing `final` fields), but swapping the canonical stack *instance* out for a
  fresh one is in tension with the new design's apparent intent of a single
  always-live shared instance — flagged as a semantic risk worth revisiting, not
  addressed now since it isn't a compile error.
- **`Minecraft.getTimer()` renamed to `getDeltaTracker()`** (return type changed
  `Timer`→`DeltaTracker` to match `GameRenderer.renderLevel(DeltaTracker)`'s param
  type exactly).
- **`GameRenderer.getDarkenWorldAmount(float)` renamed to
  `getBossOverlayWorldDarkening(float)`** (confirmed via `javap`; same purpose/shape).
- **`EntityRenderDispatcher.prepare(ClientLevel, Camera, Entity)` dropped the
  `ClientLevel` param** — now just `prepare(Camera, Entity)` (confirmed via `javap`).
- **`GameRenderer.setRenderHand(boolean)`/the backing `renderHand` field removed
  entirely** (confirmed via `javap --private` — not just made inaccessible, gone).
  Since `IEGameRenderer`'s existing duck (`ip_getDoRenderHand()`) already
  `@Shadow`ed this exact field (previously working, now silently broken at Mixin
  weave time only), converted it to a self-contained `@Unique` field on
  `MixinGameRenderer` and added a matching `ip_setDoRenderHand(boolean)` to both the
  duck interface and its implementation. This fixes the compile-time contract
  cleanly, but doesn't by itself make hand-rendering actually respect the flag again
  — that also needs the separate `renderItemInHand` signature-mismatch fix (already
  tracked/fixed alongside the rendering-pipeline mechanical fixes above).
- **`BlockEntityRenderDispatcher.level` field removed with no replacement**
  (reconfirmed — same finding as the P1 round's `ClientTeleportationManager` fix).
  Stubbed out with a `TODO` at both call sites (set-before/restore-after), same
  precedent.
- **`ClientLevel.effects()` (and the entire `DimensionSpecialEffects` class) removed
  outright** — confirmed via decompiled-source grep across the whole tree that
  `DimensionSpecialEffects`, `isFoggyAt`, and `constantAmbientLight` don't exist
  anywhere in the new source at all, not just relocated. Both real usages
  (`resetFogState`/`resetDiffuseLighting`) had clean, confirmable replacements
  once the surrounding APIs were understood, detailed below — this was **not** a
  dead end requiring a stub.
- **`FogRenderer` redesigned from static methods to an `AutoCloseable` instance**
  (one instance per `GameRenderer`, exposed only via a private field — added
  `IEGameRenderer.ip_getFogRenderer()` following the same duck-accessor convention
  already used for `ip_getLightmap()` etc.). `FogRenderer.setupFog(Camera camera,
  int renderDistanceInChunks, DeltaTracker, float darkenWorldAmount, ClientLevel)`
  (confirmed via decompiled source) now returns a `FogData` object and **computes
  fogginess internally** from the camera's current fluid/block context — the old
  external `isFoggyAt(...)`/boss-fog-overlay boolean input is gone because it's no
  longer needed as an input at all. Actual GPU upload is a separate explicit step,
  `fogRenderer.updateBuffer(FogData)`. `FogMode.FOG_TERRAIN` doesn't exist either —
  the enum shrank to just `NONE`/`WORLD` (confirmed via decompiled source), `WORLD`
  being the correct replacement. `FogRenderer.levelFogColor()` (the old static
  color-only getter) has no direct equivalent — folded into the same
  `setupFog`+`updateBuffer` pair instead. `resetFogState()`/`updateFogColor()`
  rewritten around this new shape (both currently dead code — verified via
  workspace-wide grep that nothing calls either method — so this is lower-risk than
  it looks, but written as a faithful, real translation rather than a stub since a
  confident one was possible).
- **`Lighting` redesigned from static methods to an `AutoCloseable` instance**
  (via `GameRenderer.getLighting()`, a real public method, no duck needed) — old
  `Lighting.setupLevel()`/`.setupNetherLevel()` collapsed into a single
  `instance.updateLevel(CardinalLighting.Type)` (confirmed via `javap`).
  `DimensionSpecialEffects.constantAmbientLight()`'s old boolean role is now
  covered directly by a new `DimensionType.cardinalLightType()` accessor
  (`CardinalLighting.Type.DEFAULT`/`.NETHER`, confirmed via `javap`) — since the
  dimension type declares its own lighting mode directly now, `resetDiffuseLighting()`
  simplified from an if/else into one line:
  `client.gameRenderer.getLighting().updateLevel(world.dimensionType().cardinalLightType())`.

## Changelog — `ClientWorldLoader`/`MixinLevelRenderer` round (131 → 113 errors, 49 → 40 symbols)

All resolved except 1 reclassified as DimLib-blocked, zero regressions:

- **`LevelRenderer`'s constructor gained 2 new trailing params**: `GameRenderState`
  and `FeatureRenderDispatcher` (confirmed via `javap`), both per-`GameRenderer`
  singletons (not per-`LevelRenderer` state) — reused from the single client
  `GameRenderer` instance via its own public `getGameRenderState()`/
  `getFeatureRenderDispatcher()` getters, the same way this constructor call
  already reused `EntityRenderDispatcher`/`BlockEntityRenderDispatcher` from
  `Minecraft`. Fixed in `ClientWorldLoader.createSecondaryClientWorld`.
- **`LevelRenderer.tick()` gained a required `Camera` param** (confirmed via
  `javap`; only used internally for spawning nearby weather particles, per
  decompiled source) — passed the main camera as an approximation since this mod
  doesn't track a separate camera for background (currently-not-being-viewed)
  dimensions' renderers; cosmetic-only risk (misplaced weather particles for
  off-screen dimensions), not correctness-critical.
- **`Registry<T>`/`HolderLookup.RegistryLookup<T>`'s `getHolderOrThrow(ResourceKey)`
  renamed to `getOrThrow(ResourceKey)`** (confirmed via `javap` on the
  `HolderGetter<T>` superinterface — same shape, `getOrThrow` is a `default` method
  there now). Fixed in `ClientWorldLoader.createSecondaryClientWorld`.
- **`ClientLevel`'s constructor reshaped**: dropped its `Supplier<ProfilerFiller>`
  param entirely (consistent with `Profiler.get()` becoming a static thread-local
  accessor elsewhere in this migration — no per-instance profiler supplier needed
  anymore) and gained a new trailing `int seaLevel` param (confirmed via decompiled
  source: vanilla's own `ClientPacketListener.handleLogin` sources this from
  `CommonPlayerSpawnInfo.seaLevel()`, which isn't available when constructing a
  *secondary* client-side dimension outside the normal login flow — approximated
  with the current dimension's own `ClientLevel.getSeaLevel()`, consistent with
  this same method's existing "good enough for a secondary world" approximations
  for `biomeZoomSeed`/`isDebug`). Fixed in `ClientWorldLoader.createSecondaryClientWorld`.
- **`Registry<T>.get(Identifier)` returning `Optional<Holder.Reference<T>>` instead
  of `T`** (same finding as the `GlobalPortalStorage`/P1 rounds) swept to one more
  call site — `.getValue(Identifier)` (direct, non-`Optional`) is the correct
  replacement when a raw `T` is needed. Fixed in
  `ClientWorldLoader.RemoteCallables.checkBiomeRegistry`.
- **`LevelRenderer.renderSectionLayer(...)`/`RenderType.translucent()` both
  confirmed gone** (the former already documented as part of the `FrameGraphBuilder`
  rewrite; the latter has no replacement on either `RenderType` or the new
  `RenderTypes` — only more specific factories like `glintTranslucent()`/
  `linesTranslucent()` exist now). Removed the `@Redirect` hook targeting them with
  a `TODO`, same precedent as the other already-removed `renderSectionLayer` hooks.
  Fixed in `MixinLevelRenderer_Optional.java`.
- **`SectionRenderDispatcher.setCamera(Vec3)` renamed to
  `setCameraPosition(Vec3)`** (confirmed via `javap`, same shape). Fixed in
  `MixinLevelRenderer_Optional.java`.
- **`SectionRenderDispatcher.RenderSection.compiled` (an `AtomicReference<
  SectionRenderDispatcher.CompiledSection>`) renamed/reshaped to `sectionMesh`
  (an `AtomicReference<SectionMesh>`)** — `SectionMesh` is now an interface (was a
  concrete class), and the `CompiledSection.UNCOMPILED` sentinel moved to a new
  implementing class, `CompiledSectionMesh.UNCOMPILED` (both confirmed via
  decompiled source). Fixed in `MixinLevelRenderer.java`.
- **Reclassified as DimLib-blocked**: `ClientWorldLoader.java`'s remaining 1 error
  (`DimensionAPI.CLIENT_DIMENSION_UPDATE_EVENT.register(...)`) — same root cause as
  `EntitySync.java`/`ImmPtlChunkTickets.java`/`ImmPtlChunkTracking.java`, folded
  into the DimLib-blocked cluster tracked in migration-26.1-plan.md.

## Changelog — `RendererUsingStencil`/`ImmPtlViewArea`/`RenderTarget` round (113 → 95 errors, 40 → 33 symbols)

All fixed, zero regressions:

- **`RenderSystem.depthMask(boolean)`/`.enableDepthTest()` removed** — vanilla's own
  `GlStateManager._depthMask(boolean)`/`._enableDepthTest()` (already imported in
  this file, matching the comment already present about preferring `GlStateManager`
  for its internal state caching) are the direct replacements, confirmed via
  `javap`. Fixed in `RendererUsingStencil.java` (4 call sites); removed the
  now-unused `RenderSystem` import.
- **`SectionRenderDispatcher.RenderSection`'s constructor reshaped**: no longer
  `(int index, int x, int y, int z)` in raw block coordinates — now
  `(int index, long sectionNode)`, a single packed section-coordinate value built
  via `SectionPos.asLong(sectionX, sectionY, sectionZ)` (confirmed via decompiled
  vanilla `ViewArea.createSections`, which calls exactly this). This mod's own
  block-coordinate math (`sectionX << 4`, `(offsetCY << 4) + minY`, `sectionZ << 4`)
  was replaced with the section-coordinate equivalent, reusing the `minSectionY`
  field the constructor already computes elsewhere in the same class. Fixed in
  `ImmPtlViewArea.createColumn`.
- **`SectionRenderDispatcher.RenderSection.releaseBuffers()` renamed to `reset()`**
  (confirmed via decompiled vanilla `ViewArea.releaseAllBuffers`, which calls
  `section.reset()`). Fixed in `ImmPtlViewArea.java` (2 call sites, one a method
  reference).
- **`RenderTarget`'s constructor gained a leading `String label` param**
  (`RenderTarget(String, boolean)`, confirmed via `javap`) — fixed the fake/
  never-invoked pass-through constructors in `MixinMainTarget.java` (mirroring
  `MainTarget`'s own real `super("Main", true)` call, confirmed via decompiled
  source) and updated a matching `@Inject(method = "<init>")` handler in
  `MixinRenderTarget.java` to accept the new leading `String` param too (this one
  wasn't in the compile-error list — Mixin `@Inject`/`method=` targets aren't
  validated by `compileJava` — but was fixed anyway since it's the exact same
  ctor-shape change already being fixed in the same file, low-risk/high-value to
  do together rather than leave a known-latent weave-time bug right next to the fix).
- **`RenderTarget.resize(int, int, boolean)` dropped its trailing `boolean
  clearError` param** — now just `resize(int, int)` (confirmed via `javap`). Fixed
  the `@Shadow` declaration in `MixinRenderTarget.java` to match.

## Changelog — `ClientboundSetTimePacket`/`WorldClock` redesign round (95 → 91 errors, 33 → 31 symbols)

Both affected files fixed, zero regressions. Full research via decompiled
`net.minecraft.world.clock.ServerClockManager`/`WorldClock`/`ClockNetworkState`
(previously deferred, not guessed at). Confirmed this is a genuine new global
feature, not a per-dimension rename: the day/night clock system moved from being a
single boolean+long pair owned by each `ServerLevel` to a server-wide registry of
named `WorldClock`s (each just a `Holder`-referenced marker/tag, no data of its
own), each with independent `ClockNetworkState(long totalTicks, float partialTick,
float rate)` state tracked centrally by a new `ServerClockManager` (itself a
`SavedData` singleton, not per-level). Vanilla now broadcasts clock updates to
**every player regardless of which dimension they're in**
(`ServerClockManager.modifyClock` calls `server.getPlayerList().broadcastAll(...)`
unconditionally) — this may make part of `WorldInfoSender`'s original purpose
(manually re-sending time to a player viewing a different dimension through a
portal) redundant now, but this needs real-game verification to confirm rather than
being assumed; left a `TODO` rather than removing the method.
- `ClientboundSetTimePacket`'s constructor rebuilt around
  `Map<Holder<WorldClock>, ClockNetworkState> clockUpdates` instead of a single
  `dayTime`/`daylightCycle` pair. Best-effort translation in
  `WorldInfoSender.sendWorldInfo`: build a single-entry map keyed by the target
  dimension's own `DimensionType.defaultClock()` (an `Optional<Holder<WorldClock>>`
  — empty for dimensions with no clock, e.g. Nether-like ones), with a
  `ClockNetworkState` built from the world's own game time and the `ADVANCE_TIME`
  game rule mapped to a `1.0`/`0.0` rate (closest equivalent to the old
  daylight-cycle boolean).
- `ClientLevel.setGameTime(long)` renamed to `setTimeFromServer(long)` (confirmed
  via `javap` and vanilla's own `ClientPacketListener.handleSetTime`, which calls
  `this.level.setTimeFromServer(gameTime)` for the currently-active level).
  `ClientboundSetTimePacket.getGameTime()` renamed to the record accessor
  `.gameTime()`. Since clocks are now global or (not per-`ClientLevel` fields), this
  mod's cross-dimension time-mirroring loop only needed the `setTimeFromServer`
  rename to keep every background `ClientLevel` in sync — no separate client-side
  "clock manager" mirroring needed per secondary world, since that part of the new
  system is already global/shared. Fixed in `MixinClientPacketListener.onSetTime`.

## DimLib merged into this repo as an in-repo module — done

DimLib (`iPortalTeam/DimLib`) was previously consumed as an external, unconditional
Gradle dependency (`api` + `include` on `com.github.iPortalTeam:DimLib`) with no
26.1-compatible release, and an earlier round of this migration started forking it
into a separate sibling repo (`C:\repos\DimLib`, `MattLavalleeMA/DimLib`) with its
own independent Gradle/Loom upgrade. That approach was abandoned partway through
(after hitting a Fabric Loom 1.17.13 / Gradle 9 wrapper-version mismatch in the
fork) in favor of a simpler one: **since DimLib is small, shares the same authors
as this mod, and its upstream is archived/dead anyway, its source was imported
directly into this repo as a module** (`src/main/java/qouteall/dimlib`) rather than
maintained as a separate fork + external dependency. This reuses this repo's own
already-working 26.1.2 build pipeline instead of standing up and maintaining a
second one, and lets DimLib's own compile errors be fixed with the exact same
tooling/patterns already established here.

**Integration steps:**
- Copied DimLib's full source tree (23 Java files under `qouteall.dimlib` — no
  package collision with this mod's own `qouteall.imm_ptl`/`qouteall.q_misc_util`
  packages) into `src/main/java/qouteall/dimlib`, plus `dimlib.mixins.json` and
  `assets/dimlib/{icon.png,lang/en_us.json}` into `src/main/resources`.
  `dimlib.mixins.json` kept as its own separate file (referenced from this mod's
  own `fabric.mod.json` `mixins` array) rather than merged into one of this mod's
  existing `*.mixins.json` files, since Mixin configs are independent per-file
  units — no need to combine them.
- `fabric.mod.json`: merged DimLib's `main`/`client`/`modmenu` entrypoints
  (`qouteall.dimlib.DimLibEntry`/`DimLibEntryClient`/`config.DimLibModmenuIntegration`)
  into this mod's own entrypoint arrays (Fabric supports multiple entrypoints per
  key), added `"dimlib.mixins.json"` to the `mixins` array, and removed the now
  purely-internal `"dimlib": "*"` entry from `depends` (DimLib is no longer a
  separate mod-loader-visible mod, just part of this one's source).
- `build.gradle`/`gradle.properties`: removed the external
  `com.github.iPortalTeam:DimLib:${dimlib_version}` `api`+`include` dependency
  block and the `dimlib_version` property entirely. Added `midnightlib` (DimLib's
  own config-screen dependency, `eu.midnightdust.lib.config.MidnightConfig`) as a
  new `implementation`+`include` dependency instead
  (`maven.modrinth:midnightlib:1.9.3+26.1-fabric` — confirmed via the Modrinth API
  as the exact version published for game versions `26.1`/`26.1.1`/`26.1.2`,
  fabric/quilt loaders; the `Modrinth` maven repo already configured in this
  repo's `build.gradle` for Sodium covers this too, no new repo needed).
- Applied this repo's own `migration_tools/bulk_rename.py` (using the existing,
  already-confirmed `renames.json`) directly to the newly-added DimLib files —
  correctly picked up `ResourceLocation`→`Identifier`, `GameRules`→
  `gamerules.GameRules`, etc. across 8 files, 30 replacements, with zero
  false-positive edits outside the new files (verified via dry-run first).

**DimLib's own compile errors, fixed using the exact same patterns already
established elsewhere in this migration** (163 → 25 errors, the remaining 25 being
the pre-existing, unrelated `GravityChangerInterface.java` cluster):
- `ResourceKey<T>.location()`/`.identifier()` and `CompoundTag.getAllKeys()`/
  `.keySet()` sweeps, `Optional<String>`-returning `CompoundTag.getString(...)` →
  `getStringOr(...)`, `PayloadTypeRegistry.playS2C()`→`.clientboundPlay()`,
  `ServerPlayNetworking.createS2CPacket(...)`→`.createClientboundPacket(...)`,
  `WorldData.worldGenOptions()`→`server.getWorldGenSettings().options()`,
  `CommandSourceStack.hasPermission(int)`→`Commands.hasPermission(Commands.
  LEVEL_GAMEMASTERS)`, `ServerLevel.getSharedSpawnPos()`→
  `getRespawnData().pos()`, `ServerPlayer.server`(private)→`.level().getServer()`
  — all identical to fixes already catalogued earlier in this log; applied the
  same way in `DimLibNetworking.java`, `DimLibUtil.java`, `DimsCommand.java`,
  `DimensionImpl.java`, `DynamicDimensionsImpl.java`, `MixinPlayerList.java`.
  Also swept one straggler in the *main* mod's own source that only surfaced now
  that DimLib compiles as part of the same module:
  `AlternateDimensions.java`'s `Registry.getHolderOrThrow(ResourceKey)` →
  `.getOrThrow(...)`.
- **`net.minecraft.server.level.progress.ChunkProgressListener` fully removed,
  not just renamed to `LevelLoadListener`** (unlike this mod's own earlier
  `ChunkProgressListenerFactory`→`LevelLoadListener` fix, DimLib's usage was a
  *custom* no-op implementation of the old interface passed into
  `ServerLevel`'s constructor as a per-dimension progress listener). Confirmed
  via `inspect_class.py` that the new `LevelLoadListener` interface has a
  completely different method shape (`start(Stage,int)`/`update(Stage,int,int)`/
  `finish(Stage)`/`updateFocus(ResourceKey<Level>,ChunkPos)` vs. the old
  `updateSpawnPos(ChunkPos)`/`onStatusChange(ChunkPos,ChunkStatus)`/`start()`/
  `stop()`) — but moot anyway, since `ServerLevel`'s constructor **dropped the
  progress-listener parameter entirely** (confirmed via `inspect_class.py`:
  10-param ctor now, vs. 12 before). `DynamicDimensionsImpl`'s dummy
  `ChunkProgressListener` implementation was simply deleted (dead code) along
  with the now-unused `ChunkPos`/`ChunkStatus`/`@Nullable` imports it required,
  and the constructor call site had both the listener arg and the (also-dropped)
  trailing `RandomSequences` arg removed.
  `MixinMinecraftServer.java`'s two `@Inject`s targeting
  `createLevels(ChunkProgressListener)` were retargeted to the new no-arg
  `createLevels()` (matching this mod's own earlier `LevelLoadListener`
  migration finding), with the handler methods' now-nonexistent
  `ChunkProgressListener` parameter dropped.
- **`BorderChangeListener.DelegateBorderChangeListener` (a built-in
  "forward every world-border change from one border to another" helper class)
  fully removed with no replacement** (confirmed via `find_candidates.py` — no
  match anywhere in the jar). `BorderChangeListener` itself is unchanged (still a
  7-method interface: `onSetSize`/`onLerpSize`/`onSetCenter`/`onSetWarningTime`/
  `onSetWarningBlocks`/`onSetDamagePerBlock`/`onSetSafeZone` — `onLerpSize` gained
  one extra `long` param, confirmed matching `WorldBorder.lerpSizeBetween(double,
  double, long, long)`'s shape via `inspect_class.py`). Replaced with a small
  local `DimLibDelegateBorderChangeListener` class replicating the exact same
  forwarding behavior (each `onX` callback calls the equivalent setter on the
  wrapped target `WorldBorder`) in `DynamicDimensionsImpl.java`, used at both of
  the class's original 2 call sites.
- **`WorldBorder` now `extends SavedData`** (confirmed via `inspect_class.py` —
  gained a `TYPE` static `SavedDataType<WorldBorder>` field and a `CODEC`), and
  **`ServerLevelData`/`WorldData` lost their `getWorldBorder()` accessor
  entirely** (confirmed via `inspect_class.py` dumping the full
  `ServerLevelData` interface — no border-related method left on it at all).
  The one real call site (`worldBorder.applySettings(serverLevelData
  .getWorldBorder())`, used to seed a newly-created dimension's border from the
  overworld's persisted settings and, as a side effect, fire the just-registered
  delegate listener) was replaced with directly copying the 7 individual
  settings (`getCenterX/Z`, `getSize`, `getAbsoluteMaxSize`, `getWarningBlocks`,
  `getWarningTime`, `getDamagePerBlock`, `getSafeZone`) from the live overworld
  `WorldBorder` object onto the new dimension's own `WorldBorder` object
  directly — same net effect (new dimension's border starts in sync with the
  overworld's), without depending on the removed settings-snapshot type or the
  listener-firing side effect trick. Note `ServerLevel.getWorldBorder()` itself
  (as opposed to `ServerLevelData.getWorldBorder()`) is unaffected and still
  works fine — only the level-*data* accessor was removed.
- **`ServerChunkCache.removeTicketsOnClosing()` renamed to
  `deactivateTicketsOnClosing()`** (confirmed via `inspect_class.py`; same
  purpose/shape, part of the same ticket-system rework already covered by this
  mod's own `ImmPtlChunkTickets.java` fix earlier in this migration). Fixed in
  `DynamicDimensionsImpl.removeDimensionDynamically`.
- **`MinecraftServer.pollTask()` changed from (effectively) callable to
  `protected`** (confirmed via `inspect_class.py --private`) — `ServerChunkCache
  .pollTask()` (a *different* method on a different class, called right next to
  it in the same busy-wait loop) is unaffected and still public. Since DimLib's
  classes aren't `MinecraftServer` subclasses, added a new
  `IMinecraftServer.dimlib_pollTask()` duck method, implemented in
  `MixinMinecraftServer.java` via a new `@Shadow protected abstract boolean
  pollTask();` declaration (Mixin can see protected shadowed members even
  though external callers can't call the real method directly) that just calls
  the shadowed `pollTask()`. Fixed the one call site in
  `DynamicDimensionsImpl.removeDimensionDynamically`.
- **`ReentrantBlockableEventLoop(String)` ctor gained a required trailing
  `boolean`** — same fix already applied to this mod's own
  `MixinMinecraftServer_Misc`/`MixinMinecraft_RedirectedPacket` earlier in this
  migration, applied identically to DimLib's own `MixinMinecraftServer`'s
  never-actually-invoked fake constructor (`super(name, false)`, value
  irrelevant since dead code).
- **`WorldDimensions` mixin's `ResourceKey<T>.location()`** straggler (missed by
  the initial bulk-rename pass since it wasn't part of `renames.json`'s
  import-based scan — this was a bare `resourceKey.location()` call, not an
  import) → `.identifier()`. Fixed in `MixinWorldDimensions.java`.

**Confirmed via `parse_compile_errors.py --run`**: after all of the above, the
project-wide count is 25 errors / 7 symbols, **100% confined to
`GravityChangerInterface.java`** (verified by listing distinct files across all
remaining error groups) — DimLib itself has zero compile errors of its own.

## `net.minecraft.gizmos` debug-drawing system — investigated, not adopted

Full research via decompiled source (`Gizmos.java`, `Gizmo.java`,
`GizmoCollector.java`, `CuboidGizmo.java`, and `LevelRenderer.java`'s
`extractLevel(...)`/`emitGizmos(...)` call site), following up on the
`WireRenderingHelper.renderLineBox` stub-with-a-`TODO` left earlier in this
migration (see "Small mechanical fixes" above). Confirmed the new declarative
`net.minecraft.gizmos` API (`Gizmos.cuboid`/`circle`/`line`/`arrow`/`rect`/`point`/
`billboardText...`, each building a `Gizmo` object collected into a thread-local
`GizmoCollector` via `Gizmos.addGizmo(...)`, later drawn by `LevelRenderer`'s own
gizmo-submission step — see `LevelRenderer$FinalizedGizmos`/
`client.renderer.gizmos.DrawableGizmoPrimitives`) is vanilla's wholesale
replacement for the old immediate-mode debug-drawing helpers
(`LevelRenderer.renderLineBox(...)` and `DebugRenderer.render(PoseStack,
MultiBufferSource.BufferSource, double, double, double)`, both fully removed, not
renamed).

**Decision: not adopted for this mod's own debug-wireframe drawing**
(`WireRenderingHelper`'s box-edge helper, and the portal wand's marker overlays in
`ClientPortalWandPortalCreation`/`Drag`/`Copy`), for two confirmed, concrete
reasons:
- The Gizmo API only exposes **axis-aligned** primitives (`Gizmos.cuboid(AABB,
  GizmoStyle)` takes a plain `AABB`, no rotation parameter anywhere on
  `CuboidGizmo`/`GizmoStyle`) — but `WireRenderingHelper.renderSmallCubeFrame`
  draws an animated *rotating* highlight cube (a per-frame smoothly-interpolated
  `DQuaternion` rotation applied via `matrixStack.mulPose(...)`), which the Gizmo
  API cannot express at all.
- Gizmo collection happens during `LevelRenderer.extractLevel(...)`, a CPU-only
  data-extraction phase with **no `PoseStack`/`VertexConsumer` available at all**
  (`DebugRenderer.emitGizmos(Frustum, double, double, double, float)` only takes a
  frustum, camera position, and partial tick). This mod's wireframe drawing needs
  precise immediate-mode control over an *already-transformed* `PoseStack` — the
  transform in place when rendering through a mirrored/rotated portal view into
  another dimension — which the single-main-camera, world-space-only Gizmo
  pipeline has no hook for at all.

`WireRenderingHelper.renderLineBox`'s existing hand-rolled reimplementation
(confirmed correct, kept as-is) and the portal wand's own custom
`PoseStack`+`VertexConsumer`-based drawing remain the right approach.

**A real, previously-undiscovered weave-time-crash bug was found in the course of
this investigation and fixed**: `MixinDebugRenderer.java`
(`peripheral.mixin.client.portal_wand` package) had an `@Inject` targeting
`DebugRenderer.render(PoseStack, MultiBufferSource.BufferSource, double, double,
double)` to hook the portal wand's marker rendering in — that method is **fully
removed** (confirmed via `inspect_class.py`: `DebugRenderer`'s only public methods
left are `refreshRendererList()`, `emitGizmos(Frustum, double, double, double,
float)`, and the static `getTargetedEntity(...)`). Since Mixin `@Inject` is
`require`d by default, targeting a nonexistent method would have **hard-crashed
Mixin weaving at actual game launch** (not merely a silent no-op or a
`compileJava`-visible error — Mixin string-based `method=` targets are never
checked by `compileJava`, only at weave time, per the pattern established
throughout this migration). The real `PoseStack`+`MultiBufferSource`+
camera-position render call site now lives inside `LevelRenderer.addMainPass`'s
captured `FramePass` lambda (confirmed via decompiled source) — the same
synthetic-lambda-method re-anchoring problem already tracked for
`MixinLevelRenderer.java`'s ~8 disabled hooks, not a stable, directly-injectable
named method. `MixinDebugRenderer.java` was emptied (same precedent as
`MixinLevelRenderer_BeforeIris.java`) pending a real game launch to re-anchor
against the actual lambda method; `PortalWandItem.clientRender`/
`ClientPortalWandPortalCreation`/`Drag`/`Copy.render(...)`'s actual marker-drawing
logic was left completely untouched and is ready to reconnect once a hook is
found — confirmed (via `grep`) that `MixinDebugRenderer` was their only caller, so
nothing else needed updating. Verified via `parse_compile_errors.py --run`:
project-wide count unchanged at 25/7 (this was never a compile error, only a
latent weave-time crash).

## GravityChanger support dropped (stubbed out) — done

**Decision: this mod will not support GravityChanger going forward.** Upstream
(`com.github.qouteall/GravityChanger`) is archived (read-only since Apr 2026) and
its last release only targets mc1.20.4 — there is no compatible API to bind
against for 26.1.2, and no fork/migration was planned (see the now-removed
"Blocking / external dependency issues" entry in the main plan doc). Rather than
fencing the file off from compilation with a guard, the real-API-bound
implementation was deleted outright:

- `GravityChangerInterface.java`: removed the `OnGravityChangerPresent` subclass
  (which called into `gravity_changer.api.GravityChangerAPI`/
  `gravity_changer.util.RotationUtil`) and its now-unused imports
  (`gravity_changer.*`, `net.minecraft.client.Minecraft`,
  `org.apache.commons.lang3.Validate`). The class now only ever has its existing
  no-op default `Invoker` (gravity always `Direction.DOWN`, eye offset defaults
  to `entity.getEyeHeight()`, world-space velocity/vector transforms are
  identity, `setClientPlayerGravityDirection` just warns via the existing
  `imm_ptl.missing_gravity_changer` chat message/lang key, which was kept since
  it's still reachable).
- `IPModEntry.java`: removed the `FabricLoader.getInstance().isModLoaded(
  "gravity_changer_q")` branch that swapped in
  `GravityChangerInterface.OnGravityChangerPresent` at mod-init time.
- `build.gradle`: removed the `compileOnly("com.github.qouteall:GravityChanger:...")`
  declaration and the `enable_gravity_changer`-gated `localRuntime(...)` block.
- `gradle.properties`: removed the now-unused `gravity_changer_version` and
  `enable_gravity_changer` properties (updated the comment above `sodium_path`/
  `iris_path` to note the permanent stub instead).

This was the **only** remaining compile-error cluster (25 errors / 7 distinct
symbols, all in `GravityChangerInterface.java`). Verified via
`parse_compile_errors.py --run`: **0 errors / 0 symbols** — the project now
compiles clean end-to-end for the first time this migration.

## `MixinFogRenderer.java`'s cross-dimension fog color swap, redesigned — done

**The last remaining weave-time-only issue is fixed.** `FogRenderer` (confirmed
via decompiled source and `javap --private`) was rewritten from a bag of static
per-dimension fields (`fogRed`/`fogGreen`/`fogBlue`/`targetBiomeFog`/
`previousBiomeFog`/`biomeChangedTime`) into a plain instance whose `setupFog(
Camera, int, DeltaTracker, float, ClientLevel)` is a **pure function** of its
arguments — the class's only remaining instance fields are two GPU buffers
(`emptyBuffer`/`regularBuffer`), there is no mutable per-dimension color or
biome-transition state left to track at all. This means the old
`MixinFogRenderer.java` (in `multiworld_awareness`), which shadowed those 6
now-nonexistent static fields to swap them per-dimension via a generic
`StaticFieldsSwappingManager<Context>` helper, was both weave-broken (the
`@Shadow`ed fields don't exist — would have hard-crashed Mixin weaving at game
launch) *and* fundamentally obsolete as a design, since there's no longer any
static state to swap in the first place.

**Fix: removed the mixin and swapping mechanism entirely, replaced with direct
`setupFog`-based computation.**

- Deleted `MixinFogRenderer.java` outright (not just emptied — unlike
  `MixinDebugRenderer.java`'s temporary emptying pending re-anchoring, this
  mixin's entire premise no longer applies, so there was nothing to preserve)
  and removed its entry from `imm_ptl.mixins.json`.
- Deleted `StaticFieldsSwappingManager.java` (the generic per-dimension
  static-field-swap helper) since `FogRendererContext` was its only consumer
  and no longer needs it.
- Rewrote `FogRendererContext.java` down to two static methods, both computing
  fresh `FogData` via `setupFog` instead of reading/writing swapped state:
  - `getFogColorOf(ClientLevel, Vec3)` — cross-dimension query (used by
    portal-teleportation code), simplified since `setupFog` takes the target
    `ClientLevel` directly as a parameter — no longer needs to temporarily
    swap `client.level`/push-pop a swapping-manager context at all, since
    `computeFogColor`/`getFogType`/the `FogEnvironment`s all read only from
    their passed-in `Camera`/`ClientLevel` params (confirmed via decompiled
    source), never from `Minecraft.getInstance().level` or any other ambient
    static state.
  - `getCurrentFogColor()` (replaces the old `Supplier<Vec3>` field of the same
    name) — recomputes fog fresh via `setupFog`, using the shared
    `GameRenderer`'s own `FogRenderer` instance (via the existing
    `IEGameRenderer.ip_getFogRenderer()` duck) and whatever `client.gameRenderer
    .getMainCamera()`/`client.level` currently are. Used by
    `RendererUsingStencil.java`'s `replaceFrameBufferClearing()` to pick the
    fog color for the full-screen triangle drawn in place of a framebuffer
    clear — this is now self-contained and correct regardless of dimension,
    since it always reflects whatever the *actual current* render
    target is at call time.
  - Removed `init()`/`update()`/`onPlayerTeleport(...)` (all existed only to
    manage the now-deleted swapping manager's per-dimension context map) and
    their call sites: `RenderStates.updatePreRenderInfo(...)`'s
    `FogRendererContext.update()` call, and
    `ClientTeleportationManager.changePlayerDimension(...)`'s
    `FogRendererContext.onPlayerTeleport(from, to)` call (with the now-unused
    `FogRendererContext` import removed from that file too).

## `MixinDebugRenderer.java`'s portal wand marker rendering reconnected — implemented, weave-time unverified

**Re-anchored to `LevelRenderer`'s synthetic `lambda$addMainPass$0` method,
replicating a real, currently-shipping mod's own solution for our exact MC
version rather than guessing.** Searched GitHub for other maintained mods
already solving the same "inject custom `PoseStack`/`MultiBufferSource`-based
drawing into the main render pass" problem on this MC version family:

- [MeteorDevelopment/meteor-client](https://github.com/MeteorDevelopment/meteor-client)
  targets **MC 26.1.2 — an exact version match** — and ships a working
  `@Inject(method = "lambda$addMainPass$0", ...)`, confirming the synthetic
  method name is real and weave-stable on our own MC version (not just a
  `javap`-visible guess).
- [Vivecraft/VivecraftMod](https://github.com/Vivecraft/VivecraftMod)'s default
  branch (MC 26.2) hooks a real, stably-named, non-synthetic method instead:
  `@Inject(method = "submitFeatures", at = @At(value = "INVOKE", target =
  "Lnet/minecraft/client/renderer/LevelRenderer;finalizeGizmoCollection()V"))`.
  Checking a decompiled MC 26.1.1 source (`brebathe/Eaglercraft-26-1-1-src`,
  structurally identical to our 26.1.2) confirmed `submitFeatures` is **not** a
  real method there — it's still a `profiler.popPush("submitFeatures")` label
  inlined inside `addMainPass`'s lambda, so that hook is 26.2-only and doesn't
  apply to us. Vivecraft also maintains a `Multiloader-26.1` branch (MC
  26.1.2, exact match) where the same `LevelRendererVRMixin.java` does **not**
  use `submitFeatures` at all — it falls back to
  `lambda$addMainPass$0`/`lambda$addMainPass$0*`, independently landing on the
  same synthetic-lambda anchor. This is the strongest available real-world
  confirmation short of our own launch: the same mod, targeting our exact
  version, solving the identical problem the same way.
- The Vivecraft 26.1 branch's own `@At` patterns inside that lambda
  (`@At(value = "CONSTANT", args = "stringValue=renderSolidFeatures")`,
  `@At("TAIL")`, `@At(value = "INVOKE", target = "...endOutlineBatch()V",
  shift = Shift.AFTER)`, etc., with `PoseStack poseStack` and
  `LevelRenderState levelRenderState` captured via MixinExtras `@Local`/
  `@Local(argsOnly = true)`) were used as the direct template for our own hook.

**Implementation** (`MixinDebugRenderer.java`, `peripheral.mixin.client
.portal_wand` package, now `@Mixin(LevelRenderer.class)` instead of
`DebugRenderer.class`): injects at
`@Inject(method = "lambda$addMainPass$0*", at = @At(value = "CONSTANT", args =
"stringValue=renderSolidFeatures"))`, capturing `LevelRenderState
levelRenderState` (`@Local(argsOnly = true)` — a genuine parameter of the
synthetic lambda method, since captured effectively-final locals become real
parameters of the generated method at the bytecode level), `PoseStack
poseStack`, and `MultiBufferSource.BufferSource bufferSource`
(`@Local(ordinal = 0)`, needed to disambiguate from the sibling
`crumblingBufferSource` local of the same type also in scope). The injected
handler checks `Minecraft.getInstance().player`'s main-hand item against
`PortalWandItem.instance` (same check pattern already used elsewhere in
`PortalWandItem`/`PortalWandInteraction`) and, if held, calls the completely
unmodified `PortalWandItem.clientRender(player, itemStack, poseStack,
bufferSource, cameraPos.x, cameraPos.y, cameraPos.z)` using
`levelRenderState.cameraRenderState.pos` for the camera position — the exact
same call contract the old (now-removed) `DebugRenderer.render(...)` hook
used, so `PortalWandItem`/`ClientPortalWandPortalCreation`/`Drag`/`Copy`/
`WireRenderingHelper` needed **zero** changes; only the injection anchor
changed. Verified via `parse_compile_errors.py --run`: 0 errors.

**Not yet verified:** whether `lambda$addMainPass$0*`'s wildcard actually
resolves at Mixin weave time, and whether the `CONSTANT("renderSolidFeatures")`
slice/`ordinal = 0` for `bufferSource` are exactly right, can only be confirmed
by an actual game launch (per the established pattern throughout this
migration — `compileJava` never validates Mixin's string-based targets). If
weaving fails, Mixin's own error output will list the real available targets,
which is expected to make correcting this fast.

**The actual render-time fog switch, in `MyGameRenderer.switchAndRenderTheWorld`
— confirmed via decompiled source, not a guess.** Tracing the real call chain
(`GameRenderer.render()` → private `extractCamera(...)` → `this.fogRenderer
.setupFog(this.mainCamera, ..., this.minecraft.level)`, storing the result in
`this.gameRenderState.levelRenderState.cameraRenderState.fogData`, a **public,
mutable field on a publicly-reachable object graph** (`GameRenderer
.getGameRenderState()` is a real public method) confirmed this is computed
**once per real frame**, using the outer/main dimension's camera+level, and is
never recomputed by `GameRenderer.renderLevel(DeltaTracker)` itself (the method
this mod calls directly to render nested/portal dimension content — it only
re-**uploads** whatever `cameraState.fogData` currently holds via
`this.fogRenderer.updateBuffer(cameraState.fogData)`, it doesn't recompute it).
Left untouched, portal-rendered dimensions would silently keep using the
*outer* world's stale fog data for their own terrain/sky/weather passes.
Fixed by mirroring `extractCamera`'s exact real logic at the world-switch point:
saves `cameraRenderState.fogType`/`.fogData` before switching, installs a fresh
`setupFog(...)`-computed replacement for the new camera/dimension (after giving
the fresh scratch `Camera` a real position/focused-entity via the same
`portal_setPos`/`portal_setFocusedEntity` pattern `FogRendererContext
.getFogColorOf` already used), then restores the saved values when switching
back to the outer world. No new Mixin/duck accessor was needed at all —
`GameRenderer.getGameRenderState()`, `LevelRenderState.cameraRenderState`, and
`CameraRenderState.fogType`/`.fogData` are all already public fields/methods on
vanilla's own classes (confirmed via `javap --private` on all three).

**Not fully verified by real gameplay yet** — this is a source-confirmed,
faithful translation of the real vanilla logic (not a stub or a guess), but
like the rest of the portal-rendering-pipeline redesign work, actually seeing
correct fog color through a portal into another dimension needs a real game
launch to confirm end-to-end (tracked under "Next steps" in the main plan doc).
Verified via `parse_compile_errors.py --run`: **0 errors / 0 symbols**, no
regressions — this was never a compile error to begin with, only a
weave-time-crash risk that's now fully eliminated.

## `MixinFogRenderer_A_CVB.java`'s weave-time-broken targets, fixed — done

**Found while investigating the `MixinFogRenderer.java` redesign above, and
fixed in the same pass** (not previously tracked anywhere in either migration
doc — a genuinely new discovery, not a re-surfacing of a known item).
`MixinFogRenderer_A_CVB.java` (`peripheral.mixin.client.alternate_dimension`
package — overrides the void-darkness fog falloff to avoid alternate
dimensions looking artificially dark when viewed from the overworld through a
portal) had **two** stale Mixin string targets pointing at APIs that no longer
exist at all:

- `method = "...FogRenderer;setupColor(Camera,float,ClientLevel,int,float)..."`
  — this static method was removed entirely (see the `MixinFogRenderer.java`
  section above: replaced by the instance method `setupFog(Camera, int,
  DeltaTracker, float, ClientLevel)`).
- `at.target = "...Camera;getPosition()..."` — renamed to `Camera.position()`
  (confirmed via `javap`).

Since Mixin `@Redirect` is `require`d by default just like `@Inject`, both
stale strings would have **hard-crashed Mixin weaving at game launch** — the
same failure mode as `MixinFogRenderer.java`/`MixinDebugRenderer.java`'s
previously-found issues, just not previously noticed since nothing had gone
looking at this file specifically until now.

**Fix: retargeted both strings to their real replacements, no other changes
needed.** Traced the actual color/darkness computation in `setupFog`'s new
decompiled source to a private helper, `computeFogColor(Camera, float,
ClientLevel, int, float, Vector4f)`, confirmed (via the real decompiled body)
to contain **exactly one** call to `camera.position()` — precisely the
void-darkness falloff read (`(voidDarknessOnsetRange + level.getMinY() -
camera.position().y) / voidDarknessOnsetRange`) this mixin exists to override,
with no other camera-position reads inside that method that could conflict.
Retargeted `method` to `FogRenderer.computeFogColor(...)`'s full descriptor and
`at.target` to `Camera.position()`. The mixin's own handler body
(`redirectCameraGetPos`) needed **no changes at all** — it already called
`camera.position()` (the new name) internally; only the two Mixin annotation
strings pointing at the old, now-removed API surface were stale. Verified via
`parse_compile_errors.py --run`: **0 errors / 0 symbols**, no regressions —
like its sibling fix above, this was never a compile error (Mixin string
targets aren't compile-checked), only a weave-time-crash risk, now eliminated.
Not yet verified against a real game launch (tracked under "Next steps" in the
main plan doc, same as the rest of the fog-rendering redesign work).

## `MixinLevelRenderer.java`'s `redirectRenderEntity` weave-crash risk fixed — implemented, weave-time unverified

**Found via the same GitHub-research technique used for `MixinDebugRenderer.java`,
but applied to audit this mod's own existing mixins rather than to find a
replacement pattern.** While surveying other mods' `LevelRenderer` mixins for
patterns to reuse elsewhere, none of MeteorDevelopment/meteor-client's,
CaffeineMC/sodium's, or Vivecraft/VivecraftMod's own `LevelRenderer` mixins
touch a method called `renderEntity` at all — they all wrap
`submitEntities`/`EntityRenderDispatcher.submit(...)` instead. That prompted a
direct check: searching the decompiled MC 26.2 source for `renderEntity(Entity`
turned up **zero matches**. `LevelRenderer.renderEntity(Entity, double, double,
double, float, PoseStack, MultiBufferSource)` is fully removed — entity
rendering is now always extract-then-submit
(`EntityRenderDispatcher.extractEntity`/`.submit(...)`, confirmed via
decompiled `EntityRenderDispatcher.java`).

This affected **two** real weave-time-crash risks in `MixinLevelRenderer.java`,
both targeting the same removed method (the same "one broken target, check for
siblings" lesson from the `MixinFogRenderer`/`MixinFogRenderer_A_CVB` pair
earlier this session):

- `@Shadow protected abstract void renderEntity(...)` — a `@Shadow` for a
  member that no longer exists also fails at weave time, same as an invalid
  `@Inject`/`@Redirect` target.
- `@Redirect(method = "renderLevel", at = @At(value = "INVOKE", target =
  "Lnet/minecraft/client/renderer/LevelRenderer;renderEntity(...)"))` — the
  `redirectRenderEntity` handler that used the `@Shadow` above to call through
  to vanilla after running `CrossPortalEntityRenderer.beforeRenderingEntity`/
  `afterRenderingEntity` around each entity.

**Fix:** both removed; replaced with two new hooks targeting real, stably-named,
non-synthetic methods (no `lambda$addMainPass$0`-style re-anchoring needed
here, unlike most of this file's other disabled hooks):

- A new duck, `IEEntityRenderState` (`ip_getEntity()`/`ip_setEntity(Entity)`),
  implemented by a new `MixinEntityRenderState` (`@Mixin(EntityRenderState
  .class)`, registered in `imm_ptl.mixins.json`). Vanilla's `EntityRenderState`
  has no back-reference to the source `Entity` it was extracted from (it never
  needed one before), but `CrossPortalEntityRenderer.beforeRenderingEntity`/
  `afterRenderingEntity` need the real `Entity` (they key a
  `WeakHashMap<Entity, ...>` and read an `IEEntity` duck off it). This is the
  same technique MeteorDevelopment/meteor-client uses for the identical
  problem (its own `IEntityRenderState.meteor$getEntity()` duck, confirmed in
  its `LevelRendererMixin.draw(...)`).
- `@Inject(method = "extractEntity", at = @At("RETURN"))` in
  `MixinLevelRenderer.java` stashes the `Entity` onto the returned
  `EntityRenderState` via that duck. `extractEntity` is `LevelRenderer`'s own
  private one-line wrapper around `EntityRenderDispatcher.extractEntity(...)`
  (confirmed via decompiled source) — a real, stable, directly-injectable
  method, not a lambda.
- `@Redirect(method = "submitEntities", at = @At(value = "INVOKE", target =
  "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;submit(...)"))`
  (`redirectSubmitEntity`) replaces `redirectRenderEntity`: retrieves the
  `Entity` via the new duck, calls `beforeRenderingEntity`/
  `afterRenderingEntity` around the real `dispatcher.submit(...)` call.
  `submitEntities` is also a real, stably-named, non-synthetic private method
  (confirmed via decompiled source) called once per entity from a plain
  `for` loop — same effect as before (once per entity, wrapped), just anchored
  to the new dispatch shape.

**A third, deeper issue found in the same sweep, deliberately stubbed rather
than guessed at:** `IEWorldRenderer.ip_myRenderEntity(...)` (used by
`CrossPortalEntityRenderer.renderEntityProjections` to immediately draw a
single entity's cross-portal "projection" at a transformed position) also
called the removed `renderEntity(...)`, but unlike the redirect above, it
can't simply be re-anchored: `EntityRenderDispatcher.submit(...)` now only
*queues* a submission into a `SubmitNodeCollector` (confirmed via decompiled
source — no more synchronous immediate-draw path exists at all). Actually
executing a queued submission requires the shared `FeatureRenderDispatcher`/
`SubmitNodeStorage` (e.g. `FeatureRenderDispatcher.prepareFrame(...)
.executeSolid()`/`.executeTranslucent()`, the pattern
Vivecraft/VivecraftMod's own `vivecraft$renderGizmos()` uses) — reusing the
main frame's shared dispatcher/storage for this one-off nested render risks
colliding with whatever submission the main frame already has in flight
(double-submission, premature clearing, etc.). This is a real redesign, not a
rename, and one that needs an actual game launch to verify rather than
guessing blind — consistent with how every other genuinely-uncertain
render-pipeline change has been handled this migration. Stubbed to a no-op:
entity projections through portals simply won't render for now (a visual
regression only, not a crash).

Verified via `parse_compile_errors.py --run`: 0 errors. Not yet verified
against a real game launch, same as every other Mixin string-target change
this migration.

## `MixinSodiumOcclusionCuller.java` investigated — not a quick fix, real redesign needed

Checked while surveying whether other stubbed/TODO items could be resolved via
the same GitHub-research technique used for `MixinDebugRenderer.java`. Fetched
Sodium's actual source at the exact-version-matching tag `mc26.1.2-0.9.1`
(`CaffeineMC/sodium`). The new `OcclusionCuller.findVisible(...)` signature
this file's existing TODO comment already described (three separate visitor
types — `GraphOcclusionVisitor` ×2, `VisibilityTestingVisitor` — plus a
`CancellationToken`, no single `useOcclusionCulling` override flag) is
confirmed correct. But the bigger picture is worse than a rename: Sodium's
whole chunk-culling pipeline is now **asynchronous and tree-based**
(`RenderSectionManager` schedules a `CullTask` on a dedicated background
thread via `scheduleAsyncWork(...)`; results are cached per `CullType` as
`SectionTree`s and consumed later by `finalizeRenderLists(...)`/
`readRenderListFromTree(...)`). The old assumption behind this mod's
portal-cave-culling override — synchronously redirecting the culling
iteration's start point to the portal's visible-section origin — doesn't map
cleanly onto this async model at all; there's no single synchronous call site
left to redirect. This needs genuine new design work against the async
pipeline (or an explicit decision to keep behaving like vanilla Sodium's own
culling through portals, a performance-only regression per the existing stub's
comment), not a quick GitHub-research-informed fix like `MixinDebugRenderer
.java`'s was. Left stubbed as-is; not attempted this round.

## `setupRender`-targeting hooks re-verified and fixed — done

**Found while reviewing the main plan's "Remaining items in this cluster" list
for leads.** Re-reading `MixinLevelRenderer.java`/`MixinLevelRenderer_Optional
.java` in full (prompted by the `redirectRenderEntity` fix earlier turning up a
sibling bug) surfaced several more active mixins targeting `LevelRenderer
.setupRender(Camera,Frustum,boolean,boolean)`, confirmed via decompiled MC 26.2
source to be **fully removed** (zero matches anywhere in the decompiled repo)
— consistent with the TODO already tracked in `MixinLevelRenderer_Optional
.java`, but these particular hooks were still live (not yet stubbed):

- **`modifyIsSpectator`** (`MixinLevelRenderer.java`, `@ModifyVariable`,
  default `require = 1`) — a genuine weave-crash risk, since `setupRender`
  doesn't exist at all. Fixed: `setupRender`'s spectator-based smart-cull-disable
  logic now lives in the private `cullTerrain(Camera, Frustum, boolean
  spectator)` method (confirmed via decompiled source: `if (spectator &&
  ...isSolidRender()) smartCull = false;`, called from `update(Camera)` as
  `cullTerrain(camera, camera.getCullFrustum(),
  minecraft.player.isSpectator())`). Re-anchored to `cullTerrain`'s own single
  boolean parameter (`ordinal = 0`).
- **`onSetChunkBuilderCameraPosition`** (`MixinLevelRenderer_Optional.java`,
  `@Redirect`, `require = 0` — so not a hard crash, just a silently-inactive
  feature) — same `setupRender` target. Fixed: `cullTerrain` directly calls
  `this.sectionRenderDispatcher.setCameraPosition(cameraPos);` (confirmed via
  decompiled source), so re-anchored `method` to `cullTerrain` with the exact
  same `@At(INVOKE)` target (`SectionRenderDispatcher.setCameraPosition(Vec3)`)
  unchanged.
- **`redirectGetXInSetupRender`/`redirectGetYInSetupRender`/
  `redirectGetZInSetupRender`** (`MixinLevelRenderer_Optional.java`, all
  `@Redirect`, `require = 0`) — investigated and found to be **redundant, not
  broken**: `LocalPlayer.getX()/getY()/getZ()` are no longer called anywhere
  near this code path at all (confirmed via decompiled source — camera
  position now flows uniformly as a single `camera.position()` `Vec3` into
  `cullTerrain` → `scheduleTranslucentSectionResort(camera.position())`, never
  as separate coordinate reads). That position is already corrected for portal
  rendering earlier and separately, directly on `Camera` itself:
  `MixinCamera.onUpdateFinished`'s `WorldRenderInfo.adjustCameraPos(this_)`
  mutates `Camera`'s own `position` field at the end of every `Camera
  .update(...)`, well before `cullTerrain` ever reads it (confirmed already
  fixed and working — see `MixinCamera.java` weave-time-only fix earlier in
  this log). These three redirects were therefore doing nothing useful even
  when `setupRender` existed on this exact call path; removed outright rather
  than kept as dead weight pointed at a nonexistent method.
- **`redirectTranslucentFramebuffer`** (`MixinLevelRenderer.java`, `@Redirect`
  on a `FIELD` read, default `require = 1`) and its backing `@Shadow @Nullable
  private RenderTarget translucentTarget;` — both real weave-crash risks,
  since the `translucentTarget` field is fully removed (translucent rendering
  now goes through a `FrameGraphBuilder`-managed `LevelTargetBundle`,
  `this.targets.translucent`). Fixed with a more precise mechanism than a
  field redirect: vanilla itself exposes a real public getter,
  `getTranslucentTarget()` (confirmed via decompiled source: `public
  RenderTarget getTranslucentTarget() { return this.targets.translucent !=
  null ? this.targets.translucent.get() : null; }`, also used by vanilla's own
  `ChunkSectionLayerGroup`) — re-anchored to `@Inject(method =
  "getTranslucentTarget", at = @At("HEAD"), cancellable = true)`, returning
  `null` during portal rendering. This is arguably an improvement over the old
  approach: it intercepts every caller of the getter, not just one read site
  that used to be inline in `renderLevel`'s own body.
- **`redirectRunQueuedChunkUpdates`** (`MixinLevelRenderer.java`, `@Redirect`,
  default `require = 1`) — targeted `ClientLevel.pollLightUpdates()` being
  called from within `renderLevel`, wrapping it in
  `ClientWorldLoader.withSwitchedWorld(...)` since this mod calls `renderLevel`
  repeatedly per frame for different dimensions. Confirmed via decompiled
  source that `pollLightUpdates()` is **no longer called from `renderLevel` at
  all** — it moved entirely into `ClientLevel`'s own per-tick `update()`
  method (`populateLightUpdates`/`runLightUpdates` profiler sections),
  decoupled from this mod's per-dimension render calls. Since the original
  problem this redirect solved (the "wrong world" context during a
  per-dimension `renderLevel` call) no longer applies to a call site that
  isn't reached from `renderLevel` anymore, removed outright rather than
  guessed at a new home for it — flagged for real-launch verification in case
  light updates in non-primary rendered dimensions need different handling
  some other way now.
- **`onIsChunkCompiled`** (`MixinLevelRenderer.java`, `@Inject`, default
  `require = 1`) — targeted `isSectionCompiled(BlockPos)`, confirmed via
  decompiled source (and its call site in `extractVisibleEntities`) to have
  been simply **renamed** to `isSectionCompiledAndVisible(BlockPos)` — same
  method body/semantics. Retargeted, no other changes needed.

Also investigated: whether [IrisShaders/Iris](https://github.com/IrisShaders/Iris)'s
own `26.1` branch (exact version match) has already solved
`IPIrisHelper.java`'s framebuffer-copy problem
(`RenderTarget.frameBufferId`/`getColorTextureId()`/`getDepthTextureId()` all
removed, real replacement is `CommandEncoder.copyTextureToTexture(...)`) —
searched Iris's source for `copyTextureToTexture` and found no matches, so
unlike `MixinDebugRenderer.java`/`MixinLevelRenderer.java`'s fixes above, there
was no quick externally-sourced lead to act on here. Left stubbed; still real
design/testing work, same as the rest of the Iris-compatibility renderer stack
and the broader stencil-masking redesign it depends on.

Verified via `parse_compile_errors.py --run`: 0 errors after every change in
this round, including removal of now-unused `LocalPlayer`/`WorldRenderInfo`
imports in `MixinLevelRenderer_Optional.java`. Not yet verified against a real
game launch, same as every other Mixin string-target change this migration.

## `MixinLevelRenderer.java`'s ~8 disabled hooks re-anchored — implemented, weave-time unverified

**Recovered the actual pre-migration hook implementations from git history**
(`git show 6831a3a7:...MixinLevelRenderer.java`, the last pre-26.1 commit)
rather than guessing from the file-level TODO comment's summary alone — this
gave exact original logic to port instead of reconstructing it from
descriptions. Re-anchored each against real, decompiled-source-confirmed
targets in the current MC 26.1.2 render pipeline:

- **`onBeginRenderingEntitiesAndBlockEntities`** (old anchor:
  `DimensionSpecialEffects.constantAmbientLight()`, confirmed fully removed) —
  re-anchored to `@Inject(method = "submitEntities", at = @At("HEAD"))`, a
  real, stable, non-synthetic method that begins the bulk entity-rendering
  pass. Uses `RenderSystem.getModelViewMatrix()` for the model-view matrix
  (see version-mismatch note below).
- **`onEndRenderingEntities`** (old anchor: an `endLastBatch()`-ordinal-1
  landmark) — entities/block entities are only *submitted* (queued) by
  `submitEntities`/`submitBlockEntities` now; the actual GPU draw happens
  later in the same `lambda$addMainPass$0` frame pass via
  `featureRenderDispatcher.renderSolidFeatures()` (confirmed via decompiled
  source). Re-anchored to `@Inject(method = "lambda$addMainPass$0*", at =
  @At(value = "INVOKE", target =
  "...FeatureRenderDispatcher;renderSolidFeatures()V", shift = At.Shift.AFTER))`,
  capturing the lambda's own `PoseStack poseStack` local via MixinExtras
  `@Local`.
- **`onMyBeforeTranslucentRendering`** (old anchor:
  `Sheets.translucentItemSheet()`) — re-anchored to
  `ChunkSectionsToRender.renderGroup(ChunkSectionLayerGroup, GpuSampler)`
  ordinal 1 (the translucent-group call; ordinal 0 is opaque, confirmed via
  decompiled source — this method is called exactly twice per frame).
- **`onBeforeRenderingLayer`/`onAfterRenderingLayer`** (old anchor: the
  per-layer `LevelRenderer.renderSectionLayer(RenderType,...)` call, which used
  to fire once per fine-grained render layer in a loop) — re-anchored to the
  same `ChunkSectionsToRender.renderGroup(...)` call, this time with no
  `ordinal` specified (deliberately matching both the opaque and translucent
  occurrences), preserving the original "before/after any render layer"
  semantics despite there now being two coarser calls instead of many
  fine-grained ones.
- **`redirectClearing`** (old anchor: `RenderSystem.clear(int)`, confirmed no
  longer called anywhere near this pass) — the "clear" `FramePass` now clears
  via `CommandEncoder.clearColorAndDepthTextures(GpuTexture, int, GpuTexture,
  double)` instead (confirmed via decompiled source). Re-anchored to a
  `@Redirect` on that call within `"lambda$renderLevel$0*"`. **Lower
  confidence than the other hooks in this round**: this "clear" pass's
  `executes(...)` lambda is written directly inline in `renderLevel`'s own
  body (not inside a dedicated named helper method like `addMainPass`/
  `addWeatherPass` are) and appears to be the only lambda literal there, so
  `lambda$renderLevel$0` is a reasoned guess (first/only lambda in that
  method's source) rather than cross-confirmed via another mod's mixin the way
  the others in this round are.
- **`beforeRenderingWeather`/`afterRenderingWeather`** (old anchor: the lambda
  in `addWeatherPass`) — re-anchored to `"lambda$addWeatherPass$0*"`, same
  confidence level as the `addMainPass` hooks since `addWeatherPass` is its
  own dedicated private method (confirmed via decompiled source), so its
  lambda is unambiguous.
- **`onFinishRenderLevel`** (old anchor: `renderLevel`'s own `RETURN`, which is
  still a real, stable method — just a different parameter list now) — no
  re-anchoring needed for the target itself, but its body
  (`Lighting.setupLevel()`) needed updating: `Lighting` was redesigned from
  static methods to an `AutoCloseable` instance earlier this migration (see
  the `ClientWorldLoader`/`MixinLevelRenderer` changelog round above), so this
  now calls `minecraft.gameRenderer.getLighting()
  .updateLevel(CardinalLighting.Type.DEFAULT)` instead — the direct
  `CardinalLighting.Type.DEFAULT` equivalent of the old unconditional
  `Lighting.setupLevel()` call's "make hand rendering normal again" intent.

**Not re-anchored — left as genuinely open design work:** the old
`onSetupTerrainBegin`/`onSetupTerrainEnd` terrain-visibility override (via
`VisibleSectionDiscovery.discoverVisibleSections`, replacing vanilla's own
frustum culling during portal rendering for correctness). Its old anchor,
`setupRender`, is confirmed fully removed; its structural replacement,
`cullTerrain(Camera, Frustum, boolean)`, is built around a fundamentally
different `SectionOcclusionGraph`-based algorithm with persistent per-frame
traversal state, not a one-shot linear setup the old override can be ported
onto by a simple re-anchor. This is real, dedicated redesign work, not a
rename — left unimplemented rather than guessed at.

**A real mistake caught by the compiler, not by review:** the first pass at
this fix used `RenderSystem.getModelViewMatrixCopy()` throughout, sourced from
the `Renekovski/26.2-mcp` decompile used elsewhere this session — but that
repo is MC **26.2**, one version ahead of this mod's 26.1.2 target.
`getModelViewMatrixCopy()` doesn't exist on 26.1.x at all (9 compile errors
surfaced this immediately); a direct check against a decompiled **26.1.1**
source (`brebathe/Eaglercraft-26-1-1-src`, exact version match) confirmed the
real, still-current name on our version is `RenderSystem.getModelViewMatrix()`
(unrenamed). Also caught: `Lighting.setupLevel()` doesn't exist either (2 more
compile errors) — already known and already fixed elsewhere in this migration,
just not cross-referenced before writing this round's first draft. Both fixed;
see the version-mismatch lesson recorded for future rounds.

Verified via `parse_compile_errors.py --run`: 0 errors after every change.
Not yet verified against a real game launch — per the established pattern,
`compileJava` cannot validate Mixin's string-based targets (method names,
`@At` slices, lambda indices), so weave-time correctness for all of the above
(especially `redirectClearing`'s lower-confidence lambda index guess) remains
to be confirmed by an actual launch.

## Small leftover items fixed — done

**`src/main/resources/fabric.mod.json`:**
- `"minecraft": ["1.21", "1.21.1"]` → `"26.1.x"` (matches
  `gradle.properties`' `minecraft_version=26.1.2`, and the same range-string
  convention `Vivecraft/VivecraftMod`'s own `fabric_mc_range=["26.1.x"]` uses).
- `"fabricloader": ">=0.7.4"` → `">=0.19.3"` (matches `gradle.properties`'
  `loader_version=0.19.3`) — noticed while fixing the explicitly-flagged
  `fabric-api` floor below; this one was even more stale and just as wrong, so
  fixed alongside it rather than left as a second pass.
- `"fabric-api": ">=0.109.0"` → `">=0.154.2"` (matches `gradle.properties`'
  `fabric_version=0.154.2+26.1.2`).
- `"iris"`/`"sodium"` `breaks` ranges — `["<1.8.0", ">1.8.0"]`/`["<0.6.0",
  ">0.6.0"]` → `["<1.11.2", ">1.11.2"]`/`["<0.9.1", ">0.9.1"]`, matching the
  exact versions this project's own `gradle.properties` builds/tests against
  (`iris_path=...iris:1.11.2+26.1-fabric`,
  `sodium_path=...sodium:mc26.1.2-0.9.1-fabric`) — same "breaks below and
  above the one known-good version" pattern as before, just updated numbers.

**All 5 `*.mixins.json` files' `"compatibilityLevel": "JAVA_17"`:** bumped to
`"JAVA_21"`. Checked what real mods on this exact MC version actually declare
rather than guessing: 
[MeteorDevelopment/meteor-client](https://github.com/MeteorDevelopment/meteor-client)
(MC 26.1.2, exact version match) uses `"JAVA_21"` across all of its own mixin
configs. Interestingly,
[Vivecraft/VivecraftMod](https://github.com/Vivecraft/VivecraftMod) and
[CaffeineMC/sodium](https://github.com/CaffeineMC/sodium) (both MC 26.1.x/26.2)
still declare `"JAVA_17"` everywhere despite also building with Java 21+/25 —
`compatibilityLevel` evidently doesn't need to track the project's own JDK
toolchain version, it only needs to be high enough for whatever Mixin
bytecode-level features are actually used. Went with `"JAVA_21"` (matching the
one mod that's on our exact MC version) as a reasonable, precedent-backed
alignment with this project's `sourceCompatibility = JavaVersion.VERSION_25`,
rather than leaving the pre-26.1-migration-era `"JAVA_17"` unexamined. Like
`compatibilityLevel` itself, this is a weave-time-only setting `compileJava`
doesn't validate — not yet confirmed against a real launch, though the `JAVA_21`
value is real-world-precedented on our exact MC version, so risk is low.

Verified via `parse_compile_errors.py --run`: 0 errors.



