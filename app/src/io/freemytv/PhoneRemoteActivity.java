package io.freemytv;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Settings > Remote Buttons > Phone remote: turns the phone remote on, and shows the address and the PIN to pair a phone. */
public class PhoneRemoteActivity extends Activity {
    private static final int BACKGROUND = 0xFF10141C, FIELD = 0xFF1E2430, LINE = 0xFF3A4252, FOCUS = 0xFF5AA9FF;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };
    private TextView status, address, pin, note, phones;
    private Button toggle, fresh, forget;
    private float d;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        d = getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * d);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad * 4, pad, pad * 4, pad);
        box.addView(label(Ui.tr("Controle pelo celular", "Phone remote", "Mando desde el móvil"), 28, 0xFFFFFFFF));
        status = label("", 20, 0xFFFFFFFF);
        box.addView(status);
        address = label("", 22, 0xFFB8C2D6);
        box.addView(address);
        pin = label("", 44, 0xFFFFFFFF);
        pin.setLetterSpacing(0.25f);
        box.addView(pin);
        phones = label("", 16, 0xFFB8C2D6);
        box.addView(phones);

        toggle = button(box, 340);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle(); }
        });
        fresh = button(box, 340);
        fresh.setText(Ui.tr("Novo PIN", "New PIN", "Nuevo PIN"));
        fresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (PhoneRemote.enabled(PhoneRemoteActivity.this)) PhoneRemote.newPin();
                refresh();
            }
        });
        forget = button(box, 340);
        forget.setText(Ui.tr("Esquecer os celulares", "Forget the phones", "Olvidar los móviles"));
        forget.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                PhoneRemote.forgetPhones(PhoneRemoteActivity.this);
                refresh();
            }
        });

        note = label(Ui.tr(
                "No celular, abra o endereço acima no navegador e digite o PIN. Qualquer celular da sua rede que souber o PIN poderá controlar a TV: desligue quando não usar. O ADB (depuração USB) da TV precisa ficar ligado.",
                "On the phone, open the address above in the browser and type the PIN. Any phone on your network that knows the PIN can control the TV, so turn this off when you don't use it. The TV's ADB (USB debugging) has to stay on.",
                "En el móvil, abre la dirección de arriba en el navegador y escribe el PIN. Cualquier móvil de tu red que sepa el PIN podrá controlar la TV: apágalo cuando no lo uses. El ADB (depuración USB) de la TV debe seguir activado."),
                16, 0xFF8A94A8);
        box.addView(note);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BACKGROUND);
        scroll.addView(box);
        setContentView(scroll);
        toggle.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        handler.postDelayed(tick, 1000);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void toggle() {
        PhoneRemote.setEnabled(this, !PhoneRemote.enabled(this));
        // The remote has to stay up while other apps are open: the accessibility service is what keeps this process alive.
        if (PhoneRemote.enabled(this)) HomeMode.ensureRemoteService(this, 0);
        refresh();
    }

    private void refresh() {
        boolean on = PhoneRemote.enabled(this);
        boolean up = PhoneRemote.running();
        toggle.setText(on ? Ui.tr("Desligar", "Turn off", "Apagar") : Ui.tr("Ligar", "Turn on", "Encender"));
        fresh.setEnabled(on);
        fresh.setAlpha(on ? 1f : 0.4f);
        int count = PhoneRemote.phones(this);
        forget.setEnabled(count > 0);
        forget.setAlpha(count > 0 ? 1f : 0.4f);
        phones.setText(Ui.tr("Celulares emparelhados: ", "Paired phones: ", "Móviles emparejados: ") + count);
        if (!on) {
            status.setText(Ui.tr("Desligado", "Off", "Apagado"));
            address.setText("");
            pin.setText("");
            return;
        }
        if (!up) {
            status.setText(Ui.tr("Ligando… se não ligar, a porta " + PhoneRemote.PORT + " está ocupada.",
                    "Starting… if it doesn't start, port " + PhoneRemote.PORT + " is busy.",
                    "Encendiendo… si no enciende, el puerto " + PhoneRemote.PORT + " está ocupado."));
            PhoneRemote.start(this);
            address.setText("");
            pin.setText("");
            return;
        }
        status.setText(Ui.tr("Ligado", "On", "Encendido"));
        List<String> ips = PhoneRemote.addresses();
        StringBuilder sb = new StringBuilder();
        for (String ip : ips) sb.append(sb.length() > 0 ? "   " : "").append("http://").append(ip).append(':').append(PhoneRemote.PORT);
        address.setText(ips.isEmpty()
                ? Ui.tr("A TV não está numa rede local.", "The TV isn't on a local network.", "La TV no está en una red local.")
                : sb.toString());
        String code = PhoneRemote.pin();
        pin.setText(code != null ? code
                : Ui.tr("PIN usado ou vencido: toque em Novo PIN", "PIN used or expired: choose New PIN", "PIN usado o caducado: elige Nuevo PIN"));
        pin.setTextSize(TypedValue.COMPLEX_UNIT_SP, code != null ? 44 : 18);
        pin.setLetterSpacing(code != null ? 0.25f : 0f);
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

    private Button button(LinearLayout into, int widthDp) {
        Button b = new Button(this);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        b.setTextColor(0xFFFFFFFF);
        b.setAllCaps(false);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_focused}, shape(0xFF2B6CB0, FOCUS, 3));
        s.addState(new int[] {}, shape(FIELD, LINE, 1));
        b.setBackground(s);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(widthDp * d), Math.round(56 * d));
        lp.topMargin = Math.round(14 * d);
        into.addView(b, lp);
        return b;
    }

    private GradientDrawable shape(int fill, int line, int widthDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(10 * d);
        g.setStroke(Math.round(widthDp * d), line);
        return g;
    }
}
