package qouteall.imm_ptl.core.ducks;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public interface IECamera {
    void ip_resetState(Vec3 pos, ClientLevel currWorld);
    
    void portal_setPos(Vec3 pos);
    
    float ip_getCameraY();
    
    float ip_getLastCameraY();
    
    void ip_setCameraY(float cameraY, float lastCameraY);
    
    void portal_setFocusedEntity(Entity arg);
    
    /**
     * A freshly-constructed {@link net.minecraft.client.Camera}'s zoom-in/out fov
     * modifier starts at {@code 0} (only eased toward {@code 1} over time by
     * {@code tick()}, which is never called on the short-lived per-portal-render
     * camera object) - without this, {@code Camera.update()}'s fov calculation would
     * multiply by a modifier of {@code 0}, producing a degenerate zero-FOV projection
     * for portal content. Call this right after construction, before {@code update()}.
     */
    void ip_setFovModifier(float modifier);
}
