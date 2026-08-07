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
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.LocaleController;
import org.telegram.ui.ActionBar.Theme;

@SuppressLint("ViewConstructor")
public class FilterHiddenView extends FrameLayout {
    private MessageObject messageObject;
    private boolean expanded = false;

    private final TextView hintView;
    private final LinearLayout expandContainer;
    private final TextView previewView;
    private final TextView collapseBtn;

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

        expandContainer = new LinearLayout(context);
        expandContainer.setOrientation(LinearLayout.VERTICAL);
        expandContainer.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(4));
        FrameLayout.LayoutParams el = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        el.gravity = Gravity.CENTER;
        expandContainer.setVisibility(GONE);
        addView(expandContainer, el);

        previewView = new TextView(context);
        previewView.setTextSize(14);
        previewView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        previewView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        previewView.setAutoLinkMask(0);
        previewView.setLinksClickable(false);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        expandContainer.addView(previewView, pl);

        collapseBtn = new TextView(context);
        collapseBtn.setText(LocaleController.getString(R.string.FilterHiddenCollapse));
        collapseBtn.setTextSize(13);
        collapseBtn.setGravity(Gravity.CENTER);
        collapseBtn.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        collapseBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.gravity = Gravity.CENTER_HORIZONTAL;
        bl.topMargin = AndroidUtilities.dp(4);
        expandContainer.addView(collapseBtn, bl);
    }

    public MessageObject getMessageObject() {
        return messageObject;
    }

    public void setMessageObject(MessageObject messageObject) {
        this.messageObject = messageObject;
    }

    public boolean isExpanded() {
        return expanded;
    }

    /** Collapsed state: a single centered hint line ("hidden by filter, tap to show"). */
    public void setCollapsedHint(CharSequence text) {
        expanded = false;
        hintView.setText(text);
        hintView.setVisibility(VISIBLE);
        expandContainer.setVisibility(GONE);
        setMinimumHeight(AndroidUtilities.dp(44));
    }

    /** Expanded state: shows the original message text (with matched fragments highlighted)
     *  plus a "收起" button to collapse it back into a placeholder. */
    public void setExpandedContent(CharSequence content) {
        expanded = true;
        hintView.setVisibility(GONE);
        previewView.setText(content);
        expandContainer.setVisibility(VISIBLE);
        setMinimumHeight(0);
    }
}
