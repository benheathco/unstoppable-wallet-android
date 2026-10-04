package io.horizontalsystems.evidence.net

import io.horizontalsystems.evidence.model.SendRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One client-proposed attribution candidate, wire-compatible with Opie's CandidateSerializer
 * (apps/evidence/serializers.py). An unknown subject_type or extraction_method 400s the whole
 * capture, so only the server's enum values are used here.
 */
@Serializable
data class Candidate(
    val identifier: String,
    val network: String,
    @SerialName("subject_type") val subjectType: String,
    val asset: String = "",
    val memo: String = "",
    // Machine-captured by the wallet (QR decode / send flow), not typed by the operator
    @SerialName("extraction_method") val extractionMethod: String = EXTRACTION_DETECTED,
) {
    // CandidateSerializer max_lengths: an over-long field would 400 the whole capture
    fun clamped(): Candidate = copy(
        identifier = identifier.take(255),
        network = network.take(64),
        asset = asset.take(32),
        memo = memo.take(128),
    )

    companion object {
        const val DEPOSIT_ADDRESS = "DEPOSIT_ADDRESS"
        const val EXTRACTION_DETECTED = "dom_detected"
    }
}

@Serializable
private data class CaptureEnvelope(
    val project: String,
    @SerialName("source_url") val sourceUrl: String,
    @SerialName("client_captured_at") val clientCapturedAt: String,
    @SerialName("capture_method") val captureMethod: String,
    @SerialName("extension_version") val extensionVersion: String,
    @SerialName("expected_artifacts") val expectedArtifacts: List<String>,
    @SerialName("client_request_id") val clientRequestId: String,
    val candidates: List<Candidate>? = null,
    val notes: String? = null,
)

private val json = Json {
    encodeDefaults = true
    explicitNulls = false
}

const val CAPTURE_METHOD_MOBILE_APP = "mobile_app"

/**
 * The evidence-capture registration body. `source_url` is always blank (the server's URLField
 * rejects a wallet:// scheme); the locator belongs in [notes]. [appVersion] rides in
 * `extension_version`, the server's client-version field.
 */
fun buildEnvelope(
    projectUuid: String,
    capturedAtIso: String,
    clientRequestId: String,
    expectedArtifacts: List<String>,
    candidates: List<Candidate>,
    notes: String?,
    appVersion: String,
): String = json.encodeToString(
    CaptureEnvelope.serializer(),
    CaptureEnvelope(
        project = projectUuid,
        sourceUrl = "",
        clientCapturedAt = capturedAtIso,
        captureMethod = CAPTURE_METHOD_MOBILE_APP,
        extensionVersion = appVersion,
        expectedArtifacts = expectedArtifacts,
        clientRequestId = clientRequestId,
        candidates = candidates.ifEmpty { null },
        notes = notes?.trim()?.ifEmpty { null },
    ),
)

/**
 * The payee address as a deposit-address candidate. Opie has no token subject type, so the
 * token contract travels in [sendNotes] and the candidate carries the asset symbol.
 */
fun sendCandidates(r: SendRecord): List<Candidate> = listOf(
    Candidate(
        identifier = r.address,
        network = mapNetworkKey(r.network).key,
        subjectType = Candidate.DEPOSIT_ADDRESS,
        asset = r.assetSymbol,
        memo = r.memo.orEmpty(),
    ).clamped()
)

fun sendNotes(r: SendRecord): String = buildList {
    add("send/${r.network}: ${r.amount} ${r.assetSymbol}")
    r.tokenContract?.let { add("token contract $it") }
    r.fee?.let { add("fee $it") }
    r.memo?.let { add("memo $it") }
    r.txHashIfKnown?.let { add("tx $it") }
    unmappedNetworkNote(r.network)?.let { add(it) }
    r.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
}.joinToString("\n")

/** Flags a network Opie won't recognise, so the attribution miss is visible to the reviewer. */
fun unmappedNetworkNote(walletChain: String): String? =
    mapNetworkKey(walletChain).takeUnless { it.mapped }?.let { "network '${it.key}' not in Opie chain registry" }
