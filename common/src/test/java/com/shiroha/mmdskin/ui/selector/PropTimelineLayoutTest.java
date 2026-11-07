package com.shiroha.mmdskin.ui.selector;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PropTimelineLayoutTest {
    @Test void panelsTracksAndControlsStaySeparateAtSupportedGuiSizes() {
        for (int[] size : new int[][]{{320,240},{427,240},{640,360},{854,480},{1280,720}}) {
            var layout = new PropTimelineLayout(size[0],size[1]);
            assertTrue(layout.preview.w()>0 && layout.preview.h()>0);
            assertTrue(layout.preview.right()<layout.inspector.x());
            assertTrue(layout.preview.bottom()<layout.visibility.y());
            assertTrue(layout.properties.bottom()<=layout.inspector.bottom());
            assertTrue(layout.attachment.bottom()<layout.controlsY);
            assertTrue(layout.controlsY+18<layout.footerY);
            assertTrue(layout.footerY+18<=size[1]);
        }
    }

    @Test void draggingCanReachBothEndpointsAndClampsOutsideTheRail() {
        var layout = new PropTimelineLayout(854,480);
        for (int end : new int[]{0,1,30,900,100_000}) {
            assertEquals(0,layout.frameAt(layout.railX-100,end));
            assertEquals(end,layout.frameAt(layout.railX+layout.railWidth+100,end));
            assertEquals(0,layout.frameAt(layout.xAt(0,end),end));
            assertEquals(end,layout.frameAt(layout.xAt(end,end),end));
        }
        assertEquals(450,layout.frameAt(layout.railX+(layout.railWidth-1)*.5,900));
    }
}
