package com.buildhubs.app;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.util.regex.*;
import android.icu.text.Bidi;

public class MainActivity extends AppCompatActivity {
    private Uri sourceUri, targetUri;
    private TextView sourceLabel, targetLabel, status;
    private ActivityResultLauncher<String[]> picker;
    private boolean pickingSource;

    @Override public void onCreate(Bundle b) { super.onCreate(b); PDFBoxResourceLoader.init(getApplicationContext()); buildUi(); }

    private void buildUi() {
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28,30,28,28); root.setBackgroundColor(Color.WHITE);
        TextView title=new TextView(this); title.setText("Build Hubs"); title.setTextSize(30); title.setTextColor(Color.rgb(13,91,120)); title.setGravity(Gravity.CENTER); root.addView(title,new LinearLayout.LayoutParams(-1,70));
        TextView sub=new TextView(this); sub.setText("העתקת שדות מ-PDF מקור אל טופס PDF"); sub.setTextSize(18); sub.setGravity(Gravity.CENTER); root.addView(sub,new LinearLayout.LayoutParams(-1,60));
        sourceLabel=label("לא נבחר PDF מקור"); root.addView(sourceLabel);
        Button s=button("1. בחירת PDF מקור"); s.setOnClickListener(v->{pickingSource=true; picker.launch(new String[]{"application/pdf"});}); root.addView(s);
        targetLabel=label("לא נבחר PDF יעד"); root.addView(targetLabel);
        Button t=button("2. בחירת PDF יעד / טופס"); t.setOnClickListener(v->{pickingSource=false; picker.launch(new String[]{"application/pdf"});}); root.addView(t);
        Space sp=new Space(this); root.addView(sp,new LinearLayout.LayoutParams(1,24));
        Button make=button("3. יצירת PDF חדש"); make.setOnClickListener(v->create()); root.addView(make);
        status=label("הערכים שיועתקו: בעלים, ת.ז., מספר רכב, מען, שנת ייצור, נפח, תוצר ומספר שילדה."); root.addView(status);
        setContentView(root);
        picker=registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri->{if(uri!=null){if(pickingSource){sourceUri=uri;sourceLabel.setText("מקור: "+uri.getLastPathSegment());}else{targetUri=uri;targetLabel.setText("יעד: "+uri.getLastPathSegment());}}});
    }
    private TextView label(String s){TextView v=new TextView(this);v.setText(s);v.setTextSize(16);v.setPadding(4,12,4,12);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(16);return b;}

    private void create(){
        if(sourceUri==null||targetUri==null){status.setText("יש לבחור גם PDF מקור וגם PDF יעד.");return;}
        try{
            Fields f=readFields(sourceUri);
            File out=new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),"BuildHubs_"+System.currentTimeMillis()+".pdf");
            editTarget(targetUri,out,f);
            status.setText("נוצר PDF חדש בהצלחה:\n"+out.getAbsolutePath()+"\n\nבמכשיר אפשר לפתוח אותו דרך מנהל הקבצים.");
            share(out);
        }catch(Exception e){status.setText("שגיאה: "+e.getMessage());}
    }

    private Fields readFields(Uri uri)throws Exception{
        InputStream in=getContentResolver().openInputStream(uri); PDDocument d=PDDocument.load(in); String text=new PDFTextStripper().getText(d); d.close(); if(in!=null)in.close();
        Fields f=new Fields();
        f.owner=find(text,"(?s)בעלים\\s*\\R\\s*(.+?)\\R");
        f.id=find(text,"תעודת\\s*זהות\\s*([0-9]{5,}-[0-9]+)");
        f.vehicle=find(text,"מספר\\s*רכב\\s*([0-9]{5,})");
        f.year=find(text,"שנת\\s*ייצור\\s*([0-9]{4})");
        f.engine=find(text,"נפח\\s*([0-9]{2,5})");
        f.make=find(text,"(?s)תוצר\\s*\\R\\s*(.+?)\\R");
        f.vin=find(text,"מספר\\s*שילדה\\s*([A-Za-z0-9]+)");
        String a=find(text,"(?s)מען\\s*\\R\\s*(.+?)\\R"); f.address=normalizeAddress(a);
        if(f.owner==null||f.id==null||f.vehicle==null||f.address==null||f.year==null||f.engine==null||f.make==null||f.vin==null) throw new Exception("לא הצלחתי לזהות את כל השדות ב-PDF המקור.");
        return f;
    }
    private String find(String s,String r){Matcher m=Pattern.compile(r,Pattern.UNICODE_CASE).matcher(s);return m.find()?m.group(1).trim():null;}
    private String normalizeAddress(String a){if(a==null)return null; a=a.replaceAll("\\s+"," ").trim(); Matcher m=Pattern.compile("(.+?)\\s+(\\d+)\\s+דירה\\s*(\\d+)\\s+(.+)").matcher(reverseTokensIfNeeded(a)); if(m.matches()) return m.group(1)+" "+m.group(2)+" דירה "+m.group(3)+" "+m.group(4); return a;}
    private String reverseTokensIfNeeded(String a){String[] p=a.split(" "); if(p.length>=4 && p[0].matches(".*\\d$") && p[p.length-1].matches(".*[א-ת].*")){StringBuilder b=new StringBuilder();for(int i=p.length-1;i>=0;i--){if(b.length()>0)b.append(' ');b.append(p[i]);}return b.toString();}return a;}

    private void editTarget(Uri uri,File out,Fields f)throws Exception{
        InputStream in=getContentResolver().openInputStream(uri); PDDocument d=PDDocument.load(in); if(in!=null)in.close(); PDPage p=d.getPage(0); PDRectangle box=p.getMediaBox();
        PDType0Font font=PDType0Font.load(d,getAssets().open("DejaVuSans.ttf"),true);
        // Coordinates are points in the supplied one-page insurance template.
        coverAndText(d,p,font,f.vehicle,346,219,392,237,9,false);
        coverAndText(d,p,font,f.id,270,245,329,263,9,false);
        coverAndText(d,p,font,f.owner,468,243,563,263,9,true);
        coverAndText(d,p,font,f.address,444,270,564,288,8,true);
        coverAndText(d,p,font,f.year,88,305,118,322,9,false);
        coverAndText(d,p,font,f.engine,190,305,232,322,9,false);
        coverAndText(d,p,font,f.make,250,304,327,322,8,true);
        coverAndText(d,p,font,f.vin,468,304,558,322,7,false);
        // Large line: rebuild the whole value so both ID and owner are guaranteed to match the source.
        coverAndText(d,p,font,"ת\"ז "+f.id+" "+f.owner+" בלבד",106,355,442,395,22,true);
        d.save(out); d.close();
    }
    private void coverAndText(PDDocument d,PDPage p,PDType0Font font,String text,float x0,float y0,float x1,float y1,float size,boolean rtl)throws Exception{
        PDPageContentStream cs=new PDPageContentStream(d,p,PDPageContentStream.AppendMode.APPEND,true,true); cs.setNonStrokingColor(Color.WHITE); cs.addRect(x0,y0,x1-x0,y1-y0); cs.fill();
        cs.beginText(); cs.setNonStrokingColor(Color.BLACK); cs.setFont(font,size); String visual=rtl?new Bidi(text,Bidi.DIRECTION_RIGHT_TO_LEFT).writeReordered(Bidi.DO_MIRRORING):text; float tw=font.getStringWidth(visual)/1000f*size; float tx=rtl?x1-Math.min(tw,x1-x0):x0; float ty=y0+(y1-y0-size)/2f+size*0.72f; cs.newLineAtOffset(tx,ty); cs.showText(visual); cs.endText(); cs.close();
    }
    private void share(File f){Intent i=new Intent(Intent.ACTION_SEND);i.setType("application/pdf");i.putExtra(Intent.EXTRA_STREAM,androidx.core.content.FileProvider.getUriForFile(this,getPackageName()+".provider",f));startActivity(Intent.createChooser(i,"שליחת PDF"));}
    static class Fields{String owner,id,vehicle,address,year,engine,make,vin;}
}
