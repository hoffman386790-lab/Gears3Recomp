package com.xport;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

// on-screen Xbox 360 pad drawn over the game (no physical pad needed). Every change is
// sent to the runtime as one pad state (MainActivity.nativeSetPad -> os::touch_pad ->
// XamInputGetState / XamInputGetKeystrokeEx in runtime/kernel/xam.cpp).
//   - Left half: floating left stick (move, steer); its centre is where the finger lands.
//   - Right half: floating right stick (camera); the centre follows the finger past the edge, so
//     dragging keeps turning.
//   - Buttons: A B X Y bottom right, RT/LT above them (both on the right, so braking works while
//     steering with the left thumb), LB/RB and L3/R3 at the top corners, the d-pad on the left
//     (the phone is d-pad up), BACK and START at the top centre.
// A finger that lands on a button keeps it pressed until it is lifted.
public class TouchControls extends View {
    boolean editMode = false;
    Button draggingBtn = null;
    float editToggleX, editToggleY, editToggleR;
    long editDownTime = 0;
    // XAMINPUT_GAMEPAD_* bits.
    static final int DPAD_UP = 0x0001, DPAD_DOWN = 0x0002, DPAD_LEFT = 0x0004, DPAD_RIGHT = 0x0008;
    static final int START = 0x0010, BACK = 0x0020, LEFT_THUMB = 0x0040, RIGHT_THUMB = 0x0080;
    static final int LEFT_SHOULDER = 0x0100, RIGHT_SHOULDER = 0x0200;
    static final int A = 0x1000, B = 0x2000, X = 0x4000, Y = 0x8000;
    // Pseudo-bits for the triggers (sent as 255 in the trigger values, not as buttons).
    static final int LEFT_TRIGGER = 0x10000, RIGHT_TRIGGER = 0x20000;

    interface Listener {
        void onPad(int buttons, int leftTrigger, int rightTrigger, int lx, int ly, int rx, int ry);
    }

    private static final class Button {
        final String label;
        final int bits;
        final float fx, fy, radiusDp;
        final int color;
        float cx, cy, radius;
        int pointer = -1;

        Button(String label, int bits, float fx, float fy, float radiusDp, int color) {
            this.label = label;
            this.bits = bits;
            this.fx = fx;
            this.fy = fy;
            this.radiusDp = radiusDp;
            this.color = color;
        }
    }

    private static final class Stick {
        int pointer = -1;
        float originX, originY, x, y;
        float defaultX, defaultY;
        float radius;
        boolean follow;

        float valueX() {
            return pointer < 0 ? 0 : clamp((x - originX) / radius);
        }

        float valueY() {
            return pointer < 0 ? 0 : clamp((originY - y) / radius);
        }
    }

    private final ArrayList<Button> buttons = new ArrayList<>();
    private final Stick leftStick = new Stick();
    private final Stick rightStick = new Stick();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    // The launcher's Tamaño de botones (0.85, 1, 1.2) and Vibración.
    private float scale = 1;
    private boolean haptics = true;
    private Listener listener;
    private int lastButtons = -1, lastLX, lastLY, lastRX, lastRY;
    // setStickRedrawLimit: stick moves redraw at most every this many ms (0 = on every move). Following
    // the finger at the panel's 120 Hz redrew the whole overlay, labels included, four times per
    // 30 fps game frame: ~11% of a core on the UI threads plus GPU time beside the game's (Gears of
    // War 3, POCO X6 Pro, 2026-11-03). Presses, releases and the pad state sent to the game are not
    // delayed.
    private long stickRedrawMs;
    private long lastRedraw;
    private boolean redrawPosted;
    private final Runnable delayedRedraw = new Runnable() {
        @Override
        public void run() {
            redrawPosted = false;
            lastRedraw = android.os.SystemClock.uptimeMillis();
            invalidate();
        }
    };

