package tw.nekomimi.nekogram.settings;

import android.content.Context;
import android.text.InputType;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.helpers.ProxyParse;
import tw.nekomimi.nekogram.helpers.VlessProxyManager;
import tw.nekomimi.nekogram.ui.PopupBuilder;

import java.nio.charset.StandardCharsets;

/**
 * Add / edit a VMess node through its own fields.
 *
 * Adapted from Nekogram X 9.3.3 (GPL-3.0) `proxy/VmessSettingsActivity.java`
 * (field set and defaults) but re-targeted to the sing-box engine: saving
 * assembles a canonical `vmess://` (v2rayN base64 JSON) link and hands it to
 * [VlessProxyManager], exactly like the generic node editor does. Editing an
 * existing node pre-fills the form by parsing its link with
 * [ProxyParse.parseVmess].
 */
public class VmessNodeEditActivity extends BaseFragment {

    private static final int MENU_DONE = 1;

    private static final String[] securitySet = {
            "chacha20-poly1305", "aes-128-gcm", "auto", "none", "zero"
    };
    private static final String[] networkSet = {
            "tcp", "ws", "http", "h2", "grpc", "kcp", "quic"
    };
    private static final String[] headTypeSet = {
            "none", "http", "srtp", "utp", "wechat-video", "dtls", "wireguard"
    };

    /** Link being edited, or null when adding a new node. */
    private final String editingLink;

    private LinearLayout fieldsContainer;
    private EditTextBoldCursor remarksEdit;
    private EditTextBoldCursor addressEdit;
    private EditTextBoldCursor portEdit;
    private EditTextBoldCursor userIdEdit;
    private EditTextBoldCursor alterIdEdit;
    private TextSettingsCell securityCell;
    private TextSettingsCell networkCell;
    private TextSettingsCell headTypeCell;
    private EditTextBoldCursor requestHostEdit;
    private EditTextBoldCursor pathEdit;
    private TextCheckCell tlsCell;

    // Selector values are mirrored into these fields so save() never has to read
    // the cell value back through the UI.
    private String currentSecurity = "auto";
    private String currentNetwork = "tcp";
    private String currentHeaderType = "none";

    public VmessNodeEditActivity() {
        this(null);
    }

    public VmessNodeEditActivity(String link) {
        editingLink = link;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(editingLink == null ? R.string.AddProxyVmess : R.string.ProxyDetails));
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

        ProxyParse.VmessBean bean = editingLink == null ? null : ProxyParse.parseVmess(editingLink);
        if (bean != null) {
            currentSecurity = valueOrDefault(bean.getSecurity(), "auto");
            currentNetwork = valueOrDefault(bean.getNetwork(), "tcp");
            currentHeaderType = valueOrDefault(bean.getHeaderType(), "none");
        }

