package tw.mediaorganizer;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.media.*;
import android.net.Uri;
import android.provider.MediaStore;
import java.io.*;
import java.security.*;
import java.util.*;

final class Scanner {
 static final class Item {
  Uri uri;String name,mime,path;long size,duration;boolean video;File thumb;Similarity.Signature[] signatures;List<FaceEngine.FaceSample> faces=new ArrayList<>();boolean removed;
 }
 static final class Group {String title;List<Item> items=new ArrayList<>();float[] person;File face;Group(String t){title=t;}}
 static final class Result {List<Item> all=new ArrayList<>();List<Group> exact=new ArrayList<>(),similar=new ArrayList<>(),people=new ArrayList<>();int unreadable,faceFailures;String faceError="";}
 interface Progress {void report(String s);boolean cancelled();void onPartialResult(Result partial);}
 final Context context;final Progress progress;final boolean people;final int bits;final double faceThreshold;final String sourceFilter;
 Scanner(Context c,Progress p,boolean f,int b,double t,String src){context=c;progress=p;people=f;bits=b;faceThreshold=t;sourceFilter=src!=null?src:"";}
 private void check()throws InterruptedException{if(progress.cancelled()||Thread.currentThread().isInterrupted())throw new InterruptedException();}
 Result run()throws Exception{
  Result result=new Result();query(result,false);query(result,true);check();
  if(progress!=null)progress.onPartialResult(result);
  Map<String,List<Item>> sizes=new HashMap<>();for(Item item:result.all)sizes.computeIfAbsent((item.video?"v":"p")+item.size,k->new ArrayList<>()).add(item);
  Map<String,Group> hashes=new LinkedHashMap<>();int i=0;
  for(List<Item> candidates:sizes.values())if(candidates.size()>1)for(Item item:candidates){check();progress.report("檔案雜湊核對 · "+(++i)+" 個候選");try{String hash=sha(item.uri);Group group=hashes.computeIfAbsent((item.video?"v":"p")+hash,k->new Group("完全重複 · SHA-256"));group.items.add(item);}catch(IOException e){result.unreadable++;}}
  for(Group group:hashes.values())if(group.items.size()>1)result.exact.add(group);
  if(progress!=null)progress.onPartialResult(result);

  FaceEngine engine=null;if(people)try{engine=new FaceEngine(context);}catch(Exception|LinkageError e){result.faceError="人物模型無法啟動："+e.getClass().getSimpleName();}
  try{for(i=0;i<result.all.size();i++){
   check();Item item=result.all.get(i);progress.report("分析 "+(i+1)+" / "+result.all.size()+" · "+item.name);
   try{analyze(item,engine,result);}catch(Exception e){result.unreadable++;}
   if(i%5==0&&progress!=null)progress.onPartialResult(result);
  }}finally{if(engine!=null)engine.close();}
  progress.report("建立相似群組…");List<Group> representatives=new ArrayList<>();
  for(Item item:result.all){check();if(item.signatures==null)continue;Group found=null;
   for(Group g:representatives){Item other=g.items.get(0);if(item.video!=other.video)continue;boolean same=item.video?Similarity.video(item.signatures,other.signatures,bits):Similarity.visual(item.signatures[0],other.signatures[0],bits);if(same){found=g;break;}}
   if(found==null){found=new Group(item.video?"相似影片 · 多畫面比對":"相似照片 · 畫面比對");representatives.add(found);}found.items.add(item);
  }
  for(Group group:representatives)if(group.items.size()>1)result.similar.add(group);
  if(people&&result.faceError.isEmpty())for(Item item:result.all){check();for(FaceEngine.FaceSample f:item.faces){
   Group best=null;double score=faceThreshold;for(Group g:result.people){double s=Similarity.cosine(f.vector,g.person);if(s>=score){score=s;best=g;}}
   if(best==null){best=new Group("疑似同一人物 · 請人工確認");best.person=f.vector;best.face=f.thumbnail;result.people.add(best);}
   if(!best.items.contains(item))best.items.add(item);if(f.crop!=null)f.crop.recycle();
  }item.faces.clear();}
  result.people.removeIf(g->g.items.size()<2);
  if(progress!=null)progress.onPartialResult(result);
  return result;
 }
 private void query(Result result,boolean video)throws InterruptedException{
  Uri base=video?MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
  String[] columns={"_id","_display_name","mime_type","_size","relative_path",video?"duration":"width"};
  String selection="_size > 0";
  String[] selectionArgs=null;
  if(!sourceFilter.isEmpty()){
   selection+=" AND relative_path LIKE ?";
   selectionArgs=new String[]{sourceFilter+"%"};
  }
  try(Cursor cursor=context.getContentResolver().query(base,columns,selection,selectionArgs,"date_added DESC")){
   if(cursor==null)return;while(cursor.moveToNext()){check();Item item=new Item();item.uri=ContentUris.withAppendedId(base,cursor.getLong(0));item.name=cursor.getString(1);item.mime=cursor.getString(2);item.size=cursor.getLong(3);item.path=cursor.getString(4);item.video=video;item.duration=video?cursor.getLong(5):0;result.all.add(item);}
  }catch(SecurityException e){progress.report((video?"影片":"圖片")+"讀取權限不足");}
 }
 private String sha(Uri uri)throws IOException,InterruptedException,NoSuchAlgorithmException{
  MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=context.getContentResolver().openInputStream(uri)){
   if(in==null)throw new IOException();byte[] buffer=new byte[262144];int count;while((count=in.read(buffer))!=-1){check();digest.update(buffer,0,count);}}
  StringBuilder s=new StringBuilder();for(byte b:digest.digest())s.append(String.format(Locale.US,"%02x",b&255));return s.toString();
 }
 private void analyze(Item item,FaceEngine engine,Result result)throws Exception{
  if(!item.video){Bitmap bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(),item.uri),(decoder,info,source)->{int w=info.getSize().getWidth(),h=info.getSize().getHeight();float scale=Math.min(1f,640f/Math.max(w,h));decoder.setTargetSize(Math.max(1,(int)(w*scale)),Math.max(1,(int)(h*scale)));decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);});
   try{item.thumb=writeThumb(bitmap,"photo");item.signatures=new Similarity.Signature[]{signature(bitmap)};faces(item,bitmap,engine,result);}finally{bitmap.recycle();}
  }else{MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{
   retriever.setDataSource(context,item.uri);String duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);if(duration!=null)item.duration=Long.parseLong(duration);
   if(item.duration<=0)throw new IOException("影片時長不可讀");item.signatures=new Similarity.Signature[7];
   for(int n=0;n<7;n++){check();long time=(long)(item.duration*1000*(n+.5)/7);Bitmap frame=retriever.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,640,640);if(frame!=null){try{if(item.thumb==null)item.thumb=writeThumb(frame,"video");item.signatures[n]=signature(frame);faces(item,frame,engine,result);}finally{frame.recycle();}}}
  }finally{retriever.release();}}
 }
 private void faces(Item item,Bitmap bitmap,FaceEngine engine,Result result){
  if(engine==null||item.faces.size()>=8)return;
  try{for(FaceEngine.FaceSample sample:engine.extract(bitmap)){
   boolean duplicate=false;for(FaceEngine.FaceSample old:item.faces)if(Similarity.cosine(old.vector,sample.vector)>=faceThreshold){duplicate=true;break;}
   if(duplicate||item.faces.size()>=8)sample.crop.recycle();else {sample.thumbnail=writeThumb(sample.crop,"face-sample");sample.crop.recycle();sample.crop=null;item.faces.add(sample);}
  }}catch(Exception e){result.faceFailures++;}
 }
 private File writeThumb(Bitmap bitmap,String prefix)throws IOException{
  File directory=new File(context.getCacheDir(),"thumbs");directory.mkdirs();File file=new File(directory,prefix+UUID.randomUUID()+".jpg");
  float scale=Math.min(1f,220f/Math.max(bitmap.getWidth(),bitmap.getHeight()));Bitmap thumb=Bitmap.createScaledBitmap(bitmap,Math.max(1,(int)(bitmap.getWidth()*scale)),Math.max(1,(int)(bitmap.getHeight()*scale)),true);
  try(OutputStream out=new FileOutputStream(file)){thumb.compress(Bitmap.CompressFormat.JPEG,80,out);}finally{if(thumb!=bitmap)thumb.recycle();}return file;
 }
 private Similarity.Signature signature(Bitmap bitmap){
  Bitmap tiny=Bitmap.createScaledBitmap(bitmap,9,8,true);int[] p=new int[72];tiny.getPixels(p,0,9,0,0,9,8);if(tiny!=bitmap)tiny.recycle();
  long hash=0;double r=0,g=0,b=0,sum=0,sq=0;for(int y=0;y<8;y++)for(int x=0;x<9;x++){int c=p[y*9+x];r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);double gray=.299*Color.red(c)+.587*Color.green(c)+.114*Color.blue(c);sum+=gray;sq+=gray*gray;if(x<8){int d=p[y*9+x+1];double next=.299*Color.red(d)+.587*Color.green(d)+.114*Color.blue(d);if(gray>next)hash|=1L<<(y*8+x);}}
  return new Similarity.Signature(hash,r/72,g/72,b/72,sq/72-Math.pow(sum/72,2),(double)bitmap.getWidth()/bitmap.getHeight());
 }
}
