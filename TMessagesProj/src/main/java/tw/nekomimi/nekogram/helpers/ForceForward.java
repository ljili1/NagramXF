/*
 * This is the source code of Nagramx_Fork for Android.
 * It is licensed under GNU GPL v. 3 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * 
 * https://github.com/Keeperorowner/NagramX_Fork
 * 
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright @Chen_hai, 2025
 */

package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLRPC;

import org.telegram.ui.ChatActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

import xyz.nextalone.nagram.NaConfig;

/**
 * Force Forward Feature Handler
 * Manages force forwarding logic, separated from ChatActivity for better maintainability
 * This class handles forwarding messages as copies to bypass forwarding restrictions
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
     * Check if force forward feature is enabled in settings
     * @return true if force forward is enabled, false otherwise
     */
    public boolean isForceForwardEnabled() {
        return NaConfig.INSTANCE.getForceForward().Bool();
    }
    
    /**
     * Get the current force forward mode status
     * @return true if in force forward mode, false otherwise
     */
    public boolean isForceForwardMode() {
        return isForceForwardMode;
    }
    
    /**
     * Set force forward mode status
     * @param mode true to enable force forward mode, false to disable
     */
    public void setForceForwardMode(boolean mode) {
        this.isForceForwardMode = mode;
    }
    
    /**
     * Set the single message to be forwarded
     * @param message the message object to forward
     */
    public void setForwardingMessage(MessageObject message) {
        this.forwardingMessage = message;
    }
    
    /**
     * Set the grouped messages to be forwarded
     * @param group the grouped messages object containing multiple related messages
     */
    public void setForwardingMessageGroup(MessageObject.GroupedMessages group) {
        this.forwardingMessageGroup = group;
    }
    
    /**
     * Get the single message currently set for forwarding
     * @return the message object to be forwarded, or null if not set
     */
    public MessageObject getForwardingMessage() {
        return forwardingMessage;
    }
    
    /**
     * Get the grouped messages currently set for forwarding
     * @return the grouped messages object, or null if not set
     */
    public MessageObject.GroupedMessages getForwardingMessageGroup() {
        return forwardingMessageGroup;
    }
    
    /**
     * Set multiple messages to be forwarded in batch
     * @param messages list of message objects to forward
     */
    public void setForwardingMessages(ArrayList<MessageObject> messages) {
        this.forwardingMessages = messages;
    }
    
    /**
     * Get the list of messages currently set for batch forwarding
     * @return array list of message objects to be forwarded, or null if not set
     */
    public ArrayList<MessageObject> getForwardingMessages() {
        return forwardingMessages;
    }
    
    /**
     * Reset force forward mode and clear all forwarding data
     * Clears the force forward flag and removes all stored messages
     */
    public void resetForceForwardMode() {
        isForceForwardMode = false;
        forwardingMessages = null;
    }
    
    /**
     * Get the caption text for a message
     * @param mo the message object
     * @param validGroupedMessage the grouped message context
     * @return the caption text as CharSequence
     */
    private CharSequence getMessageCaption(MessageObject mo, MessageObject.GroupedMessages validGroupedMessage) {
        return parentFragment.getMessageCaption(mo, validGroupedMessage, null);
    }
    
    /**
     * Get valid grouped message for a message object
     * @param mo the message object
     * @return the grouped messages object if found, null otherwise
     */
    private MessageObject.GroupedMessages getValidGroupedMessage(MessageObject mo) {
        return parentFragment.getValidGroupedMessage(mo);
    }

    private String resolvePath(MessageObject mo) {
        return FileLoader.getInstance(currentAccount).getPathToMessage(mo.messageOwner).toString();
    }

    private boolean ensureDownloaded(MessageObject mo) {
        if (mo == null || mo.messageOwner == null) return false;
        String path = resolvePath(mo);
        if (TextUtils.isEmpty(path)) return false;
        File f = new File(path);
        if (f.exists()) return true;
        if (mo.getDocument() != null) {
            FileLoader.getInstance(currentAccount).loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_NORMAL, 0);
            return false;
        }
        if (mo.isPhoto()) {
            TLRPC.Photo photo = MessageObject.getPhoto(mo.messageOwner);
            if (photo != null) {
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, 1280);
                if (size != null) {
                    ImageLocation imageLocation = ImageLocation.getForObject(size, mo.messageOwner);
                    if (imageLocation != null) {
                        FileLoader.getInstance(currentAccount).loadFile(imageLocation, mo.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                    }
                }
            }
            return false;
        }
        if (mo.isVideo()) {
            if (mo.getDocument() != null) {
                FileLoader.getInstance(currentAccount).loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_NORMAL, 0);
            } else if (mo.messageOwner != null && mo.messageOwner.media instanceof TLRPC.TL_messageMediaVideo_old) {
                TLRPC.TL_messageMediaVideo_old videoMedia = (TLRPC.TL_messageMediaVideo_old) mo.messageOwner.media;
                if (videoMedia.video_unused != null && videoMedia.video_unused.thumb != null) {
                    ImageLocation imageLocation = ImageLocation.getForObject(videoMedia.video_unused.thumb, mo.messageOwner);
                    if (imageLocation != null) {
                        FileLoader.getInstance(currentAccount).loadFile(imageLocation, mo.messageOwner, "jpg", FileLoader.PRIORITY_NORMAL, 0);
                    }
                }
            }
            return false;
        }
        if (mo.isGif()) {
            if (mo.getDocument() != null) {
                FileLoader.getInstance(currentAccount).loadFile(mo.getDocument(), mo, FileLoader.PRIORITY_NORMAL, 0);
            }
            return false;
        }
        return false;
    }

    private void sendMediaGroup(ArrayList<SendMessagesHelper.SendingMediaInfo> list, long targetDialogId) {
        SendMessagesHelper.prepareSendingMedia(
                parentFragment.getAccountInstance(),
                list,
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
    }

    private void sendDocumentGroup(ArrayList<SendMessagesHelper.SendingMediaInfo> list, long targetDialogId) {
        SendMessagesHelper.prepareSendingMedia(
                parentFragment.getAccountInstance(),
                list,
                targetDialogId,
                null,
                null,
                null,
                null,
                true,
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

    private void addToGroup(long gid,
                            SendMessagesHelper.SendingMediaInfo info,
                            HashMap<Long, ArrayList<SendMessagesHelper.SendingMediaInfo>> map,
                            HashMap<Long, Integer> remain,
                            boolean document,
                            long targetDialogId) {
        ArrayList<SendMessagesHelper.SendingMediaInfo> list = map.computeIfAbsent(gid, k -> new ArrayList<>());
        list.add(info);
        Integer r = remain.get(gid);
        if (r != null) {
            r = r - 1;
            if (r <= 0) {
                ArrayList<SendMessagesHelper.SendingMediaInfo> toSend = new ArrayList<>(list);
                map.remove(gid);
                remain.remove(gid);
                if (document) {
                    sendDocumentGroup(toSend, targetDialogId);
                } else {
                    sendMediaGroup(toSend, targetDialogId);
                }
            } else {
                remain.put(gid, r);
            }
        }
    }
    
/**
     * Execute force forward operation
     * Processes messages and sends them as copies to bypass forwarding restrictions
     * Handles different message types: text, photos, videos, documents, stickers
     * Groups related media messages and sends them as albums when appropriate
     * 
     * @param messagesToSend list of messages to forward
     * @param targetDialogId ID of the target chat/dialog
     * @param showUndo whether to show undo option (currently unused)
     */
    public void runForceForward(ArrayList<MessageObject> messagesToSend, long targetDialogId, boolean showUndo) {
        if (messagesToSend == null || messagesToSend.isEmpty() || parentFragment.getParentActivity() == null) return;
        try {
            HashMap<Long, ArrayList<SendMessagesHelper.SendingMediaInfo>> albumMap = new HashMap<>();
            HashMap<Long, ArrayList<SendMessagesHelper.SendingMediaInfo>> docAlbumMap = new HashMap<>();
            HashMap<Long, Integer> albumRemain = new HashMap<>();
            ArrayList<SendMessagesHelper.SendingMediaInfo> singlePhotos = new ArrayList<>();
            ArrayList<SendMessagesHelper.SendingMediaInfo> singleVideos = new ArrayList<>();

            for (MessageObject moPre : messagesToSend) {
                long gidPre = moPre.getGroupId();
                boolean groupedMedia = moPre.isPhoto() || moPre.isVideo() || moPre.isGif();
                boolean groupedDoc = moPre.getDocument() != null && !moPre.isVideo() && !MessageObject.isGifMessage(moPre.messageOwner) && !moPre.isSticker() && !moPre.isAnimatedSticker();
                if (gidPre != 0 && (groupedMedia || groupedDoc)) {
                    albumRemain.put(gidPre, albumRemain.getOrDefault(gidPre, 0) + 1);
                }
            }

            for (MessageObject mo : messagesToSend) {
                CharSequence captionCs = getMessageCaption(mo, getValidGroupedMessage(mo));
                String caption = captionCs != null ? captionCs.toString() : null;

                if (mo.type == MessageObject.TYPE_TEXT || mo.isAnimatedEmoji() || (caption != null && (mo.messageOwner == null || TextUtils.isEmpty(mo.messageOwner.message)))) {
                    String text = mo != null && mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message)
                            ? mo.messageOwner.message
                            : caption;
                    if (!TextUtils.isEmpty(text)) {
                        ArrayList<TLRPC.MessageEntity> entities = mo.messageOwner != null && mo.messageOwner.entities != null && !mo.messageOwner.entities.isEmpty()
                                ? mo.messageOwner.entities
                                : org.telegram.messenger.MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{text}, true);
                        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, targetDialogId, null, null, null, true, entities, null, null, true, 0, null, false);
                        AndroidUtilities.runOnUIThread(() -> parentFragment.getSendMessagesHelper().sendMessage(params));
                    }
                    continue;
                }

                if (mo.isPhoto()) {
                    if (!ensureDownloaded(mo)) continue;
                    String filePath = resolvePath(mo);
                    SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                    info.path = filePath;
                    info.caption = caption;
                    info.entities = mo.messageOwner != null ? mo.messageOwner.entities : null;
                    long gid = mo.getGroupId();
                    if (gid != 0) {
                        addToGroup(gid, info, albumMap, albumRemain, false, targetDialogId);
                    } else {
                        singlePhotos.add(info);
                    }
                    continue;
                }

                if (mo.isVideo() || mo.isGif()) {
                    if (!ensureDownloaded(mo)) continue;
                    String filePath = resolvePath(mo);
                    SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                    info.path = filePath;
                    info.caption = caption;
                    info.entities = mo.messageOwner != null ? mo.messageOwner.entities : null;
                    info.isVideo = true;
                    long gid = mo.getGroupId();
                    if (gid != 0) {
                        addToGroup(gid, info, albumMap, albumRemain, false, targetDialogId);
                    } else {
                        singleVideos.add(info);
                    }
                    continue;
                }

                if (mo.getDocument() != null && !mo.isVideo() && !MessageObject.isGifMessage(mo.messageOwner) && !mo.isSticker() && !mo.isAnimatedSticker()) {
                    if (!ensureDownloaded(mo)) continue;
                    String filePath = resolvePath(mo);
                    long gid = mo.getGroupId();
                    if (gid != 0) {
                        SendMessagesHelper.SendingMediaInfo info = new SendMessagesHelper.SendingMediaInfo();
                        info.path = filePath;
                        info.caption = caption;
                        info.entities = mo.messageOwner != null ? mo.messageOwner.entities : null;
                        addToGroup(gid, info, docAlbumMap, albumRemain, true, targetDialogId);
                    } else {
                        SendMessagesHelper.prepareSendingDocument(
                                parentFragment.getAccountInstance(),
                                filePath,
                                filePath,
                                null,
                                caption,
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
                    continue;
                }

                if (mo.isSticker() || mo.isAnimatedSticker()) {
                    if (mo.getDocument() != null) {
                        parentFragment.getSendMessagesHelper().sendSticker(mo.getDocument(), null, targetDialogId, null, null, null, null, null, true, 0, false, null, parentFragment.quickReplyShortcut, parentFragment.getQuickReplyId());
                    }
                    continue;
                }

                if (mo.messageOwner != null && !TextUtils.isEmpty(mo.messageOwner.message)) {
                    String text = mo.messageOwner.message;
                    ArrayList<TLRPC.MessageEntity> entities = mo.messageOwner.entities != null && !mo.messageOwner.entities.isEmpty()
                            ? mo.messageOwner.entities
                            : org.telegram.messenger.MediaDataController.getInstance(currentAccount).getEntities(new CharSequence[]{text}, true);
                    SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, targetDialogId, null, null, null, true, entities, null, null, true, 0, null, false);
                    AndroidUtilities.runOnUIThread(() -> parentFragment.getSendMessagesHelper().sendMessage(params));
                }
            }

            for (ArrayList<SendMessagesHelper.SendingMediaInfo> group : albumMap.values()) {
                sendMediaGroup(new ArrayList<>(group), targetDialogId);
            }
            for (ArrayList<SendMessagesHelper.SendingMediaInfo> group : docAlbumMap.values()) {
                sendDocumentGroup(new ArrayList<>(group), targetDialogId);
            }

            for (SendMessagesHelper.SendingMediaInfo info : singlePhotos) {
                ArrayList<SendMessagesHelper.SendingMediaInfo> one = new ArrayList<>();
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

            for (SendMessagesHelper.SendingMediaInfo info : singleVideos) {
                ArrayList<SendMessagesHelper.SendingMediaInfo> one = new ArrayList<>();
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
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
}