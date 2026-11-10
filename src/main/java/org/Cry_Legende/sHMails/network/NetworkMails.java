package org.Cry_Legende.sHMails.network;

import com.google.gson.JsonObject;
import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.manager.ConfigManager;
import org.Cry_Legende.sHMails.model.Mail;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Mode réseau : les boîtes aux lettres vivent dans la base partagée, chaque serveur n'en garde en mémoire que
 * celles des joueurs qui s'y trouvent (et des boîtes qu'un admin consulte).
 *
 * <ul>
 *   <li>à l'arrivée d'un joueur : une fois son départ confirmé par l'autre serveur, sa boîte est lue en base hors
 *       du fil principal, puis la notification de connexion est envoyée ;</li>
 *   <li>à l'envoi : le mail est déposé en base, la limite de la boîte comptée sous verrou, puis tous les serveurs en
 *       sont prévenus par le bus : celui du destinataire le notifie et met sa boîte à jour, tous préviennent leurs
 *       espions ;</li>
 *   <li>à la suppression d'un mail ou d'une boîte : écriture en base, puis message du bus pour que les autres
 *       serveurs relisent la boîte qu'ils ont en mémoire ;</li>
 *   <li>au départ : la barrière de SHNetwork attend que ces écritures soient validées en base.</li>
 * </ul>
 *
 * Tout ce qui touche à la base passe par un fil d'écriture unique : jamais de SQL sur le fil principal.
 */
public final class NetworkMails {

    private static final int LOAD_ATTEMPTS = 3;
    private static final String TYPE = "t";

    /** Joueur trouvé : UUID hors ligne et pseudo exact. */
    public record Known(UUID uuid, String name) {
    }

