package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.UndoView;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;

import xyz.nextalone.nagram.NaConfig;

/**
 * 强制转发功能处理器
 * 负责处理强制转发相关的逻辑，从ChatActivity中分离出来以便维护
 */
public class ForceForward {
    
    private boolean isForceForwardMode = false;
    private ArrayList<MessageObject> forwardingMessages;
    private MessageObject forwardingMessage;
    private MessageObject.GroupedMessages forwardingMessageGroup;
    private ChatActivity parentFragment;
    private int currentAccount;
    
    public ForceForward(ChatActivity fragment, int account) {
        this.parentFragment = fragment;
        this.currentAccount = account;
    }
    
    /**
     * 检查是否启用强制转发功能
     */
    public boolean isForceForwardEnabled() {
        return NaConfig.INSTANCE.getForceForward().Bool();
    }
    
    /**
     * 获取强制转发模式状态
     */
    public boolean isForceForwardMode() {
        return isForceForwardMode;
    }
    
    /**
     * 设置强制转发模式
     */
    public void setForceForwardMode(boolean mode) {
        this.isForceForwardMode = mode;
    }
    
    /**
     * 设置转发消息
     */
    public void setForwardingMessage(MessageObject message) {
        this.forwardingMessage = message;
    }
    
    /**
     * 设置转发消息组
     */
    public void setForwardingMessageGroup(MessageObject.GroupedMessages group) {
        this.forwardingMessageGroup = group;
    }
    
    /**
     * 获取转发消息
     */
    public MessageObject getForwardingMessage() {
        return forwardingMessage;
    }
    
    /**
     * 获取转发消息组
     */
    public MessageObject.GroupedMessages getForwardingMessageGroup() {
        return forwardingMessageGroup;
    }
    
    /**
     * 设置要强制转发的消息
     */
    public void setForwardingMessages(ArrayList<MessageObject> messages) {
        this.forwardingMessages = messages;
    }
    
    /**
     * 获取要强制转发的消息
     */
    public ArrayList<MessageObject> getForwardingMessages() {
        return forwardingMessages;
    }
    
    /**
     * 重置强制转发状态
     */
    public void resetForceForwardMode() {
        isForceForwardMode = false;
        forwardingMessages = null;
    }
    
    /**
     * 获取消息标题
     */
    private CharSequence getMessageCaption(MessageObject mo, MessageObject.GroupedMessages validGroupedMessage) {
        return parentFragment.getMessageCaption(mo, validGroupedMessage, null);
    }
    
    /**
     * 获取有效的分组消息
     */
    private MessageObject.GroupedMessages getValidGroupedMessage(MessageObject mo) {
        return parentFragment.getValidGroupedMessage(mo);
    }
    
