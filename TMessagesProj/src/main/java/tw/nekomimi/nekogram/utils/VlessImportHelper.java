package tw.nekomimi.nekogram.utils;

import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import tw.nekomimi.nekogram.helpers.VlessProxyManager;

/**
 * Shared "add built-in proxy nodes" helpers.
 *
 * Generalized from the VLESS-only helper (class name kept): it now accepts any
 * node link the sing-box engine can carry — vless://, trojan://, ss:// and
 * hysteria2:// — extracted from pasted text / subscription bodies / QR
 * payloads through {@link ProxyUtil#parseProxies}.
 */
public class VlessImportHelper {

    /** Parses pasted text / subscription body / QR payload and adds every supported node link found. */
    public static void importText(BaseFragment fragment, String raw, Runnable onChanged) {
        if (fragment == null || raw == null || raw.trim().isEmpty()) {
            return;
        }
        if (!isAlive(fragment)) {
            return;
        }
        List<String> links = ProxyUtil.parseProxies(raw);
        int added = 0;
        for (String link : links) {
            if (VlessProxyManager.addNode(link)) {
                added++;
            }
        }
        if (isAlive(fragment)) {
            toast(fragment, added > 0
                    ? LocaleController.formatString("VlessNodesAdded", R.string.VlessNodesAdded, added)
                    : LocaleController.getString(R.string.VlessNoLinkFound));
        }
        if (added > 0 && onChanged != null) {
            onChanged.run();
        }
    }

    public static void showAddDialog(final BaseFragment fragment, final Runnable onChanged) {
        if (!isAlive(fragment)) {
            return;
        }
        showInputDialog(fragment, R.string.VlessAddNode, R.string.VlessLinkHint, false,
                text -> importText(fragment, text, onChanged));
    }

    public static void showSubscriptionDialog(final BaseFragment fragment, final Runnable onChanged) {
        if (!isAlive(fragment)) {
            return;
        }
        showInputDialog(fragment, R.string.VlessImportSubscription, R.string.VlessSubscriptionUrl, true,
                url -> fetchSubscription(fragment, url, onChanged));
    }

    public static void importFromClipboard(final BaseFragment fragment, final Runnable onChanged) {
        if (!isAlive(fragment)) {
            return;
        }
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null && cm.getPrimaryClip() != null && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).coerceToText(context);
            if (cs != null && !ProxyUtil.parseProxies(cs.toString()).isEmpty()) {
                importText(fragment, cs.toString(), onChanged);
                return;
            }
        }
        toast(fragment, LocaleController.getString(R.string.VlessNoLinkFound));
    }

    private static boolean isAlive(BaseFragment fragment) {
        return fragment != null && fragment.getParentActivity() != null && !fragment.isFinished;
    }

    private interface InputCallback {
        void run(String value);
    }

    private static void showInputDialog(final BaseFragment fragment, int titleRes, int hintRes,
                                        final boolean singleLine, final InputCallback callback) {
        if (!isAlive(fragment)) {
            return;
        }
        Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(LocaleController.getString(titleRes));

        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, fragment.getResourceProvider()));
        editText.setHintText(LocaleController.getString(hintRes));
        editText.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, fragment.getResourceProvider()));
        editText.setSingleLine(singleLine);
        if (!singleLine) {
            editText.setMinLines(2);
            editText.setMaxLines(6);
        }
        editText.setInputType(singleLine
                ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setFocusable(true);
        editText.setTransformHintToHeader(true);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, fragment.getResourceProvider()),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, fragment.getResourceProvider()),
                Theme.getColor(Theme.key_text_RedRegular, fragment.getResourceProvider()));
        editText.setBackground(null);
        if (!singleLine) {
            editText.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        }

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(8), AndroidUtilities.dp(20), 0);
        container.addView(editText);
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString(singleLine ? R.string.OK : R.string.Add),
                (dialogInterface, i) -> callback.run(editText.getText().toString().trim()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        fragment.showDialog(dialog);
    }

    private static void fetchSubscription(final BaseFragment fragment, final String url, final Runnable onChanged) {
        if (url == null || url.isEmpty()) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            String body = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestProperty("User-Agent", "NagramXF");
                int code = connection.getResponseCode();
                if (code == HttpURLConnection.HTTP_OK) {
                    InputStream is = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                    reader.close();
                    body = sb.toString();
                }
                connection.disconnect();
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final String text = body;
            AndroidUtilities.runOnUIThread(() -> {
                if (!isAlive(fragment)) {
                    return;
                }
                if (text == null || text.isEmpty()) {
                    toast(fragment, LocaleController.getString(R.string.VlessFetchFailed));
                } else {
                    importText(fragment, text, onChanged);
                }
            });
        });
    }

    private static void toast(BaseFragment fragment, CharSequence text) {
        Context context = fragment == null ? null : fragment.getParentActivity();
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }
}
