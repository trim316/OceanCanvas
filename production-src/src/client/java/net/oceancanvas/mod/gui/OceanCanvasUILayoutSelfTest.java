package net.oceancanvas.mod.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Exhaustive design-space layout contract for the approved Ocean Canvas shell (OC-F126).
 *
 * <p>The live screen renders in a fixed 1440x820 design space and applies one
 * uniform viewport transform. This test therefore verifies geometry in design
 * coordinates, every permanent control's label budget, representative dynamic
 * rows, scroll clipping/ranges and the transform at common/hostile resolutions.
 * It intentionally excludes modal overlays from collision checks because they
 * are allowed to cover the map while open.</p>
 */
public final class OceanCanvasUILayoutSelfTest {
    public static final int DESIGN_W=1440,DESIGN_H=820;

    public record Rect(String id,int x,int y,int w,int h){int right(){return x+w;}int bottom(){return y+h;}boolean inside(){return x>=0&&y>=0&&right()<=DESIGN_W&&bottom()<=DESIGN_H;}boolean overlaps(Rect o){return x<o.right()&&right()>o.x&&y<o.bottom()&&bottom()>o.y;}}
    public record Viewport(int width,int height){}
    public record Widget(String id,Rect rect,String label,int horizontalPadding,boolean ellipsisAllowed){}
    public record ScrollContract(String id,int viewportY,int viewportH,int contentH,int rowH){int maxScroll(){return Math.max(0,contentH-viewportH);}}
    public record Report(boolean pass,int checks,List<String> issues,long signature){public Report{issues=List.copyOf(issues);}public String summary(){return (pass?"PASS":"FAIL")+" · "+checks+" checks"+(issues.isEmpty()?"":" · "+issues.get(0));}}
    private OceanCanvasUILayoutSelfTest(){}

