package epheis.lyphossyss.kysheon.core

/** Kind of a simulation event. See [EventBuffer] for the fields each kind fills. */
enum class EventKind {
    /** One of our projectiles crossed the border: send a HANDOFF. Fields: id, yNormalized, vx, vy. */
    ProjectileExited,

    /** An incoming projectile hit our fighter (we decided the impact): send a HIT. Field: id. */
    FighterHit,

    /** Our fighter reached 0 hp. Emitted once, right after the last [FighterHit]. */
    FighterDown,

    /** An incoming projectile reached our outer edge without hitting. Field: id. */
    ProjectileMissed,
}

/**
 * Events produced by [HalfSimulation], in emission order, as a struct of arrays.
 *
 * Why this design rather than a list of event objects: [HalfSimulation.step] runs 60 times per second next to
 * the renderer, and must not allocate. Events are written into preallocated parallel arrays (`kind`, `id`,
 * `yNormalized`, `vx`, `vy`); the caller reads them by index after stepping, then calls [clear].
 * Events accumulate across steps until cleared, so a caller running several steps per frame drains once.
 * Storage grows (doubling) only if the caller never drains and the initial capacity is exceeded:
 * events are never dropped, since a lost exit would lose a projectile.
 *
 * [toList] builds immutable [GameEvent] values: convenient for tests and logs, but it allocates.
 */
class EventBuffer(initialCapacity: Int = 64) {
    init {
        require(initialCapacity > 0) { "initialCapacity must be > 0: $initialCapacity" }
    }

    private var kinds = arrayOfNulls<EventKind>(initialCapacity)
    private var ids = IntArray(initialCapacity)
    private var ys = DoubleArray(initialCapacity)
    private var vxs = DoubleArray(initialCapacity)
    private var vys = DoubleArray(initialCapacity)

    /** Number of pending events. */
    var count: Int = 0
        private set

    val capacity: Int get() = ids.size

    fun kind(index: Int): EventKind = kinds[checkIndex(index)]!!

    /** Projectile id (u16), or -1 for [EventKind.FighterDown]. */
    fun projectileId(index: Int): Int = ids[checkIndex(index)]

    /** [EventKind.ProjectileExited] only: exit height divided by `aspect`, in [0, 1]. */
    fun yNormalized(index: Int): Double = ys[checkIndex(index)]

    /** [EventKind.ProjectileExited] only: velocity at exit, widths per second. */
    fun vx(index: Int): Double = vxs[checkIndex(index)]

    /** [EventKind.ProjectileExited] only: velocity at exit, widths per second. */
    fun vy(index: Int): Double = vys[checkIndex(index)]

    /** Drops all pending events (storage is kept). */
    fun clear() {
        for (i in 0 until count) kinds[i] = null
        count = 0
    }

    /** Allocating snapshot of the pending events. Not for the hot path. */
    fun toList(): List<GameEvent> = List(count) { i ->
        when (kind(i)) {
            EventKind.ProjectileExited -> GameEvent.ProjectileExited(ids[i], ys[i], vxs[i], vys[i])
            EventKind.FighterHit -> GameEvent.FighterHit(ids[i])
            EventKind.FighterDown -> GameEvent.FighterDown
            EventKind.ProjectileMissed -> GameEvent.ProjectileMissed(ids[i])
        }
    }

    internal fun push(kind: EventKind, id: Int, yNormalized: Double = 0.0, vx: Double = 0.0, vy: Double = 0.0) {
        if (count == ids.size) grow()
        kinds[count] = kind
        ids[count] = id
        ys[count] = yNormalized
        vxs[count] = vx
        vys[count] = vy
        count++
    }

    private fun grow() {
        val n = ids.size * 2
        kinds = kinds.copyOf(n)
        ids = ids.copyOf(n)
        ys = ys.copyOf(n)
        vxs = vxs.copyOf(n)
        vys = vys.copyOf(n)
    }

    private fun checkIndex(index: Int): Int {
        if (index < 0 || index >= count) throw IndexOutOfBoundsException("event $index of $count")
        return index
    }
}

/** Immutable view of an event, produced by [EventBuffer.toList]. */
sealed interface GameEvent {
    data class ProjectileExited(val projectileId: Int, val yNormalized: Double, val vx: Double, val vy: Double) :
        GameEvent

    data class FighterHit(val projectileId: Int) : GameEvent

    data object FighterDown : GameEvent

    data class ProjectileMissed(val projectileId: Int) : GameEvent
}
