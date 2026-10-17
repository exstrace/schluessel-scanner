package de.ric.schluessel

/** Seite A = Vorderseite, Seite B = Rückseite des Schlüssels. */
const val SIDE_A = "A"
const val SIDE_B = "B"

data class KeyImage(
    val file: String,
    val side: String
)

data class KeyEntry(
    val id: String,
    var name: String,
    var number: String,
    var location: String,
    var note: String,
    val created: Long,
    val images: MutableList<KeyImage> = mutableListOf()
) {
    fun firstImage(): KeyImage? = images.firstOrNull { it.side == SIDE_A } ?: images.firstOrNull()
    fun count(side: String) = images.count { it.side == side }
    fun subtitle(): String = listOf(number, location).filter { it.isNotBlank() }.joinToString(" · ")
}
