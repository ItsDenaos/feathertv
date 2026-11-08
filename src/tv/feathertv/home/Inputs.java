package tv.feathertv.home;

import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The TV's own inputs (HDMI, composite...). On Android TV the stock home screen is the usual way to
 * reach them, so FeatherTV lists them when it replaces it. Devices without inputs, such as a Fire TV
 * Stick, have an empty list and the button stays hidden.
 */
final class Inputs {
    /** One input; {@code device} is what HDMI-CEC reports behind the port (a console, a player). */
    static final class Input {
        final String id;
        final String label;
        final String device;

        Input(String id, String label, String device) {
            this.id = id;
            this.label = label;
            this.device = device;
        }
    }

    private Inputs() { }

    /** Runs off the main thread: the labels come from another app's resources. */
    static List<Input> load(Context c) {
        List<Input> out = new ArrayList<>();
        TvInputManager tim = manager(c);
        if (tim == null) return out;
        List<TvInputInfo> all;
        try {
            all = tim.getTvInputList();
        } catch (RuntimeException e) {
            return out;
        }
        // A device connected by HDMI-CEC appears as an input of its own whose parent is the port.
        Map<String, String> devices = new HashMap<>();
        for (TvInputInfo info : all) {
            if (info.getParentId() != null) devices.put(info.getParentId(), label(c, info));
        }
        final Map<String, Integer> types = new HashMap<>();
        for (TvInputInfo info : all) {
            if (!info.isPassthroughInput() || info.getParentId() != null) continue;
            if (Build.VERSION.SDK_INT >= 24 && info.isHidden(c)) continue;
            String label = label(c, info);
            if (label.isEmpty()) continue;
            out.add(new Input(info.getId(), label, devices.get(info.getId())));
            types.put(info.getId(), info.getType());
        }
        // HDMI first (the inputs people use), then the rest; within a type, by name (HDMI 1, 2, 3).
        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> {
            boolean ha = types.get(a.id) == TvInputInfo.TYPE_HDMI, hb = types.get(b.id) == TvInputInfo.TYPE_HDMI;
            if (ha != hb) return ha ? -1 : 1;
            int t = Integer.compare(types.get(a.id), types.get(b.id));
            return t != 0 ? t : collator.compare(a.label, b.label);
        });
        return out;
    }

    /** False when nothing is plugged into the input (only known from Android 7). */
    static boolean isConnected(Context c, Input in) {
        if (Build.VERSION.SDK_INT < 24) return true;
        TvInputManager tim = manager(c);
        try {
            return tim == null || tim.getInputState(in.id) != TvInputManager.INPUT_STATE_DISCONNECTED;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Switches the TV to the input: the system's TV app shows it full screen. */
    static Intent intent(Input in) {
        return new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(in.id))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static TvInputManager manager(Context c) {
        try {
            return (TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The name the user gave the input in the TV's settings, or the TV's own. */
    private static String label(Context c, TvInputInfo info) {
        CharSequence l = null;
        try {
            if (Build.VERSION.SDK_INT >= 24) l = info.loadCustomLabel(c);
            if (TextUtils.isEmpty(l)) l = info.loadLabel(c);
        } catch (RuntimeException e) {
            // The input's app is being updated: no name this time.
        }
        return l == null ? "" : l.toString().trim();
    }
}
