package tw.mediaorganizer;

public final class Similarity {
 public static final class Signature {
  public long hash; public double r,g,b,variance,aspect;
  public Signature(long h,double red,double green,double blue,double v,double a){hash=h;r=red;g=green;b=blue;variance=v;aspect=a;}
 }
 public static boolean visual(Signature a,Signature b,int bits){
  if(a==null||b==null||a.variance<80||b.variance<80)return false;
  if(Math.abs(Math.log(a.aspect/b.aspect))>.16)return false;
  double color=Math.abs(a.r-b.r)+Math.abs(a.g-b.g)+Math.abs(a.b-b.b);
  return Long.bitCount(a.hash^b.hash)<=bits && color<65;
 }
 public static double cosine(float[] a,float[] b){
  if(a==null||b==null||a.length!=b.length)return -1;
  double dot=0,na=0,nb=0;
  for(int i=0;i<a.length;i++){if(!Float.isFinite(a[i])||!Float.isFinite(b[i]))return -1;dot+=a[i]*b[i];na+=a[i]*a[i];nb+=b[i]*b[i];}
  return na>1e-12&&nb>1e-12?dot/Math.sqrt(na*nb):-1;
 }
 public static String escapeLike(String s){
  if(s==null)return "";
  return s.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
 }
 public static boolean video(Signature[] a,Signature[] b,int bits){
  if(a==null||b==null||a.length<3||b.length<3)return false;
  int n=Math.min(a.length,b.length),matches=0;
  for(int i=0;i<n;i++)if(visual(a[i],b[i],bits))matches++;
  return matches>=Math.max(3,(int)Math.ceil(n*.6));
 }
}