    private final SHMails plugin;
    private final MailDatabase db;
    private final NetworkLink link;
    private final Duration waitMax;
    private final String channel;
    private final String firstSeenTable;
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SHMails-reseau");
        thread.setDaemon(true);
        return thread;
    });
    /** Boîtes en mémoire. Les listes ne sont modifiées que sur le fil principal. */
    private final Map<UUID, List<Mail>> boxes = new ConcurrentHashMap<>();
    /** Nombre de modifications locales de chaque boîte : une relecture lancée avant une modification est refaite. Fil principal. */
    private final Map<UUID, Integer> versions = new HashMap<>();
    /** Chargements d'arrivée en cours. */
    private final Map<UUID, CompletableFuture<Void>> joinLoads = new ConcurrentHashMap<>();
    /** Joueurs qui ont reçu un mail ou vu leur boîte changer pendant leur chargement : relus une fois chargés. */
    private final Set<UUID> stale = ConcurrentHashMap.newKeySet();

    public NetworkMails(SHMails plugin, MailDatabase db, NetworkLink link, Duration waitMax, String channel, String firstSeenTable) {
        this.plugin = plugin;
        this.db = db;
        this.link = link;
        this.waitMax = waitMax;
        this.channel = channel;
        this.firstSeenTable = firstSeenTable;
    }

    /** Branche le mode réseau : sauvegarde au départ, écoute du bus, chargement des joueurs déjà connectés. */
    public void start() {
        link.registerSaveTask(player -> flush());
        link.listen(channel, this::onMessage);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            joined(online, null);
        }
    }

    /** Termine les écritures en cours puis ferme la base. */
    public void stop() {
        io.shutdown();
        try {
            if (!io.awaitTermination(10, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Des écritures de mails n'ont pas pu être terminées à l'arrêt.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        db.close();
    }

    // ------------------------------------------------------------------ boîtes en mémoire

    /** Boîte en mémoire ; vide (non modifiable) si elle n'est pas chargée : voir {@link #ensureLoaded}. */
    public List<Mail> mails(UUID uuid) {
        List<Mail> list = boxes.get(uuid);
        return list != null ? list : Collections.emptyList();
    }

    /** Boîte d'un joueur parti ou d'un admin qui a fini de la consulter : libérée (tout est déjà en base). */
    public void drop(UUID uuid) {
        boxes.remove(uuid);
        versions.remove(uuid);
        stale.remove(uuid);
        joinLoads.remove(uuid);
    }

    /**
     * Arrivée d'un joueur : boîte et espionnage lus en base une fois le départ de l'autre serveur confirmé.
     *
     * @param afterLoad lancé sur le fil principal avec le nombre de mails, une fois la boîte en mémoire (peut être null)
     */
    public void joined(Player player, Consumer<Integer> afterLoad) {
        UUID uuid = player.getUniqueId();
        CompletableFuture<Void> done = new CompletableFuture<>();
        joinLoads.put(uuid, done);
        link.awaitSaved(uuid, waitMax).whenComplete((saved, error) -> {
            if (error != null || !Boolean.TRUE.equals(saved)) {
                plugin.getLogger().warning("Départ de " + uuid + " non confirmé par l'autre serveur"
                    + (error != null ? " (" + error + ")" : "") + " : boîte relue quand même.");
            }
            submit(() -> loadOnJoin(uuid, done, afterLoad));
        });
    }

    private void loadOnJoin(UUID uuid, CompletableFuture<Void> done, Consumer<Integer> afterLoad) {
        if (plugin.getServer().getPlayer(uuid) == null) {
            main(() -> {
                joinLoads.remove(uuid, done);
                done.complete(null);
            });
            return;
        }
        SQLException last = null;
        for (int attempt = 1; attempt <= LOAD_ATTEMPTS; attempt++) {
            try {
                List<Mail> list = db.loadBox(uuid);
                boolean spy = db.isSpy(uuid);
                main(() -> finishJoin(uuid, list, spy, done, afterLoad));
                return;
            } catch (SQLException e) {
                last = e;
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        plugin.getLogger().log(Level.SEVERE, "Boîte de " + uuid + " non lue en base : " + (last == null ? "interrompu" : last.getMessage()));
        SQLException failure = last;
        main(() -> {
            joinLoads.remove(uuid, done);
            done.completeExceptionally(failure != null ? failure : new IllegalStateException("interrompu"));
        });
    }

    private void finishJoin(UUID uuid, List<Mail> list, boolean spy, CompletableFuture<Void> done, Consumer<Integer> afterLoad) {
        joinLoads.remove(uuid, done);
        Player player = plugin.getServer().getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            done.complete(null);
            return;
        }
        boxes.put(uuid, new ArrayList<>(list));
        if (spy) {
            plugin.getMailSpies().add(uuid);
        }
        done.complete(null);
        if (stale.remove(uuid)) {
            refresh(uuid);
        }
        if (afterLoad != null) {
            afterLoad.accept(list.size());
        }
    }

    /**
     * Rend la boîte disponible puis lance {@code onLoaded} sur le fil principal. Un joueur qui vient d'arriver
     * attend la fin de son chargement d'arrivée (la barrière de passage) plutôt que de lire la base trop tôt.
     *
     * @param onFailed lancé sur le fil principal si la base ne répond pas (peut être null)
     */
    public void ensureLoaded(UUID uuid, Runnable onLoaded, Runnable onFailed) {
        CompletableFuture<Void> pending = joinLoads.get(uuid);
        if (boxes.containsKey(uuid)) {
            main(onLoaded);
            return;
        }
        if (pending != null) {
            pending.whenComplete((v, error) -> main(() -> {
                if (error != null) {
                    if (onFailed != null) onFailed.run();
                } else if (boxes.containsKey(uuid)) {
                    onLoaded.run();
                } else {
                    // joueur reparti pendant son chargement : on lit sa boîte pour celui qui la demande
                    loadForViewer(uuid, onLoaded, onFailed);
                }
            }));
            return;
        }
        loadForViewer(uuid, onLoaded, onFailed);
    }

    private void loadForViewer(UUID uuid, Runnable onLoaded, Runnable onFailed) {
        submit(() -> {
            List<Mail> list;
            try {
                list = db.loadBox(uuid);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Boîte de " + uuid + " non lue en base : " + e.getMessage());
                if (onFailed != null) {
                    main(onFailed);
                }
                return;
            }
            main(() -> {
                boxes.putIfAbsent(uuid, new ArrayList<>(list));
                onLoaded.run();
            });
        });
    }

    // ------------------------------------------------------------------ envoi

    /**
     * Dépose un mail. Rien n'est lu ni gardé en mémoire pour le destinataire : la base compte sa boîte sous verrou,
     * puis le bus prévient les serveurs.
     *
     * @param recipientName pseudo connu du destinataire, ou null
     * @param onDelivered   lancé sur le fil principal une fois le mail remis, jamais si la boîte est pleine (peut être null)
     */
    public void send(UUID senderUuid, String senderName, UUID recipientUuid, String recipientName, String content, Runnable onDelivered) {
        Mail mail = new Mail(senderUuid, senderName, recipientUuid, content);
        int max = plugin.getConfigManager().getMaxMailsPerPlayer();
        submit(() -> {
            MailDatabase.Delivery result;
            try {
                result = db.deliver(mail, max);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Mail de " + senderName + " pour " + recipientUuid + " non déposé en base : " + e.getMessage());
                main(() -> {
                    Player sender = Bukkit.getPlayer(senderUuid);
                    if (sender != null && sender.isOnline()) {
                        sender.sendMessage(plugin.getConfigManager().getMessage("error_occurred"));
                        plugin.getConfigManager().playSound(sender, "action_error");
                    }
                });
                return;
            }
            main(() -> delivered(mail, recipientName, result, onDelivered));
        });
    }

    /** Fil principal : suite d'un dépôt, dans le même ordre qu'avec un seul serveur. */
    private void delivered(Mail mail, String recipientName, MailDatabase.Delivery result, Runnable onDelivered) {
        ConfigManager config = plugin.getConfigManager();
        String name = nameOf(mail.getRecipientUUID(), recipientName);
        Player senderOnline = Bukkit.getPlayer(mail.getSenderUUID());
        if (!result.stored()) {
            if (senderOnline != null && senderOnline.isOnline()) {
                senderOnline.sendMessage(config.getMessage("recipient_mailbox_full",
                    "%recipient%", name != null ? name : "Joueur Inconnu",
                    "%current%", String.valueOf(result.count()),
                    "%max%", String.valueOf(config.getMaxMailsPerPlayer())));
                config.playSound(senderOnline, "action_error");
            }
            return;
        }
        addToBox(mail);
        notifyRecipient(mail);
        if (onDelivered != null) {
            onDelivered.run();
        }
        if (senderOnline != null && senderOnline.isOnline()) {
            senderOnline.sendMessage(config.getMessage("mail_sent", "%recipient%", name != null ? name : "Joueur Inconnu"));
            config.playSound(senderOnline, "mail_sent");
        }
        tellSpies(mail, name);

        JsonObject o = new JsonObject();
        o.addProperty(TYPE, "mail");
        o.addProperty("id", mail.getMailId().toString());
        o.addProperty("from", mail.getSenderUUID().toString());
        o.addProperty("fromName", mail.getSenderName());
        o.addProperty("to", mail.getRecipientUUID().toString());
        if (name != null) {
            o.addProperty("toName", name);
        }
        o.addProperty("msg", mail.getMessage());
        o.addProperty("ts", mail.getTimestamp());
        link.publish(channel, o);
    }

    private void notifyRecipient(Mail mail) {
        Player recipient = Bukkit.getPlayer(mail.getRecipientUUID());
        if (recipient == null || !recipient.isOnline()) {
            return;
        }
        ConfigManager config = plugin.getConfigManager();
        recipient.sendMessage(config.getMessage("mail_received_notification", "%sender%", mail.getSenderName()));
        config.playSound(recipient, "mail_received");
    }

    private void tellSpies(Mail mail, String recipientName) {
        String spyMessage = plugin.getConfigManager().getRawMessage("admin_spy_format",
            "%sender%", mail.getSenderName(),
            "%recipient%", recipientName != null ? recipientName : "Inconnu",
            "%message%", mail.getMessage());
        for (UUID spyUuid : plugin.getMailSpies()) {
            Player spy = Bukkit.getPlayer(spyUuid);
            if (spy != null && spy.isOnline()) {
                spy.sendMessage(spyMessage);
            }
        }
    }

    /** Ajoute le mail à la boîte du destinataire si ce serveur la garde en mémoire (sans doublon). */
    private void addToBox(Mail mail) {
        List<Mail> box = boxes.get(mail.getRecipientUUID());
        if (box == null || box.contains(mail)) {
            return;
        }
        box.add(mail);
        box.sort(Comparator.comparingLong(Mail::getTimestamp).reversed());
    }

    // ------------------------------------------------------------------ suppression

    /** Fil principal : retire le mail de la boîte en mémoire, de la base, puis prévient les autres serveurs. */
    public void deleteMail(UUID owner, UUID mailId) {
        List<Mail> box = boxes.get(owner);
        if (box != null) {
            box.removeIf(m -> m.getMailId().equals(mailId));
        }
        bump(owner);
        submit(() -> {
            try {
                db.deleteMail(mailId);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Mail " + mailId + " non supprimé en base : " + e.getMessage());
                return;
            }
            announceChange(owner);
        });
    }

    /** Fil principal : vide la boîte en mémoire et en base, puis prévient les autres serveurs. */
    public void clearBox(UUID owner) {
        List<Mail> box = boxes.get(owner);
        if (box != null) {
            box.clear();
        }
        bump(owner);
        submit(() -> {
            try {
                db.deleteBox(owner);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Boîte de " + owner + " non vidée en base : " + e.getMessage());
                return;
            }
            announceChange(owner);
        });
    }

    private void announceChange(UUID owner) {
        JsonObject o = new JsonObject();
        o.addProperty(TYPE, "box");
        o.addProperty("to", owner.toString());
        link.publish(channel, o);
    }

    // ------------------------------------------------------------------ bus

    private void onMessage(JsonObject o) {
        try {
            String type = o.has(TYPE) ? o.get(TYPE).getAsString() : "";
            UUID to = UUID.fromString(o.get("to").getAsString());
            if (type.equals("mail")) {
                Mail mail = new Mail(UUID.fromString(o.get("id").getAsString()), UUID.fromString(o.get("from").getAsString()),
                    o.get("fromName").getAsString(), to, o.get("msg").getAsString(), o.get("ts").getAsLong());
                if (boxes.containsKey(to)) {
                    addToBox(mail);
                } else if (joinLoads.containsKey(to)) {
                    stale.add(to);
                }
                notifyRecipient(mail);
                tellSpies(mail, o.has("toName") ? o.get("toName").getAsString() : nameOf(to, null));
            } else if (type.equals("box")) {
                refresh(to);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Message du bus illisible : " + e);
        }
    }

    /** Fil principal : relit en base la boîte que ce serveur garde en mémoire (changée ailleurs). */
    private void refresh(UUID uuid) {
        if (!boxes.containsKey(uuid)) {
            if (joinLoads.containsKey(uuid)) {
                stale.add(uuid);
            }
            return;
        }
        int version = version(uuid);
        submit(() -> {
            List<Mail> list;
            try {
                list = db.loadBox(uuid);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Boîte de " + uuid + " non relue en base : " + e.getMessage());
                return;
            }
            main(() -> {
                if (!boxes.containsKey(uuid)) {
                    return;
                }
                if (version(uuid) != version) {
                    refresh(uuid); // modifiée ici pendant la lecture : on relit après ses écritures
                    return;
                }
                boxes.put(uuid, new ArrayList<>(list));
            });
        });
    }

    private int version(UUID uuid) {
        return versions.getOrDefault(uuid, 0);
    }

    private void bump(UUID uuid) {
        versions.merge(uuid, 1, Integer::sum);
    }

    // ------------------------------------------------------------------ espions

    /** Fil principal : l'espionnage suit l'admin d'un serveur à l'autre (écrit en base, relu à son arrivée). */
    public void setSpy(UUID uuid, boolean spying) {
        submit(() -> {
            try {
                db.setSpy(uuid, spying);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.SEVERE, "Espionnage de " + uuid + " non écrit en base : " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ joueurs

    /**
     * Retrouve un joueur par son pseudo, sans le fichier de joueur de ce serveur : connecté quelque part sur le réseau,
     * déjà venu sur ce serveur, ou présent dans le registre des premières connexions. Le rappel a lieu sur le fil principal.
     *
     * @param result le joueur, ou null s'il est introuvable
     */
    public void resolve(String name, Consumer<Known> result) {
        NetworkLink.Online online = link.find(name);
        if (online != null) {
            result.accept(new Known(online.uuid(), online.name()));
            return;
        }
        // Pseudo exact (il fixe l'UUID hors ligne) : cache de ce serveur, puis registre des premières connexions
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        String localName = cached != null ? cached.getName() : null;
        if (localName != null && Bukkit.getOfflinePlayer(offlineUuid(localName)).hasPlayedBefore()) {
            result.accept(new Known(offlineUuid(localName), localName));
            return;
        }
        if (firstSeenTable.isEmpty()) {
            result.accept(null);
            return;
        }
        submit(() -> {
            String exact = db.firstSeen(firstSeenTable, name);
            main(() -> result.accept(exact == null ? null : new Known(offlineUuid(exact), exact)));
        });
    }

    /** Pseudo d'un joueur d'après son UUID : connecté sur le réseau, puis le nom connu de l'appelant, puis ce serveur. */
    public String nameOf(UUID uuid, String known) {
        Player local = Bukkit.getPlayer(uuid);
        if (local != null) {
            return local.getName();
        }
        if (known != null) {
            return known;
        }
        NetworkLink.Online online = link.find(uuid.toString());
        if (online != null) {
            return online.name();
        }
        return Bukkit.getOfflinePlayer(uuid).getName();
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ reprise des données locales

    /**
     * Reprend en base les mails et les espions de ce serveur (un mail déjà en base est laissé tel quel).
     * À appeler hors du fil principal.
     *
     * @return nombre de mails ajoutés
     */
    public int importAll(Collection<Mail> mails, Collection<UUID> spies) throws SQLException {
        return db.importAll(mails, spies);
    }

    // ------------------------------------------------------------------ outils

    /** Futur terminé quand toutes les écritures déjà demandées sont faites (le fil d'écriture les traite dans l'ordre). */
    private CompletableFuture<Void> flush() {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            io.execute(() -> done.complete(null));
        } catch (RejectedExecutionException stopped) {
            done.complete(null);
        }
        return done;
    }

    private void submit(Runnable task) {
        try {
            io.execute(task);
        } catch (RejectedExecutionException stopped) {
            // plugin arrêté : plus rien à écrire
        }
    }

    /** Lance la tâche sur le fil principal : tout de suite si on y est déjà, sinon au tick suivant. */
    private void main(Runnable task) {
        if (link.isMainThread()) {
            task.run();
        } else if (plugin.isEnabled()) {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }
}
