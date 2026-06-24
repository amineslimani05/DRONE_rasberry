import socket
import json
import time
import sys
import glob
from pymavlink import mavutil

# ==============================================================================
# 1. CONFIGURATION DU MATÉRIEL SÉRIE
# ==============================================================================
def obtenir_port_actif():
    """Scanne les interfaces matérielles disponibles pour valider la liaison MAVLink."""
    liste_peripheriques = glob.glob('/dev/ttyACM*') + glob.glob('/dev/ttyUSB*')
    if not liste_peripheriques:
        print("[ERREUR] Absence de connexion matérielle détectée.")
        sys.exit()
    
    for peripherique in liste_peripheriques:
        try:
            liaison = mavutil.mavlink_connection(peripherique, baud=115200)
            signal = liaison.wait_heartbeat(timeout=2)
            if signal is not None:
                return liaison, peripherique
        except Exception:
            pass
    print("[ERREUR] Aucun port MAVLink valide (Console NuttX interceptée).")
    sys.exit()

liaison_vol, port_connexion = obtenir_port_actif()
print(f"[MATÉRIEL] Synchronisation MAVLink établie sur {port_connexion}.")

# ==============================================================================
# 2. PHASE DE CHAUFFE (WARM-UP) ET ACTIVATION DU MODE
# ==============================================================================
print("[SYSTÈME] Génération du signal pilote virtuel (Phase de chauffe)...")

# Envoi de 15 paquets neutres (1.5s) pour satisfaire la sécurité RC Loss de PX4
for _ in range(15):
    liaison_vol.mav.manual_control_send(
        liaison_vol.target_system, 0, 0, 0, 0, 0
    )
    time.sleep(0.1)

print("[MATÉRIEL] Signal validé. Activation du mode STABILIZED...")
liaison_vol.mav.command_long_send(
    liaison_vol.target_system, liaison_vol.target_component,
    mavutil.mavlink.MAV_CMD_DO_SET_MODE, 0, 1, 1, 0, 0, 0, 0, 0
)
time.sleep(1)

# ==============================================================================
# 3. CONFIGURATION DU RÉSEAU (UDP NON BLOQUANT)
# ==============================================================================
port_reception = 14550
relais_udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)

# Délai maximal d'attente réseau fixé à 100 millisecondes (Maintien de la boucle à 10Hz)
relais_udp.settimeout(0.1)

try:
    relais_udp.bind(("0.0.0.0", port_reception))
    print(f"[RÉSEAU] Écoute active sur le port {port_reception}.")
except Exception as erreur_liaison:
    print(f"[ERREUR] Conflit d'adresse réseau : {erreur_liaison}. (Libérer avec fuser -k)")
    sys.exit()

# ==============================================================================
# 4. VARIABLES DE MÉMOIRE D'ÉTAT ET FILTRES ALTIMÉTRIQUES
# ==============================================================================
# Mémoire du pilotage
memoire_axe_x = 0
memoire_axe_y = 0
memoire_axe_z = 0
memoire_axe_r = 0
temps_dernier_armement = 0

# Variables pour le filtre et le tarage de l'altitude
historique_altitude = []
taille_echantillon = 10
altitude_reference = None

print("[SYSTÈME] Boucle de maintien rythmée à 10 Hz démarrée.\n")

