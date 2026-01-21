private interface WindowKeyLike {
    val values: Array<Any?>
}

private class WindowKey(
    override val values: Array<Any?>,
    private val hash: Int
) : WindowKeyLike {
    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is WindowKeyLike) {
            return false
        }
        if (hash != other.hashCode()) {
            return false
        }
        val otherValues = other.values
        if (values.size != otherValues.size) {
            return false
        }
        for (index in values.indices) {
            if (values[index] != otherValues[index]) {
                return false
            }
        }
        return true
    }
}

private class LookupWindowKey(override val values: Array<Any?>) : WindowKeyLike {
    private var cachedHash = 0
    private var hashValid = false

    fun set(index: Int, value: Any?) {
        values[index] = value
        hashValid = false
    }

    override fun hashCode(): Int {
        if (hashValid) {
            return cachedHash
        }
        var result = 1
        for (value in values) {
            result = 31 * result + (value?.hashCode() ?: 0)
        }
        cachedHash = result
        hashValid = true
        return result
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is WindowKeyLike) {
            return false
        }
        if (hashCode() != other.hashCode()) {
            return false
        }
        val otherValues = other.values
        if (values.size != otherValues.size) {
            return false
        }
        for (index in values.indices) {
            if (values[index] != otherValues[index]) {
                return false
            }
        }
        return true
    }
}

private fun computeWindowHash(values: Array<Any?>): Int {
    var result = 1
    for (value in values) {
        result = 31 * result + (value?.hashCode() ?: 0)
    }
    return result
}

class MarkovChain<T>(private val consideredValuesForGeneration: Int, private val inputValueMapperFunction: (T) -> T = { it }) {
    val chainStarts = WeightedSet<List<T>>()
    private val followingValues = mutableMapOf<WindowKeyLike, WeightedSet<T?>>()

    fun addData(data: List<List<T>>, chainStarts: List<List<T>> = data.map { values -> values.take(consideredValuesForGeneration).map { inputValueMapperFunction(it) } }) {
        this.chainStarts.addData(chainStarts)

        data.forEach { sequence ->
            if (sequence.size < consideredValuesForGeneration) {
                return@forEach
            }

            val lastStartIndex = sequence.size - consideredValuesForGeneration
            for (startIndex in 0..lastStartIndex) {
                val consideredValues = Array<Any?>(consideredValuesForGeneration) { offset ->
                    inputValueMapperFunction(sequence[startIndex + offset])
                }
                val key = WindowKey(consideredValues, computeWindowHash(consideredValues))

                val nextIndex = startIndex + consideredValuesForGeneration
                val generatedValue: T? = if (nextIndex < sequence.size) {
                    sequence[nextIndex]
                } else {
                    null
                }

                followingValues.getOrPut(key) { WeightedSet() }.addData(listOf(generatedValue))
            }
        }
    }

    fun generateSequence(start: List<T> = chainStarts.randomValue(), maxLength: Int = 100.coerceAtLeast(consideredValuesForGeneration)): List<T> {
        require(maxLength >= consideredValuesForGeneration) {
            "Max length must be at least as large as the number of considered values. Is ${maxLength}, should be >= $consideredValuesForGeneration"
        }

        if (start.size < consideredValuesForGeneration) {
            return start
        }

        val generatedValues = start.toMutableList()
        val windowStart = generatedValues.size - consideredValuesForGeneration
        val windowValues = Array<Any?>(consideredValuesForGeneration) { offset ->
            inputValueMapperFunction(generatedValues[windowStart + offset])
        }
        val lookupKey = LookupWindowKey(windowValues)

        for (index in start.size..<maxLength) {
            val nextValue = followingValues[lookupKey]?.randomValue()
            if (nextValue != null) {
                generatedValues.add(nextValue)
                val mappedNextValue = inputValueMapperFunction(nextValue)
                for (offset in 0 until consideredValuesForGeneration - 1) {
                    lookupKey.set(offset, lookupKey.values[offset + 1])
                }
                lookupKey.set(consideredValuesForGeneration - 1, mappedNextValue)
            } else {
                break
            }
        }

        return generatedValues
    }
}