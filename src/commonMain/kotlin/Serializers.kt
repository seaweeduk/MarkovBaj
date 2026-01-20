import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object InstantSerializer : KSerializer<kotlin.time.Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): kotlin.time.Instant = kotlin.time.Instant.fromEpochMilliseconds(decoder.decodeLong())
    override fun serialize(encoder: Encoder, value: kotlin.time.Instant) = encoder.encodeLong(value.toEpochMilliseconds())
}
