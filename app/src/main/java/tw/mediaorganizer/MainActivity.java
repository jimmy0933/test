package tw.mediaorganizer;
import android.app.*;
import android.app.Dialog;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;
import org.json.*;

import androidx.appcompat.app.AppCompatActivity;
public class MainActivity extends AppCompatActivity implements ScanService.ScanListener {
 private final int BG=0xff0F1720,CARD=0xff182330,TEXT=0xffF1F5F9,ACCENT=0xff53D6BD,BUTTON_BG=0xff333333,TEXT_SEC=0xffA8B6C7;
 private FrameLayout root;
 private LinearLayout mainContent;
 private androidx.recyclerview.widget.RecyclerView list;
 private ItemAdapter adapter;
 private final android.util.LruCache<String, android.graphics.Bitmap> thumbCache = new android.util.LruCache<String, android.graphics.Bitmap>((int)(Runtime.getRuntime().maxMemory()/1024)/8){protected int sizeOf(String k,android.graphics.Bitmap v){return v.getByteCount()/1024;}};private TextView status,selection,srcLabel;
 private TextView[] tabButtons;
 private View footerContainer;
 private android.widget.Switch people,bgScan;private Spinner strictness,mediaTypeSpinner;private Button scan,move,deleteBtn,cancel;
 private final ExecutorService fileWorker=Executors.newSingleThreadExecutor();
 private volatile boolean dead,busy,fileBusy;private Scanner.Result result;
 private final LinkedHashSet<Scanner.Item> selected=new LinkedHashSet<>();private int mode=0,limit=20;
 private List<MoveRecord> pending;private boolean undoing;private String destination;private long lastProgress;
 private boolean isDeleting;private List<Scanner.Item> pendingDeletes;
 private final Set<String> sourceFilters=new TreeSet<>();
 private long resultGeneration;

