package io.github.mojira.arisa.infrastructure.apiclient.models

import kotlinx.serialization.Serializable
// import java.io.InputStream

@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val created: String,
    val mimeType: String,
    val author: User?
)
