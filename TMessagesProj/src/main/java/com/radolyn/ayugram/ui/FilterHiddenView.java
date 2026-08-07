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
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
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
        // Ensure this view can receive click/touch events even when nested inside
        // Telegram's RecyclerListView which has aggressive touch interception
        // (long-press menus, swipe-to-reply, cell selection, etc.).
        setClickable(true);
        setFocusable(true);

        textView = new TextView(context);
        textView.setGravity(Gravity.CENTER);
        textView.setTextSize(14);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        addView(textView, lp);
    }

    /**
     * Consume ACTION_DOWN so that the parent RecyclerListView does not intercept the
     * touch gesture (for scrolling / long-press / swipe). This ensures our onClickListener
     * fires reliably.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            // Tell parent not to intercept — we want the click.
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Mark as handled so the event reaches our onClickListener.
        super.onTouchEvent(event);
        return true;
    }

    public MessageObject getMessageObject() {
        return messageObject;
    }

    public void setMessageObject(MessageObject messageObject) {
        this.messageObject = messageObject;
    }

    /** Sets the placeholder hint text shown inside this view. */
    public void setPlaceholderText(CharSequence text) {
        textView.setText(text);
    }
}
