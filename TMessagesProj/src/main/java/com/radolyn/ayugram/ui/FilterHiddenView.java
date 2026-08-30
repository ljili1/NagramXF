/*
 * This is the source code of AyuGram for Android.
 *
 * We do not and cannot prevent the use of our code,
 * but be respectful and credit the original author.
 *
 * Copyright @Radolyn, 2023
 */

package com.radolyn.ayugram.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

/**
 * Placeholder row shown in place of a message that the regex filter hid.
 *
 * <p>Instead of the message silently disappearing (the original behaviour, which made it
 * impossible to tell that anything had been filtered), the row renders a themed rounded
 * "pill" with an eye-off icon and a hint such as "Hidden by filter. Tap to show." Tapping
 * reveals the message; long-pressing explains which rule matched.
 */
@SuppressLint("ViewConstructor")
public class FilterHiddenView extends FrameLayout {
    private MessageObject messageObject;

    private final LinearLayout pill;
    private final ImageView iconView;
    private final TextView hintView;

    public FilterHiddenView(Context context) {
        super(context);

        // The pill is inset from both edges so a run of merged placeholders reads as a
        // distinct band rather than as a full-width message bubble.
        pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        pill.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(16), Theme.getColor(Theme.key_graySection)));
        pill.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(9),
                AndroidUtilities.dp(14), AndroidUtilities.dp(9));

        iconView = new ImageView(context);
        iconView.setImageResource(R.drawable.ayu_eye_crossed);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iconView.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), PorterDuff.Mode.MULTIPLY));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                AndroidUtilities.dp(18), AndroidUtilities.dp(18));
        iconLp.setMarginEnd(AndroidUtilities.dp(8));
        pill.addView(iconView, iconLp);

        hintView = new TextView(context);
        hintView.setGravity(Gravity.CENTER);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        hintView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        // Merged runs can produce a long hint (and the reason dialog previews matched text),
        // so cap the height and ellipsize instead of letting the row grow unbounded.
        hintView.setMaxLines(2);
        hintView.setEllipsize(TextUtils.TruncateAt.END);
        pill.addView(hintView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams pillLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = Gravity.CENTER_VERTICAL;
        pillLp.leftMargin = AndroidUtilities.dp(24);
        pillLp.rightMargin = AndroidUtilities.dp(24);
        pillLp.topMargin = AndroidUtilities.dp(3);
        pillLp.bottomMargin = AndroidUtilities.dp(3);
        addView(pill, pillLp);

        setMinimumHeight(AndroidUtilities.dp(44));
    }

    public MessageObject getMessageObject() {
        return messageObject;
    }

    public void setMessageObject(MessageObject messageObject) {
        this.messageObject = messageObject;
    }

    /** Set the collapsed hint text ("hidden by filter, tap to show" or the merged variant). */
    public void setCollapsedHint(CharSequence text) {
        hintView.setText(text);
        // An empty hint means this row is a merged run follower and was hidden by the adapter;
        // dropping the icon too keeps it from flashing before the visibility change lands.
        iconView.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
    }
}
