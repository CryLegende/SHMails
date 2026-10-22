package org.Cry_Legende.sHMails.dialog;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.Cry_Legende.sHMails.SHMails;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.logging.Level;

/**
 * Reçoit les clics des boutons du dialogue de réponse (clés {@code shmails:...}) et les départs de joueurs.
 *
 * <p>Ce listener ne fait que lire l'événement : le traitement (jeton, état, envoi du mail) est confié à
 * {@link ReplyDialogs} sur le thread principal. Aucune exception ne sort d'ici.</p>
 */
public final class ReplyDialogListener implements Listener {

    private final SHMails plugin;
    private final ReplyDialogs dialogs;

    public ReplyDialogListener(SHMails plugin, ReplyDialogs dialogs) {
        this.plugin = plugin;
        this.dialogs = dialogs;
    }

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent event) {
        try {
            Key id = event.getIdentifier();
            // Les clics des autres plugins ne nous concernent pas.
            if (!ReplyDialogs.NAMESPACE.equals(id.namespace())) {
                return;
            }
            if (!(event.getCommonConnection() instanceof PlayerGameConnection game)) {
                return;
            }
            Player player = game.getPlayer();
            DialogResponseView view = event.getDialogResponseView();
            String text = null;
            if (view != null) {
                try {
                    text = view.getText(ReplyDialogs.INPUT);
                } catch (RuntimeException ignored) {
                    // champ absent : traité comme une saisie vide
                }
            }
            dialogs.onClick(player, id.value(), text);
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Clic de dialogue ignoré (erreur inattendue).", e);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        dialogs.forget(event.getPlayer().getUniqueId());
    }
}
