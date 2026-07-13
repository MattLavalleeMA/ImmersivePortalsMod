package qouteall.imm_ptl.core;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import qouteall.imm_ptl.core.collision.CollisionHelper;
import qouteall.imm_ptl.core.commands.ClientDebugCommand;
import qouteall.imm_ptl.core.compat.IPFlywheelCompat;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import qouteall.imm_ptl.core.miscellaneous.DubiousThings;
import qouteall.imm_ptl.core.miscellaneous.GcMonitor;
import qouteall.imm_ptl.core.network.ImmPtlNetworkConfig;
import qouteall.imm_ptl.core.network.ImmPtlNetworking;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.portal.animation.ClientPortalAnimationManagement;
import qouteall.imm_ptl.core.portal.animation.StableClientTimer;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.ForceMainThreadRebuild;
import qouteall.imm_ptl.core.render.GuiPortalRendering;
import qouteall.imm_ptl.core.render.ImmPtlViewArea;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;
import qouteall.imm_ptl.core.render.VisibleSectionDiscovery;
import qouteall.imm_ptl.core.render.optimization.GLResourceCache;
import qouteall.imm_ptl.core.render.optimization.SharedBlockMeshBuffers;
import qouteall.imm_ptl.core.render.renderer.RendererUsingFrameBuffer;
import qouteall.imm_ptl.core.render.renderer.RendererUsingStencil;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.q_misc_util.dimension.DimensionIntId;
import qouteall.q_misc_util.my_util.MyTaskList;

public class IPModMainClient {
    
    private static void showNvidiaVideoCardWarning() {
        IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.withDelayCondition(
            () -> Minecraft.getInstance().level == null,
            MyTaskList.oneShotTask(() -> {
                if (IPMcHelper.isNvidiaVideocard()) {
                    if (!SodiumInterface.invoker.isSodiumPresent()) {
                        CHelper.printChat(
                            Component.translatable("imm_ptl.nvidia_warning")
                                .withStyle(ChatFormatting.RED)
                                .append(McHelper.getLinkText("https://github.com/CaffeineMC/sodium-fabric/issues/1486"))
                        );
                    }
                }
            })
        ));
    }
    
    private static void showQuiltWarning() {
        IPGlobal.CLIENT_TASK_LIST.addTask(MyTaskList.withDelayCondition(
            () -> Minecraft.getInstance().level == null,
            MyTaskList.oneShotTask(() -> {
                if (O_O.isQuilt()) {
                    if (IPConfig.getConfig().shouldDisplayWarning("quilt")) {
                        CHelper.printChat(
                            Component.translatable("imm_ptl.quilt_warning")
                                .append(IPMcHelper.getDisableWarningText("quilt"))
                        );
                    }
                }
            })
        ));
    }
    
    public static void init() {
        ClientWorldLoader.init();
        
        ClientTeleportationManager.init();
        
        Minecraft.getInstance().execute(() -> {
            ShaderCodeTransformation.init();
            
            MyRenderHelper.init();
            
            IPCGlobal.rendererUsingStencil = new RendererUsingStencil();
            IPCGlobal.rendererUsingFrameBuffer = new RendererUsingFrameBuffer();
            
            // MC 26.1: stencil testing was removed from Blaze3D's RenderPipeline
            // entirely (DepthStencilState has no stencil fields, and the main render
            // target's depth texture format has no combined depth+stencil option --
            // confirmed via decompiled source, see /memories/repo/
            // portal-stencil-fbo3-bleed-finding.md) -- the main render target ends up
            // with GL_STENCIL_BITS=0 regardless, making RendererUsingStencil's mask
            // a no-op that always "passes". Switched to the render-to-texture +
            // compositing-quad renderer instead, which needs no stencil/clip-plane
            // cooperation from Sodium/Distant Horizons/Iris at all.
            IPCGlobal.renderer = IPCGlobal.rendererUsingFrameBuffer;
        });
        
        DubiousThings.init();
        
        CrossPortalEntityRenderer.init();
        
        GLResourceCache.init();
        
        CollisionHelper.initClient();
        
        PortalRenderInfo.init();
        
        SharedBlockMeshBuffers.init();
        
        GcMonitor.initClient();
        
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            ClientDebugCommand.register(dispatcher);
        });
        
//        showIntelVideoCardWarning();
        
        showNvidiaVideoCardWarning();
        
        showQuiltWarning();
        
        StableClientTimer.init();
        
        ClientPortalAnimationManagement.init();
        
        VisibleSectionDiscovery.init();
        
        ImmPtlViewArea.init();
        
        IPFlywheelCompat.init();
    
        GuiPortalRendering._init();
        
        ImmPtlNetworking.initClient();
        ImmPtlNetworkConfig.initClient();
        
        ForceMainThreadRebuild.init();
        
        IPCGlobal.CLIENT_CLEANUP_EVENT.register(() -> {
            IPGlobal.CLIENT_TASK_LIST.forceClearTasks();
        });
        
        DimensionIntId.initClient();
    }
    
}