    /**
     * 执行强制转发
     */
    public void runForceForward(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo) {
        if (messagesToSend == null || messagesToSend.isEmpty() || parentFragment.getParentActivity() == null) return;

        try {
                // 1) ensure media downloaded
                ArrayList<MessageObject> needDownload = new ArrayList<>();
                for (MessageObject mo : messagesToSend) {
                    if (mo == null || mo.messageOwner == null) continue;
                    boolean hasMedia = mo.getDocument() != null || mo.isPhoto();
                    if (!hasMedia) continue;
                    File f = xyz.nextalone.nagram.helper.MessageHelper.INSTANCE.getPathToMessage(mo);
                    if (f == null || !f.exists()) {
                        needDownload.add(mo);
                    }
                }

                // trigger downloads
                for (MessageObject mo : needDownload) {
                    if (mo.getDocument() != null) {
                        FileLoader.getInstance(currentAccount).loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_HIGH, 0);
                    } else if (mo.isPhoto()) {
                        TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(MessageObject.getPhoto(mo.messageOwner).sizes, AndroidUtilities.getPhotoSize());
                        if (size != null) {
                            FileLoader.getInstance(currentAccount).loadFile(ImageLocation.getForObject(size, mo.messageOwner), mo.messageOwner, "jpg", FileLoader.PRIORITY_HIGH, 0);
                        }
                    }
                }

                // wait loop (polling) - 增强的下载等待机制
                int attempts = 0;
                int maxAttempts = 600; // up to ~60s
                while (!needDownload.isEmpty() && attempts < maxAttempts) {
                    Iterator<MessageObject> it = needDownload.iterator();
                    while (it.hasNext()) {
                        MessageObject mo = it.next();
                        File f = xyz.nextalone.nagram.helper.MessageHelper.INSTANCE.getPathToMessage(mo);
                        if (f != null && f.exists()) {
                            it.remove();
                            }
                        }
                               

                               
                        Thread.sleep(100);
                            attempts++;
                        }
                           
                        // 如果仍有文件未下载完成，但不阻塞发送过程
                        if (!needDownload.isEmpty()) {
                            FileLog.w("Some media files not fully downloaded, proceeding anyway: " + needDownload.size());
                        }

                // 2) send messages as copies
                //    - 文本：直接使用原始 messageOwner.message，重新生成 entities，避免受限聊天的取文案失败
                //    - 相册：按 grouped_id 聚合，使用 prepareSendingMedia(..., groupMedia=true) 发送为相册
                //    - 其他：按原有逐条路径

                // 收集相册与零散媒体（按原始顺序即时发送，分组在遇到组尾时发送，确保顺序与位置保持不变）
                java.util.HashMap<Long, java.util.ArrayList<SendMessagesHelper.SendingMediaInfo>> albumMap = new java.util.HashMap<>();
                java.util.HashMap<Long, java.util.ArrayList<SendMessagesHelper.SendingMediaInfo>> docAlbumMap = new java.util.HashMap<>();
                java.util.HashMap<Long, Integer> albumRemain = new java.util.HashMap<>();
                java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> singlePhotos = new java.util.ArrayList<>();
                java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> singleVideos = new java.util.ArrayList<>();
                java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> singleDocuments = new java.util.ArrayList<>();
                // 预统计每个分组的数量
                for (MessageObject moPre : messagesToSend) {
                    long gidPre = moPre.getGroupId();
                    boolean isGroupedMedia = moPre.isPhoto() || moPre.isVideo();
                    boolean isGroupedDoc = moPre.getDocument() != null && !moPre.isVideo() && !moPre.isSticker() && !moPre.isAnimatedSticker();
                    if (gidPre != 0 && (isGroupedMedia || isGroupedDoc)) {
                        albumRemain.put(gidPre, albumRemain.getOrDefault(gidPre, 0) + 1);
                    }
                }

                for (MessageObject mo : messagesToSend) {
                    CharSequence captionCs = getMessageCaption(mo, getValidGroupedMessage(mo));
                    String caption = captionCs != null ? captionCs.toString() : null;

                    // 先处理纯文本/带文字
                    if (mo.type == MessageObject.TYPE_TEXT || mo.isAnimatedEmoji() || (caption != null && TextUtils.isEmpty(mo.messageOwner.message))) {
                        // 优先取原始文本，避免 getMessageContent 在 noforwards 聊天返回空
                        String text = mo != null && mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message)
                                ? mo.messageOwner.message
                                : (captionCs != null ? captionCs.toString() : null);
                        if (!TextUtils.isEmpty(text)) {
                            java.util.ArrayList<TLRPC.MessageEntity> entities = mo.messageOwner != null && mo.messageOwner.entities != null && !mo.messageOwner.entities.isEmpty()
                                    ? mo.messageOwner.entities
                                    : org.telegram.messenger.MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{text}, true);
                            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, targetDialogId, null, null, null, true, entities, null, null, true, 0, null, false);
                            AndroidUtilities.runOnUIThread(() -> parentFragment.getSendMessagesHelper().sendMessage(params));
                        }
                        continue;
                    }

                    // 照片：收集到相册/单张列表，稍后统一发
                    if (mo.isPhoto()) {
                        File f = xyz.nextalone.nagram.helper.MessageHelper.INSTANCE.getPathToMessage(mo);
                        if (f != null && f.exists()) {
                            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                            info.path = f.getAbsolutePath();
                            info.caption = caption;
                            info.entities = mo.messageOwner != null ? mo.messageOwner.entities : null;
                            long gid = mo.getGroupId();
                            if (gid != 0) {
                                java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> list = albumMap.computeIfAbsent(gid, k -> new java.util.ArrayList<>());
                                list.add(info);
                                Integer remain = albumRemain.get(gid);
                                if (remain != null) {
                                    remain = remain - 1;
                                    if (remain <= 0) {
                                        // 分组最后一项，到此处立即发送该分组，保持与文本等消息的原始相对顺序
                                        java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> toSend = new java.util.ArrayList<>(list);
                                        albumMap.remove(gid);
                                        albumRemain.remove(gid);
                                        SendMessagesHelper.prepareSendingMedia(
                                            parentFragment.getAccountInstance(),
                                            toSend,
                                            targetDialogId,
                                            null,
                                            null,
                                            null,
                                            null,
                                            false,
                                            true,  // groupMedia
                                            null,
                                            true,
                                            0,
                                            parentFragment.getChatMode(),
                                            false,
                                            null,
                                            parentFragment.quickReplyShortcut,
                                            parentFragment.getQuickReplyId(),
                                            0,
                                            false,
                                            0,
                                            parentFragment.getSendMonoForumPeerId(),
                                            parentFragment.getSendMessageSuggestionParams()
                                        );
                                    } else {
                                        albumRemain.put(gid, remain);
                                    }
                                }
                            } else {
                                singlePhotos.add(info);
                            }
                        }
                        continue;
                    }

                    // 视频：也加入相册分组
                    if (mo.isVideo()) {
                        File f = xyz.nextalone.nagram.helper.MessageHelper.INSTANCE.getPathToMessage(mo);
                        if (f != null && f.exists()) {
                            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                            info.path = f.getAbsolutePath();
                            info.caption = caption;
                            info.entities = mo.messageOwner != null ? mo.messageOwner.entities : null;
                            info.isVideo = true;
                            long gid = mo.getGroupId();
                            if (gid != 0) {
                                java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> list = albumMap.computeIfAbsent(gid, k -> new java.util.ArrayList<>());
                                list.add(info);
                                Integer remain = albumRemain.get(gid);
                                if (remain != null) {
                                    remain = remain - 1;
                                    if (remain <= 0) {
                                        java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> toSend = new java.util.ArrayList<>(list);
                                        albumMap.remove(gid);
                                        albumRemain.remove(gid);
                                        SendMessagesHelper.prepareSendingMedia(
                                            parentFragment.getAccountInstance(),
                                            toSend,
                                            targetDialogId,
                                            null,
                                            null,
                                            null,
                                            null,
                                            false,
                                            true,
                                            null,
                                            true,
                                            0,
                                            parentFragment.getChatMode(),
                                            false,
                                            null,
                                            parentFragment.quickReplyShortcut,
                                            parentFragment.getQuickReplyId(),
                                            0,
                                            false,
                                            0,
                                            parentFragment.getSendMonoForumPeerId(),
                                            parentFragment.getSendMessageSuggestionParams()
                                        );
                                    } else {
                                        albumRemain.put(gid, remain);
                                    }
                                }
                            } else {
                                singleVideos.add(info);
                            }
                        }
                        continue;
                    }

                    // GIF消息：像视频一样处理，确保以GIF格式发送而不是文档
                    if (MessageObject.isGifMessage(mo.messageOwner)) {
                        File f = xyz.nextalone.nagram.helper.MessageHelper.INSTANCE.getPathToMessage(mo);
                        if (f != null && f.exists()) {
                            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                            info.path = f.getAbsolutePath();
                            info.caption = caption;
                            info.entities = mo.messageOwner != null ? mo.messageOwner.entities : null;
                            info.isVideo = true; // 标记为视频类型，让prepareSendingMedia正确处理GIF
                            long gid = mo.getGroupId();
                            if (gid != 0) {
                                java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> list = albumMap.computeIfAbsent(gid, k -> new java.util.ArrayList<>());
                                list.add(info);
                                Integer remain = albumRemain.get(gid);
                                if (remain != null) {
                                    remain = remain - 1;
                                    if (remain <= 0) {
                                        java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> toSend = new java.util.ArrayList<>(list);
                                        albumMap.remove(gid);
                                        albumRemain.remove(gid);
                                        SendMessagesHelper.prepareSendingMedia(
                                            parentFragment.getAccountInstance(),
                                            toSend,
                                            targetDialogId,
                                            null,
                                            null,
                                            null,
                                            null,
                                            false,
                                            true,
                                            null,
                                            true,
                                            0,
                                            parentFragment.getChatMode(),
                                            false,
                                            null,
                                            parentFragment.quickReplyShortcut,
                                            parentFragment.getQuickReplyId(),
                                            0,
                                            false,
                                            0,
                                            parentFragment.getSendMonoForumPeerId(),
                                            parentFragment.getSendMessageSuggestionParams()
                                        );
                                    } else {
                                        albumRemain.put(gid, remain);
                                    }
                                }
                            } else {
                                singleVideos.add(info);
                            }
                        }
                        continue;
                    }

                    // 文档（排除视频/贴纸/GIF）：按 grouped_id 收集合并；未分组的逐条发送
                    if (mo.getDocument() != null && !mo.isVideo() && !MessageObject.isGifMessage(mo.messageOwner) && !mo.isSticker() && !mo.isAnimatedSticker()) {
                        File f = xyz.nextalone.nagram.helper.MessageHelper.INSTANCE.getPathToMessage(mo);
                        if (f != null && f.exists()) {
                            SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                            info.path = f.getAbsolutePath();
                            info.caption = caption;
                            info.entities = mo.messageOwner != null ? mo.messageOwner.entities : null;
                            long gid = mo.getGroupId();
                            if (gid != 0) {
                                java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> list = docAlbumMap.computeIfAbsent(gid, k -> new java.util.ArrayList<>());
                                list.add(info);
                                Integer remain = albumRemain.get(gid);
                                if (remain != null) {
                                    remain = remain - 1;
                                    if (remain <= 0) {
                                        java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> toSend = new java.util.ArrayList<>(list);
                                        docAlbumMap.remove(gid);
                                        albumRemain.remove(gid);
                                        // 文档分组：通过 prepareSendingMedia + forceDocument=true 作为一条合并消息发送
                                        SendMessagesHelper.prepareSendingMedia(
                                            parentFragment.getAccountInstance(),
                                            toSend,
                                            targetDialogId,
                                            null,
                                            null,
                                            null,
                                            null,
                                            true,   // forceDocument
                                            false,  // groupMedia=false（文档不走相册图片逻辑）
                                            null,
                                            true,
                                            0,
                                            parentFragment.getChatMode(),
                                            false,
                                            null,
                                            parentFragment.quickReplyShortcut,
                                            parentFragment.getQuickReplyId(),
                                            0,
                                            false,
                                            0,
                                            parentFragment.getSendMonoForumPeerId(),
                                            parentFragment.getSendMessageSuggestionParams()
                                        );
                                    } else {
                                        albumRemain.put(gid, remain);
                                    }
                                }
                            } else {
                                singleDocuments.add(info);
                            }
                        }
                        continue;
                    }

                    // 贴纸
                    if (mo.isSticker() || mo.isAnimatedSticker()) {
                        if (mo.getDocument() != null) {
                            parentFragment.getSendMessagesHelper().sendSticker(mo.getDocument(), null, targetDialogId, null, null, null, null, null, true, 0, false, null, parentFragment.quickReplyShortcut, parentFragment.getQuickReplyId());
                        }
                        continue;
                    }

                    // 兜底：还有原始 messageOwner.message
                    if (mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message)) {
                        String text = mo.messageOwner.message;
                        java.util.ArrayList<TLRPC.MessageEntity> entities = mo.messageOwner.entities != null && !mo.messageOwner.entities.isEmpty()
                                ? mo.messageOwner.entities
                                : org.telegram.messenger.MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{text}, true);
                        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, targetDialogId, null, null, null, true, entities, null, null, true, 0, null, false);
                        AndroidUtilities.runOnUIThread(() -> parentFragment.getSendMessagesHelper().sendMessage(params));
                    }
                }

            // 分组在遍历时遇到末尾已即时发送；此处无需再次统一发送

            // 发送未分组的单张照片
            for (SendMessagesHelper.SendingMediaInfo info : singlePhotos) {
                java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> one = new java.util.ArrayList<>();
                one.add(info);
                SendMessagesHelper.prepareSendingMedia(
                    parentFragment.getAccountInstance(),
                    one,
                    targetDialogId,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false, // single
                    null,
                    true,
                    0,
                    parentFragment.getChatMode(),
                    false,
                    null,
                    parentFragment.quickReplyShortcut,
                    parentFragment.getQuickReplyId(),
                    0,
                    false,
                    0,
                    parentFragment.getSendMonoForumPeerId(),
                    parentFragment.getSendMessageSuggestionParams()
                );
            }

            // 发送未分组的视频（逐条）
            for (SendMessagesHelper.SendingMediaInfo info : singleVideos) {
                java.util.ArrayList<SendMessagesHelper.SendingMediaInfo> one = new java.util.ArrayList<>();
                one.add(info);
                SendMessagesHelper.prepareSendingMedia(
                    parentFragment.getAccountInstance(),
                    one,
                    targetDialogId,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false,
                    null,
                    true,
                    0,
                    parentFragment.getChatMode(),
                    false,
                    null,
                    parentFragment.quickReplyShortcut,
                    parentFragment.getQuickReplyId(),
                    0,
                    false,
                    0,
                    parentFragment.getSendMonoForumPeerId(),
                    parentFragment.getSendMessageSuggestionParams()
                );
            }

            // 发送未分组的文档（逐条）
            for (SendMessagesHelper.SendingMediaInfo info : singleDocuments) {
                SendMessagesHelper.prepareSendingDocument(
                    parentFragment.getAccountInstance(),
                    info.path,
                    info.path,
                    null,
                    info.caption,
                    null,
                    targetDialogId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    true,
                    0,
                    null,
                    parentFragment.quickReplyShortcut,
                    parentFragment.getQuickReplyId(),
                    false
                );
            }

        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}