        remarksEdit = addEditRow(context, LocaleController.getString(R.string.ProxyRemarks),
                bean == null ? "" : bean.getRemarks(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addressEdit = addEditRow(context, LocaleController.getString(R.string.UseProxyAddress),
                bean == null ? "" : bean.getAddress(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        portEdit = addEditRow(context, LocaleController.getString(R.string.UseProxyPort),
                bean == null || bean.getPort() <= 0 ? "" : String.valueOf(bean.getPort()), InputType.TYPE_CLASS_NUMBER);
        userIdEdit = addEditRow(context, LocaleController.getString(R.string.VmessUserId),
                bean == null ? "" : bean.getId(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        alterIdEdit = addEditRow(context, LocaleController.getString(R.string.VmessAlterId),
                bean == null || bean.getAlterId() <= 0 ? "0" : String.valueOf(bean.getAlterId()), InputType.TYPE_CLASS_NUMBER);

        securityCell = addSelectorRow(context, LocaleController.getString(R.string.VmessSecurity), currentSecurity, securitySet, value -> currentSecurity = value);
        networkCell = addSelectorRow(context, LocaleController.getString(R.string.VmessNetwork), currentNetwork, networkSet, value -> currentNetwork = value);
        headTypeCell = addSelectorRow(context, LocaleController.getString(R.string.VmessHeadType), currentHeaderType, headTypeSet, value -> currentHeaderType = value);

        requestHostEdit = addEditRow(context, LocaleController.getString(R.string.VmessRequestHost),
                bean == null ? "" : bean.getRequestHost(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        pathEdit = addEditRow(context, LocaleController.getString(R.string.VmessPath),
                bean == null ? "" : bean.getPath(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        tlsCell = new TextCheckCell(context);
        tlsCell.setBackground(Theme.getSelectorDrawable(false));
        boolean useTls = bean != null && "tls".equals(bean.getStreamSecurity());
        tlsCell.setTextAndCheck(LocaleController.getString(R.string.VmessTls), useTls, false);
        FrameLayout tlsContainer = new FrameLayout(context);
        tlsContainer.addView(tlsCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 0));
        tlsCell.setOnClickListener(v -> tlsCell.setChecked(!tlsCell.isChecked()));
        fieldsContainer.addView(tlsContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, getResourceProvider()));
        info.setGravity(Gravity.LEFT);
        info.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(16));
        info.setText(LocaleController.getString(R.string.ProxyInfoVmess));
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

    private TextSettingsCell addSelectorRow(Context context, String label, String value, final String[] options,
                                            ValueConsumer onSelected) {
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setTextAndValue(label, value, false);
        FrameLayout container = new FrameLayout(context);
        container.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 0));
        cell.setOnClickListener(v -> {
            PopupBuilder popup = new PopupBuilder(v, true);
            popup.setItems(options, (i, text) -> {
                String selected = text.toString();
                cell.setTextAndValue(label, selected, false);
                onSelected.accept(selected);
                return kotlin.Unit.INSTANCE;
            });
            popup.show();
        });
        fieldsContainer.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));
        return cell;
    }

    /** Simple value callback so no java.util.function (API 24+) dependency is needed. */
    private interface ValueConsumer {
        void accept(String value);
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

    /** Assembles the canonical v2rayN `vmess://` (base64 JSON) link, or null when invalid. */
    private String buildVmessLink() {
        if (addressEdit == null || portEdit == null || userIdEdit == null || alterIdEdit == null) {
            return null;
        }
        String address = addressEdit.getText().toString().trim();
        String userId = userIdEdit.getText().toString().trim();
        if (address.isEmpty()) {
            focusField(addressEdit);
            return null;
        }
        if (userId.isEmpty()) {
            focusField(userIdEdit);
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
        int alterId;
        try {
            alterId = Integer.parseInt(alterIdEdit.getText().toString().trim());
        } catch (NumberFormatException e) {
            alterId = 0;
        }
        if (alterId < 0) {
            alterId = 0;
        }
        String security = valueOrDefault(currentSecurity, "auto");
        String network = valueOrDefault(currentNetwork, "tcp");
        String headType = valueOrDefault(currentHeaderType, "none");
        String remarks = remarksEdit == null ? "" : remarksEdit.getText().toString();
        String requestHost = requestHostEdit == null ? "" : requestHostEdit.getText().toString().trim();
        String path = pathEdit == null ? "" : pathEdit.getText().toString().trim();
        boolean useTls = tlsCell != null && tlsCell.isChecked();
        try {
            JSONObject json = new JSONObject();
            json.put("v", "2");
            json.put("ps", remarks);
            json.put("add", address);
            json.put("port", port);
            json.put("id", userId);
            json.put("aid", alterId);
            json.put("scy", valueOrDefault(security, "auto"));
            json.put("net", valueOrDefault(network, "tcp"));
            json.put("type", valueOrDefault(headType, "none"));
            json.put("host", requestHost);
            json.put("path", path);
            json.put("tls", useTls ? "tls" : "");
            String payload = Base64.encodeToString(json.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            return ProxyParse.VMESS_PROTOCOL + payload;
        } catch (Throwable e) {
            return null;
        }
    }

    private void save() {
        if (isFinished || getParentActivity() == null) {
            return;
        }
        String link = buildVmessLink();
        if (link == null) {
            return;
        }
        boolean ok;
        if (editingLink != null) {
            ok = VlessProxyManager.replaceNode(editingLink, link);
        } else {
            ok = VlessProxyManager.addNode(link);
        }
        if (!ok) {
            toastInvalid();
            return;
        }
        // kcp/quic are kept selectable so existing imported nodes can be edited,
        // but the sing-box engine has no kcp/quic transport: VlessConfig falls
        // back to plain TCP for them. Surface that so the saved node is not
        // mistaken for a true kcp/quic connection.
        if ("kcp".equals(currentNetwork) || "quic".equals(currentNetwork)) {
            Context context = getParentActivity();
            if (context != null) {
                Toast.makeText(context, LocaleController.getString(R.string.ProxyTransportFallback), Toast.LENGTH_SHORT).show();
            }
        }
        finishFragment();
    }
}
