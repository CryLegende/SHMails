package org.Cry_Legende.sHMails.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.manager.ConfigManager;
import org.Cry_Legende.sHMails.manager.MailManager;
import org.Cry_Legende.sHMails.model.Mail;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Réponse à un mail par dialogue natif (clients 1.21.6 et plus), à la place du « tapez votre message dans le chat ».
 *
 * <p>Les clients plus anciens, ou {@code dialogues.actif: false}, gardent le mode réponse par chat
 * ({@code PlayerChatListener}), qui n'est pas touché.</p>
 *
 * <p>Sécurité : un joueur n'a qu'une réponse en attente. Chaque dialogue porte un jeton aléatoire inscrit dans la clé
 * de ses boutons ({@code shmails:reply/<jeton>/<action>}). Un clic n'est pris en compte que si le jeton est celui du
 * dialogue en cours du joueur et si la saisie n'est pas périmée ; elle est alors retirée (usage unique) avant l'envoi.
 * Le texte est contrôlé côté serveur comme celui du chat (256 caractères, ni § ni caractère de contrôle), puis envoyé
 * par {@code MailManager#sendMail}, qui applique la limite de boîte pleine. Tout se passe sur le thread principal.</p>
 */
public final class ReplyDialogs {

    /** Espace de noms des clés de boutons ; le listener ignore tout le reste. */
    public static final String NAMESPACE = "shmails";
    /** Clé du champ de saisie dans la réponse du client. */
    public static final String INPUT = "message";

    private static final String KIND = "reply";
    private static final String ACTION_OK = "ok";
    private static final String ACTION_CANCEL = "cancel";

    private static final int WIDTH = 250;
    private static final int BUTTON_WIDTH = 120;
    /** Limite d'un message de chat Minecraft : la même pour une réponse. */
    private static final int MAX_LENGTH = 256;
    private static final long DEFAULT_TTL_SECONDS = 300L;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    /** Réponse en attente : le mail auquel on répond, tel que le joueur l'a vu à l'ouverture. */
    private record Pending(UUID mailId, UUID senderUuid, String senderName, String mailMessage, long mailTimestamp,
                           String token, long expiresAtMillis) {
        Pending renewed(String newToken, long newExpiry) {
            return new Pending(mailId, senderUuid, senderName, mailMessage, mailTimestamp, newToken, newExpiry);
        }
    }

    private final SHMails plugin;
    private final ConcurrentHashMap<UUID, Pending> pending = new ConcurrentHashMap<>();
    // Dernier jeton consommé par joueur : un double clic qui arrive après le premier est ignoré sans bruit.
    private final ConcurrentHashMap<UUID, String> lastConsumed = new ConcurrentHashMap<>();

    public ReplyDialogs(SHMails plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ disponibilité

    /**
     * Ce client peut-il recevoir un dialogue ? Sans ViaVersion, tous les clients sont natifs (1.21.11).
     */
    public boolean supports(Player player) {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("dialogues.actif", true)) {
            return false;
        }
        if (!plugin.getServer().getPluginManager().isPluginEnabled("ViaVersion")) {
            return true;
        }
        try {
            return ViaSupport.protocol(player) >= config.getInt("dialogues.min-protocol", 771);
        } catch (LinkageError e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ ouverture

    /**
     * Ouvre le dialogue de réponse à ce mail. Renvoie false si le mode réponse par chat doit être utilisé (client trop
     * ancien, dialogues désactivés ou erreur d'affichage) : l'appelant continue alors comme avant.
     */
    public boolean open(Player player, Mail mail) {
        if (mail == null) {
            return false;
        }
        Pending state = new Pending(mail.getMailId(), mail.getSenderUUID(), mail.getSenderName(), mail.getMessage(),
                mail.getTimestamp(), newToken(), expiry());
        if (!show(player, state, null, null, true)) {
            return false;
        }
        // Un éventuel mode réponse par chat resté en attente ne doit pas intercepter la suite.
        plugin.replyingToOriginalMailId.remove(player.getUniqueId());
        plugin.replyingToSenderUUID.remove(player.getUniqueId());
        plugin.replyingToSenderName.remove(player.getUniqueId());
        return true;
    }

    private boolean show(Player player, Pending state, Component error, String prefill, boolean closeInventory) {
        try {
            if (!player.isOnline() || !supports(player)) {
                return false;
            }
            Dialog dialog = build(state, error, prefill);
            pending.put(player.getUniqueId(), state);
            if (closeInventory) {
                player.closeInventory();
            }
            player.showDialog(dialog);
            return true;
        } catch (RuntimeException | LinkageError e) {
            pending.remove(player.getUniqueId(), state);
            plugin.getLogger().log(Level.WARNING, "Dialogue impossible pour " + player.getName() + ", repli sur le chat.", e);
            return false;
        }
    }

    private Dialog build(Pending state, Component error, String prefill) {
        MailManager mails = plugin.getMailManager();
        Map<String, Component> placeholders = new HashMap<>();
        placeholders.put("sender", Component.text(String.valueOf(state.senderName())));
        placeholders.put("date", Component.text(mails.formatDate(state.mailTimestamp())));
        // Le texte du mail est celui d'un joueur : affiché tel quel, jamais interprété (pas de codes couleur).
        placeholders.put("message", Component.text(state.mailMessage() == null || state.mailMessage().isEmpty()
                ? "(Message vide)" : state.mailMessage()));
        placeholders.put("max", Component.text(String.valueOf(MAX_LENGTH)));

        List<DialogBody> body = new ArrayList<>();
        if (error != null) {
            body.add(DialogBody.plainMessage(error, WIDTH));
        }
        for (String line : texts("dialogues.reponse.corps")) {
            body.add(DialogBody.plainMessage(render(line, placeholders), WIDTH));
        }

        DialogInput input = DialogInput.text(INPUT, render(text("dialogues.reponse.champ", "&fVotre réponse"), placeholders))
                .width(WIDTH)
                .maxLength(MAX_LENGTH)
                .initial(prefill == null ? "" : prefill)
                .build();

        ActionButton send = button(text("dialogues.reponse.envoyer", "&a&lEnvoyer"), state, ACTION_OK, placeholders);
        ActionButton cancel = button(text("dialogues.reponse.annuler", "&c&lAnnuler"), state, ACTION_CANCEL, placeholders);

        DialogBase base = DialogBase.builder(render(text("dialogues.reponse.titre", "&lRépondre"), placeholders))
                // Échap = bouton « Annuler » : le client lance son action, la réponse est annulée proprement.
                .canCloseWithEscape(true)
                // L'écran reste affiché tant que le serveur n'a pas répondu (fermeture ou nouvel écran) : pas de double clic.
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(List.of(input))
                .build();
        return Dialog.create(factory -> factory.empty().base(base).type(DialogType.confirmation(send, cancel)));
    }

    private ActionButton button(String label, Pending state, String action, Map<String, Component> placeholders) {
        Key key = Key.key(NAMESPACE, KIND + "/" + state.token() + "/" + action);
        return ActionButton.builder(render(label, placeholders))
                .width(BUTTON_WIDTH)
                .action(DialogAction.customClick(key, null))
                .build();
    }

    private String text(String path, String def) {
        String value = plugin.getConfig().getString(path);
        return value == null ? def : value;
    }

    private List<String> texts(String path) {
        return plugin.getConfig().getStringList(path);
    }

    /** Texte de config.yml (codes &amp; et &amp;#RRGGBB) dont les %variables% sont remplacées par des composants. */
    private static Component render(String template, Map<String, Component> placeholders) {
        Component out = LEGACY.deserialize(template == null ? "" : template);
        for (Map.Entry<String, Component> entry : placeholders.entrySet()) {
            out = out.replaceText(TextReplacementConfig.builder()
                    .matchLiteral("%" + entry.getKey() + "%")
                    .replacement(entry.getValue())
                    .build());
        }
        return out;
    }

    private long expiry() {
        long ttl = Math.max(10L, plugin.getConfig().getLong("dialogues.expiration-secondes", DEFAULT_TTL_SECONDS));
        return System.currentTimeMillis() + ttl * 1000L;
    }

    private static String newToken() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    // ------------------------------------------------------------------ clics

    /**
     * Point d'entrée des clics (appelé par le listener, éventuellement hors du thread principal).
     *
     * @param value partie « valeur » de la clé du bouton : {@code reply/<jeton>/<action>}
     * @param text  contenu du champ de réponse (null si absent)
     */
    void onClick(Player player, String value, String text) {
        sync(() -> {
            try {
                handle(player, value, text);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Erreur pendant la réponse par dialogue de " + player.getName() + " :", e);
                pending.remove(player.getUniqueId());
                safeClose(player);
                try {
                    player.sendMessage(plugin.getConfigManager().getMessage("error_occurred"));
                } catch (RuntimeException ignored) {
                    // le joueur est peut-être parti : rien d'autre à faire
                }
            }
        });
    }

    private void handle(Player player, String value, String rawText) {
        if (!player.isOnline()) {
            return;
        }
        String[] parts = value == null ? new String[0] : value.split("/", -1);
        if (parts.length != 3 || !KIND.equals(parts[0])) {
            return;
        }
        String token = parts[1];
        String action = parts[2];
        if (!ACTION_OK.equals(action) && !ACTION_CANCEL.equals(action)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        ConfigManager config = plugin.getConfigManager();

        Pending state = pending.get(uuid);
        if (state == null) {
            // Double clic déjà traité : rien à dire.
            if (token.equals(lastConsumed.get(uuid))) {
                return;
            }
            // Écran resté ouvert après un rechargement ou une expiration : on le ferme.
            safeClose(player);
            player.sendMessage(config.getMessage("dialog_expired"));
            return;
        }
        // Jeton d'un ancien dialogue : ce n'est pas le dialogue en cours, on ignore.
        if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), state.token().getBytes(StandardCharsets.UTF_8))) {
            return;
        }
        // Usage unique : le premier traitement retire la saisie, un second clic ne trouvera plus rien.
        if (!pending.remove(uuid, state)) {
            return;
        }
        lastConsumed.put(uuid, state.token());

        if (System.currentTimeMillis() > state.expiresAtMillis()) {
            safeClose(player);
            player.sendMessage(config.getMessage("dialog_expired"));
            return;
        }

        String content = rawText == null ? "" : rawText.trim();
        // Bouton Annuler (ou Échap), ou mot-clé d'annulation tapé dans le champ comme dans le chat.
        if (ACTION_CANCEL.equals(action) || content.equalsIgnoreCase("cancel") || content.equalsIgnoreCase("annuler")) {
            safeClose(player);
            player.sendMessage(config.getMessage("reply_cancelled"));
            config.playSound(player, "reply_mode_cancel");
            return;
        }
        if (content.isEmpty()) {
            retry(player, state, "dialogues.reponse.erreurs.vide", content);
            return;
        }
        if (content.length() > MAX_LENGTH || !isAllowedText(content)) {
            retry(player, state, "dialogues.reponse.erreurs.invalide", content);
            return;
        }

        safeClose(player);
        if (state.senderUuid() == null || state.senderName() == null) {
            player.sendMessage(config.getMessage("error_occurred")); // Erreur interne
            config.playSound(player, "action_error");
            return;
        }
        MailManager mails = plugin.getMailManager();
        // Même suite qu'en mode chat : envoi, puis suppression du mail original et confirmation une fois la réponse
        // remise (pas si la boîte du destinataire est pleine).
        mails.sendMail(uuid, player.getName(), state.senderUuid(), content, () -> {
            mails.deleteMail(uuid, state.mailId());
            if (player.isOnline()) {
                player.sendMessage(config.getMessage("reply_message_sent", "%recipient%", state.senderName()));
            }
        });
        // Le son "mail_sent" est déjà joué par mailManager.sendMail
    }

    /** Saisie refusée avant l'envoi : on réaffiche le dialogue avec l'erreur, sans perdre ce que le joueur a tapé. */
    private void retry(Player player, Pending state, String errorPath, String typed) {
        Component error = render(text(errorPath, "&cMessage invalide."), Map.of("max", Component.text(String.valueOf(MAX_LENGTH))));
        Pending next = state.renewed(newToken(), expiry());
        String prefill = typed.length() > MAX_LENGTH ? typed.substring(0, MAX_LENGTH) : typed;
        if (!show(player, next, error, prefill, false)) {
            // Impossible de réafficher (client parti, erreur) : on ne laisse pas l'écran d'attente.
            safeClose(player);
        }
    }

    /** Mêmes caractères que le chat de Minecraft : ni § ni caractères de contrôle. */
    static boolean isAllowedText(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < ' ' || c == '§' || c == 127) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ cycle de vie

    /** Départ du joueur : plus rien n'est en attente pour lui. */
    public void forget(UUID uuid) {
        pending.remove(uuid);
        lastConsumed.remove(uuid);
    }

    /** Arrêt du plugin : on ne laisse aucun joueur devant un dialogue qui ne répondra plus. */
    public void shutdown() {
        for (UUID uuid : new ArrayList<>(pending.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                safeClose(player);
            }
        }
        pending.clear();
        lastConsumed.clear();
    }

    private void safeClose(Player player) {
        try {
            player.closeDialog();
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.FINE, "Fermeture du dialogue impossible pour " + player.getName(), e);
        }
    }

    private void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }
}
