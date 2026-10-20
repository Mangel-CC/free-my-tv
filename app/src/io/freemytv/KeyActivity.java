package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
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
    private EditText key, city, lat, lon;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        float d = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * d);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad * 4, pad, pad * 4, pad);
        box.addView(label(Ui.tr("Chave TMDB e local do clima", "TMDB key and weather location",
                "Clave de TMDB y lugar del clima"), 26, d));
        key = field(Ui.tr("Chave TMDB (themoviedb.org → Settings → API)", "TMDB key (themoviedb.org → Settings → API)",
                "Clave de TMDB (themoviedb.org → Settings → API)"), Prefs.tmdbKey(), InputType.TYPE_CLASS_TEXT, box, d);
        city = field(Ui.tr("Cidade (nome no cartão do clima)", "City (name on the weather card)",
                "Ciudad (nombre en la tarjeta del clima)"), Prefs.city(), InputType.TYPE_CLASS_TEXT, box, d);
        int number = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
        lat = field(Ui.tr("Latitude (ex.: 18.0)", "Latitude (e.g. 18.0)", "Latitud (ej.: 18.0)"), Prefs.latitude(), number, box, d);
        lon = field(Ui.tr("Longitude (ex.: -92.9)", "Longitude (e.g. -92.9)", "Longitud (ej.: -92.9)"), Prefs.longitude(), number, box, d);
        Button save = new Button(this);
        save.setText(Ui.tr("Salvar", "Save", "Guardar"));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        });
        box.addView(save);
        ScrollView scroll = new ScrollView(this);   // the keyboard takes half the screen
        scroll.addView(box);
        setContentView(scroll);
    }

    private TextView label(String text, float sp, float d) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setGravity(Gravity.START);
        t.setPadding(0, 0, 0, Math.round(16 * d));
        return t;
    }

    private EditText field(String hint, String value, int type, LinearLayout into, float d) {
        into.addView(label(hint, 16, d));
        EditText e = new EditText(this);
        e.setInputType(type);
        e.setSingleLine(true);
        e.setText(value);
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
