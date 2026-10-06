package org.bakasu.bakasu.domain.model

data class RepositorySource(
    val url: String,
    val name: String,
    val id: String = "",
    val description: String = "",
    val customName: String = "",
) {
    val displayName: String get() = customName.ifBlank { name }
    val isBuiltIn: Boolean get() = url == KERNEL_SU_URL

    companion object {
        const val KERNEL_SU_URL = "https://modules.kernelsu.org/"
    }
}

enum class RepositoryError { INVALID_URL, INVALID_FEED, UNSUPPORTED_FORMAT, BUILT_IN, NETWORK, OFFLINE, STORAGE }

class RepositoryException(val reason: RepositoryError) : Exception(reason.name)
