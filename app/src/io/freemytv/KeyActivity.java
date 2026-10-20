package io.freemytv;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings > TMDB Key and Location: plain fields, so the TV's own keyboard types the key and the place. */
public class KeyActivity extends Activity {
    private static final int BACKGROUND = 0xFF10141C, FIELD = 0xFF1E2430, LINE = 0xFF3A4252, FOCUS = 0xFF5AA9FF;
    private EditText key, city, lat, lon;
    private float d;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        d = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * d);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad * 4, pad, pad * 4, pad);
        box.addView(label(Ui.tr("Chave TMDB e local do clima", "TMDB key and weather location",
                "Clave de TMDB y lugar del clima"), 28, 0xFFFFFFFF));
        key = field(Ui.tr("Chave TMDB (themoviedb.org → Settings → API)", "TMDB key (themoviedb.org → Settings → API)",
                "Clave de TMDB (themoviedb.org → Settings → API)"), Prefs.tmdbKey(), InputType.TYPE_CLASS_TEXT, box);
        city = field(Ui.tr("Cidade (nome no cartão do clima)", "City (name on the weather card)",
                "Ciudad (nombre en la tarjeta del clima)"), Prefs.city(), InputType.TYPE_CLASS_TEXT, box);
        int number = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
        lat = field(Ui.tr("Latitude (ex.: 18.0)", "Latitude (e.g. 18.0)", "Latitud (ej.: 18.0)"), Prefs.latitude(), number, box);
        lon = field(Ui.tr("Longitude (ex.: -92.9)", "Longitude (e.g. -92.9)", "Longitud (ej.: -92.9)"), Prefs.longitude(), number, box);
        Button save = new Button(this);
        save.setText(Ui.tr("Salvar", "Save", "Guardar"));
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        save.setTextColor(0xFFFFFFFF);
        save.setBackground(rounded(0xFF2B6CB0, FOCUS));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(240 * d), Math.round(56 * d));
        lp.topMargin = Math.round(16 * d);
        box.addView(save, lp);
        ScrollView scroll = new ScrollView(this);   // the keyboard takes half the screen
        scroll.setBackgroundColor(BACKGROUND);
        scroll.addView(box);
        setContentView(scroll);
    }

    private TextView label(String text, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.START);
        t.setPadding(0, Math.round(12 * d), 0, Math.round(6 * d));
        return t;
    }

    /** A rounded box that gets a bright border when it has the focus (the remote's selection). */
    private StateListDrawable rounded(int fill, int focusLine) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, shape(fill, focusLine, 3));
        s.addState(new int[] {}, shape(fill, LINE, 1));
        return s;
    }

    private GradientDrawable shape(int fill, int line, int widthDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(10 * d);
        g.setStroke(Math.round(widthDp * d), line);
        return g;
    }

    private EditText field(String hint, String value, int type, LinearLayout into) {
        into.addView(label(hint, 16, 0xFFB8C2D6));
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setText(value);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF7A8499);
        e.setBackground(rounded(FIELD, FOCUS));
        int p = Math.round(14 * d);
        e.setPadding(p, p, p, p);
        e.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        into.addView(e);
        return e;
    }

    private void save() {
        Prefs.setTmdbKey(key.getText().toString());
        Prefs.setPlace(city.getText().toString(), lat.getText().toString(), lon.getText().toString());
        finish();
    }
}
