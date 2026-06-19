package com.example.drone_rasberry

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Modèle de vue (ViewModel) gérant la logique métier et les interactions réseau.
 */
class  DroneViewModel : ViewModel() {
    private val gestionnaireReseau = GestionnaireReseau()
    private var tacheDeplacement: Job? = null

    // États observés par l'interface utilisateur
    var estArme by mutableStateOf(false)
        private set
    
    var axeX by mutableStateOf(0f)
        private set
        
    var axeY by mutableStateOf(0f)
        private set

    // Le dernier message JSON brut envoyé au Raspberry
    var dernierMessageJson by mutableStateOf("En attente de commandes...")
        private set

    /**
     * Envoie la commande d'armement du drone via MAVLink.
     */
    fun armerLeDrone() {
        viewModelScope.launch {
            dernierMessageJson = gestionnaireReseau.envoyerArmement(true)
            estArme = true
        }
    }

    /**
     * Envoie la commande de désarmement du drone via MAVLink.
     */
    fun desarmerLeDrone() {
        viewModelScope.launch {
            dernierMessageJson = gestionnaireReseau.envoyerArmement(false)
            estArme = false
        }
    }

    /**
     * Arrêt d'urgence immédiat.
     */
    fun arretUrgence() {
        tacheDeplacement?.cancel()
        axeX = 0f
        axeY = 0f
        viewModelScope.launch {
            dernierMessageJson = gestionnaireReseau.envoyerManualControl(0, 0, 500, 0)
            gestionnaireReseau.envoyerArmement(false)
            estArme = false
        }
    }

    /**
     * Met à jour les coordonnées du joystick et gère l'envoi périodique.
     */
    fun mettreAJourDeplacement(x: Float, y: Float) {
        axeX = x
        axeY = y

        if (x == 0f && y == 0f) {
            tacheDeplacement?.cancel()
            tacheDeplacement = null
            viewModelScope.launch {
                dernierMessageJson = gestionnaireReseau.envoyerManualControl(0, 0, 500, 0)
            }
        } else if (tacheDeplacement == null || !tacheDeplacement!!.isActive) {
            tacheDeplacement = viewModelScope.launch {
                while (true) {
                    val mavPitch = (axeY * 1000).toInt().coerceIn(-1000, 1000)
                    val mavRoll = (axeX * 1000).toInt().coerceIn(-1000, 1000)
                    val mavThrust = if (estArme) 500 else 0
                    
                    dernierMessageJson = gestionnaireReseau.envoyerManualControl(
                        x = mavPitch,
                        y = mavRoll,
                        z = mavThrust,
                        r = 0
                    )
                    delay(50)
                }
            }
        }
    }
}
