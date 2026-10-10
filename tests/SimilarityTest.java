package tw.mediaorganizer;
public class SimilarityTest {
 static int checks;
 static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
 public static void main(String[] args){
  Similarity.Signature a=new Similarity.Signature(0x123456789abcdefL,120,80,60,1200,1.5);
  Similarity.Signature b=new Similarity.Signature(a.hash^7,124,82,58,1000,1.5);
  check(Similarity.visual(a,b,5),"near visual variant grouped");
  check(!Similarity.visual(a,new Similarity.Signature(a.hash,120,80,60,0,1.5),5),"blank frames excluded");
  check(!Similarity.visual(a,new Similarity.Signature(a.hash,120,80,60,1200,.7),5),"incompatible aspect excluded");
  check(!Similarity.visual(a,new Similarity.Signature(a.hash,255,0,255,1200,1.5),5),"different colors excluded");
  check(!Similarity.visual(a,new Similarity.Signature(~a.hash,120,80,60,1200,1.5),5),"different hashes excluded");
  check(Similarity.video(new Similarity.Signature[]{a,a,a,a,a,a,a},new Similarity.Signature[]{b,b,b,b,b,null,null},5),"video majority frames accepted");
  check(!Similarity.video(new Similarity.Signature[]{a,a,a,a,a,a,a},new Similarity.Signature[]{b,null,null,null,null,null,null},5),"one matching frame insufficient");
  check(Math.abs(Similarity.cosine(new float[]{1,2},new float[]{2,4})-1)<1e-6,"embedding scale invariance");
  check(Similarity.cosine(new float[]{1,0},new float[]{0,1})==0,"different embedding directions");
  check(Similarity.cosine(new float[]{0,0},new float[]{0,0})==-1,"zero embeddings rejected");
  check(Similarity.cosine(new float[]{Float.NaN},new float[]{1})==-1,"nonfinite embeddings rejected");
  check(!Similarity.visual(null,a,5),"unreadable image excluded");
  check(Similarity.escapeLike("trip_2026%").equals("trip\\_2026\\%"),"SQL LIKE wildcards escaped");
  System.out.println(checks+" checks passed");
 }
}
