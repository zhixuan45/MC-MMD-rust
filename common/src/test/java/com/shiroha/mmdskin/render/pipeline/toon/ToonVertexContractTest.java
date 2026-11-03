package com.shiroha.mmdskin.render.pipeline.toon;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToonVertexContractTest {
    private static final String INPUT = "in vec3 iris_Position;\n";
    private static final String POSITION = "gl_Position = iris_ProjMat * iris_ModelViewMat * vec4(iris_Position, 1.0);";

    @Test void canonicalMatrixOutputAndExactFtransformHelperAreAccepted() {
        assertTrue(ToonVertexContract.canonical(INPUT + "void main(){" + POSITION + "}"));
        assertTrue(ToonVertexContract.canonical("in vec3 Position; uniform vec3 ChunkOffset;"
                + "void main(){gl_Position=ProjMat*ModelViewMat*vec4(Position+ChunkOffset,1.0);}"));
        assertTrue(ToonVertexContract.canonical(INPUT
                + "vec4 iris_ftransform() { return iris_ProjMat * iris_ModelViewMat * vec4(iris_Position,1.0); }"
                + "void main(){ gl_Position=iris_ftransform(); }"));
    }

    @Test void localPositionJitterAndComponentWritesFailClosed() {
        assertFalse(ToonVertexContract.canonical(INPUT + "void main(){gl_Position=iris_ProjMat*warpedPosition;}"));
        assertFalse(ToonVertexContract.canonical(INPUT + POSITION + "gl_Position.xy += jitter * gl_Position.w;"));
        assertFalse(ToonVertexContract.canonical(INPUT + POSITION + POSITION));
    }

    @Test void commentsMacrosAndExtraClipPlanesCannotVerifyGeometry() {
        assertFalse(ToonVertexContract.canonical(INPUT + "/*" + POSITION + "*/ gl_Position=other;"));
        assertFalse(ToonVertexContract.canonical("#define iris_Position warped\n" + INPUT + POSITION));
        assertFalse(ToonVertexContract.canonical(INPUT + POSITION + "gl_ClipDistance[0]=plane;"));
        assertFalse(ToonVertexContract.canonical("vec3 iris_Position;" + POSITION));
    }
}