# ==============================================================================
# 5. BOUCLE DE TRAITEMENT CONTINU
# ==============================================================================
try:
    while True:
        # --- LECTURE RÉSEAU ---
        try:
            # Tampon de 4096 octets pour prévenir la fragmentation JSON
            donnees_entrantes, adresse_emetteur = relais_udp.recvfrom(4096)
            chaine_texte = donnees_entrantes.decode('utf-8')
            
            dictionnaire_commande = json.loads(chaine_texte)
            categorie_message = dictionnaire_commande.get("mavpackettype")

            # --- GESTION DE L'ARMEMENT ---
            if categorie_message == "COMMAND_LONG":
                temps_actuel = time.time()
                # Filtre anti-spam : 1 seconde de carence
                if temps_actuel - temps_dernier_armement > 1.0:
                    instruction = int(dictionnaire_commande.get("command", 0))
                    argument_1 = float(dictionnaire_commande.get("param1", 0.0))
                    argument_2 = 21196 if instruction == 400 else 0.0
                    
                    if instruction == 400:
                        if argument_1 == 1.0:
                            # Revalidation du mode STABILIZED avant réarmement
                            print("\n[SÉCURITÉ] Confirmation du mode STABILIZED avant armement...")
                            liaison_vol.mav.command_long_send(
                                liaison_vol.target_system, liaison_vol.target_component,
                                mavutil.mavlink.MAV_CMD_DO_SET_MODE, 0, 1, 1, 0, 0, 0, 0, 0
                            )
                            time.sleep(0.1)
                            print("[ACTION] Transmission de l'ordre d'ARMEMENT.")
                        else:
                            # Nettoyage de sécurité : annulation de la poussée au désarmement
                            memoire_axe_z = 0
                            print("\n[ACTION] DÉSARMEMENT : Verrouillage des gaz à 0.")

                    liaison_vol.mav.command_long_send(
                        liaison_vol.target_system, liaison_vol.target_component,
                        instruction, 0, argument_1, argument_2, 0, 0, 0, 0, 0
                    )
                    temps_dernier_armement = temps_actuel
                    time.sleep(0.2)

            # --- GESTION DU PILOTAGE ---
            elif categorie_message == "MANUAL_CONTROL":
                memoire_axe_x = int(dictionnaire_commande.get("x", 0))
                memoire_axe_y = int(dictionnaire_commande.get("y", 0))
                memoire_axe_z = int(dictionnaire_commande.get("z", 0))
                memoire_axe_r = int(dictionnaire_commande.get("r", 0))

        except socket.timeout:
            pass  # Le réseau est vide, la boucle continue
        except json.JSONDecodeError:
            pass  # Trame altérée ignorée

        # --- ÉMISSION MAVLINK PERMANENTE ---
        # Garantit que la carte de vol reçoit toujours un signal
        liaison_vol.mav.manual_control_send(
            liaison_vol.target_system,
            memoire_axe_x,
            memoire_axe_y,
            memoire_axe_z,
            memoire_axe_r,
            0
        )

        # --- RÉCEPTION TÉLÉMÉTRIE ET TARAGE ALTIMÉTRIQUE ---
        donnees_vol = liaison_vol.recv_match(type='VFR_HUD', blocking=False)

        if donnees_vol:
            altitude_brute = donnees_vol.alt
            historique_altitude.append(altitude_brute)

            # Maintien de la taille du tampon
            if len(historique_altitude) > taille_echantillon:
                historique_altitude.pop(0)

            # Moyenne mobile
            altitude_lissee_absolue = sum(historique_altitude) / len(historique_altitude)

            # Calibrage du zéro initial une fois le tampon rempli
            if len(historique_altitude) == taille_echantillon and altitude_reference is None:
                altitude_reference = altitude_lissee_absolue
                print(f"\n[SYSTÈME] Altitude de référence fixée à : {altitude_reference:.2f} m\n")

            # Affichage de la position par rapport au point zéro
            if altitude_reference is not None:
                altitude_relative = altitude_lissee_absolue - altitude_reference
                
                # Impression formatée effaçant la ligne précédente pour une lecture propre
                sys.stdout.write(f"\r[TÉLÉMÉTRIE] Alt relative: {altitude_relative:+05.2f} m | Gaz(Z): {memoire_axe_z:4d} | Roulis/Tangage: {memoire_axe_x:4d}/{memoire_axe_y:4d}    ")
                sys.stdout.flush()

except KeyboardInterrupt:
    print("\n\n[SYSTÈME] Arrêt manuel. Libération des ressources.")
    relais_udp.close()
    sys.exit()