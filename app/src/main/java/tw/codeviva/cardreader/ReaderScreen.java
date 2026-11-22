package tw.codeviva.cardreader;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

final class ReaderScreen {
    static final int BG=0xfff5f6f1, INK=0xff172c29, MUTED=0xff50645d, GREEN=0xff07594c,
        LINE=0xffdbe2da, PALE=0xffe2f0e6, WHITE=0xffffffff;
    final Context c;
    ReaderScreen(Context context){c=context;}
    int dp(float v){return (int)(c.getResources().getDisplayMetrics().density*v+.5f);}
    void pad(View v,int h,int d){v.setPadding(dp(h),dp(d),dp(h),dp(d));}
    LinearLayout col(){LinearLayout v=new LinearLayout(c);v.setOrientation(1);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(c);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    GradientDrawable bg(int color,int radius,boolean border){
        GradientDrawable s=new GradientDrawable();s.setColor(color);s.setCornerRadius(dp(radius));
        if(border)s.setStroke(dp(1),LINE);return s;
    }
    void weight(LinearLayout row,View child){row.addView(child,new LinearLayout.LayoutParams(0,-2,1));}
    TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(c);t.setText(s);t.setTextColor(color);t.setTextSize(size);
        t.setIncludeFontPadding(false);t.setLineSpacing(dp(2),1);if(bold)t.setTypeface(Typeface.create("sans-serif-medium",0));
        return t;
    }
    void gap(LinearLayout parent,int size){
        Space space=new Space(c);int px=dp(size);
        parent.addView(space,parent.getOrientation()==1?new LinearLayout.LayoutParams(1,px):new LinearLayout.LayoutParams(px,1));
    }
    LinearLayout panel(){LinearLayout v=col();v.setBackground(bg(WHITE,16,true));pad(v,18,16);return v;}
    void line(LinearLayout v){View line=new View(c);line.setBackgroundColor(LINE);v.addView(line,-1,dp(1));}
    Button action(String s,boolean primary,Runnable r){
        Button b=new Button(c);b.setAllCaps(false);b.setText(s);b.setTextColor(primary?WHITE:GREEN);b.setTextSize(15);
        b.setMinimumWidth(0);b.setMinWidth(0);b.setMinimumHeight(dp(48));b.setMinHeight(dp(48));b.setStateListAnimator(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x301e8a73),bg(primary?GREEN:PALE,12,false),null));
        pad(b,10,7);b.setOnClickListener(v->r.run());return b;
    }
    void tile(LinearLayout parent, String title, String state, boolean complete, String description, Runnable open) {
        LinearLayout item = col();
        item.setOnClickListener(v -> open.run());
        item.setFocusable(true);
        LinearLayout header = row();
        header.setMinimumHeight(dp(description == null ? 50 : 42));
        weight(header, text(title, 16, INK, false));
        TextView badge = text(state, 13, complete ? GREEN : MUTED, complete);
        if (complete) {
            badge.setBackground(bg(PALE, 8, false));
            pad(badge, 8, 3);
        }
        header.addView(badge);
        gap(header, 8);
        header.addView(text("›", 22, MUTED, false));
        item.addView(header);
        if (description != null) {
            TextView preview = text(description, 14, MUTED, false);
            preview.setMaxLines(2);
            preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.addView(preview);
            gap(item, 10);
        }
        parent.addView(item);
    }
    static final class PageArt extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        PageArt(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        private void stroke(Canvas c,float x,float y,float a,float b,float r,int clr,float width){
            p.setStyle(Paint.Style.STROKE);p.setColor(clr);p.setStrokeWidth(width);c.drawRoundRect(x,y,a,b,r,r,p);
        }
        private void fill(Canvas c,float x,float y,float a,float b,float r,int clr){
            p.setStyle(Paint.Style.FILL);p.setColor(clr);c.drawRoundRect(x,y,a,b,r,r,p);
        }
        protected void onDraw(Canvas c){
            float scale=Math.min(getWidth()/114f,getHeight()/86f);
            c.save();c.translate((getWidth()-114*scale)/2,(getHeight()-86*scale)/2);c.scale(scale,scale);
            c.rotate(-8,57,43);
            stroke(c,26,10,79,70,5,0xff639b88,1.6f);
            fill(c,35,16,89,76,5,0xffc2e9d4);
            fill(c,43,26,70,30,1,0xff356d58);
            for (int y = 38; y <= 62; y += 8) {
                fill(c,43,y,y == 62 ? 67 : 81,y+2,1,0xff62967e);
            }
            c.restore();
        }
    }
}
