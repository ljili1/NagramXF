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
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.Theme;

@SuppressLint("ViewConstructor")
public class FilterHiddenView extends FrameLayout {
    private MessageObject messageObject;

    private final TextView hintView;

    public FilterHiddenView(Context context) {
        super(context);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        hintView = new TextView(context);
        hintView.setGravity(Gravity.CENTER);
        hintView.setTextSize(14);
        hintView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        hl.gravity = Gravity.CENTER;
        hintView.setMinHeight(AndroidUtilities.dp(44));
        hintView.setGravity(Gravity.CENTER);
        hintView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12));
        addView(hintView, hl);
    }

    public MessageObject getMessageObject() {
        return messageObject;
    }

    public void setMessageObject(MessageObject messageObject) {
        this.messageObject = messageObject;
    }

    /** Set the collapsed hint text ("hidden by filter, tap to show" or merged variant). */
    public void setCollapsedHint(CharSequence text) {
        hintView.setText(text);
    }
}
