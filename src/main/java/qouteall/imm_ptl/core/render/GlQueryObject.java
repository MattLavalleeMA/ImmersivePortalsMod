package qouteall.imm_ptl.core.render;

import net.minecraft.util.Util;
import org.apache.commons.lang3.Validate;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayList;

public class GlQueryObject {
    private int idQueryObject = -1;
    private boolean isQuerying = false;
    private boolean hasResult = false;
    
    public GlQueryObject(int handle) {
        this.idQueryObject = handle;
    }
    
    public void performQueryAnySamplePassed(Runnable renderingFunc) {
        // mac does not support any samples passed query
        if (Util.getPlatform() == Util.OS.OSX) {
            performQuerySampleNumPassed(renderingFunc);
            return;
        }
        
        performQuery(renderingFunc, GL33.GL_ANY_SAMPLES_PASSED);
    }
    
    public void performQuerySampleNumPassed(Runnable renderingFunc) {
        performQuery(renderingFunc, GL15.GL_SAMPLES_PASSED);
    }
    
    private void performQuery(Runnable renderingFunc, int glQueryType) {
        Validate.isTrue(isValid());
        
        Validate.isTrue(!isQuerying);
        
        GL15.glBeginQuery(glQueryType, idQueryObject);
        
        // TEMP DIAGNOSTIC (2026-07-12): verify glBeginQuery itself didn't silently
        // fail (e.g. GL_INVALID_OPERATION from an already-active query of the same
        // target elsewhere) -- this call site had NO error checking at all before.
        // Remove once root-caused/fixed.
        {
            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) {
                qouteall.q_misc_util.Helper.log(
                    "[PORTAL-SKIP-DIAG] glBeginQuery GL error=" + err + " queryType=" + glQueryType
                        + " idQueryObject=" + idQueryObject
                );
            }
        }
        
        isQuerying = true;
        
        renderingFunc.run();
        
        GL15.glEndQuery(glQueryType);
        
        // TEMP DIAGNOSTIC (2026-07-12): same for glEndQuery, plus an immediate
        // (blocking) peek at the query result right here, before
        // PortalRenderInfo/fetchQueryResult ever reads it later -- to see the raw
        // value as close to the draw as possible. Remove once root-caused/fixed.
        {
            int err = GL11.glGetError();
            int immediateResult = GL15.glGetQueryObjecti(idQueryObject, GL15.GL_QUERY_RESULT);
            qouteall.q_misc_util.Helper.log(
                "[PORTAL-SKIP-DIAG] glEndQuery GL error=" + err + " queryType=" + glQueryType
                    + " idQueryObject=" + idQueryObject + " immediateResult=" + immediateResult
            );
        }
        
        isQuerying = false;
        
        hasResult = true;
    }
    
    public boolean fetchQueryResult() {
        Validate.isTrue(isValid());
        Validate.isTrue(hasResult);
        
        int result = GL15.glGetQueryObjecti(idQueryObject, GL15.GL_QUERY_RESULT);
        
        return result != 0;
    }
    
    private void dispose() {
        if (idQueryObject != -1) {
            GL15.glDeleteQueries(idQueryObject);
            idQueryObject = -1;
        }
    }
    
    public boolean isValid() {
        return idQueryObject != -1;
    }
    
    private void reset() {
        hasResult = false;
    }
    
    private static final ArrayList<GlQueryObject> queryObjects = new ArrayList<>();
    
    private static void prepareQueryObjects() {
        int[] buf = new int[500];
        GL15.glGenQueries(buf);
        for (int id : buf) {
            queryObjects.add(new GlQueryObject(id));
        }
    }
    
    public static GlQueryObject acquireQueryObject() {
        if (queryObjects.isEmpty()) {
            prepareQueryObjects();
        }
        
        return queryObjects.remove(queryObjects.size() - 1);
    }
    
    public static void returnQueryObject(GlQueryObject obj) {
        obj.reset();
        if (queryObjects.size() > 1500) {
            obj.dispose();
        }
        else {
            queryObjects.add(obj);
        }
    }
}
