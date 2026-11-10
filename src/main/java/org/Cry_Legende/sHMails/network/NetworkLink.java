package org.Cry_Legende.sHMails.network;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Ce dont le mode réseau a besoin de SHNetwork. Cette interface ne mentionne aucune classe de SHNetwork :
 * le plugin la manipule sans jamais charger {@link ShNetworkLink}, donc sans SHNetwork il ne se passe rien.
 */
public interface NetworkLink {

    /** Joueur connecté quelque part sur le réseau. */
    record Online(UUID uuid, String name, String server) {
    }

    /** Vrai sur le fil principal du serveur (Bukkit.isPrimaryThread() répond aussi vrai sur les fils de monde). */
    boolean isMainThread();

    /** Attend que le serveur de départ ait fini de sauvegarder le joueur ; se termine hors du fil principal. */
    CompletableFuture<Boolean> awaitSaved(UUID uuid, Duration timeout);

    /** Inscrit la tâche exécutée au départ de chaque joueur (fil principal, pendant PlayerQuitEvent). */
    void registerSaveTask(Function<Player, CompletableFuture<Void>> task);

    /** Joueur connecté sur le réseau (pseudo sans tenir compte de la casse, ou UUID sous forme de texte), ou null. */
    Online find(String nameOrUuid);

    /** Publie sur un canal à destination des autres serveurs (pas de celui-ci). Peut être appelé de n'importe quel fil. */
    void publish(String channel, JsonObject message);

    /** Écoute un canal ; le gestionnaire est appelé sur le fil principal. */
    void listen(String channel, Consumer<JsonObject> handler);
}
