package com.vayunmathur.sdk.openassistant

class AssistantNotInstalledException :
    Exception("OpenAssistant app is not installed on this device")

class AssistantException(message: String) : Exception(message)

/**
 * OpenAssistant is installed but its `versionCode` is below
 * [OpenAssistant.MIN_EMBED_VERSION_CODE], so it predates embedding support and
 * would silently ignore an embed request. The caller should prompt the user to
 * update OpenAssistant.
 */
class EmbeddingUnsupportedException :
    Exception("The installed OpenAssistant is too old to provide embeddings; please update it")

/**
 * OpenAssistant is up and the models are ready, but this specific request could
 * not be embedded (e.g. the image failed to decode on OpenAssistant's side).
 * Unlike the other embedding exceptions this is **per-item**: the caller should
 * skip this item rather than treat the whole provider as unavailable.
 */
class EmbeddingImageFailedException(message: String) : Exception(message)

/**
 * OpenAssistant accepted the embed request but the SigLIP2 models are still
 * downloading on demand, so no vector is available yet. [progress] is a 0..1
 * fraction when known (else 0). The caller should retry later and can surface
 * the progress in its UI.
 */
class EmbeddingModelDownloadingException(val progress: Double = 0.0) :
    Exception("OpenAssistant is downloading the embedding models (progress=$progress)")
