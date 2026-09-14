package com.exteragram.messenger.utils.ui;

import org.telegram.messenger.AndroidUtilities;

public class TextPaint extends android.text.TextPaint {
    public TextPaint(int flags) {
        super(flags);
        setTypeface(AndroidUtilities.regular());
    }
}
