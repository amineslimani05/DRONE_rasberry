package com.example.drone_rasberry

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Classe responsable de la communication réseau via le protocole UDP.
 * Envoie des messages JSON structurés comme des paquets MAVLink réels.
 */
class GestionnaireReseau {
    // Adresse IP de destination (PC ou Raspberry Pi)
    private val ADRESSE_IP = "192.168.184.103"
    private val PORT = 14550

    /**
     * Envoie un message MANUAL_CONTROL (#68) au format JSON.
     * @return Le message JSON envoyé
     */
    suspend fun envoyerManualControl(x: Int, y: Int, z: Int, r: Int): String {
        val json = """{"mavpackettype":"MANUAL_CONTROL","target":1,"x":$x,"y":$y,"z":$z,"r":$r,"buttons":0}"""
        envoyerUdp(json)
        return json
    }

    /**
     * Envoie un message COMMAND_LONG (#76) pour l'armement.
     * @return Le message JSON envoyé
     */
    suspend fun envoyerArmement(armer: Boolean): String {
        val param1 = if (armer) 1.0f else 0.0f
        val json = """{"mavpackettype":"COMMAND_LONG","target_system":1,"target_component":1,"command":400,"confirmation":0,"param1":$param1,"param2":0.0,"param3":0.0,"param4":0.0,"param5":0.0,"param6":0.0,"param7":0.0}"""
        envoyerUdp(json)
        return json
    }

    private suspend fun envoyerUdp(message: String) {
        withContext(Dispatchers.IO) {
            try {
                val socket = DatagramSocket()
                val adresseCible = InetAddress.getByName(ADRESSE_IP)
                val donnees = message.toByteArray()
                val paquet = DatagramPacket(donnees, donnees.size, adresseCible, PORT)
                socket.send(paquet)
                socket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
