package epheis.lyphossyss.kysheon.app.audio

/** Every sound effect of the game. All of them are synthesized at startup by [Synth]: no audio asset is shipped. */
enum class Sfx {
    /** The local player fires a projectile. */
    Fire,

    /** A projectile crosses the seam and enters the local half. */
    ProjectileEnter,

    /** A local projectile hits the opponent. */
    HitDealt,

    /** The local player is hit. */
    HitTaken,

    /** One tick of the countdown before a round. */
    Countdown,

    /** The round starts. */
    Start,

    /** The local player wins the round. */
    Victory,

    /** The local player loses the round. */
    Defeat,

    /** The round ends without a winner. */
    Draw,
}
