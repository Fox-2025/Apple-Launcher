package com.tvos.channelprobe;

import android.app.Activity;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;
import android.util.AtomicFile;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class MainActivity extends Activity {
    private static final String TAG = "TCLChannelProbe";
    private static final String MANAGER = "com.tcl.tvmanager.TTvChannelManager";
    private static final String ENUM = "com.tcl.tvmanager.vo.EnTCLChanelListType";
    private static final String[] FIELDS = {"channelId","number","programName","freq",
        "index","dbPrimaryKey","antennaType","serviceID","transportStreamID","onID",
        "serviceType","lcn","simucastLcn","videopid","audiopid","pcrpid",
        "isFavorite","isHD","isHide","isSkip","isDelete","isLock","isScramble","isAppVisible"};
    private TextView status;
    private Button[] buttons;
    private boolean busy;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36,28,36,28);
        layout.setBackgroundColor(Color.rgb(20,25,34));
        TextView title = new TextView(this);
        title.setText("TCL Kanal Testi - Salt Okuma");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        layout.addView(title);
        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(17);
        status.setText("Test secin. Kanal ayarlari degistirilmez.");
        String[] names = {"1. Kanal arama (ATV / KOZA / TRT)", "2. Su anki kanal", "3. Kanal listesi"};
        String[] keys = {"search","current","list"};
        buttons = new Button[3];
        for (int i=0; i<keys.length; i++) {
            final String key=keys[i];
            Button button=new Button(this);
            button.setText(names[i]);
            button.setOnClickListener(v -> startTest(key));
            buttons[i]=button;
            layout.addView(button);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(status);
        layout.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(layout);
        buttons[0].requestFocus();
        String test=getIntent().getStringExtra("test");
        if ("search".equals(test)||"current".equals(test)||"list".equals(test)) startTest(test);
    }

    private void save(String test, JSONObject data) throws Exception {
        AtomicFile file = new AtomicFile(new File(getFilesDir(),test+".json"));
        FileOutputStream out = file.startWrite();
        try {
            out.write(data.toString(2).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Throwable t) {
            file.failWrite(out);
            throw t;
        }
    }
    private static String error(Throwable t) {
        if (t instanceof InvocationTargetException && t.getCause()!=null) t=t.getCause();
        return Log.getStackTraceString(t);
    }
    private void startTest(String test) {
        if (busy) return;
        busy=true;
        for (Button b:buttons) b.setEnabled(false);
        status.setText("Test: "+test+" ...");
        new Thread(() -> {
            JSONObject report=new JSONObject();
            String result;
            try {
                report.put("test",test);
                report.put("status","started");
                report.put("startedAt",System.currentTimeMillis());
                save(test,report);
                Log.i(TAG,"START "+test);
                if ("search".equals(test)) search(report);
                else if ("current".equals(test)) current(report);
                else list(report);
                report.put("status","completed");
            } catch (Throwable t) {
                try {report.put("status","error");report.put("error",error(t));} catch(Exception ignored){}
                Log.e(TAG,"FAIL "+test,t);
            }
            try {
                report.put("finishedAt",System.currentTimeMillis());
                save(test,report);
                result=report.toString(2);
                Log.i(TAG,"END "+test+" status="+report.optString("status"));
            } catch (Throwable t) {
                result="Report write error: "+error(t);
                Log.e(TAG,result);
            }
            final String shown=result.length()>9000?result.substring(0,9000)+"\n... Tam rapor dosyada.":result;
            runOnUiThread(() -> {status.setText(shown); busy=false; for(Button b:buttons)b.setEnabled(true);});
        },"tcl-read-"+test).start();
    }
    private void search(JSONObject report) throws Exception {
        JSONArray results=new JSONArray();
        report.put("queries",results);
        for (String term:new String[]{"ATV","KOZA","TRT"}) {
            JSONObject item=new JSONObject();
            item.put("query",term);
            results.put(item);
            Uri uri=Uri.parse("content://com.tcl.tv.SearchContentProvider/search_suggest_query")
                .buildUpon().appendQueryParameter("limit","20").build();
            try (Cursor c=getContentResolver().query(uri,null,null,new String[]{term},null)) {
                JSONArray rows=new JSONArray();
                item.put("rows",rows);
                if(c==null){item.put("status","null_cursor");continue;}
                item.put("count",c.getCount());
                String[] cols=c.getColumnNames();
                while(c.moveToNext() && rows.length()<100){
                    JSONObject row=new JSONObject();
                    for(int i=0;i<cols.length;i++){
                        if(c.isNull(i)) row.put(cols[i],JSONObject.NULL);
                        else if(c.getType(i)==Cursor.FIELD_TYPE_BLOB) row.put(cols[i],"<blob omitted>");
                        else row.put(cols[i],c.getString(i));
                    }
                    rows.put(row);
                }
                item.put("status","ok");
            } catch(Throwable t){item.put("status","error");item.put("error",error(t));}
        }
        // Standard provider comparison, not a write operation.
        JSONObject standard=new JSONObject();
        report.put("standardTvProvider",standard);
        try(Cursor c=getContentResolver().query(Uri.parse("content://android.media.tv/channel"),
                new String[]{"_id","display_number","display_name","input_id","service_id","transport_stream_id","original_network_id"},null,null,null)){
            if(c==null){standard.put("status","null_cursor");return;}
            standard.put("count",c.getCount());
            JSONArray rows=new JSONArray();standard.put("rows",rows);
            while(c.moveToNext() && rows.length()<10000){
                JSONObject row=new JSONObject();
                for(int i=0;i<c.getColumnCount();i++) row.put(c.getColumnName(i),c.isNull(i)?JSONObject.NULL:c.getString(i));
                rows.put(row);
            }
            standard.put("status","ok");
        }catch(Throwable t){standard.put("status","error");standard.put("error",error(t));}
    }
    private Object manager() throws Exception {
        Class<?> cls=Class.forName(MANAGER);
        return cls.getMethod("getInstance",android.content.Context.class).invoke(null,getApplicationContext());
    }
    private JSONObject channel(Object item) throws Exception {
        JSONObject row=new JSONObject();
        if(item==null){row.put("null",true);return row;}
        for(String name:FIELDS){
            try {
                Field f=item.getClass().getField(name);
                Object v=f.get(item);
                if(v instanceof Enum<?>) v=((Enum<?>)v).name();
                row.put(name,v==null?JSONObject.NULL:v);
            } catch(NoSuchFieldException e){row.put(name,JSONObject.NULL);}
        }
        return row;
    }
    private void current(JSONObject report) throws Exception {
        Object manager=manager();
        report.put("managerClass",manager.getClass().getName());
        Object info=manager.getClass().getMethod("getCurrentProgramInfo").invoke(manager);
        report.put("channel",channel(info));
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private void list(JSONObject report) throws Exception {
        Object manager=manager();
        Class enumClass=Class.forName(ENUM);
        JSONArray attempts=new JSONArray();
        report.put("attempts",attempts);
        Method method=manager.getClass().getMethod("getChannelList",enumClass,boolean.class);
        for(String type:new String[]{"EN_CHANNEL_ALL_COMPLETE","EN_CHANNEL_ALL"}){
            JSONObject attempt=new JSONObject();attempt.put("type",type);
            attempt.put("includeSkipped",true);attempts.put(attempt);
            try{
                Object value=method.invoke(manager,Enum.valueOf(enumClass,type),true);
                if(!(value instanceof List)){attempt.put("status","not_list");continue;}
                List<?> channels=(List<?>)value;
                JSONArray rows=new JSONArray();attempt.put("channels",rows);
                attempt.put("count",channels.size());
                for(Object item:channels){if(rows.length()>=10000)break;rows.put(channel(item));}
                attempt.put("status","ok");
            }catch(Throwable t){attempt.put("status","error");attempt.put("error",error(t));}
            // Preserve first result if the next OEM method crashes.
            save("list",report);
        }
    }
}
