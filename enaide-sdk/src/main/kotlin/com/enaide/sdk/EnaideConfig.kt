package com.enaide.sdk

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configurazione di runtime dell'SDK enaide.
 *
 * Tutti i campi hanno default sensati per partire subito. In produzione vorrai
 * almeno cambiare [routingBaseUrl] per puntare al tuo Valhalla self-hosted e
 * personalizzare [userAgent] secondo le linee guida del tuo backend.
 *
 * @property routingBaseUrl URL base del Valhalla. Default: endpoint pubblico FOSSGIS,
 *   utile in sviluppo. Soggetto a rate limit e richiede attribuzione OSM nelle app
 *   che lo usano in produzione: https://github.com/valhalla/valhalla/blob/master/docs/sif/elevation_service.md
 * @property nominatimBaseUrl URL base del servizio di geocoding Nominatim. Default:
 *   istanza pubblica OSM. Soggetta a usage policy (max ~1 req/s, UA obbligatorio,
 *   attribuzione): https://operations.osmfoundation.org/policies/nominatim/
 * @property userAgent stringa User-Agent inviata col routing e col geocoding. Il
 *   server pubblico FOSSGIS e Nominatim richiedono un UA identificativo non vuoto.
 * @property requestTimeout timeout di rete per ogni chiamata HTTP di routing.
 * @property defaultProfile profilo trasporto usato se la richiesta non lo specifica.
 * @property offRouteThresholdMeters distanza oltre la quale consideriamo l'utente fuori percorso.
 * @property offRouteConfirmationCount numero di fix consecutivi off-route necessari per dichiarare deviazione.
 *   Evita di ricalcolare per un singolo fix rumoroso.
 */
public data class EnaideConfig(
    public val routingBaseUrl: String = DEFAULT_ROUTING_BASE_URL,
    public val nominatimBaseUrl: String = DEFAULT_NOMINATIM_BASE_URL,
    public val overpassBaseUrl: String = DEFAULT_OVERPASS_BASE_URL,
    public val userAgent: String = DEFAULT_USER_AGENT,
    public val requestTimeout: Duration = 15.seconds,
    public val defaultProfile: com.enaide.sdk.model.TransportProfile = com.enaide.sdk.model.TransportProfile.AUTO,
    public val offRouteThresholdMeters: Double = 30.0,
    public val offRouteConfirmationCount: Int = 3,
) {
    public companion object {
        public const val DEFAULT_ROUTING_BASE_URL: String = "https://valhalla1.openstreetmap.de"
        public const val DEFAULT_NOMINATIM_BASE_URL: String = "https://nominatim.openstreetmap.org"
        public const val DEFAULT_OVERPASS_BASE_URL: String = "https://overpass-api.de"
        public const val DEFAULT_USER_AGENT: String = "enaide-sdk/0.1 (+https://enaide.example/contact)"
    }
}
