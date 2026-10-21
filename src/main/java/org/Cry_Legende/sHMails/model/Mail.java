package org.Cry_Legende.sHMails.model;

import java.util.UUID;

public class Mail {
    private final UUID mailId;
    private final UUID senderUUID;
    private final String senderName;
    private final UUID recipientUUID;
    private String message;
    private final long timestamp;
    // boolean read; // Supprimé

    public Mail(UUID senderUUID, String senderName, UUID recipientUUID, String message) {
        this.mailId = UUID.randomUUID();
        this.senderUUID = senderUUID;
        this.senderName = senderName;
        this.recipientUUID = recipientUUID;
        this.message = message;
        this.timestamp = System.currentTimeMillis();
        // this.read = false; // Supprimé
    }

    // Constructeur pour le chargement
    public Mail(UUID mailId, UUID senderUUID, String senderName, UUID recipientUUID, String message, long timestamp /*, boolean read (supprimé) */) {
        this.mailId = mailId;
        this.senderUUID = senderUUID;
        this.senderName = senderName;
        this.recipientUUID = recipientUUID;
        this.message = message;
        this.timestamp = timestamp;
        // this.read = read; // Supprimé
    }

    public UUID getMailId() {
        return mailId;
    }

    public UUID getSenderUUID() {
        return senderUUID;
    }

    public String getSenderName() {
        return senderName;
    }

    public UUID getRecipientUUID() {
        return recipientUUID;
    }

    public String getMessage() {
        return message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    // public boolean isRead() { return read; } // Supprimé
    // public void setRead(boolean read) { this.read = read; } // Supprimé
    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Mail mail = (Mail) o;
        return mailId.equals(mail.mailId);
    }

    @Override
    public int hashCode() {
        return mailId.hashCode();
    }
}
