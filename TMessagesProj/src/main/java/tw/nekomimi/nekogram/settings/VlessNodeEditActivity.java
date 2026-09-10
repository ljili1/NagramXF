package tw.nekomimi.nekogram.settings;

import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.helpers.ProxyTypes;
import tw.nekomimi.nekogram.utils.ProxyUtil;

import java.util.List;

/**
 * Add / edit a single built-in proxy node by its standard link.
 *
 * This is the form page that pairs with the native proxy list page — the same
 * structure the 8.x front end uses (list page + one field form). It accepts any
 * link the sing-box engine can carry (vless://, trojan://, ss://, hysteria2://), so
 * it doubles as the generic fallback editor for protocols whose dedicated form
 * does not exist yet. All bulk operations (multi-select, share, delete,
 * subscription import, ping) live in ProxyListActivity, so there is no second
 * management page.
 */
public class VlessNodeEditActivity extends BaseFragment {

    private static final int MENU_DONE = 1;
    private static final int MENU_SCAN_QR = 2;

    /** Link being edited, or null when adding a new node. */
    private final String editingLink;

    private EditTextBoldCursor linkEdit;

    public VlessNodeEditActivity() {
        this(null);
    }

    public VlessNodeEditActivity(String link) {
        editingLink = link;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(editingLink == null ? R.string.ProxyAddNode : R.string.ProxyDetails));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_DONE) {
                    save();
                } else if (id == MENU_SCAN_QR) {
                    showQrScanner();
                }
            }
        });
        ActionBarMenu menu = actionBar.createMenu();
        menu.addItem(MENU_SCAN_QR, R.drawable.msg_qrcode_mini_remix);
        menu.addItem(MENU_DONE, R.drawable.ic_done);

        ScrollView scrollView = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        fragmentView = scrollView;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // --- link field ---
        LinearLayout fieldContainer = new LinearLayout(context);
        fieldContainer.setOrientation(LinearLayout.VERTICAL);
        fieldContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(fieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Permanent field label: a plain TextView, so it stays visible whether
        // the editor is empty, focused or already filled. Only this long-link
        // field uses the generic "links" caption.
        TextView linkHeader = new TextView(context);
        linkHeader.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        linkHeader.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, getResourceProvider()));
        linkHeader.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(12), AndroidUtilities.dp(21), 0);
        linkHeader.setText(LocaleController.getString(R.string.ProxyLinkFieldLabel));
        fieldContainer.addView(linkHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        linkEdit = new EditTextBoldCursor(context);
        linkEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        linkEdit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        // Long values (vless://…, vmess://<base64>, a subscription URL) contain no
        // whitespace to wrap on, so the field is allowed to grow vertically until
        // the whole value is visible instead of being clipped after N lines.
        linkEdit.setSingleLine(false);
        linkEdit.setMinLines(1);
        linkEdit.setMaxLines(Integer.MAX_VALUE);
        linkEdit.setHorizontallyScrolling(false);
        linkEdit.setGravity(Gravity.TOP);
        linkEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        linkEdit.setFocusable(true);
        linkEdit.setTextIsSelectable(true);
        linkEdit.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, getResourceProvider()),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, getResourceProvider()),
                Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        linkEdit.setBackground(null);
        linkEdit.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(4), AndroidUtilities.dp(21), AndroidUtilities.dp(12));
        if (editingLink != null) {
            linkEdit.setText(editingLink);
            linkEdit.setSelection(editingLink.length());
        }
        fieldContainer.addView(linkEdit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // --- helpers ---
        TextSettingsCell pasteCell = new TextSettingsCell(context);
        pasteCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        pasteCell.setText(LocaleController.getString(R.string.PasteFromClipboard), false);
        pasteCell.setOnClickListener(v -> pasteFromClipboard());
        content.addView(pasteCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // --- description ---
        TextView info = new TextView(context);
        info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4, getResourceProvider()));
        info.setGravity(Gravity.LEFT);
        info.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(16));
        info.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        info.setTextIsSelectable(true);
        info.setText(LocaleController.getString(R.string.VlessDescription));
        content.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        return fragmentView;
    }

    private void pasteFromClipboard() {
        if (isFinished || getParentActivity() == null) {
            return;
        }
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
            if (cs != null) {
                fillLinkFromText(cs.toString());
                return;
            }
        }
        toastInvalidLink();
    }

    /**
     * Fills the editor from [text]. A direct node link (any sing-box scheme)
     * fills the field; a subscription URL is downloaded first and then either
     * fills the field (single node) or imports every node it carries.
     */
    private void fillLinkFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            toastInvalidLink();
            return;
        }
        if (ProxyUtil.isSubscriptionText(text)) {
            toast(LocaleController.getString(R.string.SubscriptionFetching));
            final String raw = text;
            new Thread(() -> {
                final String expanded = ProxyUtil.expandSubscriptions(raw);
                final List<tw.nekomimi.nekogram.helpers.ProxyLinkParser.Parsed> parsed =
                        tw.nekomimi.nekogram.helpers.ProxyLinkParser.parse(expanded);
                AndroidUtilities.runOnUIThread(() -> applyParsedLinks(parsed));
            }, "proxy-subscription").start();
            return;
        }
        applyParsedLinks(tw.nekomimi.nekogram.helpers.ProxyLinkParser.parse(text));
    }

    /**
     * A single supported node fills the field; several nodes (a subscription
     * body) are all imported into the saved proxy list instead.
     */
    private void applyParsedLinks(List<tw.nekomimi.nekogram.helpers.ProxyLinkParser.Parsed> parsed) {
        if (isFinished || getParentActivity() == null) {
            return;
        }
        List<String> links = new java.util.ArrayList<>();
        for (tw.nekomimi.nekogram.helpers.ProxyLinkParser.Parsed p : parsed) {
            if (!(p instanceof tw.nekomimi.nekogram.helpers.ProxyLinkParser.Parsed.NodeLink)) {
                continue;
            }
            String link = tw.nekomimi.nekogram.helpers.ProxyLinkParser.normalizeScheme(
                    ((tw.nekomimi.nekogram.helpers.ProxyLinkParser.Parsed.NodeLink) p).getLink());
            if (!ProxyTypes.isSupported(link)) {
                continue;
            }
            if (!links.contains(link)) {
                links.add(link);
            }
        }
        if (links.isEmpty()) {
            toastInvalidLink();
            return;
        }
        if (links.size() == 1) {
            linkEdit.setText(links.get(0));
            linkEdit.setSelection(links.get(0).length());
            return;
        }
        int before = SharedConfig.proxyList.size();
        for (String link : links) {
            SharedConfig.addNodeProxy(link);
        }
        int added = SharedConfig.proxyList.size() - before;
        if (added <= 0) {
            toastInvalidLink();
            return;
        }
        toast(LocaleController.formatString("VlessNodesAdded", R.string.VlessNodesAdded, added));
        finishFragment();
    }

    private void toast(String text) {
        Context context = getParentActivity();
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }

    private void toastInvalidLink() {
        toast(LocaleController.getString(R.string.VlessNoLinkFound));
    }

    /** Open Telegram's built-in QR scanner and feed the decoded payload to the editor. */
    private void showQrScanner() {
        if (isFinished || getParentActivity() == null) {
            return;
        }
        CameraScanActivity.showAsSheet(VlessNodeEditActivity.this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                fillLinkFromText(text);
            }
        });
    }

    private void save() {
        if (isFinished || getParentActivity() == null) {
            return;
        }
        String link = linkEdit == null ? "" : linkEdit.getText().toString().trim();
        boolean ok;
        if (editingLink != null) {
            ok = SharedConfig.editNodeProxy(editingLink, link);
        } else {
            ok = SharedConfig.addNodeProxy(link) != null;
        }
        if (!ok) {
            toastInvalidLink();
            return;
        }
        finishFragment();
    }
}
