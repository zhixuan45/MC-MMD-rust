package com.shiroha.mmdskin.ui.selector;

/** Geometry shared by drawing and pointer-to-frame conversion, including small GUI scales. */
final class PropTimelineLayout {
    record Rect(int x,int y,int w,int h) {
        int right() { return x+w; }
        int bottom() { return y+h; }
        boolean contains(double px,double py) { return px>=x && px<right() && py>=y && py<bottom(); }
    }
    final Rect preview, inspector, properties, visibility, attachment;
    final int railX,railWidth,controlsY,footerY;

    PropTimelineLayout(int width,int height) {
        int sidebar = Math.min(226,Math.max(148,width/3));
        int top = 48, timelineTop = height-104;
        preview = new Rect(12,top,Math.max(1,width-sidebar-32),Math.max(1,timelineTop-top-8));
        inspector = new Rect(width-sidebar-12,top,sidebar,preview.h);
        properties = new Rect(inspector.x+4,top+25,sidebar-8,Math.max(1,preview.h-29));
        railX = 82;
        railWidth = Math.max(1,width-railX-12);
        visibility = new Rect(12,timelineTop+13,width-24,17);
        attachment = new Rect(12,timelineTop+31,width-24,17);
        controlsY = timelineTop+55;
        footerY = height-24;
    }

    int xAt(int frame,int end) {
        return railX + (end<=0?0:(int)Math.round((railWidth-1)*(double)frame/end));
    }

    int frameAt(double x,int end) {
        if (railWidth<=1 || end<=0) return 0;
        return (int)Math.round(Math.max(0,Math.min(1,(x-railX)/(railWidth-1)))*end);
    }
}