    public TouchControls(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2 * density);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT_BOLD);
        text.setColor(Color.argb(200, 255, 255, 255));

        int green = Color.rgb(80, 200, 90), red = Color.rgb(230, 70, 60);
        int blue = Color.rgb(70, 130, 240), yellow = Color.rgb(240, 200, 50);
        int gray = Color.rgb(200, 200, 200);
        // Positions as fractions of the view size, radii in dp.
        // Layout ergonomico estilo shooter movil con camara libre en mitad derecha
        buttons.add(new Button("A", A, 0.89f, 0.84f, 35, green));
        buttons.add(new Button("B", B, 0.78f, 0.85f, 30, red));
        buttons.add(new Button("X", X, 0.80f, 0.65f, 29, blue));
        buttons.add(new Button("Y", Y, 0.72f, 0.49f, 26, yellow));
        buttons.add(new Button("RB", RIGHT_SHOULDER, 0.93f, 0.62f, 28, gray));
        buttons.add(new Button("LT", LEFT_TRIGGER, 0.70f, 0.71f, 32, gray));
        buttons.add(new Button("RT", RIGHT_TRIGGER, 0.93f, 0.37f, 34, red));
        buttons.add(new Button("RT", RIGHT_TRIGGER, 0.08f, 0.22f, 34, red));
        buttons.add(new Button("R3", RIGHT_THUMB, 0.82f, 0.14f, 22, gray));
        buttons.add(new Button("LB", LEFT_SHOULDER, 0.20f, 0.14f, 24, gray));
        buttons.add(new Button("L3", LEFT_THUMB, 0.31f, 0.14f, 22, gray));
        buttons.add(new Button("BACK", BACK, 0.44f, 0.08f, 21, gray));
        buttons.add(new Button("START", START, 0.56f, 0.08f, 21, gray));
        buttons.add(new Button("▲", DPAD_UP, 0.06f, 0.42f, 19, gray));
        buttons.add(new Button("▼", DPAD_DOWN, 0.06f, 0.60f, 19, gray));
        buttons.add(new Button("◄", DPAD_LEFT, 0.025f, 0.51f, 19, gray));
        buttons.add(new Button("►", DPAD_RIGHT, 0.095f, 0.51f, 19, gray));
        rightStick.follow = true;
        editToggleX = width * 0.50f;
        editToggleY = height * 0.035f;
        editToggleR = 20 * density;
        loadCustomHud(width, height);
    }

    void loadCustomHud(int width, int height) {
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("gears3_hud", android.content.Context.MODE_PRIVATE);
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            float nx = prefs.getFloat("btn_x_" + i, -1f);
            float ny = prefs.getFloat("btn_y_" + i, -1f);
            if (nx >= 0f && ny >= 0f) {
                b.cx = nx * width;
                b.cy = ny * height;
            }
        }
    }

    void saveCustomHud() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        android.content.SharedPreferences.Editor ed = getContext().getSharedPreferences("gears3_hud", android.content.Context.MODE_PRIVATE).edit();
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            ed.putFloat("btn_x_" + i, b.cx / w);
            ed.putFloat("btn_y_" + i, b.cy / h);
        }
        ed.apply();
    }

    void resetCustomHud() {
        getContext().getSharedPreferences("gears3_hud", android.content.Context.MODE_PRIVATE).edit().clear().apply();
        onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setScale(float scale) {
        this.scale = scale;
        if (getWidth() > 0) {
            onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
            invalidate();
        }
    }

    void setHaptics(boolean haptics) {
        this.haptics = haptics;
    }

    void setStickRedrawLimit(long milliseconds) {
        stickRedrawMs = Math.max(0, milliseconds);
    }

    // At once for presses and releases; stick moves are spaced stickRedrawMs apart, the last one
    // always drawn.
    private void redraw(boolean now) {
        long time = android.os.SystemClock.uptimeMillis();
        if (now || stickRedrawMs == 0 || time - lastRedraw >= stickRedrawMs) {
            if (redrawPosted) {
                removeCallbacks(delayedRedraw);
                redrawPosted = false;
            }
            lastRedraw = time;
            invalidate();
        } else if (!redrawPosted) {
            redrawPosted = true;
            postDelayed(delayedRedraw, stickRedrawMs - (time - lastRedraw));
        }
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        for (Button button : buttons) {
            button.cx = button.fx * width;
            button.cy = button.fy * height;
            button.radius = button.radiusDp * density * scale;
        }
        leftStick.radius = 64 * density * scale;
        leftStick.defaultX = 0.18f * width;
        leftStick.defaultY = 0.77f * height;
        rightStick.radius = 32 * density * scale;
        rightStick.defaultX = 0.62f * width;
        rightStick.defaultY = 0.72f * height;
        text.setTextSize(13 * density * scale);
        editToggleX = width * 0.50f;
        editToggleY = height * 0.035f;
        editToggleR = 20 * density;
        loadCustomHud(width, height);
    }

    private static float clamp(float value) {
        return Math.max(-1, Math.min(1, value));
    }

    private Button hitButton(float x, float y) {
        Button best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Button button : buttons) {
            float dx = x - button.cx, dy = y - button.cy;
            float distance = dx * dx + dy * dy;
            float reach = button.radius * 1.3f;
            if (distance <= reach * reach && distance < bestDistance) {
                best = button;
                bestDistance = distance;
            }
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int act = event.getActionMasked();
        float tx = event.getX(), ty = event.getY();
        if (act == MotionEvent.ACTION_DOWN) {
            if (Math.hypot(tx - editToggleX, ty - editToggleY) <= editToggleR * 1.35f) {
                editDownTime = System.currentTimeMillis();
                return true;
            }
            if (editMode) {
                Button best = null;
                float bestD = Float.MAX_VALUE;
                for (Button b : buttons) {
                    float d = (float) Math.hypot(tx - b.cx, ty - b.cy);
                    if (d <= b.radius * 1.4f && d < bestD) { best = b; bestD = d; }
                }
                draggingBtn = best;
                return true;
            }
        } else if (act == MotionEvent.ACTION_MOVE && editMode && draggingBtn != null) {
            draggingBtn.cx = Math.max(draggingBtn.radius, Math.min(getWidth() - draggingBtn.radius, tx));
            draggingBtn.cy = Math.max(draggingBtn.radius, Math.min(getHeight() - draggingBtn.radius, ty));
            invalidate();
            return true;
        } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
            if (editDownTime > 0) {
                long held = System.currentTimeMillis() - editDownTime;
                editDownTime = 0;
                if (held >= 900) {
                    editMode = false;
                    resetCustomHud();
                } else {
                    editMode = !editMode;
                    if (!editMode) saveCustomHud();
                }
                invalidate();
                return true;
            }
            if (editMode) {
                draggingBtn = null;
                return true;
            }
        }
        if (editMode) return true;
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int pointer = event.getPointerId(index);
                float x = event.getX(index), y = event.getY(index);
                Button button = hitButton(x, y);
                if (button != null && button.pointer < 0) {
                    button.pointer = pointer;
                    if (haptics) {
                        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                } else {
                    Stick stick = x < getWidth() * 0.5f ? leftStick : rightStick;
                    if (stick.pointer < 0) {
                        stick.pointer = pointer;
                        stick.originX = stick.x = x;
                        stick.originY = stick.y = y;
                    }
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); ++i) {
                    int pointer = event.getPointerId(i);
                    for (Stick stick : new Stick[] {leftStick, rightStick}) {
                        if (stick.pointer != pointer) {
                            continue;
                        }
                        stick.x = event.getX(i);
                        stick.y = event.getY(i);
                        float dx = stick.x - stick.originX, dy = stick.y - stick.originY;
                        float length = (float) Math.sqrt(dx * dx + dy * dy);
                        if (stick.follow && length > stick.radius) {
                            // The centre follows the finger, so the stick stays at full tilt.
                            float excess = (length - stick.radius) / length;
                            stick.originX += dx * excess;
                            stick.originY += dy * excess;
                        }
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL: {
                for (int i = 0; i < event.getPointerCount(); ++i) {
                    if (action != MotionEvent.ACTION_CANCEL && i != index) {
                        continue;
                    }
                    int pointer = event.getPointerId(i);
                    for (Button button : buttons) {
                        if (button.pointer == pointer) {
                            button.pointer = -1;
                        }
                    }
                    if (leftStick.pointer == pointer) {
                        leftStick.pointer = -1;
                    }
                    if (rightStick.pointer == pointer) {
                        rightStick.pointer = -1;
                    }
                }
                break;
            }
            default:
                return true;
        }
        sendState(action != MotionEvent.ACTION_MOVE);
        return true;
    }

    private void sendState(boolean pressOrRelease) {
        int bits = 0;
        for (Button button : buttons) {
            if (button.pointer >= 0) {
                bits |= button.bits;
            }
        }
        int lx = Math.round(leftStick.valueX() * 32767), ly = Math.round(leftStick.valueY() * 32767);
        int rx = Math.round(rightStick.valueX() * 32767), ry = Math.round(rightStick.valueY() * 32767);
        boolean changed = bits != lastButtons || lx != lastLX || ly != lastLY || rx != lastRX
                || ry != lastRY;
        if (!changed) {
            return;
        }
        boolean buttonsChanged = bits != lastButtons;
        lastButtons = bits;
        lastLX = lx;
        lastLY = ly;
        lastRX = rx;
        lastRY = ry;
        if (listener != null) {
            listener.onPad(bits & 0xFFFF, (bits & LEFT_TRIGGER) != 0 ? 255 : 0,
                    (bits & RIGHT_TRIGGER) != 0 ? 255 : 0, lx, ly, rx, ry);
        }
        redraw(pressOrRelease || buttonsChanged);
    }

    private void drawStick(Canvas canvas, Stick stick) {
        float baseX = stick.pointer >= 0 ? stick.originX : stick.defaultX;
        float baseY = stick.pointer >= 0 ? stick.originY : stick.defaultY;
        fill.setColor(Color.argb(stick.pointer >= 0 ? 60 : 30, 255, 255, 255));
        canvas.drawCircle(baseX, baseY, stick.radius, fill);
        stroke.setColor(Color.argb(110, 255, 255, 255));
        canvas.drawCircle(baseX, baseY, stick.radius, stroke);
        float knobX = baseX + stick.valueX() * stick.radius;
        float knobY = baseY - stick.valueY() * stick.radius;
        fill.setColor(Color.argb(stick.pointer >= 0 ? 150 : 70, 255, 255, 255));
        canvas.drawCircle(knobX, knobY, stick.radius * 0.42f, fill);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawStick(canvas, leftStick);
        fill.setColor(editMode ? 0xCC00CC66 : 0x66222222);
        canvas.drawCircle(editToggleX, editToggleY, editToggleR, fill);
        stroke.setColor(editMode ? 0xFFFFFFFF : 0x99CCCCCC);
        canvas.drawCircle(editToggleX, editToggleY, editToggleR, stroke);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(9.5f * density);
        canvas.drawText(editMode ? "OK" : "HUD", editToggleX, editToggleY + 3.5f * density, text);

        // Camara libre con el dedo en mitad derecha (sin circulo estorboso)
        for (Button button : buttons) {
            boolean pressed = button.pointer >= 0;
            fill.setColor(Color.argb(pressed ? 170 : 55, Color.red(button.color),
                    Color.green(button.color), Color.blue(button.color)));
            canvas.drawCircle(button.cx, button.cy, button.radius, fill);
            stroke.setColor(Color.argb(pressed ? 230 : 120, 255, 255, 255));
            canvas.drawCircle(button.cx, button.cy, button.radius, stroke);
            float size = (button.label.length() > 2 ? 9 : 13) * density * scale;
            text.setTextSize(size);
            canvas.drawText(button.label, button.cx, button.cy + size * 0.35f, text);
        }
    }
}
