package tw.mediaorganizer;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import org.json.*;

public class MainActivity extends Activity {
 private final int BG=0xff101723,CARD=0xff1d293b,TEXT=0xffedf4ff,ACCENT=0xff72d9c3;
 private LinearLayout root,list;private TextView status,selection,srcLabel;private EditText folder;private CheckBox people;private Spinner strictness;private Button scan,move,deleteBtn,cancel;
 private final ExecutorService scanWorker=Executors.newSingleThreadExecutor();
 private final ExecutorService fileWorker=Executors.newSingleThreadExecutor();
 private volatile boolean cancelled,dead,busy,fileBusy;private Scanner.Result result;
 private final LinkedHashSet<Scanner.Item> selected=new LinkedHashSet<>();private int mode=0,limit=20;
 private List<MoveRecord> pending;private boolean undoing;private String destination;private long lastProgress;
 private boolean isDeleting;private List<Scanner.Item> pendingDeletes;
 private String sourceFilter="";

 private static final class MoveRecord {Uri uri;String oldPath,oldName,newPath,newName;Scanner.Item item;}
 public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);build();}
 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
 private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(TEXT);t.setTextSize(size);t.setPadding(0,dp(5),0,dp(5));return t;}
 private Button button(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setTextColor(TEXT);b.setAllCaps(false);b.setOnClickListener(v->r.run());return b;}
 private void build(){
  root=new LinearLayout(this);root.setOrientation(1);root.setBackgroundColor(BG);root.setPadding(dp(16),dp(12),dp(16),dp(8));
  root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());root.setPadding(dp(16),dp(8)+i.top,dp(16),dp(8)+i.bottom);return insets;});setContentView(root);
  TextView title=text("相簿整理員 v1.2",27);title.setTypeface(null,1);root.addView(title);TextView subtitle=text("離線掃描 · 邊掃邊整理 · 刪除與搬移",12);subtitle.setTextColor(ACCENT);root.addView(subtitle);

  LinearLayout srcBox=new LinearLayout(this);srcBox.setGravity(Gravity.CENTER_VERTICAL);
  srcBox.addView(button("選擇來源資料夾",this::selectSourceFolder),new LinearLayout.LayoutParams(-2,dp(42)));
  srcLabel=text(sourceFilter.isEmpty()?" 來源：全部媒體":" 來源："+sourceFilter,12);
  srcBox.addView(srcLabel,new LinearLayout.LayoutParams(0,-2,1));
  root.addView(srcBox);

  people=new CheckBox(this);people.setText("同時分析人物（較耗時）");people.setTextColor(TEXT);people.setChecked(true);root.addView(people);
  LinearLayout actions=new LinearLayout(this);scan=button("開始掃描",this::requestScan);cancel=button("停止",()->{cancelled=true;status.setText("正在停止，已完成的掃描結果已保留…");});cancel.setEnabled(false);actions.addView(scan,new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(cancel,new LinearLayout.LayoutParams(dp(80),dp(48)));root.addView(actions);
  LinearLayout opts=new LinearLayout(this);opts.setGravity(Gravity.CENTER_VERTICAL);opts.addView(text("相似程度",12));strictness=new Spinner(this);String[] labels={"嚴格（建議）","一般","寬鬆（較多誤判）"};ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){public View getView(int p,View v,android.view.ViewGroup parent){TextView t=(TextView)super.getView(p,v,parent);t.setTextColor(TEXT);return t;}};adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);strictness.setAdapter(adapter);opts.addView(strictness);root.addView(opts);

  LinearLayout dstBox=new LinearLayout(this);dstBox.setGravity(Gravity.CENTER_VERTICAL);
  folder=new EditText(this);folder.setSingleLine(true);folder.setTextColor(TEXT);folder.setTextSize(14);folder.setHintTextColor(0xff9baac2);folder.setHint("目的資料夾名稱");folder.setText(getPreferences(0).getString("folder","整理收藏"));
  dstBox.addView(folder,new LinearLayout.LayoutParams(0,dp(44),1));
  dstBox.addView(button("選擇/新建",this::selectOrNewFolder),new LinearLayout.LayoutParams(-2,dp(42)));
  root.addView(text("目的地：手機內部儲存空間 / DCIM / 資料夾名稱",11));root.addView(dstBox);

  status=text("授權後可掃描手機相簿。僅能讀取 Android 允許的檔案。",12);root.addView(status);
  LinearLayout tabs=new LinearLayout(this);String[] names={"重複","相似","人物","全部"};for(int i=0;i<4;i++){final int idx=i;Button tab=button(names[i],()->{mode=idx;limit=20;render();});tab.setTextSize(12);tabs.addView(tab,new LinearLayout.LayoutParams(0,dp(44),1));}root.addView(tabs);
  ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(1);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
  selection=text("已選 0 個",12);root.addView(selection);

  LinearLayout footer=new LinearLayout(this);move=button("移到此資料夾",this::confirmMove);deleteBtn=button("刪除所選檔",this::confirmDelete);Button clear=button("取消勾選",()->{selected.clear();render();});
  footer.addView(move,new LinearLayout.LayoutParams(0,dp(46),1));
  footer.addView(deleteBtn,new LinearLayout.LayoutParams(0,dp(46),1));
  footer.addView(clear,new LinearLayout.LayoutParams(dp(90),dp(46)));
  root.addView(footer);

  LinearLayout tools=new LinearLayout(this);tools.addView(button("復原上次移動",this::undo),new LinearLayout.LayoutParams(0,dp(42),1));tools.addView(button("說明／清除快取",this::help),new LinearLayout.LayoutParams(0,dp(42),1));root.addView(tools);render();
 }

 private void selectSourceFolder(){
  List<String> folders=getDcimSubfolders();
  folders.add(0,"[ 全部媒體 ]");
  String[] items=folders.toArray(new String[0]);
  new AlertDialog.Builder(this).setTitle("選擇來源資料夾").setItems(items,(dialog,which)->{
   if(which==0)sourceFilter="";
   else sourceFilter="DCIM/"+items[which]+"/";
   srcLabel.setText(sourceFilter.isEmpty()?" 來源：全部媒體":" 來源："+sourceFilter);
   Toast.makeText(this,"已選擇來源："+(sourceFilter.isEmpty()?"全部媒體":sourceFilter),Toast.LENGTH_SHORT).show();
  }).setNegativeButton("取消",null).show();
 }

 private void selectOrNewFolder(){
  List<String> folders=getDcimSubfolders();
  folders.add("[ 新增資料夾… ]");
  String[] items=folders.toArray(new String[0]);
  new AlertDialog.Builder(this).setTitle("選擇或新建目的資料夾").setItems(items,(dialog,which)->{
   if(which==items.length-1){
    EditText input=new EditText(this);
    input.setHint("請輸入新資料夾名稱");
    new AlertDialog.Builder(this).setTitle("新建資料夾").setView(input).setPositiveButton("確定",(d,w)->{
     String name=input.getText().toString().trim();
     if(!name.isEmpty()){
      folder.setText(name);
      Toast.makeText(this,"已設定目的資料夾："+name,Toast.LENGTH_SHORT).show();
     }
    }).setNegativeButton("取消",null).show();
   }else{
    folder.setText(items[which]);
    Toast.makeText(this,"已選擇目的資料夾："+items[which],Toast.LENGTH_SHORT).show();
   }
  }).setNegativeButton("取消",null).show();
 }

 private List<String> getDcimSubfolders(){
  Set<String> subfolders=new TreeSet<>();
  try(android.database.Cursor c=getContentResolver().query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),new String[]{"relative_path"},"relative_path LIKE ?",new String[]{"DCIM/%"},null)){
   if(c!=null)while(c.moveToNext()){
    String p=c.getString(0);
    if(p!=null&&p.startsWith("DCIM/")){
     String sub=p.substring(5);
     int slash=sub.indexOf('/');
     if(slash>0){
      subfolders.add(sub.substring(0,slash));
     }
    }
   }
  }catch(Exception e){}
  return new ArrayList<>(subfolders);
 }

 private boolean permission(String p){return checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;}
 private void requestScan(){
  if(busy)return;if(Build.VERSION.SDK_INT>=33){if(!permission("android.permission.READ_MEDIA_IMAGES")||!permission("android.permission.READ_MEDIA_VIDEO")){ArrayList<String> perms=new ArrayList<>(Arrays.asList("android.permission.READ_MEDIA_IMAGES","android.permission.READ_MEDIA_VIDEO"));if(Build.VERSION.SDK_INT>=34)perms.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");requestPermissions(perms.toArray(new String[0]),41);return;}}
  else if(!permission("android.permission.READ_EXTERNAL_STORAGE")){requestPermissions(new String[]{"android.permission.READ_EXTERNAL_STORAGE"},41);return;}startScan();
 }
 public void onRequestPermissionsResult(int code,String[] perms,int[] grants){super.onRequestPermissionsResult(code,perms,grants);if(code==41){boolean granted=false;for(int g:grants)if(g==PackageManager.PERMISSION_GRANTED)granted=true;if(granted)startScan();else status.setText("尚未取得媒體權限；請重新掃描並授權。");}}
 private void setBusy(boolean b){busy=b;scan.setEnabled(!b);cancel.setEnabled(b);people.setEnabled(!b);strictness.setEnabled(!b);folder.setEnabled(!b);move.setEnabled(!selected.isEmpty()&&!fileBusy);deleteBtn.setEnabled(!selected.isEmpty()&&!fileBusy);if(b)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
 private void startScan(){
  selected.clear();result=new Scanner.Result();render();setBusy(true);cancelled=false;status.setText("掃描中，可即時查看結果與進行處理…");final boolean faces=people.isChecked();final int setting=strictness.getSelectedItemPosition();lastProgress=0;
  scanWorker.execute(()->{try{clearThumbs();
    Scanner scanner=new Scanner(this,new Scanner.Progress(){
     public boolean cancelled(){return cancelled||dead;}
     public void report(String s){long now=System.currentTimeMillis();if(now-lastProgress<250)return;lastProgress=now;ui(()->status.setText(s));}
     public void onPartialResult(Scanner.Result partial){ui(()->{result=partial;render();});}
    },faces,new int[]{5,8,12}[setting],new double[]{.72,.65,.58}[setting],sourceFilter);
    Scanner.Result scanned=scanner.run();
    ui(()->{result=scanned;setBusy(false);boolean full=Build.VERSION.SDK_INT<33||permission("android.permission.READ_MEDIA_IMAGES")&&permission("android.permission.READ_MEDIA_VIDEO");String summary="完成 · "+scanned.all.size()+" 個檔案 · "+scanned.exact.size()+" 組重複 · "+scanned.similar.size()+" 組相似 · "+scanned.people.size()+" 組人物";if(!sourceFilter.isEmpty())summary+=" (來源: "+sourceFilter+")";if(!full)summary+="\n目前只有部分媒體授權；結果不涵蓋全手機。";if(scanned.unreadable>0)summary+="\n讀取／解碼失敗 "+scanned.unreadable+" 次";if(scanned.faceFailures>0)summary+="\n人物分析失敗 "+scanned.faceFailures+" 個畫面";if(!scanned.faceError.isEmpty())summary+="\n"+scanned.faceError;status.setText(summary);render();});
   }catch(InterruptedException e){ui(()->{setBusy(false);status.setText("掃描已停止，已呈現部分結果。");render();});}catch(Exception|LinkageError e){ui(()->{setBusy(false);status.setText("掃描失敗："+e.getClass().getSimpleName()+" · "+e.getMessage());});}});
 }
 private void ui(Runnable r){if(!dead)runOnUiThread(()->{if(!dead)r.run();});}
 private void render(){
  list.removeAllViews();updateSelection();if(result==null||result.all.isEmpty()){list.addView(text("先掃描，再選擇要整理的群組。\n完全重複：檔案內容一致\n相似：畫面接近，仍須人工查看\n人物：抽樣人臉特徵接近，不保證同一人\n註：掃描過程中即可勾選刪除或搬移！",15));return;}
  List<Scanner.Group> groups=mode==0?result.exact:mode==1?result.similar:result.people;
  if(mode==3){groups=new ArrayList<>();for(Scanner.Item item:result.all)if(!item.removed){Scanner.Group g=new Scanner.Group(item.video?"影片":"照片");g.items.add(item);groups.add(g);}}
  int displayed=0;for(Scanner.Group group:groups){List<Scanner.Item> active=new ArrayList<>();for(Scanner.Item item:group.items)if(!item.removed)active.add(item);if(active.size()<(mode==3?1:2))continue;
   if(displayed++>=limit)break;LinearLayout card=new LinearLayout(this);card.setOrientation(1);card.setPadding(dp(10),dp(9),dp(10),dp(9));card.setBackgroundColor(CARD);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.setMargins(0,0,0,dp(10));list.addView(card,cp);
   TextView title=text(group.title+" · "+active.size()+" 個",14);title.setTypeface(null,1);card.addView(title);
   if(mode==2&&group.face!=null){ImageView face=new ImageView(this);face.setImageBitmap(BitmapFactory.decodeFile(group.face.getPath()));card.addView(face,new LinearLayout.LayoutParams(dp(72),dp(72)));card.addView(text("此分組只代表至少一張抽樣人臉接近；多人、側臉或模糊畫面可能誤判。",11));}
   LinearLayout tools=new LinearLayout(this);tools.addView(button("勾選整組",()->{selected.addAll(active);render();}),new LinearLayout.LayoutParams(0,dp(40),1));tools.addView(button("取消此組",()->{selected.removeAll(active);render();}),new LinearLayout.LayoutParams(0,dp(40),1));card.addView(tools);
   int count=0;for(Scanner.Item item:active){if(count++>=12){card.addView(text("大型群組：先顯示 12 個，可勾選整組。全部頁面可逐檔查看。",11));break;}LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);CheckBox box=new CheckBox(this);box.setChecked(selected.contains(item));box.setOnCheckedChangeListener((v,checked)->{if(checked)selected.add(item);else selected.remove(item);updateSelection();});row.addView(box,new LinearLayout.LayoutParams(dp(40),dp(60)));ImageView thumb=new ImageView(this);thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);if(item.thumb!=null)thumb.setImageBitmap(smallThumb(item.thumb));row.addView(thumb,new LinearLayout.LayoutParams(dp(64),dp(64)));thumb.setOnClickListener(v->preview(item));TextView label=text((item.video?"▶ ":"")+item.name+"\n"+format(item.size)+(item.video?" · "+item.duration/1000+" 秒":"")+"\n"+item.path,11);label.setOnClickListener(v->preview(item));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.setMargins(dp(8),0,0,0);row.addView(label,lp);card.addView(row);}
  }
  if(displayed>limit)list.addView(button("顯示更多群組",()->{limit+=20;render();}));if(displayed==0)list.addView(text(mode==2?"沒有跨檔案的人物群組。需有清楚正面人臉；人物分析選項須在掃描前啟用。":"此分類尚無符合項目。",15));
 }
 private Bitmap smallThumb(File file){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;return BitmapFactory.decodeFile(file.getPath(),options);}
 private String format(long bytes){return String.format(Locale.TAIWAN,"%.1f MB",bytes/1048576.0);}
 private void updateSelection(){long total=0;for(Scanner.Item i:selected)total+=i.size;selection.setText("已選 "+selected.size()+" 個 · "+format(total));if(move!=null)move.setEnabled(!selected.isEmpty()&&!fileBusy);if(deleteBtn!=null)deleteBtn.setEnabled(!selected.isEmpty()&&!fileBusy);}
 private void preview(Scanner.Item item){try{Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(item.uri,item.mime!=null?item.mime:item.video?"video/*":"image/*");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}catch(ActivityNotFoundException e){new AlertDialog.Builder(this).setMessage("手機沒有可開啟此媒體的應用程式。").setPositiveButton("確定",null).show();}}
 private String safeDestination(){String name=folder.getText().toString().trim();if(name.isEmpty()||name.equals(".")||name.equals("..")||name.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")||name.length()>80){status.setText("請填寫有效的單層資料夾名稱（80 字以內）。");return null;}getPreferences(0).edit().putString("folder",name).apply();return "DCIM/"+name+"/";}
 private void confirmMove(){if(selected.isEmpty()||fileBusy||busy)return;String target=safeDestination();if(target==null)return;ArrayList<Scanner.Item> snapshot=new ArrayList<>(selected);
  new AlertDialog.Builder(this).setTitle("確認移動 "+snapshot.size()+" 個檔案").setMessage("目的地："+target+"\n移動後會離開原資料夾；不刪除重複檔。相似與人物分組請先確認。\n同名檔會加上識別碼，不覆蓋既有檔案。\n這次成功的移動會取代上次復原紀錄。").setNegativeButton("取消",null).setPositiveButton("移動",(d,w)->{destination=target;pending=new ArrayList<>();for(Scanner.Item item:snapshot)if(!target.equals(item.path)){MoveRecord r=new MoveRecord();r.uri=item.uri;r.oldPath=item.path;r.oldName=item.name;r.newPath=target;r.newName=item.name;r.item=item;pending.add(r);}undoing=false;isDeleting=false;operationHasSuccess=false;operationMoved=operationFailed=0;fileBusy=true;updateSelection();requestWrite();}).show();
 }
 private void confirmDelete(){
  if(selected.isEmpty()||fileBusy||busy)return;
  ArrayList<Scanner.Item> snapshot=new ArrayList<>(selected);
  new AlertDialog.Builder(this).setTitle("確認刪除 "+snapshot.size()+" 個檔案").setMessage("警告：刪除後檔案將直接由系統移除，無法透過本程式復原！\n請確定要刪除這些勾選的媒體檔案。").setNegativeButton("取消",null).setPositiveButton("刪除",(d,w)->{
   pendingDeletes=snapshot;
   isDeleting=true;
   fileBusy=true;
   updateSelection();
   requestDelete();
  }).show();
 }
 private void requestDelete(){
  if(pendingDeletes==null||pendingDeletes.isEmpty()){
   fileBusy=false;updateSelection();status.setText("沒有需要刪除的項目。");return;
  }
  try{
   ArrayList<Uri> uris=new ArrayList<>();
   for(int i=0;i<Math.min(100,pendingDeletes.size());i++)uris.add(pendingDeletes.get(i).uri);
   startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(),uris).getIntentSender(),52,null,0,0,0);
  }catch(Exception e){status.setText("無法取得刪除授權："+e.getMessage());pendingDeletes=null;fileBusy=false;updateSelection();}
 }
 private void requestWrite(){if(pending==null||pending.isEmpty()){status.setText("檔案已在目的資料夾，沒有需要移動的項目。");pending=null;fileBusy=false;updateSelection();return;}
  try{ArrayList<Uri> uris=new ArrayList<>();for(int i=0;i<Math.min(100,pending.size());i++)uris.add(pending.get(i).uri);startIntentSenderForResult(MediaStore.createWriteRequest(getContentResolver(),uris).getIntentSender(),51,null,0,0,0);}catch(Exception e){status.setText("無法取得移動授權："+e.getMessage());pending=null;fileBusy=false;updateSelection();}}
 public void onActivityResult(int request,int response,Intent data){super.onActivityResult(request,response,data);
  if(request==51){if(response==RESULT_OK)applyBatch();else{pending=null;fileBusy=false;updateSelection();status.setText("此批移動未獲授權，未移動此批檔案。先前成功的批次可復原。");}}
  else if(request==52){if(response==RESULT_OK)applyDeleteBatch();else{pendingDeletes=null;fileBusy=false;updateSelection();status.setText("此批刪除未獲授權。");}}
 }
 private void applyDeleteBatch(){
  if(pendingDeletes==null)return;
  final List<Scanner.Item> batch=new ArrayList<>(pendingDeletes.subList(0,Math.min(100,pendingDeletes.size())));
  pendingDeletes.subList(0,batch.size()).clear();
  for(Scanner.Item item:batch){item.removed=true;selected.remove(item);}
  if(pendingDeletes!=null&&!pendingDeletes.isEmpty()){
   requestDelete();
  }else{
   pendingDeletes=null;
   fileBusy=false;
   status.setText("刪除完成；已移除所選檔案。");
   render();
  }
 }
 private boolean operationHasSuccess=false;private int operationMoved=0,operationFailed=0;
 private void applyBatch(){
  if(pending==null)return;final List<MoveRecord> batch=new ArrayList<>(pending.subList(0,Math.min(100,pending.size())));pending.subList(0,batch.size()).clear();
  fileWorker.execute(()->{int moved=0,failed=0;for(MoveRecord r:batch){if(dead)break;try{
    if(r.newPath==null||r.newPath.isEmpty()||(!undoing&&(r.oldPath==null||r.oldPath.isEmpty())))throw new IOException("原資料夾紀錄不完整");String finalName=collision(r.uri,r.newPath,r.newName)?uniqueName(r.newName):r.newName;
    ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.RELATIVE_PATH,r.newPath);values.put(MediaStore.MediaColumns.DISPLAY_NAME,finalName);
    int updated=getContentResolver().update(r.uri,values,null,null);if(updated!=1)throw new IOException("檔案更新失敗");moved++;
    if(!undoing){if(!operationHasSuccess){writeJournal(new JSONArray());operationHasSuccess=true;}appendJournal(r);}
    else{removeJournal(r.uri);}
    if(r.item!=null){r.item.removed=true;}
   }catch(Exception e){failed++;}}
   final int m=moved,f=failed;ui(()->{operationMoved+=m;operationFailed+=f;if(!undoing)for(MoveRecord r:batch)if(r.item!=null&&r.item.removed)selected.remove(r.item);
    if(pending!=null&&!pending.isEmpty()){status.setText("已移動 "+operationMoved+" 個，繼續請求下一批授權…");requestWrite();}
    else{pending=null;fileBusy=false;status.setText((undoing?"復原":"移動")+"完成 · 成功 "+operationMoved+" · 失敗 "+operationFailed+(undoing?"；請重新掃描更新分組。":"；可繼續勾選移到同一資料夾。"));operationHasSuccess=false;operationMoved=operationFailed=0;if(undoing){result=null;selected.clear();}render();}});
  });
 }
 private boolean collision(Uri self,String path,String name){try(android.database.Cursor c=getContentResolver().query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),new String[]{"_id"},"relative_path = ? AND _display_name = ?",new String[]{path,name},null)){if(c!=null)while(c.moveToNext())if(c.getLong(0)!=ContentUris.parseId(self))return true;}return false;}
 private String uniqueName(String name){int i=name.lastIndexOf('.');String id="_"+UUID.randomUUID().toString().substring(0,8);return i>0?name.substring(0,i)+id+name.substring(i):name+id;}
 private File journal(){return new File(getFilesDir(),"move-journal.json");}
 private synchronized JSONArray readJournal()throws Exception{if(!journal().exists())return new JSONArray();try(InputStream in=new FileInputStream(journal())){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);return new JSONArray(out.toString("UTF-8"));}}
 private synchronized void writeJournal(JSONArray a)throws Exception{File tmp=new File(getFilesDir(),"move-journal.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(a.toString().getBytes("UTF-8"));out.getFD().sync();}if(!tmp.renameTo(journal()))throw new IOException("復原紀錄儲存失敗");}
 private synchronized void appendJournal(MoveRecord r)throws Exception{JSONArray a=readJournal();JSONObject o=new JSONObject();o.put("uri",r.uri.toString());o.put("path",r.oldPath);o.put("name",r.oldName);a.put(o);writeJournal(a);}
 private synchronized void removeJournal(Uri uri)throws Exception{JSONArray a=readJournal(),next=new JSONArray();for(int i=0;i<a.length();i++)if(!a.getJSONObject(i).getString("uri").equals(uri.toString()))next.put(a.getJSONObject(i));writeJournal(next);}
 private void undo(){if(fileBusy||busy)return;try{JSONArray a=readJournal();if(a.length()==0){status.setText("沒有可復原的移動紀錄。");return;}new AlertDialog.Builder(this).setTitle("復原上次移動").setMessage("將 "+a.length()+" 個紀錄移回原資料夾。同名檔會加識別碼；已不存在的檔案會列為失敗。").setNegativeButton("取消",null).setPositiveButton("復原",(d,w)->{try{pending=new ArrayList<>();for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);MoveRecord r=new MoveRecord();r.uri=Uri.parse(o.getString("uri"));r.newPath=o.getString("path");r.newName=o.getString("name");pending.add(r);}undoing=true;operationHasSuccess=false;operationMoved=operationFailed=0;fileBusy=true;updateSelection();requestWrite();}catch(Exception e){status.setText("復原紀錄無法讀取。");}}).show();}catch(Exception e){status.setText("復原紀錄無法讀取。");}}
 private void help(){new AlertDialog.Builder(this).setTitle("使用說明與隱私").setMessage("1. 授權所有照片與影片後開始掃描。可篩選來源資料夾，或對全相簿掃描。\n2. 重複採 SHA-256；相似照片使用感知雜湊、色彩與比例，影片抽取 7 個畫面。\n3. 人物在手機內用 MobileFaceNet 比對抽樣人臉；分數不是準確率。正面清楚人臉效果較好。\n4. 掃描過程中即可即時勾選檔案進行『移到此資料夾』或『刪除』。\n5. 不自動刪除、不上傳、不在背景持續監控。\n6. 復原只保留上次移動批次。清快取只移除本程式縮圖，不動原照片／影片。").setNegativeButton("關閉",null).setPositiveButton("清除縮圖快取",(d,w)->{selected.clear();result=null;fileWorker.execute(()->{clearThumbs();ui(()->{status.setText("縮圖快取已清除，請重新掃描。");render();});});}).show();}
 private void clearThumbs(){File directory=new File(getCacheDir(),"thumbs");File[] files=directory.listFiles();if(files!=null)for(File f:files)f.delete();}
 protected void onDestroy(){dead=true;cancelled=true;scanWorker.shutdownNow();fileWorker.shutdownNow();super.onDestroy();}
}
