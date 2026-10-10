package tw.mediaorganizer;
import android.content.Context;
import android.graphics.*;
import android.media.FaceDetector;
import org.tensorflow.lite.Interpreter;
import java.nio.*;
import java.io.*;
import java.util.*;

final class FaceEngine implements AutoCloseable {
 private final Interpreter model;
 FaceEngine(Context c)throws IOException{
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=c.getAssets().open("mobilefacenet.tflite")){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}
  ByteBuffer bytes=ByteBuffer.allocateDirect(out.size()).order(ByteOrder.nativeOrder());bytes.put(out.toByteArray()).rewind();
  model=new Interpreter(bytes,new Interpreter.Options().setNumThreads(2));
  model.allocateTensors();
  if(model.getOutputTensor(0).shape()[1]!=192)throw new IOException("人物模型尺寸不符");
 }
 List<FaceSample> extract(Bitmap original){
  List<FaceSample> result=new ArrayList<>();
  int w=Math.min(640,original.getWidth());w=Math.max(2,w&~1);int h=Math.max(2,original.getHeight()*w/original.getWidth());
  Bitmap scaled=Bitmap.createScaledBitmap(original,w,h,true);Bitmap rgb=scaled.copy(Bitmap.Config.RGB_565,false);
  FaceDetector.Face[] detected=new FaceDetector.Face[6];
  int n=new FaceDetector(w,h,6).findFaces(rgb,detected);
  for(int i=0;i<n;i++){
   FaceDetector.Face f=detected[i];if(f.confidence()<.5||f.eyesDistance()<12)continue;
   PointF mid=new PointF();f.getMidPoint(mid);float d=f.eyesDistance();
   // Align eye line, then normalize a face region around both eyes.
   Matrix transform=new Matrix();transform.postTranslate(-mid.x,-mid.y);transform.postRotate(-f.pose(FaceDetector.Face.EULER_Z));transform.postScale(112f/(d*3.0f),112f/(d*3.0f));transform.postTranslate(56,44);
   float left=mid.x-1.5f*d,top=mid.y-1.18f*d,right=mid.x+1.5f*d,bottom=mid.y+1.82f*d;
   if(left<0||top<0||right>w||bottom>h)continue;
   Bitmap face=Bitmap.createBitmap(112,112,Bitmap.Config.ARGB_8888);new Canvas(face).drawBitmap(scaled,transform,new Paint(Paint.FILTER_BITMAP_FLAG));
   ByteBuffer input=ByteBuffer.allocateDirect(2*112*112*3*4).order(ByteOrder.nativeOrder());
   int[] pixels=new int[112*112];face.getPixels(pixels,0,112,0,0,112,112);
   for(int batch=0;batch<2;batch++)for(int p:pixels){input.putFloat((Color.red(p)-127.5f)/128f);input.putFloat((Color.green(p)-127.5f)/128f);input.putFloat((Color.blue(p)-127.5f)/128f);}input.rewind();
   float[][] output=new float[2][192];model.run(input,output);
   result.add(new FaceSample(output[0],face));
  }
  rgb.recycle();if(scaled!=original)scaled.recycle();return result;
 }
 public void close(){model.close();}
 static final class FaceSample {final float[] vector;Bitmap crop;File thumbnail;FaceSample(float[] v,Bitmap b){vector=v;crop=b;}}
}
