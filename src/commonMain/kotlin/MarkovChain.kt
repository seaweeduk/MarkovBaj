class MarkovChain<T>(private val consideredValuesForGeneration: Int, private val inputValueMapperFunction: (T) -> T = { it }) {
    val chainStarts = WeightedSet<List<T>>()
    private val followingValues = mutableMapOf<List<T>, WeightedSet<T?>>()

    fun addData(data: List<List<T>>, chainStarts: List<List<T>> = data.map { values -> values.take(consideredValuesForGeneration).map { inputValueMapperFunction(it) } }) {
        this.chainStarts.addData(chainStarts)

        data.forEach { sequence ->
            if (sequence.size < consideredValuesForGeneration) {
                return@forEach
            }

            val lastStartIndex = sequence.size - consideredValuesForGeneration
            for (startIndex in 0..lastStartIndex) {
                val consideredValues = ArrayList<T>(consideredValuesForGeneration)
                for (offset in 0 until consideredValuesForGeneration) {
                    val value = sequence[startIndex + offset]
                    consideredValues.add(inputValueMapperFunction(value))
                }

                val nextIndex = startIndex + consideredValuesForGeneration
                val generatedValue: T? = if (nextIndex < sequence.size) {
                    sequence[nextIndex]
                } else {
                    null
                }

                followingValues.getOrPut(consideredValues) { WeightedSet() }.addData(listOf(generatedValue))
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

        for (index in start.size..<maxLength) {
            val windowStart = index - consideredValuesForGeneration
            val consideredValues = ArrayList<T>(consideredValuesForGeneration)
            for (offset in 0 until consideredValuesForGeneration) {
                val value = generatedValues[windowStart + offset]
                consideredValues.add(inputValueMapperFunction(value))
            }

            val nextValue = followingValues[consideredValues]?.randomValue()
            if (nextValue != null) {
                generatedValues.add(nextValue)
            } else {
                break
            }
        }

        return generatedValues
    }
}