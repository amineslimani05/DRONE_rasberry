import socket
import json
import time
import sys
import glob
from pymavlink import mavutil

# ==============================================================================
# 1. INITIALISATION DE LA LIAISON SÉRIE AVEC LE DRONE (PX4)
# ==============================================================================
def rechercher_port_actif():
    """Recherche dynamique du port USB connecté au contrôleur de vol."""
    liste_ports = glob.glob('/dev/ttyACM*') + glob.glob('/dev/ttyUSB*')
    if not liste_ports:
        print("[ERREUR] Aucun drone détecté. Vérifier la connexion USB.")
        sys.exit()
    return liste_ports[0]

port_serie_drone = rechercher_port_actif()
vitesse_transmission = 115200

print(f"[SÉRIE] Connexion au drone sur {port_serie_drone}...")
try:
    liaison_drone = mavutil.mavlink_connection(port_serie_drone, baud=vitesse_transmission)
    liaison_drone.wait_heartbeat()
    print("[SÉRIE] Drone connecté et Heartbeat reçu !")
except Exception as erreur_liaison:
    print(f"[ERREUR] Impossible d'ouvrir le port série : {erreur_liaison}")
    sys.exit()

# Passage forcé en mode STABILIZED au démarrage pour accepter les commandes manuelles
print("[SÉRIE] Configuration initiale du mode de vol sur STABILIZED...")
liaison_drone.mav.command_long_send(
    liaison_drone.target_system, liaison_drone.target_component,
    mavutil.mavlink.MAV_CMD_DO_SET_MODE, 0,
    1, 1, 0, 0, 0, 0, 0
)
time.sleep(1)

# ==============================================================================
# 2. INITIALISATION DU SERVEUR UDP (Écoute des commandes Android)
# ==============================================================================
adresse_ecoute = "0.0.0.0" # Écoute sur toutes les interfaces réseau du Raspberry
port_ecoute = 14550

serveur_udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
try:
    serveur_udp.bind((adresse_ecoute, port_ecoute))
    print(f"[RÉSEAU] Serveur UDP démarré. En attente de paquets sur le port {port_ecoute}...\n")
except Exception as erreur_reseau:
    print(f"[ERREUR] Impossible de lier le port UDP : {erreur_reseau}")
    sys.exit()

# ==============================================================================
# 3. BOUCLE PRINCIPALE DE RÉCEPTION ET CONVERSION
# ==============================================================================
try:
    while True:
        # Attente bloquante d'un paquet UDP venant d'Android
        donnees_brutes, adresse_client = serveur_udp.recvfrom(1024)
        chaine_json = donnees_brutes.decode('utf-8')
        
        try:
            # Extraction du dictionnaire à partir de la chaîne JSON
            commande_recue = json.loads(chaine_json)
            type_paquet = commande_recue.get("mavpackettype")

            # ------------------------------------------------------------------
            # CAS A : Commande d'armement ou désarmement (COMMAND_LONG)
            # ------------------------------------------------------------------
            if type_paquet == "COMMAND_LONG":
                code_commande = int(commande_recue.get("command", 0))
                parametre_1 = float(commande_recue.get("param1", 0.0))
                
                # INJECTION DE SÉCURITÉ : Forçage de l'armement (21196) côté serveur
                parametre_2 = 21196 if code_commande == 400 else 0.0
                
                print(f"[RÉSEAU -> SÉRIE] Ordre d'armement reçu : Param1={parametre_1}")
                
                liaison_drone.mav.command_long_send(
                    liaison_drone.target_system,
                    liaison_drone.target_component,
                    code_commande,
                    0,              # Confirmation
                    parametre_1,    # 1 = Armer, 0 = Désarmer
                    parametre_2,    # 21196 pour forcer
                    0, 0, 0, 0, 0
                )

            # ------------------------------------------------------------------
            # CAS B : Commande de pilotage manuel (MANUAL_CONTROL)
            elif type_paquet == "MANUAL_CONTROL":
                valeur_x = int(commande_recue.get("x", 0))
                valeur_y = int(commande_recue.get("y", 0))
                valeur_z = int(commande_recue.get("z", 0))
                valeur_r = int(commande_recue.get("r", 0))
                
                # --- LIGNE À AJOUTER POUR L'AFFICHAGE ---
                print(f"[JOYSTICK] Tangage(X): {valeur_x:4d} | Roulis(Y): {valeur_y:4d} | Gaz(Z): {valeur_z:4d}")
                
                liaison_drone.mav.manual_control_send(
                    liaison_drone.target_system,
                    valeur_x,
                    valeur_y,
                    valeur_z,
                    valeur_r,
                    0               # Boutons non utilisés
                )
                
            else:
                print(f"[ATTENTION] Type de paquet inconnu : {type_paquet}")

            print(type_paquet)

        except json.JSONDecodeError:
            print(f"[ERREUR] Trame UDP corrompue ou JSON invalide : {chaine_json}")

except KeyboardInterrupt:
    print("\n[SYSTÈME] Arrêt du serveur relais. Fermeture des ports.")
    serveur_udp.close()
    sys.exit()