 private static final class MoveRecord {Uri uri;String oldPath,oldName,newPath,newName;Scanner.Item item;}
 public void onCreate(Bundle saved){
  super.onCreate(saved);
  getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);
  build();
  ScanService.setListener(this);
  if(ScanService.isScanning()){
   if(ScanService.getCurrentScanGeneration()>=resultGeneration){
    resultGeneration = ScanService.getCurrentScanGeneration();
   }
   setBusy(true);
   status.setText("背景掃描進行中…");
  }
 }

 private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
 private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextColor(TEXT);t.setTextSize(size);t.setPadding(0,dp(5),0,dp(5));return t;}
 private Button button(String s,Runnable r){
  Button b=new Button(this);
  b.setText(s);
  b.setTextColor(TEXT);
  b.setAllCaps(false);

  android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
  gd.setColor(BUTTON_BG);
  gd.setCornerRadius(dp(8));
  b.setBackground(gd);

  b.setOnClickListener(v->{
   v.setAlpha(0.7f);
   v.postDelayed(() -> v.setAlpha(1.0f), 100);
   r.run();
  });
  return b;
 }
 private void build(){
  root = new FrameLayout(this);
  root.setBackgroundColor(BG);

  LinearLayout mainContent = new LinearLayout(this);
  mainContent.setOrientation(1);
  mainContent.setPadding(dp(20),dp(16),dp(20),dp(12));

  root.setOnApplyWindowInsetsListener((v,insets)->{
      android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
      mainContent.setPadding(dp(16),dp(8)+i.top,dp(16),dp(8)+i.bottom);
      if (settingsScreen != null) {
          settingsScreen.setPadding(dp(16),dp(8)+i.top,dp(16),dp(8)+i.bottom);
      }
      return insets;
  });
  setContentView(root);

  this.mainContent = mainContent;
  root.addView(mainContent, new FrameLayout.LayoutParams(-1, -1));

  LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(0, 0, 0, dp(16));
  LinearLayout headerTitles=new LinearLayout(this);headerTitles.setOrientation(1);
  String verName="1.4";
  try{verName=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){}
  TextView title=text("相簿整理員",24);title.setTypeface(null,1);headerTitles.addView(title);
  header.addView(headerTitles,new LinearLayout.LayoutParams(0,-2,1));
  ImageView settingsBtn = new ImageView(this);
  settingsBtn.setImageResource(R.drawable.ic_settings);
  settingsBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
  settingsBtn.setBackgroundResource(android.R.attr.selectableItemBackgroundBorderless);
  settingsBtn.setOnClickListener(v -> showSettingsDialog());
  header.addView(settingsBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
  mainContent.addView(header);

  people=new android.widget.Switch(this);people.setChecked(true);
  bgScan=new android.widget.Switch(this);bgScan.setChecked(getPreferences(0).getBoolean("bg_scan",false));
  bgScan.setOnCheckedChangeListener((v,checked)->getPreferences(0).edit().putBoolean("bg_scan",checked).apply());

  mediaTypeSpinner=new Spinner(this);
  String[] mediaTypes={"全部","僅照片","僅影片"};
  ArrayAdapter<String> mediaAdapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,mediaTypes){public View getView(int p,View v,android.view.ViewGroup parent){TextView t=(TextView)super.getView(p,v,parent);t.setTextColor(TEXT);return t;}};
  mediaAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  mediaTypeSpinner.setAdapter(mediaAdapter);

  strictness=new Spinner(this);
  String[] labels={"嚴格（建議）","一般","寬鬆（較多誤判）"};
  ArrayAdapter<String> mediaAdapter2=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,labels){public View getView(int p,View v,android.view.ViewGroup parent){TextView t=(TextView)super.getView(p,v,parent);t.setTextColor(TEXT);return t;}};
  mediaAdapter2.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
  strictness.setAdapter(mediaAdapter2);

  srcLabel=text(" 來源：全部媒體",12);

  LinearLayout actions=new LinearLayout(this);
  scan=button("開始掃描",this::requestScan);
  android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
  android.graphics.drawable.GradientDrawable gdDisabled = new android.graphics.drawable.GradientDrawable();
  gdDisabled.setColor(0xff555555);
  gdDisabled.setCornerRadius(dp(8));
  android.graphics.drawable.GradientDrawable gdEnabled = new android.graphics.drawable.GradientDrawable();
  gdEnabled.setColor(ACCENT);
  gdEnabled.setCornerRadius(dp(8));
  sld.addState(new int[]{-android.R.attr.state_enabled}, gdDisabled);
  sld.addState(new int[]{}, gdEnabled);
  scan.setBackground(sld);

  android.content.res.ColorStateList textColorStateList = new android.content.res.ColorStateList(
        new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
        new int[]{0xffaaaaaa, BG}
  );
  scan.setTextColor(textColorStateList);

  cancel=button("停止",()->{
   ScanService.cancelScan();
   status.setText("正在停止，已完成的掃描結果已保留…");
  });
  cancel.setVisibility(View.GONE);
  LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(0, dp(48), 1);
  scanParams.setMargins(0, 0, dp(12), 0);
  actions.addView(scan, scanParams);
  actions.addView(cancel, new LinearLayout.LayoutParams(dp(80), dp(48)));
  mainContent.addView(actions);

  status=text("授權後可掃描手機相簿。僅能讀取 Android 允許的檔案。",12);mainContent.addView(status);
  LinearLayout tabs=new LinearLayout(this);
  tabs.setWeightSum(4);
  android.graphics.drawable.GradientDrawable tabBg = new android.graphics.drawable.GradientDrawable();
  tabBg.setColor(CARD);
  tabBg.setCornerRadius(dp(8));
  tabs.setBackground(tabBg);
  tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
  String[] names={"重複","相似","人物","全部"};
  tabButtons = new TextView[4];
  for(int i=0;i<4;i++){
    final int idx=i;
    TextView tab=new TextView(this);
    tab.setText(names[i]);
    tab.setGravity(Gravity.CENTER);
    tab.setTextSize(14);
    tab.setTextColor(i==0?BG:TEXT_SEC);

    android.graphics.drawable.GradientDrawable activeBg = new android.graphics.drawable.GradientDrawable();
    activeBg.setColor(ACCENT);
    activeBg.setCornerRadius(dp(6));

    if (i==0) tab.setBackground(activeBg);
    else tab.setBackgroundColor(Color.TRANSPARENT);

    tab.setOnClickListener(v->{
      mode=idx;limit=20;
      for(int j=0;j<4;j++) {
        tabButtons[j].setTextColor(j==idx?BG:TEXT_SEC);
        if(j==idx) tabButtons[j].setBackground(activeBg);
        else tabButtons[j].setBackgroundColor(Color.TRANSPARENT);
      }
      render();
    });
    LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(0, dp(36), 1);
    tabParams.setMargins(dp(2), 0, dp(2), 0);
    tab.setLayoutParams(tabParams);
    tabButtons[i] = tab;
    tabs.addView(tab);
  }
  LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(-1, -2);
  tabsLp.setMargins(0, dp(16), 0, dp(16));
  mainContent.addView(tabs, tabsLp);
  list = new RecyclerView(this); list.setLayoutManager(new LinearLayoutManager(this)); adapter = new ItemAdapter(); list.setAdapter(adapter); mainContent.addView(list, new LinearLayout.LayoutParams(-1,0,1));
  selection=text("已選 0 個",14);

  LinearLayout footerContainer = new LinearLayout(this);
  footerContainer.setOrientation(1);
  footerContainer.setBackgroundColor(CARD);
  footerContainer.setPadding(dp(16), dp(12), dp(16), dp(12));

  if (selection.getParent() != null) ((ViewGroup)selection.getParent()).removeView(selection);
  footerContainer.addView(selection, new LinearLayout.LayoutParams(-1, -2));
  selection.setPadding(0, 0, 0, dp(8));

  LinearLayout footer=new LinearLayout(this);
  move=button("移到此資料夾",this::confirmMove);
  deleteBtn=button("刪除所選檔",this::confirmDelete);
  Button clear=button("取消勾選",()->{selected.clear();render();});

  LinearLayout.LayoutParams moveParams = new LinearLayout.LayoutParams(0, dp(46), 1);
  moveParams.setMargins(0, 0, dp(8), 0);
  footer.addView(move, moveParams);

  LinearLayout.LayoutParams delParams = new LinearLayout.LayoutParams(0, dp(46), 1);
  delParams.setMargins(0, 0, dp(8), 0);
  footer.addView(deleteBtn, delParams);

  footer.addView(clear,new LinearLayout.LayoutParams(dp(90),dp(46)));

  footerContainer.addView(footer);
  footerContainer.setVisibility(View.GONE);
  this.footerContainer = footerContainer;
  mainContent.addView(footerContainer, new LinearLayout.LayoutParams(-1, -2));

  resultGeneration=ScanService.getGeneration();
  Scanner.Result cached=loadScanCache();
  if(cached!=null&&!cached.all.isEmpty()){
   result=cached;
   status.setText("已載入上次記錄\n" + cached.all.size() + " 個檔案 · " + cached.exact.size() + " 組重複 · " + cached.similar.size() + " 組相似");
  }
  render();

  loadOpState();
  if (fileBusy) {
      if (isDeleting && pendingDeletes != null && !pendingDeletes.isEmpty()) {
          requestDeleteBatch();
      } else if (pending != null && !pending.isEmpty()) {
          requestWrite();
      } else {
          fileBusy = false;
      }
  } else {
      fileBusy = false;
  }

  fileWorker.execute(() -> {
      try { promotePendingJournal(); } catch (Exception e) {}
  });
 }

 private void selectSourceFolder(){
  List<String> folders=getMediaSubfolders();
  if(folders.isEmpty()){
   Toast.makeText(this,"尚無媒體資料夾可選擇",Toast.LENGTH_SHORT).show();
   return;
  }
  String[] items=folders.toArray(new String[0]);
  boolean[] checked=new boolean[items.length];
  Set<String> tempFilters=new TreeSet<>(sourceFilters);
  for(int i=0;i<items.length;i++)checked[i]=tempFilters.contains(items[i]+"/");
  new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("選擇來源資料夾（可多選）").setMultiChoiceItems(items,checked,(dialog,which,isChecked)->{
   String path=items[which]+"/";
   if(isChecked)tempFilters.add(path);
   else tempFilters.remove(path);
  }).setPositiveButton("確定",(d,w)->{sourceFilters.clear();sourceFilters.addAll(tempFilters);updateSrcLabel();}).setNeutralButton("全選/全部媒體",(d,w)->{sourceFilters.clear();updateSrcLabel();}).setNegativeButton("取消",null).show();
 }

 private void updateSrcLabel(){
  if(sourceFilters.isEmpty())srcLabel.setText(" 來源：全部媒體");
  else if(sourceFilters.size()==1)srcLabel.setText(" 來源："+sourceFilters.iterator().next());
  else srcLabel.setText(" 來源：已選 "+sourceFilters.size()+" 個資料夾");
 }

 private List<String> getMediaSubfolders(){
  Set<String> subfolders=new TreeSet<>();
  try(android.database.Cursor c=getContentResolver().query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),new String[]{"relative_path"},"_size > 0 AND relative_path IS NOT NULL",null,null)){
   if(c!=null)while(c.moveToNext()){
    String p=c.getString(0);
    if(p!=null&&!p.isEmpty()){
     if(p.endsWith("/"))p=p.substring(0,p.length()-1);
     if(!p.isEmpty())subfolders.add(p);
    }
   }
  }catch(Exception e){}
  return new ArrayList<>(subfolders);
 }

 private boolean permission(String p){return checkSelfPermission(p)==PackageManager.PERMISSION_GRANTED;}
 private boolean hasMediaPermission(){
  int mediaType=mediaTypeSpinner.getSelectedItemPosition();
  if(Build.VERSION.SDK_INT>=33){
   if(mediaType==1)return permission("android.permission.READ_MEDIA_IMAGES")||permission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
   if(mediaType==2)return permission("android.permission.READ_MEDIA_VIDEO")||permission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
   return (permission("android.permission.READ_MEDIA_IMAGES")||permission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")) &&
          (permission("android.permission.READ_MEDIA_VIDEO")||permission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"));
  }
  return permission("android.permission.READ_EXTERNAL_STORAGE");
 }

 private void requestScan(){
  if(busy||fileBusy)return;int mediaType=mediaTypeSpinner.getSelectedItemPosition();
  ArrayList<String> perms=new ArrayList<>();
  if(Build.VERSION.SDK_INT>=33){
   if(mediaType!=2&&!permission("android.permission.READ_MEDIA_IMAGES"))perms.add("android.permission.READ_MEDIA_IMAGES");
   if(mediaType!=1&&!permission("android.permission.READ_MEDIA_VIDEO"))perms.add("android.permission.READ_MEDIA_VIDEO");
   if(Build.VERSION.SDK_INT>=34&&!perms.isEmpty())perms.add("android.permission.READ_MEDIA_VISUAL_USER_SELECTED");
  }else if(!permission("android.permission.READ_EXTERNAL_STORAGE")){
   perms.add("android.permission.READ_EXTERNAL_STORAGE");
  }
  if(bgScan.isChecked()&&Build.VERSION.SDK_INT>=33&&!permission("android.permission.POST_NOTIFICATIONS")){
   perms.add("android.permission.POST_NOTIFICATIONS");
  }
  if(!perms.isEmpty()){
   requestPermissions(perms.toArray(new String[0]),41);return;
  }
  startScan();
 }

 private void startScan(){
  selected.clear();result=new Scanner.Result();render();setBusy(true);
  status.setText("掃描中，可即時查看結果與進行處理…");
  final boolean faces=people.isChecked();
  final boolean isBgScan=bgScan.isChecked();
  final int setting=strictness.getSelectedItemPosition();
  final int mediaType=mediaTypeSpinner.getSelectedItemPosition();
  final Set<String> snapshotFilters=new HashSet<>(sourceFilters);
  lastProgress=0;

  resultGeneration = ScanService.startScanJob(this, isBgScan, faces, setting, mediaType, snapshotFilters);
 }

 public void onRequestPermissionsResult(int code,String[] perms,int[] grants){
  super.onRequestPermissionsResult(code,perms,grants);
  if(code==41){
   if(hasMediaPermission()){
    startScan();
   }else{
    status.setText("尚未取得媒體權限；請重新掃描並授權。");
   }
  }
 }

 private void setBusy(boolean b){
  busy=b;
  scan.setEnabled(!b&&!fileBusy);
  cancel.setEnabled(b);
  people.setEnabled(!b);
  bgScan.setEnabled(!b);
  strictness.setEnabled(!b);
  mediaTypeSpinner.setEnabled(!b);
  move.setEnabled(!selected.isEmpty()&&!fileBusy);
  deleteBtn.setEnabled(!selected.isEmpty()&&!fileBusy);
  if(b)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
  else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
 }

 public void onProgress(String s){
  ui(()->{
   long now=System.currentTimeMillis();
   if(now-lastProgress<250)return;
   lastProgress=now;
   status.setText(s);
  });
 }

 public void onPartialResult(Scanner.Result partial, long generation){
  ui(()->{
   if(generation<resultGeneration)return;
   resultGeneration=generation;
   result=partial;
   render();
  });
 }

 public void onComplete(Scanner.Result scanned, long generation){
  ui(()->{
   setBusy(false); if (cancel != null) cancel.setVisibility(View.GONE);
   if(generation<resultGeneration)return;
   resultGeneration=generation;
   result=scanned;
   saveScanCache(scanned);
   int mediaType=mediaTypeSpinner.getSelectedItemPosition();
   boolean full=Build.VERSION.SDK_INT<33||((mediaType!=2?permission("android.permission.READ_MEDIA_IMAGES"):true)&&(mediaType!=1?permission("android.permission.READ_MEDIA_VIDEO"):true));
   String summary="完成 · "+scanned.all.size()+" 個檔案 · "+scanned.exact.size()+" 組重複 · "+scanned.similar.size()+" 組相似 · "+scanned.people.size()+" 組人物";
   if(!sourceFilters.isEmpty())summary+=" (來源: 已選 "+sourceFilters.size()+" 個資料夾)";
   if(!full)summary+="\n目前只有部分媒體授權；結果不涵蓋全手機。";
   if(scanned.unreadable>0)summary+="\n讀取／解碼失敗 "+scanned.unreadable+" 次";
   if(scanned.faceFailures>0)summary+="\n人物分析失敗 "+scanned.faceFailures+" 個畫面";
   if(!scanned.faceError.isEmpty())summary+="\n"+scanned.faceError;
   status.setText(summary);
   render();
  });
 }

 public void onError(String message, long generation){
  ui(()->{
   setBusy(false); if (cancel != null) cancel.setVisibility(View.GONE);
   if(generation<resultGeneration)return;
   resultGeneration=generation;
   if(result!=null)saveScanCache(result);
   status.setText(message);
   render();
  });
 }

 private void ui(Runnable r){if(!dead)runOnUiThread(()->{if(!dead)r.run();});}

 private class ListItem {
     int type; // 0 = title, 1 = group header, 2 = item
     Scanner.Group group; Scanner.Item item;
     String text; boolean checked;
     ListItem(int t, Scanner.Group g, Scanner.Item i, String s, boolean c) { type = t; group = g; item = i; text = s; checked = c; }
 }
 private class ItemAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
     List<ListItem> data = new ArrayList<>();
     void update(List<ListItem> newData) {
         DiffUtil.calculateDiff(new DiffUtil.Callback() {
             public int getOldListSize() { return data.size(); }
             public int getNewListSize() { return newData.size(); }
             public boolean areItemsTheSame(int o, int n) {
                 ListItem oldItem = data.get(o), newItem = newData.get(n);
                 if (oldItem.type != newItem.type) return false;
                 if (oldItem.type == 2) return oldItem.item.uri.equals(newItem.item.uri);
                 if (oldItem.type == 1) return oldItem.group == newItem.group;
                 return oldItem.text.equals(newItem.text);
             }
             public boolean areContentsTheSame(int o, int n) {
                 ListItem oldItem = data.get(o), newItem = newData.get(n);
                 if (oldItem.type == 2) return oldItem.checked == newItem.checked;
                 return true;
             }
         }).dispatchUpdatesTo(this);
         data = newData;
     }
     public int getItemViewType(int pos) { return data.get(pos).type; }
     public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
         if (viewType == 3) {
             LinearLayout empty = new LinearLayout(MainActivity.this); empty.setOrientation(1); empty.setGravity(Gravity.CENTER);
             empty.setPadding(0, dp(40), 0, dp(40));
             ImageView icon = new ImageView(MainActivity.this); icon.setImageResource(R.drawable.ic_empty);
             empty.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
             TextView desc = text("", 14); desc.setGravity(Gravity.CENTER); desc.setTextColor(TEXT_SEC);
             LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.setMargins(0, dp(16), 0, 0);
             empty.addView(desc, lp);
             return new RecyclerView.ViewHolder(empty) {};
         } else if (viewType == 0) {
             TextView t = text("", 15); t.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
             return new RecyclerView.ViewHolder(t) {};
         } else if (viewType == 1) {
             LinearLayout card = new LinearLayout(MainActivity.this); card.setOrientation(1); card.setPadding(dp(10),dp(9),dp(10),dp(9)); card.setBackgroundColor(CARD);
             RecyclerView.LayoutParams cp = new RecyclerView.LayoutParams(-1,-2); cp.setMargins(0,0,0,dp(10)); card.setLayoutParams(cp);
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setColor(CARD);
                gd.setCornerRadius(dp(12));
                card.setBackground(gd);
                card.setPadding(dp(12), dp(12), dp(12), dp(12));
             TextView title = text("", 14); title.setTypeface(null,1); title.setId(101); card.addView(title);
             ImageView face = new ImageView(MainActivity.this); face.setId(102); face.setVisibility(View.GONE); card.addView(face, new LinearLayout.LayoutParams(dp(72),dp(72)));
             TextView faceLabel = text("此分組只代表至少一張抽樣人臉接近；多人、側臉或模糊畫面可能誤判。", 11); faceLabel.setId(103); faceLabel.setVisibility(View.GONE); card.addView(faceLabel);
             LinearLayout tools = new LinearLayout(MainActivity.this);
             Button checkAll = button("選取其餘項目", null); checkAll.setId(104); tools.addView(checkAll, new LinearLayout.LayoutParams(0, dp(40), 1));
             Button uncheckAll = button("取消此組", null); uncheckAll.setId(105); tools.addView(uncheckAll, new LinearLayout.LayoutParams(0, dp(40), 1));
             card.addView(tools);
             return new RecyclerView.ViewHolder(card) {};
         } else {
             LinearLayout row = new LinearLayout(MainActivity.this); row.setGravity(Gravity.CENTER_VERTICAL); row.setLayoutParams(new RecyclerView.LayoutParams(-1, -2));
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setColor(0xff333333);
                gd.setCornerRadius(dp(8));
                row.setBackground(gd);
             CheckBox box = new CheckBox(MainActivity.this); box.setId(201); row.addView(box, new LinearLayout.LayoutParams(dp(40),dp(96)));
             FrameLayout thumbContainer = new FrameLayout(MainActivity.this);
             ImageView thumb = new ImageView(MainActivity.this); thumb.setId(202); thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
             thumbContainer.addView(thumb, new FrameLayout.LayoutParams(dp(96),dp(96)));
             ImageView videoIcon = new ImageView(MainActivity.this); videoIcon.setId(205); videoIcon.setImageResource(R.drawable.ic_video); videoIcon.setVisibility(View.GONE);
             FrameLayout.LayoutParams vidLp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM | Gravity.START);
             vidLp.setMargins(dp(4), 0, 0, dp(4));
             thumbContainer.addView(videoIcon, vidLp);
             row.addView(thumbContainer, new LinearLayout.LayoutParams(dp(96),dp(96)));
             TextView label = text("", 11); label.setId(203); label.setMaxLines(2); label.setEllipsize(android.text.TextUtils.TruncateAt.END);
             LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,-2,1); lp.setMargins(dp(8),0,0,0); row.addView(label, lp);
             FrameLayout mask = new FrameLayout(MainActivity.this); mask.setId(204); mask.setBackgroundColor(0x3372d9c3); mask.setVisibility(View.GONE); row.addView(mask, new LinearLayout.LayoutParams(-1, -1));
             return new RecyclerView.ViewHolder(row) {};
         }
     }
     public void onBindViewHolder(RecyclerView.ViewHolder holder, int pos) {
         ListItem item = data.get(pos);
         if (item.type == 3) {
             LinearLayout v = (LinearLayout)holder.itemView;
             ((TextView)v.getChildAt(1)).setText(item.text);
         } else if (item.type == 0) {
             ((TextView)holder.itemView).setText(item.text);
         } else if (item.type == 1) {
             View v = holder.itemView;
             ((TextView)v.findViewById(101)).setText(item.group.title);
             ImageView face = v.findViewById(102); TextView faceLabel = v.findViewById(103);
             if (mode == 2 && item.group.face != null) {
                 face.setVisibility(View.VISIBLE); faceLabel.setVisibility(View.VISIBLE);
                 face.setImageBitmap(BitmapFactory.decodeFile(item.group.face.getPath()));
             } else { face.setVisibility(View.GONE); faceLabel.setVisibility(View.GONE); }
             v.findViewById(104).setOnClickListener(btn -> {
                 if(mode==3){selected.addAll(item.group.items);}
                 else if(!item.group.items.isEmpty()){
                     List<Scanner.Item> active = new ArrayList<>();
                     for(Scanner.Item i : item.group.items) if(!i.removed) active.add(i);
                     if(active.size()>1)selected.addAll(active.subList(1,active.size()));
                 }
                 render();
             });
             v.findViewById(105).setOnClickListener(btn -> { selected.removeAll(item.group.items); render(); });
         } else {
             View v = holder.itemView;
             CheckBox box = v.findViewById(201); box.setOnCheckedChangeListener(null); box.setChecked(item.checked);
             box.setOnCheckedChangeListener((cb, checked) -> { if(checked) selected.add(item.item); else selected.remove(item.item); updateSelection(); item.checked = checked; v.findViewById(204).setVisibility(checked?View.VISIBLE:View.GONE); });
             ImageView thumb = v.findViewById(202); thumb.setImageBitmap(null);
             ImageView videoIcon = v.findViewById(205);
             if (videoIcon != null) videoIcon.setVisibility(item.item.video ? View.VISIBLE : View.GONE);
             if (item.item.thumb != null) {
                 android.graphics.Bitmap c = thumbCache.get(item.item.thumb.getPath());
                 if (c != null) thumb.setImageBitmap(c);
                 else {
                     fileWorker.execute(() -> {
                         android.graphics.Bitmap b = smallThumb(item.item.thumb);
                         if(b!=null) { thumbCache.put(item.item.thumb.getPath(), b); ui(() -> {if(data.get(holder.getAdapterPosition()).item == item.item) thumb.setImageBitmap(b);}); }
                     });
                 }
             }
             thumb.setOnClickListener(img -> preview(item.item));
             TextView label = v.findViewById(203);
             String textLabel = item.item.name + "\n" + format(item.item.size) + (item.item.video ? " · " + item.item.duration / 1000 + " 秒" : "") + "\n" + item.item.path;


             label.setText(textLabel);
             label.setOnClickListener(lbl -> preview(item.item));
             v.findViewById(204).setVisibility(item.checked?View.VISIBLE:View.GONE);
         }
     }
     public int getItemCount() { return data.size(); }
 }

 private void render() {
  updateSelection();
  List<ListItem> newData = new ArrayList<>();
  if(result==null||result.all.isEmpty()){
      newData.add(new ListItem(0, null, null, "先掃描，再選擇要整理的群組。\n" +
"完全重複：檔案內容一致\n" +
"相似：畫面接近，仍須人工查看\n" +
"人物：抽樣人臉特徵接近，不保證同一人\n" +
"註：掃描過程中即可勾選刪除或搬移！", false));
      if (adapter != null) adapter.update(newData); return;
  }
  List<Scanner.Group> groups=mode==0?result.exact:mode==1?result.similar:result.people;
  if(mode==3){groups=new ArrayList<>();for(Scanner.Item item:result.all)if(!item.removed){Scanner.Group g=new Scanner.Group(item.video?"影片":"照片");g.items.add(item);groups.add(g);}}
  int displayed=0;
  for(Scanner.Group group:groups){
      List<Scanner.Item> active=new ArrayList<>();for(Scanner.Item item:group.items)if(!item.removed)active.add(item);
      if(active.size()<(mode==3?1:2))continue;
      if(displayed++>=limit)break;
      newData.add(new ListItem(1, group, null, null, false));
      int count=0;
      for(Scanner.Item item:active){
          if(count++>=12){newData.add(new ListItem(0, null, null, "大型群組：先顯示 12 個，可勾選整組。全部頁面可逐檔查看。", false));break;}
          newData.add(new ListItem(2, group, item, null, selected.contains(item)));
      }
  }
  if(displayed>limit) newData.add(new ListItem(0, null, null, "顯示更多群組...", false));
  if(displayed==0) {
      newData.add(new ListItem(3, null, null, mode==2?"沒有跨檔案的人物群組。需有清楚正面人臉；人物分析選項須在掃描前啟用。":"此分類尚無符合項目，請嘗試查看其他分類。", false));
  }
  if (adapter != null) adapter.update(newData);
 }
 private Bitmap smallThumb(File file){BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;return BitmapFactory.decodeFile(file.getPath(),options);}
 private View settingsScreen;
 private void showSettingsDialog(){
  if (settingsScreen != null) {
      settingsScreen.setVisibility(View.VISIBLE);
      mainContent.setVisibility(View.GONE);
      return;
  }

  ScrollView scroll = new ScrollView(this);
  scroll.setBackgroundColor(BG);
  settingsScreen = scroll;

  LinearLayout rootLayout = new LinearLayout(this);
  rootLayout.setOrientation(LinearLayout.VERTICAL);
  rootLayout.setPadding(dp(16), dp(16), dp(16), dp(32));
  scroll.addView(rootLayout);

  // Header
  LinearLayout header = new LinearLayout(this);
  header.setGravity(Gravity.CENTER_VERTICAL);
  header.setPadding(0, 0, 0, dp(24));
  ImageView backBtn = new ImageView(this);
  backBtn.setImageResource(R.drawable.ic_back);
  backBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
  backBtn.setBackgroundResource(android.R.attr.selectableItemBackgroundBorderless);
  backBtn.setOnClickListener(v -> {
      settingsScreen.setVisibility(View.GONE);
      mainContent.setVisibility(View.VISIBLE);
  });
  header.addView(backBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));

  TextView title = text("設定", 20);
  title.setTypeface(null, 1);
  LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1);
  titleLp.setMargins(dp(16), 0, 0, 0);
  header.addView(title, titleLp);

  rootLayout.addView(header);

  // Group: 掃描範圍
  rootLayout.addView(settingsTitle("掃描範圍"));
  LinearLayout scanScopeCard = createCard();
  if(srcLabel.getParent()!=null)((ViewGroup)srcLabel.getParent()).removeView(srcLabel);
  srcLabel.setTextSize(14);
  scanScopeCard.addView(srcLabel, new LinearLayout.LayoutParams(-1, -2));

  Button srcBtn = button("選擇來源資料夾", this::selectSourceFolder);
  LinearLayout.LayoutParams srcBtnLp = new LinearLayout.LayoutParams(-1, dp(42));
  srcBtnLp.setMargins(0, dp(12), 0, 0);
  scanScopeCard.addView(srcBtn, srcBtnLp);
  rootLayout.addView(scanScopeCard);

  // Group: 分析選項
  rootLayout.addView(settingsTitle("分析選項"));
  LinearLayout analysisCard = createCard();

  if(people.getParent()!=null)((ViewGroup)people.getParent()).removeView(people);
  people.setText("同時分析人物 (較耗時)");
  people.setTextColor(TEXT);
  analysisCard.addView(people, new LinearLayout.LayoutParams(-1, -2));

  if(bgScan.getParent()!=null)((ViewGroup)bgScan.getParent()).removeView(bgScan);
  bgScan.setText("鎖屏後繼續掃描");
  bgScan.setTextColor(TEXT);
  LinearLayout.LayoutParams bgScanLp = new LinearLayout.LayoutParams(-1, -2);
  bgScanLp.setMargins(0, dp(8), 0, 0);
  analysisCard.addView(bgScan, bgScanLp);

  TextView bgHint = text("開啟以在背景持續掃描，不因螢幕關閉中斷", 12);
  bgHint.setTextColor(TEXT_SEC);
  bgHint.setPadding(dp(32), 0, 0, 0);
  analysisCard.addView(bgHint);

  LinearLayout mBox = new LinearLayout(this); mBox.setGravity(Gravity.CENTER_VERTICAL);
  mBox.setPadding(dp(8), dp(16), 0, 0);
  mBox.addView(text("媒體種類：", 14));
  if(mediaTypeSpinner.getParent()!=null)((ViewGroup)mediaTypeSpinner.getParent()).removeView(mediaTypeSpinner);
  mBox.addView(mediaTypeSpinner, new LinearLayout.LayoutParams(-1, -2));
  analysisCard.addView(mBox);

  LinearLayout sBox = new LinearLayout(this); sBox.setGravity(Gravity.CENTER_VERTICAL);
  sBox.setPadding(dp(8), dp(16), 0, dp(8));
  sBox.addView(text("相似程度：", 14));
  if(strictness.getParent()!=null)((ViewGroup)strictness.getParent()).removeView(strictness);
  sBox.addView(strictness, new LinearLayout.LayoutParams(-1, -2));
  analysisCard.addView(sBox);

  rootLayout.addView(analysisCard);

  // Group: 工具
  rootLayout.addView(settingsTitle("工具"));
  LinearLayout toolsCard = createCard();

  Button undoBtn = button("復原上次移動", this::undo);
  toolsCard.addView(undoBtn, new LinearLayout.LayoutParams(-1, dp(42)));

  Button cacheBtn = button("清除縮圖快取", ()->{
    selected.clear();result=null;clearThumbs();status.setText("縮圖快取已清除，請重新掃描。");render();
  });
  LinearLayout.LayoutParams cacheBtnLp = new LinearLayout.LayoutParams(-1, dp(42));
  cacheBtnLp.setMargins(0, dp(12), 0, 0);
  toolsCard.addView(cacheBtn, cacheBtnLp);

  Button helpBtn = button("使用說明與隱私", this::help);
  LinearLayout.LayoutParams helpBtnLp = new LinearLayout.LayoutParams(-1, dp(42));
  helpBtnLp.setMargins(0, dp(12), 0, 0);
  toolsCard.addView(helpBtn, helpBtnLp);

  rootLayout.addView(toolsCard);

  // App Version
  String verName="1.4";
  try{verName=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){}
  TextView ver = text("相簿整理員 v" + verName, 12);
  ver.setTextColor(TEXT_SEC);
  ver.setGravity(Gravity.CENTER);
  LinearLayout.LayoutParams verLp = new LinearLayout.LayoutParams(-1, -2);
  verLp.setMargins(0, dp(24), 0, dp(16));
  rootLayout.addView(ver, verLp);

  root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
  mainContent.setVisibility(View.GONE);
 }

 private TextView settingsTitle(String titleText) {
     TextView t = text(titleText, 14);
     t.setTextColor(TEXT_SEC);
     t.setPadding(dp(8), dp(16), 0, dp(8));
     return t;
 }

 private LinearLayout createCard() {
     LinearLayout card = new LinearLayout(this);
     card.setOrientation(LinearLayout.VERTICAL);
     android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
     gd.setColor(CARD);
     gd.setCornerRadius(dp(16));
     card.setBackground(gd);
     card.setPadding(dp(16), dp(16), dp(16), dp(16));
     return card;
 }
 private String format(long bytes){return String.format(Locale.TAIWAN,"%.1f MB",bytes/1048576.0);}
 private void updateSelection(){
  long total=0;for(Scanner.Item i:selected)total+=i.size;
  selection.setText("已選 "+selected.size()+" 個 · "+format(total));
  if(scan!=null)scan.setEnabled(!busy&&!fileBusy);
  if(move!=null)move.setEnabled(!selected.isEmpty()&&!fileBusy);
  if(deleteBtn!=null)deleteBtn.setEnabled(!selected.isEmpty()&&!fileBusy);
  if(footerContainer!=null) footerContainer.setVisibility(selected.isEmpty() ? View.GONE : View.VISIBLE);
 }
 private void preview(Scanner.Item item){try{Intent intent=new Intent(Intent.ACTION_VIEW);intent.setDataAndType(item.uri,item.mime!=null?item.mime:item.video?"video/*":"image/*");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(intent);}catch(ActivityNotFoundException e){new androidx.appcompat.app.AlertDialog.Builder(this).setMessage("手機沒有可開啟此媒體的應用程式。").setPositiveButton("確定",null).show();}}
 private String validateDestination(String input){
  if(input==null)input="";input=input.trim();
  if(input.endsWith("/"))input=input.substring(0,input.length()-1);
  if(input.isEmpty()||input.equals(".")||input.equals("..")||input.matches(".*[*?\"<>|\\p{Cntrl}].*")||input.length()>120){status.setText("請填寫有效的資料夾名稱或路徑。");return null;}
  List<String> existing=getMediaSubfolders();
  boolean isExistingOrRoot=existing.contains(input)||input.contains("/")||Arrays.asList("DCIM","Pictures","Movies","Download","Documents","Music").contains(input);
  String target=isExistingOrRoot?input+"/":"DCIM/"+input+"/";
  getPreferences(0).edit().putString("folder",input).apply();return target;
 }
 private void confirmMove(){
  if(selected.isEmpty()||fileBusy)return;
  List<String> folders=getMediaSubfolders();
  folders.add("[ 新增/手動輸入資料夾… ]");
  String[] items=folders.toArray(new String[0]);
  new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("選擇移入的目的資料夾").setItems(items,(dialog,which)->{
   if(which==items.length-1){
    EditText input=new EditText(this);
    input.setHint("請輸入目的資料夾名稱");
    input.setText(getPreferences(0).getString("folder","整理收藏"));
    new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("輸入目的資料夾名稱").setView(input).setPositiveButton("確定",(d,w)->promptMoveConfirmation(input.getText().toString())).setNegativeButton("取消",null).show();
   }else{
    promptMoveConfirmation(items[which]);
   }
  }).setNegativeButton("取消",null).show();
 }
 private void promptMoveConfirmation(String folderName){
  String target=validateDestination(folderName);if(target==null)return;ArrayList<Scanner.Item> snapshot=new ArrayList<>(selected);
  new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("確認移動 "+snapshot.size()+" 個檔案").setMessage("目的地："+target+"\n移動後會離開原資料夾；不刪除重複檔。相似與人物分組請先確認。\n同名檔會加上識別碼，不覆蓋既有檔案。\n這次成功的移動會取代上次復原紀錄。").setNegativeButton("取消",null).setPositiveButton("移動",(d,w)->{destination=target;pending=new ArrayList<>();for(Scanner.Item item:snapshot)if(!target.equals(item.path)){MoveRecord r=new MoveRecord();r.uri=item.uri;r.oldPath=item.path;r.oldName=item.name;r.newPath=target;r.newName=item.name;r.item=item;pending.add(r);}undoing=false;isDeleting=false;operationHasSuccess=false;operationMoved=operationFailed=0;fileBusy=true;backupJournal();updateSelection();requestWrite();}).show();
 }
 private void confirmDelete(){
  if(selected.isEmpty()||fileBusy)return;
  ArrayList<Scanner.Item> snapshot=new ArrayList<>(selected);
  pendingDeletes=snapshot;
  isDeleting=true;
  fileBusy=true;
  operationDeleted=operationDeleteFailed=0;
  updateSelection();
  requestDelete();
 }
 private void requestDelete(){
  if(pendingDeletes==null||pendingDeletes.isEmpty()){
   fileBusy=false;updateSelection();
   status.setText("刪除完成 · 成功 "+operationDeleted+" · 失敗 "+operationDeleteFailed);
   render();
   return;
  }
  fileWorker.execute(()->{
   List<Scanner.Item> directList=new ArrayList<>(pendingDeletes);
   List<Scanner.Item> needPermissionList=new ArrayList<>();
   int directDeleted=0;
   for(Scanner.Item item:directList){
    try{
     int count=getContentResolver().delete(item.uri,null,null);
     if(count>0){
      item.removed=true;
      ui(()->selected.remove(item));
      directDeleted++;
     }else{
      needPermissionList.add(item);
     }
    }catch(Exception e){
     needPermissionList.add(item);
    }
   }
   final int dDeleted=directDeleted;
   ui(()->{
    operationDeleted+=dDeleted;
    pendingDeletes=needPermissionList;
    if(pendingDeletes.isEmpty()){
     fileBusy=false;
     saveScanCache(result);
     updateSelection();
     status.setText("刪除完成 · 成功 "+operationDeleted+" · 失敗 "+operationDeleteFailed);
     render();
    }else{
     requestDeleteBatch();
    }
   });
  });
 }

    private void saveOpState() {
        try {
            JSONObject root = new JSONObject();
            root.put("isDeleting", isDeleting);
            root.put("undoing", undoing);
            root.put("operationHasSuccess", operationHasSuccess);
            root.put("operationMoved", operationMoved);
            root.put("operationFailed", operationFailed);
            root.put("operationDeleted", operationDeleted);
            root.put("operationDeleteFailed", operationDeleteFailed);

            if (pendingDeletes != null) {
                JSONArray deletes = new JSONArray();
                for (Scanner.Item item : pendingDeletes) deletes.put(item.uri.toString());
                root.put("pendingDeletes", deletes);
            }

            if (pending != null) {
                JSONArray moves = new JSONArray();
                for (MoveRecord r : pending) {
                    JSONObject mo = new JSONObject();
                    mo.put("uri", r.uri.toString());
                    if (r.oldPath != null) mo.put("oldPath", r.oldPath);
                    if (r.oldName != null) mo.put("oldName", r.oldName);
                    if (r.newPath != null) mo.put("newPath", r.newPath);
                    if (r.newName != null) mo.put("newName", r.newName);
                    moves.put(mo);
                }
                root.put("pending", moves);
            }

            File f = new File(getFilesDir(), "op-state.json");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                out.write(root.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {}
    }

    private void loadOpState() {
        File f = new File(getFilesDir(), "op-state.json");
        if (!f.exists()) return;
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            JSONObject root = new JSONObject(out.toString("UTF-8"));

            isDeleting = root.optBoolean("isDeleting", false);
            undoing = root.optBoolean("undoing", false);
            operationHasSuccess = root.optBoolean("operationHasSuccess", false);
            operationMoved = root.optInt("operationMoved", 0);
            operationFailed = root.optInt("operationFailed", 0);
            operationDeleted = root.optInt("operationDeleted", 0);
            operationDeleteFailed = root.optInt("operationDeleteFailed", 0);

            if (root.has("pendingDeletes")) {
                pendingDeletes = new ArrayList<>();
                JSONArray deletes = root.getJSONArray("pendingDeletes");
                for (int i = 0; i < deletes.length(); i++) {
                    Scanner.Item item = new Scanner.Item();
                    item.uri = Uri.parse(deletes.getString(i));
                    if (result != null) {
                        for (Scanner.Item existing : result.all) {
                            if (existing.uri.equals(item.uri)) { item = existing; break; }
                        }
                    }
                    pendingDeletes.add(item);
                }
                fileBusy = true;
            }

            if (root.has("pending")) {
                pending = new ArrayList<>();
                JSONArray moves = root.getJSONArray("pending");
                for (int i = 0; i < moves.length(); i++) {
                    JSONObject mo = moves.getJSONObject(i);
                    MoveRecord r = new MoveRecord();
                    r.uri = Uri.parse(mo.getString("uri"));
                    r.oldPath = mo.optString("oldPath", null);
                    r.oldName = mo.optString("oldName", null);
                    r.newPath = mo.optString("newPath", null);
                    r.newName = mo.optString("newName", null);
                    if (result != null) {
                        for (Scanner.Item existing : result.all) {
                            if (existing.uri.equals(r.uri)) { r.item = existing; break; }
                        }
                    }
                    pending.add(r);
                }
                fileBusy = true;
            }
        } catch (Exception e) {}
    }

    private void clearOpState() {
        File f = new File(getFilesDir(), "op-state.json");
        if (f.exists()) f.delete();
    }

 private void requestDeleteBatch(){
  if(pendingDeletes==null||pendingDeletes.isEmpty()){
   fileBusy=false;
   clearOpState();
   saveScanCache(result);
   updateSelection();
   status.setText("刪除完成 · 成功 "+operationDeleted+" · 失敗 "+operationDeleteFailed);
   render();
   return;
  }
  saveOpState();
  try{
   ArrayList<Uri> uris=new ArrayList<>();
   for(int i=0;i<Math.min(100,pendingDeletes.size());i++)uris.add(pendingDeletes.get(i).uri);
   startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(),uris).getIntentSender(),52,null,0,0,0);
  }catch(Exception e){
   status.setText("刪除已完成（直接刪除 "+operationDeleted+" 個，需要授權或失敗 "+pendingDeletes.size()+" 個）");
   pendingDeletes=null;
   fileBusy=false;
   saveScanCache(result);
   updateSelection();
   render();
  }
 }
 private void finishMoveOperation(){
  pending=null;fileBusy=false;
  if(!undoing){
   if(operationMoved==0)restoreJournalBackup();else clearJournalBackup();
   saveScanCache(result);
  }else{
   result=null;selected.clear();clearThumbs();
  }
  updateSelection();
  render();
 }
 private void requestWrite(){if(pending==null||pending.isEmpty()){
  status.setText((undoing?"復原":"移動")+"完成 · 成功 "+operationMoved+" · 失敗 "+operationFailed+(undoing?"；請重新掃描更新分組。":"；可繼續勾選移到同一資料夾。"));
  clearOpState();
  finishMoveOperation();
  operationHasSuccess=false;operationMoved=operationFailed=0;
  return;
 }
  saveOpState();
  try{ArrayList<Uri> uris=new ArrayList<>();for(int i=0;i<Math.min(100,pending.size());i++)uris.add(pending.get(i).uri);startIntentSenderForResult(MediaStore.createWriteRequest(getContentResolver(),uris).getIntentSender(),51,null,0,0,0);}catch(Exception e){status.setText("無法取得移動授權："+e.getMessage());finishMoveOperation();}}
 public void onActivityResult(int request,int response,Intent data){super.onActivityResult(request,response,data);
  if(request==51){if(response==RESULT_OK)applyBatch();else{
   status.setText("未獲授權 · 已完成：成功 "+operationMoved+" · 失敗 "+operationFailed+"。未授權之檔案已保留可重試。");
   finishMoveOperation();
   operationHasSuccess=false;operationMoved=operationFailed=0;
  }}
  else if(request==52){if(response==RESULT_OK)applyDeleteBatch();else{
   status.setText("未獲刪除授權 · 已完成：成功 "+operationDeleted+" · 失敗 "+operationDeleteFailed+"。未授權之檔案已保留。");
   if(operationDeleted>0)saveScanCache(result);
   pendingDeletes=null;
   fileBusy=false;updateSelection();render();
  }}
 }
 private int operationDeleted=0,operationDeleteFailed=0;
 private void applyDeleteBatch(){
  if(pendingDeletes==null)return;
  final List<Scanner.Item> batch=new ArrayList<>(pendingDeletes.subList(0,Math.min(100,pendingDeletes.size())));
  for(Scanner.Item item:batch){item.removed=true;selected.remove(item);}
  pendingDeletes.removeAll(batch);
  operationDeleted+=batch.size();
  if(pendingDeletes!=null&&!pendingDeletes.isEmpty()){
   requestDeleteBatch();
  }else{
   pendingDeletes=null;
   fileBusy=false;
   saveScanCache(result);
   status.setText("刪除完成 · 成功 "+operationDeleted+" · 失敗 "+operationDeleteFailed);
   render();
  }
 }
 private boolean operationHasSuccess=false;private int operationMoved=0,operationFailed=0;
 private void applyBatch(){
  if(pending==null)return;final List<MoveRecord> batch=new ArrayList<>(pending.subList(0,Math.min(100,pending.size())));
  fileWorker.execute(()->{int moved=0,failed=0;List<MoveRecord> successfulRecords=new ArrayList<>();for(MoveRecord r:batch){if(dead)break;try{
    if(r.newPath==null||r.newPath.isEmpty()||(!undoing&&(r.oldPath==null||r.oldPath.isEmpty())))throw new IOException("原資料夾紀錄不完整");String finalName=collision(r.uri,r.newPath,r.newName)?uniqueName(r.newName):r.newName;
    ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.RELATIVE_PATH,r.newPath);values.put(MediaStore.MediaColumns.DISPLAY_NAME,finalName);
    int updated=getContentResolver().update(r.uri,values,null,null);if(updated!=1)throw new IOException("檔案更新失敗");moved++;
    if(!undoing){if(!operationHasSuccess){operationHasSuccess=true;}appendJournalPending(r);}
    if(undoing){removeJournal(r.uri);}
    if(r.item!=null){r.item.removed=true;}
    successfulRecords.add(r);
   }catch(Exception e){failed++;}}
   final int m=moved,f=failed;ui(()->{operationMoved+=m;operationFailed+=f;
    if(!undoing && !successfulRecords.isEmpty()) {
     try { promotePendingJournal(); } catch(Exception e){}
    }
    if(pending!=null)pending.removeAll(batch);
    if(!undoing)for(MoveRecord r:successfulRecords)if(r.item!=null)selected.remove(r.item);
    if(pending!=null&&!pending.isEmpty()){status.setText("已處理 "+(operationMoved+operationFailed)+" 個（成功 "+operationMoved+"），繼續下一批…");requestWrite();}
    else{
     status.setText((undoing?"復原":"移動")+"完成 · 成功 "+operationMoved+" · 失敗 "+operationFailed+(undoing?"；請重新掃描更新分組。":"；可繼續勾選移到同一資料夾。"));
     finishMoveOperation();
     operationHasSuccess=false;operationMoved=operationFailed=0;
    }});
  });
 }
 private boolean collision(Uri self,String path,String name){try(android.database.Cursor c=getContentResolver().query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),new String[]{"_id"},"relative_path = ? AND _display_name = ?",new String[]{path,name},null)){if(c!=null)while(c.moveToNext())if(c.getLong(0)!=ContentUris.parseId(self))return true;}return false;}
 private String uniqueName(String name){int i=name.lastIndexOf('.');String id="_"+UUID.randomUUID().toString().substring(0,8);return i>0?name.substring(0,i)+id+name.substring(i):name+id;}
 private File journal(){return new File(getFilesDir(),"move-journal.json");}
 private File journalPending(){return new File(getFilesDir(),"move-journal-pending.json");}
 private File journalBackup(){return new File(getFilesDir(),"move-journal-backup.json");}
 private synchronized void backupJournal(){
  File jp=journalPending();
  if(jp.exists())jp.delete();
 }
 private synchronized void restoreJournalBackup(){
 }
 private synchronized void clearJournalBackup(){
  File jp=journalPending();
  if(jp.exists())jp.delete();
 }
 private synchronized JSONArray readJournal()throws Exception{if(!journal().exists())return new JSONArray();try(InputStream in=new FileInputStream(journal())){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);return new JSONArray(out.toString("UTF-8"));}}
 private synchronized JSONArray readJournalPending()throws Exception{if(!journalPending().exists())return new JSONArray();try(InputStream in=new FileInputStream(journalPending())){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);return new JSONArray(out.toString("UTF-8"));}}
 private synchronized void writeJournal(JSONArray a)throws Exception{File tmp=new File(getFilesDir(),"move-journal.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(a.toString().getBytes("UTF-8"));out.getFD().sync();}if(!tmp.renameTo(journal()))throw new IOException("復原紀錄儲存失敗");}
 private synchronized void writeJournalPending(JSONArray a)throws Exception{File tmp=new File(getFilesDir(),"move-journal-pending.tmp");try(FileOutputStream out=new FileOutputStream(tmp)){out.write(a.toString().getBytes("UTF-8"));out.getFD().sync();}if(!tmp.renameTo(journalPending()))throw new IOException("暫存復原紀錄儲存失敗");}
 private synchronized void appendJournalPending(MoveRecord r)throws Exception{JSONArray a=readJournalPending();JSONObject o=new JSONObject();o.put("uri",r.uri.toString());o.put("path",r.oldPath);o.put("name",r.oldName);a.put(o);writeJournalPending(a);}
 private synchronized void promotePendingJournal()throws Exception{
  JSONArray pending = readJournalPending();
  if (pending.length() > 0) {
   writeJournal(pending);
  }
 }
 private synchronized void removeJournal(Uri uri)throws Exception{JSONArray a=readJournal(),next=new JSONArray();for(int i=0;i<a.length();i++)if(!a.getJSONObject(i).getString("uri").equals(uri.toString()))next.put(a.getJSONObject(i));writeJournal(next);}
 private void undo(){if(fileBusy||busy)return;try{JSONArray a=readJournal();if(a.length()==0){status.setText("沒有可復原的移動紀錄。");return;}new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("復原上次移動").setMessage("將 "+a.length()+" 個紀錄移回原資料夾。同名檔會加識別碼；已不存在的檔案會列為失敗。").setNegativeButton("取消",null).setPositiveButton("復原",(d,w)->{try{pending=new ArrayList<>();for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);MoveRecord r=new MoveRecord();r.uri=Uri.parse(o.getString("uri"));r.newPath=o.getString("path");r.newName=o.getString("name");pending.add(r);}undoing=true;operationHasSuccess=false;operationMoved=operationFailed=0;fileBusy=true;updateSelection();requestWrite();}catch(Exception e){status.setText("復原紀錄無法讀取。");}}).show();}catch(Exception e){status.setText("復原紀錄無法讀取。");}}
 private void help(){new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("使用說明與隱私").setMessage("1. 授權所有照片與影片後開始掃描。可篩選來源資料夾，或對全相簿掃描。\n2. 重複採 SHA-256；相似照片使用感知雜湊、色彩與比例，影片抽取 7 個畫面。\n3. 人物在手機內用 MobileFaceNet 比對抽樣人臉；分數不是準確率。正面清楚人臉效果較好。\n4. 掃描過程中即可即時勾選檔案進行『移到此資料夾』或『刪除』。\n5. 不自動刪除、不上傳、不在背景持續監控。\n6. 復原只保留上次移動批次。清快取只移除本程式縮圖，不動原照片／影片。").setPositiveButton("關閉",null).show();}
 private void clearThumbs(){resultGeneration = ScanService.clearCacheAsync(this);}
 private File cacheFile(){return new File(getFilesDir(),"scan-cache.json");}
 public void saveScanCache(Scanner.Result res){
  if(res==null)return;
  ScanService.saveScanCache(this, res, resultGeneration);
 }
 private Scanner.Result loadScanCache(){
  if(!cacheFile().exists())return null;
  try(InputStream in=new FileInputStream(cacheFile())){
   ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);
   JSONObject root=new JSONObject(out.toString("UTF-8"));
   if(root.has("generation") && root.getLong("generation") != ScanService.getGeneration()){return null;}
   Scanner.Result res=new Scanner.Result();JSONArray all=root.getJSONArray("all");
   for(int i=0;i<all.length();i++){
    JSONObject o=all.getJSONObject(i);Scanner.Item item=new Scanner.Item();item.uri=Uri.parse(o.getString("uri"));item.name=o.optString("name","");item.mime=o.optString("mime",null);item.path=o.optString("path","");item.size=o.optLong("size",0);item.duration=o.optLong("duration",0);item.video=o.optBoolean("video",false);item.removed=o.optBoolean("removed",false);
    if(o.has("thumb"))item.thumb=new File(o.getString("thumb"));res.all.add(item);
   }
   res.exact.addAll(jsonToGroups(root.getJSONArray("exact"),res.all));res.similar.addAll(jsonToGroups(root.getJSONArray("similar"),res.all));res.people.addAll(jsonToGroups(root.getJSONArray("people"),res.all));
   res.unreadable=root.optInt("unreadable",0);res.faceFailures=root.optInt("faceFailures",0);res.faceError=root.optString("faceError","");
   return res;
  }catch(Exception e){return null;}
 }
 private List<Scanner.Group> jsonToGroups(JSONArray arr,List<Scanner.Item> allItems)throws Exception{
  List<Scanner.Group> list=new ArrayList<>();
  for(int i=0;i<arr.length();i++){
   JSONObject o=arr.getJSONObject(i);Scanner.Group g=new Scanner.Group(o.optString("title",""));if(o.has("face"))g.face=new File(o.getString("face"));
   JSONArray items=o.getJSONArray("items");for(int j=0;j<items.length();j++){int idx=items.getInt(j);if(idx>=0&&idx<allItems.size())g.items.add(allItems.get(idx));}
   list.add(g);
  }
  return list;
 }

 @Override
 public void onBackPressed() {
     if (settingsScreen != null && settingsScreen.getVisibility() == View.VISIBLE) {
         settingsScreen.setVisibility(View.GONE);
         mainContent.setVisibility(View.VISIBLE);
         return;
     }
     super.onBackPressed();
 }

 protected void onDestroy(){
  dead=true;
  ScanService.removeListener(this);
  fileWorker.shutdownNow();
  super.onDestroy();
 }
}
