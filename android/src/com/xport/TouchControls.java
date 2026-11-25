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
    Button selectedEditBtn = null;
    int weaponIndex = 0;
    static final String[] WEAPON_NAMES = {"LANCER", "GNASHER", "PISTOLA", "GRANADA"};
    final int[] WEAPON_BITS = {DPAD_UP, DPAD_LEFT, DPAD_DOWN, DPAD_RIGHT};
    float weaponSwipeStartY = 0f;
    boolean weaponSwiped = false;
    int pulseWeaponBit = 0;
    long pulseWeaponUntil = 0L;
    long weaponNeonStart = 0L;
    final android.graphics.Path weaponPath = new android.graphics.Path();
    final android.graphics.Paint neonPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
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
        String label;
        int bits;
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
        buttons.add(new Button("A", A, 0.88f, 0.82f, 38, green));
        buttons.add(new Button("B", B, 0.77f, 0.85f, 30, red));
        buttons.add(new Button("X", X, 0.79f, 0.66f, 29, blue));
        buttons.add(new Button("Y", Y, 0.71f, 0.51f, 25, yellow));
        buttons.add(new Button("LT", LEFT_TRIGGER, 0.88f, 0.57f, 34, gray));
        buttons.add(new Button("RT", RIGHT_TRIGGER, 0.76f, 0.43f, 33, red));
        buttons.add(new Button("RT", RIGHT_TRIGGER, 0.09f, 0.24f, 35, red));
        buttons.add(new Button("RB", RIGHT_SHOULDER, 0.93f, 0.36f, 26, gray));
        buttons.add(new Button("LB", LEFT_SHOULDER, 0.20f, 0.14f, 24, gray));
        buttons.add(new Button("R3", RIGHT_THUMB, 0.82f, 0.14f, 22, gray));
        buttons.add(new Button("L3", LEFT_THUMB, 0.31f, 0.14f, 22, gray));
        buttons.add(new Button("BACK", BACK, 0.44f, 0.08f, 20, gray));
        buttons.add(new Button("START", START, 0.56f, 0.08f, 20, gray));
        buttons.add(new Button("LANCER", DPAD_UP, 0.54f, 0.88f, 36, gray));
        rightStick.follow = true;
        editToggleX = getWidth() * 0.50f;
        editToggleY = getHeight() * 0.035f;
        editToggleR = 20 * density;
        loadCustomHud(getWidth(), getHeight());
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
            ed.putFloat("btn_r_" + i, b.radius);
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
        editToggleX = getWidth() * 0.50f;
        editToggleY = getHeight() * 0.035f;
        editToggleR = 20 * density;
        loadCustomHud(getWidth(), getHeight());
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
                float resetX = editToggleX + editToggleR * 2.8f;
                float minusX = editToggleX + editToggleR * 5.6f;
                float plusX  = editToggleX + editToggleR * 8.1f;
                if (Math.hypot(tx - resetX, ty - editToggleY) <= editToggleR * 1.35f) {
                    resetCustomHud(); selectedEditBtn = null; editMode = false; invalidate(); return true;
                }
                if (selectedEditBtn != null && Math.hypot(tx - minusX, ty - editToggleY) <= editToggleR * 1.25f) {
                    selectedEditBtn.radius = Math.max(14f * density, selectedEditBtn.radius - 3f * density); invalidate(); return true;
                }
                if (selectedEditBtn != null && Math.hypot(tx - plusX, ty - editToggleY) <= editToggleR * 1.25f) {
                    selectedEditBtn.radius = Math.min(72f * density, selectedEditBtn.radius + 3f * density); invalidate(); return true;
                }
                Button best = null; float bestD = Float.MAX_VALUE;
                for (Button b : buttons) {
                    float d = (float) Math.hypot(tx - b.cx, ty - b.cy);
                    if (d <= b.radius * 1.4f && d < bestD) { best = b; bestD = d; }
                }
                draggingBtn = best;
                if (best != null) selectedEditBtn = best;
                invalidate();
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
                    if (((button.bits & RIGHT_TRIGGER) != 0 || (button.bits & LEFT_TRIGGER) != 0) && rightStick.pointer < 0) {
                        rightStick.pointer = pointer;
                        rightStick.originX = rightStick.x = x;
                        rightStick.originY = rightStick.y = y;
                    }
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
                    for (Button b : buttons) {
                        if (b.pointer == pointer && isWeaponBtn(b)) {
                            float dyW = event.getY(i) - weaponSwipeStartY;
                            if (Math.abs(dyW) > 16f * density) {
                                weaponIndex = (weaponIndex + (dyW < 0 ? 1 : -1) + 4) % 4;
                                b.label = WEAPON_NAMES[weaponIndex]; b.bits = WEAPON_BITS[weaponIndex];
                                pulseWeaponBit = b.bits; long nowMs = android.os.SystemClock.uptimeMillis();
                                pulseWeaponUntil = nowMs + 120L; weaponNeonStart = nowMs;
                                weaponSwipeStartY = event.getY(i); weaponSwiped = true;
                                if (haptics) performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                            }
                        }
                    }
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
                        if (!weaponSwiped && isWeaponBtn(button)) {
                            weaponIndex = (weaponIndex == 0) ? 1 : 0;
                            button.label = WEAPON_NAMES[weaponIndex]; button.bits = WEAPON_BITS[weaponIndex];
                            pulseWeaponBit = button.bits; long nowMs = android.os.SystemClock.uptimeMillis();
                            pulseWeaponUntil = nowMs + 120L; weaponNeonStart = nowMs;
                        }
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
        if (pulseWeaponBit != 0 && android.os.SystemClock.uptimeMillis() < pulseWeaponUntil) bits |= pulseWeaponBit;
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
        boolean active = stick.pointer >= 0;
        float baseX = active ? stick.originX : stick.defaultX;
        float baseY = active ? stick.originY : stick.defaultY;
        float r = stick.radius;

        fill.setColor(Color.argb(active ? 50 : 24, 18, 22, 26));
        canvas.drawCircle(baseX, baseY, r, fill);

        float prevStroke = stroke.getStrokeWidth();
        stroke.setStrokeWidth(1.5f * density);
        stroke.setColor(Color.argb(active ? 195 : 105, 232, 236, 240));
        canvas.drawCircle(baseX, baseY, r, stroke);

        stroke.setStrokeWidth(0.9f * density);
        stroke.setColor(Color.argb(active ? 120 : 55, 210, 218, 226));
        canvas.drawCircle(baseX, baseY, r * 0.72f, stroke);

        float tick = r * 0.14f;
        canvas.drawLine(baseX - r, baseY, baseX - r + tick, baseY, stroke);
        canvas.drawLine(baseX + r - tick, baseY, baseX + r, baseY, stroke);
        canvas.drawLine(baseX, baseY - r, baseX, baseY - r + tick, stroke);
        canvas.drawLine(baseX, baseY + r - tick, baseX, baseY + r, stroke);

        float knobX = baseX + stick.valueX() * r;
        float knobY = baseY - stick.valueY() * r;
        float kr = r * 0.38f;
        fill.setColor(Color.argb(active ? 135 : 62, 238, 242, 246));
        canvas.drawCircle(knobX, knobY, kr, fill);
        stroke.setStrokeWidth(1.4f * density);
        stroke.setColor(Color.argb(active ? 235 : 130, 255, 255, 255));
        canvas.drawCircle(knobX, knobY, kr, stroke);
        stroke.setStrokeWidth(prevStroke);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // invisible LS
        float prevStroke = stroke.getStrokeWidth();

        // Boton HUD / OK cristal tactico
        float pillW = editToggleR * 1.45f;
        float pillH = editToggleR * 0.85f;
        float pillRad = 8f * density;
        fill.setColor(editMode ? 0x88334155 : 0x5512161C);
        canvas.drawRoundRect(editToggleX - pillW, editToggleY - pillH, editToggleX + pillW, editToggleY + pillH, pillRad, pillRad, fill);
        stroke.setStrokeWidth(1.4f * density);
        stroke.setColor(editMode ? 0xFFFFFFFF : 0x99CBD5E1);
        canvas.drawRoundRect(editToggleX - pillW, editToggleY - pillH, editToggleX + pillW, editToggleY + pillH, pillRad, pillRad, stroke);
        text.setColor(0xFFFFFFFF);
        text.setFakeBoldText(true);
        text.setTextSize(9.5f * density);
        canvas.drawText(editMode ? "OK" : "HUD", editToggleX, editToggleY + 3.5f * density, text);

        // Boton RESET al lado de OK en modo edicion
        if (editMode) {
            float resetX = editToggleX + editToggleR * 2.8f;
            fill.setColor(0x881E293B);
            canvas.drawRoundRect(resetX - pillW, editToggleY - pillH, resetX + pillW, editToggleY + pillH, pillRad, pillRad, fill);
            stroke.setColor(0xE6F8FAFC);
            canvas.drawRoundRect(resetX - pillW, editToggleY - pillH, resetX + pillW, editToggleY + pillH, pillRad, pillRad, stroke);
            text.setTextSize(8.2f * density);
            canvas.drawText("RESET", resetX, editToggleY + 3.0f * density, text);
            if (selectedEditBtn != null) {
                float minusX = editToggleX + editToggleR * 5.6f, plusX = editToggleX + editToggleR * 8.1f, btnW = editToggleR * 1.15f;
                fill.setColor(0x881E293B); stroke.setColor(0xE6F8FAFC); text.setTextSize(12f * density);
                canvas.drawRoundRect(minusX - btnW, editToggleY - pillH, minusX + btnW, editToggleY + pillH, pillRad, pillRad, fill);
                canvas.drawRoundRect(minusX - btnW, editToggleY - pillH, minusX + btnW, editToggleY + pillH, pillRad, pillRad, stroke);
                canvas.drawText("-", minusX, editToggleY + 4.0f * density, text);
                canvas.drawRoundRect(plusX - btnW, editToggleY - pillH, plusX + btnW, editToggleY + pillH, pillRad, pillRad, fill);
                canvas.drawRoundRect(plusX - btnW, editToggleY - pillH, plusX + btnW, editToggleY + pillH, pillRad, pillRad, stroke);
                canvas.drawText("+", plusX, editToggleY + 4.0f * density, text);
                stroke.setColor(0xFF38BDF8);
                canvas.drawCircle(selectedEditBtn.cx, selectedEditBtn.cy, selectedEditBtn.radius + 4f * density, stroke);
            }
        }

        // Botones cristal tactico transparente monocromatico
        for (Button button : buttons) {
            boolean pressed = button.pointer >= 0;
            float r = button.radius * (pressed ? 0.93f : 1.0f);
            boolean isShoulderOrMenu = button.label.length() >= 2; // LT, LB, RT, RB, START, BACK

            if (isShoulderOrMenu) {
                float hw = r * 1.18f;
                float hh = r * 0.78f;
                float corner = 9f * density;

                fill.setColor(Color.argb(pressed ? 125 : 42, 18, 22, 28));
                canvas.drawRoundRect(button.cx - hw, button.cy - hh, button.cx + hw, button.cy + hh, corner, corner, fill);

                stroke.setStrokeWidth((pressed ? 2.0f : 1.4f) * density);
                stroke.setColor(Color.argb(pressed ? 245 : 155, 232, 236, 240));
                canvas.drawRoundRect(button.cx - hw, button.cy - hh, button.cx + hw, button.cy + hh, corner, corner, stroke);

                float inset = 3.2f * density;
                stroke.setStrokeWidth(0.8f * density);
                stroke.setColor(Color.argb(pressed ? 170 : 65, 210, 218, 226));
                canvas.drawRoundRect(button.cx - hw + inset, button.cy - hh + inset, button.cx + hw - inset, button.cy + hh - inset, corner * 0.75f, corner * 0.75f, stroke);

                float tLen = 4f * density;
                stroke.setStrokeWidth(1.2f * density);
                stroke.setColor(Color.argb(pressed ? 235 : 130, 232, 236, 240));
                canvas.drawLine(button.cx - hw - 2f * density, button.cy, button.cx - hw + tLen, button.cy, stroke);
                canvas.drawLine(button.cx + hw - tLen, button.cy, button.cx + hw + 2f * density, button.cy, stroke);
            } else {
                // Circulares de doble aro transparente para A, B, X, Y
                fill.setColor(Color.argb(pressed ? 125 : 42, 18, 22, 28));
                canvas.drawCircle(button.cx, button.cy, r, fill);

                stroke.setStrokeWidth((pressed ? 2.1f : 1.4f) * density);
                stroke.setColor(Color.argb(pressed ? 245 : 155, 232, 236, 240));
                canvas.drawCircle(button.cx, button.cy, r, stroke);

                stroke.setStrokeWidth(0.85f * density);
                stroke.setColor(Color.argb(pressed ? 175 : 70, 210, 218, 226));
                canvas.drawCircle(button.cx, button.cy, r * 0.80f, stroke);

                float tLen = r * 0.15f;
                stroke.setStrokeWidth(1.2f * density);
                stroke.setColor(Color.argb(pressed ? 235 : 130, 232, 236, 240));
                canvas.drawLine(button.cx - r - 2f * density, button.cy, button.cx - r + tLen, button.cy, stroke);
                canvas.drawLine(button.cx + r - tLen, button.cy, button.cx + r + 2f * density, button.cy, stroke);
                canvas.drawLine(button.cx, button.cy - r - 2f * density, button.cx, button.cy - r + tLen, stroke);
                canvas.drawLine(button.cx, button.cy + r - tLen, button.cx, button.cy + r + 2f * density, stroke);
            }

            if (isWeaponBtn(button)) { drawGearsWeaponIcon(canvas, button, r, pressed); } else {
            float size = (button.label.length() > 2 ? 9.5f : 13.5f) * density * scale;
            text.setTextSize(size);
            text.setColor(Color.argb(pressed ? 255 : 225, 244, 247, 250));
            canvas.drawText(button.label, button.cx, button.cy + size * 0.35f, text);
            }
        }
        stroke.setStrokeWidth(prevStroke);
    }

    private boolean isWeaponBtn(Button b) {
        return "LANCER".equals(b.label) || "GNASHER".equals(b.label) || "PISTOLA".equals(b.label) || "GRANADA".equals(b.label);
    }
    private void drawGearsWeaponIcon(Canvas canvas, Button b, float r, boolean pressed) {
        float cx = b.cx, cy = b.cy - r * 0.14f;
        int alpha = pressed ? 255 : 235;
        weaponPath.reset();
        if ("LANCER".equals(b.label)) {
            weaponPath.moveTo(cx - r * 0.85f, cy + r * 0.12f); weaponPath.lineTo(cx - r * 0.55f, cy - r * 0.12f);
            weaponPath.lineTo(cx + r * 0.78f, cy - r * 0.12f); weaponPath.lineTo(cx + r * 0.78f, cy + r * 0.02f);
            weaponPath.lineTo(cx + r * 0.58f, cy + r * 0.02f); weaponPath.lineTo(cx + r * 0.52f, cy + r * 0.24f);
            weaponPath.lineTo(cx - r * 0.05f, cy + r * 0.24f); weaponPath.lineTo(cx - r * 0.12f, cy + r * 0.08f);
            weaponPath.lineTo(cx - r * 0.32f, cy + r * 0.26f); weaponPath.lineTo(cx - r * 0.44f, cy + r * 0.26f);
            weaponPath.lineTo(cx - r * 0.36f, cy + r * 0.06f); weaponPath.lineTo(cx - r * 0.85f, cy + r * 0.22f);
            weaponPath.close();
        } else if ("GNASHER".equals(b.label)) {
            weaponPath.moveTo(cx - r * 0.78f, cy + r * 0.18f); weaponPath.lineTo(cx - r * 0.42f, cy - r * 0.10f);
            weaponPath.lineTo(cx + r * 0.75f, cy - r * 0.10f); weaponPath.lineTo(cx + r * 0.75f, cy + r * 0.04f);
            weaponPath.lineTo(cx + r * 0.22f, cy + r * 0.04f); weaponPath.lineTo(cx + r * 0.22f, cy + r * 0.14f);
            weaponPath.lineTo(cx - r * 0.28f, cy + r * 0.14f); weaponPath.lineTo(cx - r * 0.65f, cy + r * 0.28f);
            weaponPath.close();
        } else if ("PISTOLA".equals(b.label)) {
            weaponPath.moveTo(cx - r * 0.38f, cy + r * 0.28f); weaponPath.lineTo(cx - r * 0.26f, cy - r * 0.14f);
            weaponPath.lineTo(cx + r * 0.45f, cy - r * 0.14f); weaponPath.lineTo(cx + r * 0.45f, cy + r * 0.04f);
            weaponPath.lineTo(cx - r * 0.04f, cy + r * 0.04f); weaponPath.lineTo(cx - r * 0.14f, cy + r * 0.28f);
            weaponPath.close();
        } else {
            weaponPath.addOval(cx - r * 0.24f, cy - r * 0.14f, cx + r * 0.24f, cy + r * 0.28f, android.graphics.Path.Direction.CW);
            weaponPath.addRect(cx - r * 0.10f, cy - r * 0.26f, cx + r * 0.10f, cy - r * 0.14f, android.graphics.Path.Direction.CW);
        }
        fill.setColor(Color.argb(alpha, 244, 247, 250));
        canvas.drawPath(weaponPath, fill);
        text.setColor(Color.argb(alpha, 244, 247, 250));
        text.setTextSize(7.5f * density * scale);
        canvas.drawText("▲ " + b.label + " ▼", b.cx, b.cy + r * 0.56f, text);
    }

}