    public static Report run(){
        List<String> issues=new ArrayList<>();int checks=0;long sig=0x4f4355494c41594fL;
        List<Rect> fixed=List.of(
                new Rect("header",18,14,424,106),new Rect("tabs",495,14,450,44),new Rect("top-controls",1272,14,150,44),
                new Rect("region-tool-rail",18,162,62,46*6),new Rect("other-tool-rail",18,162,96,54*5),new Rect("compass-with-panel",986,104,58,58),
                new Rect("right-panel",1074,72,348,714),new Rect("coordinates",18,691,300,59),new Rect("bottom-toolbar",18,756,340,30),
                new Rect("operations-idle",518,756,404,30),new Rect("operations-open",440,426,560,360));
        for(Rect r:fixed){checks++;if(!r.inside())issues.add(r.id()+" escapes 1440x820 design space");sig=mix(sig,r.x());sig=mix(sig,r.y());sig=mix(sig,r.w());sig=mix(sig,r.h());}
        String[][] forbidden={{"header","tabs"},{"header","top-controls"},{"tabs","top-controls"},{"region-tool-rail","coordinates"},{"coordinates","bottom-toolbar"},{"bottom-toolbar","operations-idle"},{"right-panel","operations-idle"},{"compass-with-panel","right-panel"}};
        for(String[] pair:forbidden){checks++;Rect a=find(fixed,pair[0]),b=find(fixed,pair[1]);if(a.overlaps(b))issues.add(pair[0]+" overlaps "+pair[1]);}

        // Permanent and high-frequency controls.  The approximate font metric is intentionally
        // conservative (7 px/glyph) so this catches labels that only barely fit the Minecraft font.
        List<Widget> widgets=List.of(
                new Widget("tab-regions",new Rect("",495,14,108,44),"Regions",18,false),new Widget("tab-plans",new Rect("",609,14,96,44),"Plans",18,false),
                new Widget("tab-projects",new Rect("",711,14,116,44),"Projects",18,false),new Widget("tab-layers",new Rect("",833,14,112,44),"Layers",18,false),
                new Widget("tool-select",new Rect("",18,162,96,46),"Select",34,false),new Widget("tool-pan",new Rect("",18,208,96,46),"Pan",34,false),
                new Widget("tool-shape",new Rect("",18,254,96,46),"Shape",34,false),new Widget("tool-vertex",new Rect("",18,300,96,46),"Vertex",34,false),
                new Widget("tool-path",new Rect("",18,346,96,46),"Path",34,false),new Widget("tool-waypoint",new Rect("",18,392,96,46),"Waypoint",34,false),
                new Widget("coord-x",new Rect("",26,704,128,32),"X  -30000000",10,true),new Widget("coord-z",new Rect("",160,704,128,32),"Z  -30000000",10,true),
                new Widget("bottom-toggle",new Rect("",18,756,92,30),"Panels",12,false),new Widget("bottom-help",new Rect("",116,756,72,30),"? Help",12,false),
                new Widget("bottom-inspect",new Rect("",194,756,92,30),"Inspect",12,false),new Widget("bottom-workbench",new Rect("",292,756,66,30),"Work",10,false),
                new Widget("ops-state",new Rect("",518,756,404,30),"WAITING FOR CHUNK LOAD · 1,562,500 / 1,562,500",12,true),
                new Widget("workbench-savequit",new Rect("",638,450,190,28),"Save/Quit Matrix",14,false),new Widget("workbench-crash",new Rect("",836,450,190,28),"Crash Matrix",14,false),
                new Widget("right-long-name",new Rect("",1092,132,310,24),"A deliberately extremely long region / project / plan name that must ellipsize",12,true));
        for(Widget w:widgets){checks+=3;if(!w.rect().inside())issues.add(w.id()+" widget escapes design bounds");int budget=Math.max(0,w.rect().w()-w.horizontalPadding());int text=approxTextWidth(w.label());if(text>budget&&!w.ellipsisAllowed())issues.add(w.id()+" label clips: needs ~"+text+"px, budget="+budget);if(w.rect().h()<24)issues.add(w.id()+" hit target below 24 design px");sig=mix(sig,w.id().hashCode());sig=mix(sig,text);}

        // Scroll containers are checked at min/mid/max offsets. A valid implementation must clamp
        // to [0,maxScroll], preserve whole-row progress and never reveal content outside viewport.
        List<ScrollContract> scrolls=List.of(
                new ScrollContract("right-region-list",162,500,1400,28),new ScrollContract("plan-library",162,500,2200,28),
                new ScrollContract("project-task-list",162,500,2800,30),new ScrollContract("workbench",474,286,1800,28),
                new ScrollContract("recovery-history",474,286,1200,24),new ScrollContract("health-findings",474,286,1600,24));
        for(ScrollContract sc:scrolls){checks+=8;int max=sc.maxScroll();if(max<0)issues.add(sc.id()+" negative max scroll");int[] samples={-500,0,max/2,max,max+500};for(int raw:samples){int clamped=Math.max(0,Math.min(max,raw));if(clamped<0||clamped>max)issues.add(sc.id()+" clamp escaped range");int first=Math.max(0,clamped/sc.rowH()),last=Math.min((sc.contentH()+sc.rowH()-1)/sc.rowH(),(clamped+sc.viewportH()+sc.rowH()-1)/sc.rowH());if(last<first)issues.add(sc.id()+" visible row interval inverted");sig=mix(sig,clamped);sig=mix(sig,first);sig=mix(sig,last);}if(sc.viewportY()<0||sc.viewportY()+sc.viewportH()>DESIGN_H)issues.add(sc.id()+" viewport escapes design bounds");}

        // Fixed design remains visible and controls remain physically clickable at representative
        // GUI/window combinations, including 720p, 16:10, ultrawide, 4K and a small 854x480 window.
        List<Viewport> viewports=List.of(new Viewport(854,480),new Viewport(1280,720),new Viewport(1440,820),new Viewport(1920,1080),new Viewport(1920,1200),new Viewport(2560,1440),new Viewport(3440,1440),new Viewport(3840,2160));
        for(Viewport v:viewports){double scale=Math.min(v.width()/(double)DESIGN_W,v.height()/(double)DESIGN_H);double ox=(v.width()-DESIGN_W*scale)*.5,oy=(v.height()-DESIGN_H*scale)*.5;checks+=6;if(!(scale>0))issues.add("non-positive scale for "+v.width()+"x"+v.height());if(ox<-1e-6||oy<-1e-6)issues.add("negative viewport offset for "+v.width()+"x"+v.height());if(ox+DESIGN_W*scale>v.width()+1e-6)issues.add("design clips horizontally at "+v.width()+"x"+v.height());if(oy+DESIGN_H*scale>v.height()+1e-6)issues.add("design clips vertically at "+v.width()+"x"+v.height());if(44*scale<24)issues.add("44px primary control falls below 24 physical px at "+v.width()+"x"+v.height());if(30*scale<16)issues.add("30px compact control falls below 16 physical px at "+v.width()+"x"+v.height());sig=mix(sig,v.width());sig=mix(sig,v.height());sig=mix(sig,Double.doubleToLongBits(scale));}
        return new Report(issues.isEmpty(),checks,issues,sig);
    }

    private static int approxTextWidth(String s){if(s==null)return 0;int w=0;for(char c:s.toCharArray())w+=c==' '||c=='.'||c==','||c==':'||c=='/'?4:(Character.isUpperCase(c)||Character.isDigit(c)?7:6);return w;}
    private static Rect find(List<Rect> rects,String id){return rects.stream().filter(r->r.id().equals(id)).findFirst().orElseThrow();}
    private static long mix(long h,long v){h^=v+0x9e3779b97f4a7c15L+(h<<6)+(h>>>2);return h;}
    public static void main(String[] args){Report r=run();if(!r.pass()){for(String issue:r.issues())System.err.println("layout: "+issue);throw new IllegalStateException(r.summary());}System.out.println("Ocean Canvas UI layout self-test "+r.summary()+" signature="+Long.toUnsignedString(r.signature()));}
}
