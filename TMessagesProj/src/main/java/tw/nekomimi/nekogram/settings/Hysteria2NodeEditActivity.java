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
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.helpers.ProxyParse;
import tw.nekomimi.nekogram.ui.PopupBuilder;

/**
 * Add / edit a Hysteria2 node through its own fields.
 *
 * Saving assembles the standard `hysteria2://password@host:port?sni=...&insecure=1&obfs=...&obfs-password=...#remarks`
 * link through {@link ProxyParse#toHysteria2Link} and hands it to
 * [VlessProxyManager], exactly like the other protocol editors. Editing an
 * existing node pre-fills the form by parsing its link with
 * {@link ProxyParse#parseHysteria2}.
 *
 * Note: a `pinSHA256` pin carried by an imported link is preserved by the
 * parser (round-trip safety on the bean) but has no dedicated field in this
 * form, mirroring the field set defined for the Hysteria2 editor.
 */
public class Hysteria2NodeEditActivity extends BaseFragment {

    private static final int MENU_DONE = 1;

    private static final String[] obfsSet = {
            "none", "salamander"
    };

    /** Link being edited, or null when adding a new node. */
    private final String editingLink;

    private LinearLayout fieldsContainer;
    private EditTextBoldCursor remarksEdit;
    private EditTextBoldCursor addressEdit;
    private EditTextBoldCursor portEdit;
    private EditTextBoldCursor passwordEdit;
    private EditTextBoldCursor sniEdit;
    private TextCheckCell insecureCell;
    private TextSettingsCell obfsCell;
    private EditTextBoldCursor obfsPasswordEdit;

    // Selector value is mirrored into this field so save() never reads the cell
    // value back through the UI.
    private String currentObfs = "none";

    public Hysteria2NodeEditActivity() {
        this(null);
    }

    public Hysteria2NodeEditActivity(String link) {
        editingLink = link;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(editingLink == null ? R.string.AddProxyHysteria2 : R.string.ProxyDetails));
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

        ProxyParse.Hysteria2Bean bean = editingLink == null ? null : ProxyParse.parseHysteria2(editingLink);
        if (bean != null) {
            String obfs = bean.getObfs();
            currentObfs = obfs == null || obfs.trim().isEmpty() ? "none" : obfs;
        }

        remarksEdit = addEditRow(context, LocaleController.getString(R.string.Hysteria2Remarks),
                bean == null ? "" : bean.getRemarks(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addressEdit = addEditRow(context, LocaleController.getString(R.string.Hysteria2Address),
                bean == null ? "" : bean.getServer(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        portEdit = addEditRow(context, LocaleController.getString(R.string.Hysteria2Port),
                bean == null || bean.getServerPort() <= 0 ? "" : String.valueOf(bean.getServerPort()), InputType.TYPE_CLASS_NUMBER);
        passwordEdit = addEditRow(context, LocaleController.getString(R.string.Hysteria2Password),
                bean == null ? "" : bean.getPassword(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        sniEdit = addEditRow(context, LocaleController.getString(R.string.Hysteria2Sni),
                bean == null ? "" : bean.getSni(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        insecureCell = new TextCheckCell(context);
        insecureCell.setBackground(Theme.getSelectorDrawable(false));
        boolean insecure = bean != null && bean.getInsecure();
        insecureCell.setTextAndCheck(LocaleController.getString(R.string.Hysteria2Insecure), insecure, false);
        FrameLayout insecureContainer = new FrameLayout(context);
        insecureContainer.addView(insecureCell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 0));
        insecureCell.setOnClickListener(v -> insecureCell.setChecked(!insecureCell.isChecked()));
        fieldsContainer.addView(insecureContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 64));

        obfsCell = addObfsSelectorRow(context, LocaleController.getString(R.string.Hysteria2Obfs), currentObfs);
        obfsPasswordEdit = addEditRow(context, LocaleController.getString(R.string.Hysteria2ObfsPassword),
                bean == null ? "" : bean.getObfsPassword(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, getResourceProvider()));
        info.setGravity(Gravity.LEFT);
        info.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(16));
        info.setText(LocaleController.getString(R.string.ProxyInfoHysteria2));
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

    private TextSettingsCell addObfsSelectorRow(Context context, String label, String value) {
        TextSettingsCell cell = new TextSettingsCell(context);
        cell.setBackground(Theme.getSelectorDrawable(false));
        cell.setTextAndValue(label, value, false);
        FrameLayout container = new FrameLayout(context);
        container.addView(cell, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.TOP, 0, 0, 0, 0));
        cell.setOnClickListener(v -> {
            PopupBuilder popup = new PopupBuilder(v, true);
            popup.setItems(obfsSet, (i, text) -> {
                String selected = text.toString();
                currentObfs = selected;
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

    /** Assembles the canonical `hysteria2://` link via [ProxyParse.toHysteria2Link], or null when invalid. */
    private String buildHysteria2Link() {
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
        String remarks = remarksEdit == null ? "" : remarksEdit.getText().toString().trim();
        String sni = sniEdit == null ? "" : sniEdit.getText().toString().trim();
        boolean insecure = insecureCell != null && insecureCell.isChecked();
        String obfs = currentObfs == null || "none".equals(currentObfs) ? "" : currentObfs.trim();
        String obfsPassword = obfsPasswordEdit == null ? "" : obfsPasswordEdit.getText().toString();
        ProxyParse.Hysteria2Bean bean = new ProxyParse.Hysteria2Bean(
                address, port, password, sni, insecure, obfs, obfsPassword, "", remarks);
        return ProxyParse.toHysteria2Link(bean);
    }

    private void save() {
        if (isFinished || getParentActivity() == null) {
            return;
        }
        String link = buildHysteria2Link();
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
