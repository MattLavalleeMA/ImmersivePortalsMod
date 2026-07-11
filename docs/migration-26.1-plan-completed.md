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
