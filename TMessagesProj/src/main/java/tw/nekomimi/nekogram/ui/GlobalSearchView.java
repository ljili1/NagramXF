package tw.nekomimi.nekogram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.BlurredFrameLayout;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.FireworksEffect;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.SnowflakesEffect;

import tw.nekomimi.nekogram.NekoConfig;
import xyz.nextalone.nagram.NaConfig;

@SuppressLint("ViewConstructor")
public class GlobalSearchView extends BlurredFrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private TextView searchTextView;
    private BlurredFrameLayout searchFrame;
    private ColoredImageSpan searchIconSpan;



    public GlobalSearchView(Context context, SizeNotifierFrameLayout sizeNotifierFrameLayout) {
        super(context, sizeNotifierFrameLayout);

        setWillNotDraw(false);

        searchFrame = new BlurredFrameLayout(context, sizeNotifierFrameLayout);
        searchFrame.isTopView = false;
        searchTextView = new TextView(context);
        searchTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        searchTextView.setEllipsize(TextUtils.TruncateAt.MIDDLE);

        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder();
        searchIconSpan = new ColoredImageSpan(ContextCompat.getDrawable(context, R.drawable.ic_ab_search));
        searchIconSpan.setOverrideColor(0xFFFFFFFF); 
        spannableStringBuilder.append("..").setSpan(searchIconSpan, 0, 1, 0);
        spannableStringBuilder.setSpan(new DialogCell.FixedWidthSpan(AndroidUtilities.dp(4)), 1, 2, 0);
        spannableStringBuilder.append(getString(R.string.Search));

        searchTextView.setText(spannableStringBuilder);
        searchTextView.setGravity(Gravity.CENTER);
        searchTextView.setSingleLine(true);

        searchFrame.addView(searchTextView);
        addView(searchFrame, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 38, 0, dp(3), 0, dp(3), 0));

        updateColors();
    }

    public void updateColors() {
        int backgroundColor;

        if (Theme.getActiveTheme().isMonet()) {
            int monetAccentColor = Theme.getColor(Theme.key_chats_unreadCounter);
            int baseColor = Theme.isCurrentThemeDay() ? 0xFFFFFFFF : 0xFF2A2A2A;
            backgroundColor = ColorUtils.blendARGB(monetAccentColor, baseColor, 0.78f); 
            backgroundColor = ColorUtils.setAlphaComponent(backgroundColor, (int) (255 * 0.7f));

            searchTextView.setTextColor(monetAccentColor);
            searchIconSpan.setOverrideColor(monetAccentColor);
        } else {
            int themeColor = Theme.getColor(Theme.key_actionBarDefault);

            if (Theme.getActiveTheme().hasAccentColors()) {
                int accentColor = Theme.getActiveTheme().getAccentColor(Theme.getActiveTheme().currentAccentId);
                if (accentColor != 0) {
                    themeColor = accentColor;
                }
            }

            if (!Theme.isCurrentThemeDay()) {
                int darkColor = 0xFF1A1A1A; 
                backgroundColor = ColorUtils.blendARGB(darkColor, themeColor, 0.13f); 
                backgroundColor = ColorUtils.setAlphaComponent(backgroundColor, (int) (255 * 0.9f));
            } else {
                int grayColor = 0xFFB0B0B0; 
                int themeGrayBlend = ColorUtils.blendARGB(themeColor, grayColor, 0.40f);
                int blendedColor = ColorUtils.blendARGB(themeGrayBlend, 0xFFFFFFFF, 0.25f);
                backgroundColor = ColorUtils.setAlphaComponent(blendedColor, (int) (255 * 0.6f));
            }

            if (!Theme.isCurrentThemeDay()) {
                searchTextView.setTextColor(0xFFB0B0B0);
                searchIconSpan.setOverrideColor(0xFFB0B0B0);
            } else {
                searchTextView.setTextColor(0xFFFFFFFF);
                searchIconSpan.setOverrideColor(0xFFFFFFFF);
            }
        }

        Drawable background = Theme.createSimpleSelectorRoundRectDrawable(
                dp(10),
                backgroundColor,
                ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_listSelector), (int) (255 * 0.3f))
        );
        searchFrame.setBackground(background);


    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            updateColors();
            if (searchFrame != null) searchFrame.invalidate();
            if (searchTextView != null) searchTextView.invalidate();
            invalidate();
        }
    }
    public BlurredFrameLayout getSearchFrame() {
        return searchFrame;
    }

    public TextView getSearchTextView() {
        return searchTextView;
    }

    public static void saveFoldersExistence() {
        MessagesController messagesController = MessagesController.getInstance(UserConfig.selectedAccount);
        messagesController.getMainSettings()
                .edit()
                .putBoolean("user_has_folders_for_search", messagesController.getDialogFilters() != null && messagesController.getDialogFilters().size() > 1)
                .apply();
    }

    public boolean getFoldersExistence() {
        return MessagesController.getMainSettings(UserConfig.selectedAccount)
                .getBoolean("user_has_folders_for_search", NaConfig.INSTANCE.getIosSearchPanel().Bool());
    }
}
