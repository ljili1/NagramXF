package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.helpers.ProxyParse;
import tw.nekomimi.nekogram.ui.PopupBuilder;

/**
 * Add / edit a Shadowsocks node through its own fields.
 *
 * Adapted from Nekogram X 9.3.3 (GPL-3.0) `proxy/ShadowsocksSettingsActivity.java`
 * but re-targeted to the sing-box engine: saving assembles a canonical SIP002
 * `ss://` link via [ProxyParse.toSsLink] and hands it to [VlessProxyManager].
 * The method whitelist only contains ciphers the sing-box engine understands
 * (AEAD-2022 family included); an existing node is pre-filled by parsing its
 * link with [ProxyParse.parseSs].
 */
public class ShadowsocksNodeEditActivity extends BaseFragment {

    private static final int MENU_DONE = 1;

    private static final String[] methodSet = {
            "aes-128-gcm",
            "aes-256-gcm",
            "chacha20-ietf-poly1305",
            "xchacha20-ietf-poly1305",
            "2022-blake3-aes-128-gcm",
            "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305"
    };

    /** Link being edited, or null when adding a new node. */
    private final String editingLink;

    private LinearLayout fieldsContainer;
    private EditTextBoldCursor remarksEdit;
    private EditTextBoldCursor hostEdit;
    private EditTextBoldCursor portEdit;
    private EditTextBoldCursor passwordEdit;
    private TextSettingsCell methodCell;

    // Selector value is mirrored into this field so save() never reads the cell
    // value back through the UI.
    private String currentMethod = "aes-256-gcm";

    public ShadowsocksNodeEditActivity() {
        this(null);
    }

    public ShadowsocksNodeEditActivity(String link) {
        editingLink = link;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(editingLink == null ? R.string.AddProxySS : R.string.ProxyDetails));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_DONE) {
                    save();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_DONE, R.drawable.ic_done);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        fragmentView = scrollView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray, getResourceProvider()));

        fieldsContainer = new LinearLayout(context);
        fieldsContainer.setOrientation(LinearLayout.VERTICAL);
        fieldsContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, getResourceProvider()));
        content.addView(fieldsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ProxyParse.SsBean bean = editingLink == null ? null : ProxyParse.parseSs(editingLink);
        if (bean != null) {
            currentMethod = valueOrDefault(bean.getMethod(), "aes-256-gcm");
        }

        remarksEdit = addEditRow(context, LocaleController.getString(R.string.ProxyRemarks),
                bean == null ? "" : bean.getRemarks(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hostEdit = addEditRow(context, LocaleController.getString(R.string.UseProxyAddress),
                bean == null ? "" : bean.getHost(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        portEdit = addEditRow(context, LocaleController.getString(R.string.UseProxyPort),
                bean == null || bean.getRemotePort() <= 0 ? "" : String.valueOf(bean.getRemotePort()), InputType.TYPE_CLASS_NUMBER);
        passwordEdit = addEditRow(context, LocaleController.getString(R.string.SSPassword),
                bean == null ? "" : bean.getPassword(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        methodCell = addMethodSelectorRow(context, LocaleController.getString(R.string.SSMethod), currentMethod);

        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, getResourceProvider()));
        info.setGravity(Gravity.LEFT);
        info.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(16));
        info.setText(LocaleController.getString(R.string.ProxyInfoSS));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return fragmentView;
    }

    private EditTextBoldCursor addEditRow(Context context, String hint, String value, int inputType) {
        final EditTextBoldCursor cursor = mkCursor(context);
        cursor.setInputType(inputType);
        cursor.setHintText(hint);
        if (value != null && !value.isEmpty()) {
            cursor.setText(value);
            cursor.setSelection(cursor.length());
        }
        FrameLayout container = new FrameLayout(context);
        container.addView(cursor, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 21, 0, 21, 0));
        fieldsContainer.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
        return cursor;
    }

    private TextSettingsCell addMethodSelectorRow(Context context, String label, String value) {
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setTextAndValue(label, value, false);
        FrameLayout container = new FrameLayout(context);
        container.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 0));
        cell.setOnClickListener(v -> {
            PopupBuilder popup = new PopupBuilder(v, true);
            popup.setItems(methodSet, (i, text) -> {
                String selected = text.toString();
                currentMethod = selected;
                cell.setTextAndValue(label, selected, false);
                return kotlin.Unit.INSTANCE;
            });
            popup.show();
        });
        fieldsContainer.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
        return cell;
    }

    private EditTextBoldCursor mkCursor(Context context) {
        EditTextBoldCursor cursor = new EditTextBoldCursor(context);
        cursor.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        cursor.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        cursor.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, getResourceProvider()));
        cursor.setSingleLine(true);
        cursor.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        cursor.setTransformHintToHeader(true);
        cursor.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, getResourceProvider()),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, getResourceProvider()),
                Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        cursor.setBackground(null);
        cursor.setPadding(0, 0, 0, 0);
        return cursor;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private void toastInvalid() {
        Context context = getParentActivity();
        if (context != null) {
            Toast.makeText(context, LocaleController.getString(R.string.ProxyInvalid), Toast.LENGTH_SHORT).show();
        }
    }

    private void focusField(EditTextBoldCursor edit) {
        if (edit == null) {
            return;
        }
        edit.requestFocus();
        AndroidUtilities.showKeyboard(edit);
    }

    /** Assembles a canonical SIP002 `ss://` link via [ProxyParse.toSsLink], or null when invalid. */
    private String buildShadowsocksLink() {
        if (hostEdit == null || portEdit == null || passwordEdit == null) {
            return null;
        }
        String host = hostEdit.getText().toString().trim();
        String password = passwordEdit.getText().toString();
        if (host.isEmpty()) {
            focusField(hostEdit);
            return null;
        }
        if (password.isEmpty()) {
            focusField(passwordEdit);
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portEdit.getText().toString().trim());
        } catch (NumberFormatException e) {
            focusField(portEdit);
            return null;
        }
        if (port <= 0 || port > 65535) {
            focusField(portEdit);
            return null;
        }
        String method = valueOrDefault(currentMethod, "aes-256-gcm");
        String remarks = remarksEdit == null ? "" : remarksEdit.getText().toString().trim();
        ProxyParse.SsBean bean = new ProxyParse.SsBean(host, port, password, method, "", remarks);
        return ProxyParse.toSsLink(bean);
    }

    private void save() {
        if (isFinished || getParentActivity() == null) {
            return;
        }
        String link = buildShadowsocksLink();
        if (link == null) {
            return;
        }
        boolean ok;
        if (editingLink != null) {
            ok = SharedConfig.editNodeProxy(editingLink, link);
        } else {
            ok = SharedConfig.addNodeProxy(link) != null;
        }
        if (!ok) {
            toastInvalid();
            return;
        }
        finishFragment();
    }
}
