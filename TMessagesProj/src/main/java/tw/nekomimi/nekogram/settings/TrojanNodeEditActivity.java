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
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.helpers.ProxyParse;

import java.net.URLEncoder;

/**
 * Add / edit a Trojan node through its own fields.
 *
 * Adapted from Nekogram X 9.3.3 (GPL-3.0) `proxy/TrojanSettingsActivity.java`
 * but re-targeted to the sing-box engine: saving assembles a canonical
 * `trojan://password@host:port?sni=...#remarks` link and hands it to
 * [VlessProxyManager]. The `sni` query parameter is omitted when empty; an
 * existing node is pre-filled by parsing its link with
 * [ProxyParse.parseTrojan].
 */
public class TrojanNodeEditActivity extends BaseFragment {

    private static final int MENU_DONE = 1;

    /** Link being edited, or null when adding a new node. */
    private final String editingLink;

    private LinearLayout fieldsContainer;
    private EditTextBoldCursor addressEdit;
    private EditTextBoldCursor portEdit;
    private EditTextBoldCursor passwordEdit;
    private EditTextBoldCursor sniEdit;
    private EditTextBoldCursor remarksEdit;

    public TrojanNodeEditActivity() {
        this(null);
    }

    public TrojanNodeEditActivity(String link) {
        editingLink = link;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(editingLink == null ? R.string.AddProxyTrojan : R.string.ProxyDetails));
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

        ProxyParse.TrojanBean bean = editingLink == null ? null : ProxyParse.parseTrojan(editingLink);

        addressEdit = addEditRow(context, LocaleController.getString(R.string.UseProxyAddress),
                bean == null ? "" : bean.getAddress(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        portEdit = addEditRow(context, LocaleController.getString(R.string.UseProxyPort),
                bean == null || bean.getPort() <= 0 ? "" : String.valueOf(bean.getPort()), InputType.TYPE_CLASS_NUMBER);
        passwordEdit = addEditRow(context, LocaleController.getString(R.string.SSPassword),
                bean == null ? "" : bean.getPassword(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sniEdit = addEditRow(context, LocaleController.getString(R.string.TrojanSNI),
                bean == null ? "" : bean.getSni(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        remarksEdit = addEditRow(context, LocaleController.getString(R.string.ProxyRemarks),
                bean == null ? "" : bean.getRemarks(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, getResourceProvider()));
        info.setGravity(Gravity.LEFT);
        info.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(16));
        info.setText(LocaleController.getString(R.string.ProxyInfoTrojan));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return fragmentView;
    }

    private EditTextBoldCursor addEditRow(Context context, String hint, String value, int inputType) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);

        // Permanent field label: it stays visible whether the field is empty,
        // focused or already filled. The EditText's own floating hint used to be
        // shown only while typing, which made the form hard to scan.
        TextView header = new TextView(context);
        header.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        header.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, getResourceProvider()));
        header.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(10), AndroidUtilities.dp(21), 0);
        header.setText(hint);
        row.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final EditTextBoldCursor cursor = mkCursor(context);
        cursor.setInputType(inputType);
        if (value != null && !value.isEmpty()) {
            cursor.setText(value);
            cursor.setSelection(cursor.length());
        }
        row.addView(cursor, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, Gravity.LEFT | Gravity.TOP, 21, 0, 21, 0));
        fieldsContainer.addView(row, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return cursor;
    }

    private EditTextBoldCursor mkCursor(Context context) {
        EditTextBoldCursor cursor = new EditTextBoldCursor(context);
        cursor.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        cursor.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        cursor.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, getResourceProvider()));
        cursor.setSingleLine(true);
        cursor.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        cursor.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, getResourceProvider()),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, getResourceProvider()),
                Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        cursor.setBackground(null);
        cursor.setPadding(0, 0, 0, 0);
        return cursor;
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

    /** Assembles a canonical `trojan://password@host:port?sni=...#remarks` link, or null when invalid. */
    private String buildTrojanLink() {
        if (addressEdit == null || portEdit == null || passwordEdit == null) {
            return null;
        }
        String address = addressEdit.getText().toString().trim();
        String password = passwordEdit.getText().toString();
        if (address.isEmpty()) {
            focusField(addressEdit);
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
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(ProxyParse.TROJAN_PROTOCOL);
            sb.append(URLEncoder.encode(password, "UTF-8"));
            sb.append('@');
            appendHostPort(sb, address, port);
            String sni = sniEdit == null ? "" : sniEdit.getText().toString().trim();
            if (!sni.isEmpty()) {
                sb.append("?sni=").append(URLEncoder.encode(sni, "UTF-8"));
            }
            String remarks = remarksEdit == null ? "" : remarksEdit.getText().toString().trim();
            if (!remarks.isEmpty()) {
                sb.append('#').append(URLEncoder.encode(remarks, "UTF-8").replace("+", "%20"));
            }
            return sb.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    private static void appendHostPort(StringBuilder sb, String host, int port) {
        if (host.contains(":") && !host.startsWith("[")) {
            sb.append('[').append(host).append(']');
        } else {
            sb.append(host);
        }
        sb.append(':').append(port);
    }

    private void save() {
        if (isFinished || getParentActivity() == null) {
            return;
        }
        String link = buildTrojanLink();
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
