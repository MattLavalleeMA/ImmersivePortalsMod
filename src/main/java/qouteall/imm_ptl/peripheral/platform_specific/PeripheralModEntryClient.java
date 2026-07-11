package qouteall.imm_ptl.peripheral.platform_specific;

import net.fabricmc.api.ClientModInitializer;
import qouteall.imm_ptl.peripheral.PeripheralModMain;

public class PeripheralModEntryClient implements ClientModInitializer {
    public static void registerBlockRenderLayers() {
        // TODO MC 26.1: net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap
        // no longer exists in Fabric API 0.154.2+26.1.2 (not bundled in any of its
        // nested jars) - per-block chunk render layer registration appears to have
        // moved into the block-state-model/FabricBlockStateModel system
        // (fabric-renderer-api-v1's net.fabricmc.fabric.api.client.renderer.v1.model
        // package) rather than a simple external registry call. Not yet researched in
        // depth; portalHelperBlock will render using its default (solid) layer instead
        // of cutout until this is fixed.
    }
    
    @Override
    public void onInitializeClient() {
        PeripheralModEntryClient.registerBlockRenderLayers();
        
        PeripheralModMain.initClient();
    }
}
