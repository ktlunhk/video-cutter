package com.videocutter;

import android.app.AlertDialog;
import android.widget.Button;
import android.widget.TextView;

/** Gives every popup the same smaller text sizes. */
public final class DialogStyler {

    public static final float TITLE_SP = 16f;
    public static final float MESSAGE_SP = 13f;
    public static final float BUTTON_SP = 13f;

    private DialogStyler() {
    }

    public static AlertDialog shrink(AlertDialog dialog) {
        if (dialog == null) return null;
        try {
            int titleId = dialog.getContext().getResources()
                    .getIdentifier("alertTitle", "id", "android");
            TextView title = titleId != 0 ? (TextView) dialog.findViewById(titleId) : null;
            if (title != null) title.setTextSize(TITLE_SP);
            TextView message = (TextView) dialog.findViewById(android.R.id.message);
            if (message != null) message.setTextSize(MESSAGE_SP);
            int[] ids = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE,
                    AlertDialog.BUTTON_NEUTRAL};
            for (int id : ids) {
                Button b = dialog.getButton(id);
                if (b != null) b.setTextSize(BUTTON_SP);
            }
        } catch (Exception ignored) {
        }
        return dialog;
    }
}
