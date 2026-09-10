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
        actionBar.setTitle(LocaleController.getString(editingLink == null ? R.string.VlessAddNode : R.string.VlessSettings));
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
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // --- link field ---
        LinearLayout fieldContainer = new LinearLayout(context);
        fieldContainer.setOrientation(LinearLayout.VERTICAL);
        fieldContainer.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(fieldContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        linkEdit = new EditTextBoldCursor(context);
        linkEdit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        linkEdit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
        linkEdit.setHintText(LocaleController.getString(R.string.VlessLinkHint));
        linkEdit.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, getResourceProvider()));
        // vless:// URIs have no whitespace to wrap on, but the user expects the
        // whole link to be visible at a glance: wrap the editor at character
        // boundaries across up to three lines (long links become ~3 lines,
        // short ones stay on one), and let the outer ScrollView handle the
        // rest. Disable auto-link so the text is rendered in plain black, not
        // the system "link" colour.
        linkEdit.setSingleLine(false);
        linkEdit.setMinLines(1);
        linkEdit.setMaxLines(3);
        linkEdit.setHorizontallyScrolling(false);
        linkEdit.setAutoLinkMask(0);
        linkEdit.setGravity(Gravity.TOP);
        linkEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        linkEdit.setFocusable(true);
        linkEdit.setTransformHintToHeader(true);
        linkEdit.setTextIsSelectable(true);
        linkEdit.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, getResourceProvider()),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, getResourceProvider()),
                Theme.getColor(Theme.key_text_RedRegular, getResourceProvider()));
        linkEdit.setBackground(null);
        linkEdit.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), AndroidUtilities.dp(14));
        if (editingLink != null) {
            linkEdit.setText(editingLink);
            linkEdit.setSelection(editingLink.length());
        }
        fieldContainer.addView(linkEdit, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // --- helpers ---
        TextSettingsCell scanCell = new TextSettingsCell(context);
        scanCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        scanCell.setText(LocaleController.getString(R.string.ScanQrCode), true);
        scanCell.setOnClickListener(v -> CameraScanActivity.showAsSheet(VlessNodeEditActivity.this, false, CameraScanActivity.TYPE_QR, new CameraScanActivity.CameraScanActivityDelegate() {
            @Override
            public void didFindQr(String text) {
                fillLinkFromText(text);
            }
        }));
        content.addView(scanCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

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

    /** Fills the editor with the first supported proxy link found in [text]. */
    private void fillLinkFromText(String text) {
        if (text == null || text.trim().isEmpty()) {
            toastInvalidLink();
            return;
        }
        java.util.List<tw.nekomimi.nekogram.helpers.ProxyLinkParser.Parsed> parsed = tw.nekomimi.nekogram.helpers.ProxyLinkParser.parse(text);
        for (tw.nekomimi.nekogram.helpers.ProxyLinkParser.Parsed p : parsed) {
            if (!(p instanceof tw.nekomimi.nekogram.helpers.ProxyLinkParser.Parsed.NodeLink)) {
                continue;
            }
            tw.nekomimi.nekogram.helpers.ProxyLinkParser.Parsed.NodeLink node = (tw.nekomimi.nekogram.helpers.ProxyLinkParser.Parsed.NodeLink) p;
            if (ProxyTypes.isSupported(node.getLink())) {
                String link = node.getLink();
                linkEdit.setText(link);
                linkEdit.setSelection(link.length());
                return;
            }
        }
        toastInvalidLink();
    }

    private void toastInvalidLink() {
        Context context = getParentActivity();
        if (context != null) {
            Toast.makeText(context, LocaleController.getString(R.string.VlessNoLinkFound), Toast.LENGTH_SHORT).show();
        }
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
