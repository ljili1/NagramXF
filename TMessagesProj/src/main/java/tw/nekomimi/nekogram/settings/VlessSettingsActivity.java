package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.VlessProxyManager;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;

public class VlessSettingsActivity extends BaseNekoSettingsActivity {

    private int settingsRow;
    private int linkRow;
    private int enableRow;
    private int descriptionRow;
    private final SharedConfig.ProxyInfo currentProxyInfo;

    public VlessSettingsActivity(SharedConfig.ProxyInfo proxyInfo) {
        super();
        currentProxyInfo = proxyInfo;
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == enableRow) {
            boolean enabled = !VlessProxyManager.isEnabled();
            VlessProxyManager.setEnabled(enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            listAdapter.notifyItemChanged(linkRow, PARTIAL);
            listAdapter.notifyItemChanged(descriptionRow);
        } else if (position == linkRow) {
            Context context = getParentActivity();
            AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
            builder.setTitle(LocaleController.getString(R.string.VlessLink));

            LinearLayout ll = new LinearLayout(context);
            ll.setOrientation(LinearLayout.VERTICAL);

            final EditTextBoldCursor editText = new EditTextBoldCursor(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64), MeasureSpec.EXACTLY));
                }
            };
            editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            editText.setText(VlessProxyManager.getVlessLink());
            editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
            editText.setHintText(LocaleController.getString(R.string.VlessLinkHint));
            editText.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
            editText.setSingleLine(true);
            editText.setFocusable(true);
            editText.setTransformHintToHeader(true);
            editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                    Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                    Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
            editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
            editText.setBackground(null);
            editText.requestFocus();
            editText.setPadding(0, 0, 0, 0);
            ll.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 24, 0, 24, 0));

            builder.setView(ll);
            builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialogInterface, i2) -> {
                VlessProxyManager.setVlessLink(editText.getText().toString().trim());
                if (VlessProxyManager.isEnabled()) {
                    VlessProxyManager.setEnabled(true);
                }
                listAdapter.notifyItemChanged(linkRow, PARTIAL);
                listAdapter.notifyItemChanged(descriptionRow);
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);

            AlertDialog alertDialog = builder.create();
            alertDialog.setOnShowListener(dialog -> {
                editText.requestFocus();
                AndroidUtilities.showKeyboard(editText);
            });
            showDialog(alertDialog);
            editText.setSelection(0, editText.getText().length());
        }
    }

    @Override
    protected boolean onItemLongClick(View view, int position, float x, float y) {
        return false;
    }

    @Override
    protected String getKey() {
        return null;
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return currentProxyInfo.address;
    }

    @Override
    protected void updateRows() {
        rowCount = 0;
        settingsRow = rowCount++;
        linkRow = rowCount++;
        enableRow = rowCount++;
        descriptionRow = rowCount++;
    }

    @Override
    protected boolean hasWhiteActionBar() {
        return false;
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_SETTINGS: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == linkRow) {
                        String value = VlessProxyManager.getVlessLink();
                        if (TextUtils.isEmpty(value)) {
                            value = LocaleController.getString(R.string.VlessLinkHint);
                        }
                        textCell.setTextAndValue(LocaleController.getString(R.string.VlessLink), value, partial, true);
                    }
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell textCell = (TextCheckCell) holder.itemView;
                    if (position == enableRow) {
                        textCell.setTextAndCheck(LocaleController.getString(R.string.VlessEnable), VlessProxyManager.isEnabled(), true);
                    }
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == settingsRow) {
                        headerCell.setText(LocaleController.getString(R.string.Settings));
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(LocaleController.getString(R.string.VlessDescription));
                    cell.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider_bottom, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == descriptionRow) {
                return TYPE_INFO_PRIVACY;
            } else if (position == settingsRow) {
                return TYPE_HEADER;
            } else if (position == enableRow) {
                return TYPE_CHECK;
            } else if (position == linkRow) {
                return TYPE_SETTINGS;
            }
            return TYPE_SETTINGS;
        }
    }
}
