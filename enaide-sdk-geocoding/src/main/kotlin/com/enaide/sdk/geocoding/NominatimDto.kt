package com.enaide.sdk.geocoding

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO per la risposta Nominatim (sia `/search` che `/reverse` con `format=jsonv2`).
 *
 * Nominatim serializza lat/lon come *stringhe*, non numeri: le riconvertiamo nel
 * mapper. Tutti i campi sono opzionali per robustezza (l'API ne omette alcuni
 * a seconda della query).
 *
 * `/search` ritorna un array di questi oggetti; `/reverse` ne ritorna uno solo
 * (o un oggetto `{ "error": ... }` in caso di nessun risultato, gestito a parte).
 */
@Serializable
internal data class NominatimPlace(
    val lat: String? = null,
    val lon: String? = null,
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val type: String? = null,
    @SerialName("addresstype") val addressType: String? = null,
    val address: NominatimAddress? = null,
)

/** Componenti d'indirizzo strutturati (con `addressdetails=1`). */
@Serializable
internal data class NominatimAddress(
    val road: String? = null,
    @SerialName("house_number") val houseNumber: String? = null,
    val neighbourhood: String? = null,
    val suburb: String? = null,
    val city: String? = null,
    val town: String? = null,
    val village: String? = null,
    val municipality: String? = null,
    val county: String? = null,
    val state: String? = null,
    val country: String? = null,
    val postcode: String? = null,
) {
    /** Località principale: città/paese/comune, il primo disponibile. */
    fun locality(): String? = city ?: town ?: village ?: municipality ?: county
}

/**
 * Forma di errore di Nominatim per `/reverse` quando non trova nulla:
 * `{ "error": "Unable to geocode" }`. La distinguiamo dal place valido.
 */
@Serializable
internal data class NominatimError(
    val error: String? = null,
)
