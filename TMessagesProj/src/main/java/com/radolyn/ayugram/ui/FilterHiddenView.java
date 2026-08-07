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
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.Theme;

@SuppressLint("ViewConstructor")
public class FilterHiddenView extends FrameLayout {
    private MessageObject messageObject;
    private final TextView textView;

    public FilterHiddenView(Context context) {
        super(context);
        setMinimumHeight(AndroidUtilities.dp(44));
        int pad = AndroidUtilities.dp(8);
        setPadding(pad, pad, pad, pad);
        setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        textView = new TextView(context);
        textView.setGravity(Gravity.CENTER);
        textView.setTextSize(14);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        textView.setText(LocaleController.getString(R.string.FilterHiddenHint));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(textView, lp);
    }

    public MessageObject getMessageObject() {
        return messageObject;
    }

    public void setMessageObject(MessageObject messageObject) {
        this.messageObject = messageObject;
    }
}
