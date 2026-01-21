
import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
class WeightedSet<T>() {
    val weightMap = mutableMapOf<T, Int>()

    constructor(initialWeightMap: Map<T, Int>) : this() {
        weightMap.putAll(initialWeightMap)
    }

    private var weightSum = weightMap.values.sum()
    private var cachedValues: List<T>? = null
    private var cachedCumulativeWeights: IntArray? = null

    fun addData(data: Collection<T>) {
        data.forEach { entry ->
            weightMap[entry] = weightMap[entry]?.let { it + 1 } ?: 1
        }

        weightSum += data.size
        cachedValues = null
        cachedCumulativeWeights = null
    }

    fun randomValue(): T {
        val targetWeight = Random.nextInt(weightSum)
        val (values, cumulativeWeights) = ensureCache()
        val index = findFirstIndexAbove(cumulativeWeights, targetWeight)
        return values[index]
    }

    private fun ensureCache(): Pair<List<T>, IntArray> {
        val currentValues = cachedValues
        val currentWeights = cachedCumulativeWeights
        if (currentValues != null && currentWeights != null) {
            return currentValues to currentWeights
        }

        val values = ArrayList<T>(weightMap.size)
        val cumulativeWeights = IntArray(weightMap.size)
        var runningSum = 0
        var index = 0
        for ((value, weight) in weightMap) {
            runningSum += weight
            values.add(value)
            cumulativeWeights[index] = runningSum
            index++
        }

        cachedValues = values
        cachedCumulativeWeights = cumulativeWeights
        return values to cumulativeWeights
    }

    private fun findFirstIndexAbove(cumulativeWeights: IntArray, targetWeight: Int): Int {
        var low = 0
        var high = cumulativeWeights.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (targetWeight < cumulativeWeights[mid]) {
                high = mid
            } else {
                low = mid + 1
            }
        }
        return low
    }
}