package net.oceancanvas.mod.planning;

import java.util.ArrayList;
import java.util.List;

/** Pure client-side image-pixel <-> Minecraft X/Z registration math. */
public final class OceanCanvasReferenceRegistration {
    private OceanCanvasReferenceRegistration() { }

    public record ControlPoint(double imageX,double imageY,double worldX,double worldZ) { }
    /** X=a*x+b*y+tx; Z=c*x+d*y+tz. */
    public record Affine(double a,double b,double tx,double c,double d,double tz,double rms) {
        public double[] imageToWorld(double x,double y){return new double[]{a*x+b*y+tx,c*x+d*y+tz};}
        public double[] worldToImage(double x,double z){double det=a*d-b*c;if(Math.abs(det)<1e-12)return null;double X=x-tx,Z=z-tz;return new double[]{(d*X-b*Z)/det,(-c*X+a*Z)/det};}
        public double determinant(){return a*d-b*c;}
    }

    public static List<ControlPoint> parse(String packed){
        List<ControlPoint> out=new ArrayList<>();if(packed==null||packed.isBlank())return out;
        for(String raw:packed.split(";")){String[] f=raw.split(",",-1);if(f.length!=4)continue;try{out.add(new ControlPoint(Double.parseDouble(f[0]),Double.parseDouble(f[1]),Double.parseDouble(f[2]),Double.parseDouble(f[3])));}catch(NumberFormatException ignored){}if(out.size()>=3)break;}
        return out;
    }

    public static Affine solve(List<ControlPoint> p){
        if(p==null||p.size()<2)throw new IllegalArgumentException("At least two control points are required.");
        if(p.size()==2)return similarity(p);
        return affine3(p.get(0),p.get(1),p.get(2));
    }

    private static Affine similarity(List<ControlPoint> pts){
        double mx=0,my=0,mX=0,mZ=0;int n=pts.size();for(var p:pts){mx+=p.imageX;my+=p.imageY;mX+=p.worldX;mZ+=p.worldZ;}mx/=n;my/=n;mX/=n;mZ/=n;
        double den=0,A=0,B=0;for(var p:pts){double x=p.imageX-mx,y=p.imageY-my,X=p.worldX-mX,Z=p.worldZ-mZ;den+=x*x+y*y;A+=x*X+y*Z;B+=x*Z-y*X;}if(den<1e-9)throw new IllegalArgumentException("Control points are too close together.");
        double a=A/den,b=B/den,tx=mX-a*mx+b*my,tz=mZ-b*mx-a*my;return withRms(new Affine(a,-b,tx,b,a,tz,0),pts);
    }

    private static Affine affine3(ControlPoint p0,ControlPoint p1,ControlPoint p2){
        double x1=p1.imageX-p0.imageX,y1=p1.imageY-p0.imageY,x2=p2.imageX-p0.imageX,y2=p2.imageY-p0.imageY;
        double det=x1*y2-y1*x2;if(Math.abs(det)<1e-9)throw new IllegalArgumentException("Three image control points must not be collinear.");
        double X1=p1.worldX-p0.worldX,Z1=p1.worldZ-p0.worldZ,X2=p2.worldX-p0.worldX,Z2=p2.worldZ-p0.worldZ;
        double a=(X1*y2-X2*y1)/det,b=(x1*X2-x2*X1)/det,c=(Z1*y2-Z2*y1)/det,d=(x1*Z2-x2*Z1)/det;
        double tx=p0.worldX-a*p0.imageX-b*p0.imageY,tz=p0.worldZ-c*p0.imageX-d*p0.imageY;
        Affine out=new Affine(a,b,tx,c,d,tz,0);if(Math.abs(out.determinant())<1e-12)throw new IllegalArgumentException("Registration collapses the image to a line.");return withRms(out,List.of(p0,p1,p2));
    }

    private static Affine withRms(Affine t,List<ControlPoint> pts){double e=0;for(var p:pts){double[] q=t.imageToWorld(p.imageX,p.imageY);double dx=q[0]-p.worldX,dz=q[1]-p.worldZ;e+=dx*dx+dz*dz;}return new Affine(t.a,t.b,t.tx,t.c,t.d,t.tz,Math.sqrt(e/pts.size()));}

    public static double[] worldBounds(Affine t,int width,int height){double minX=Double.POSITIVE_INFINITY,minZ=Double.POSITIVE_INFINITY,maxX=Double.NEGATIVE_INFINITY,maxZ=Double.NEGATIVE_INFINITY;for(double[] p:new double[][]{{0,0},{width,0},{width,height},{0,height}}){double[] q=t.imageToWorld(p[0],p[1]);minX=Math.min(minX,q[0]);minZ=Math.min(minZ,q[1]);maxX=Math.max(maxX,q[0]);maxZ=Math.max(maxZ,q[1]);}return new double[]{minX,minZ,maxX,maxZ};}
}
