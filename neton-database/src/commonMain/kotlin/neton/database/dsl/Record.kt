package neton.database.dsl

// Record 接口族（Phase 3）
interface Record1<A> { val v1: A }
interface Record2<A, B> { val v1: A; val v2: B }
interface Record3<A, B, C> { val v1: A; val v2: B; val v3: C }
interface Record4<A, B, C, D> { val v1: A; val v2: B; val v3: C; val v4: D }
interface Record5<A, B, C, D, E> { val v1: A; val v2: B; val v3: C; val v4: D; val v5: E }
interface Record6<A, B, C, D, E, F> { val v1: A; val v2: B; val v3: C; val v4: D; val v5: E; val v6: F }
interface Record7<A, B, C, D, E, F, G> { val v1: A; val v2: B; val v3: C; val v4: D; val v5: E; val v6: F; val v7: G }
interface Record8<A, B, C, D, E, F, G, H> { val v1: A; val v2: B; val v3: C; val v4: D; val v5: E; val v6: F; val v7: G; val v8: H }

// 具体实现（data class）
data class Rec1<A>(override val v1: A) : Record1<A>
data class Rec2<A, B>(override val v1: A, override val v2: B) : Record2<A, B>
data class Rec3<A, B, C>(override val v1: A, override val v2: B, override val v3: C) : Record3<A, B, C>
data class Rec4<A, B, C, D>(override val v1: A, override val v2: B, override val v3: C, override val v4: D) : Record4<A, B, C, D>
data class Rec5<A, B, C, D, E>(override val v1: A, override val v2: B, override val v3: C, override val v4: D, override val v5: E) : Record5<A, B, C, D, E>
data class Rec6<A, B, C, D, E, F>(override val v1: A, override val v2: B, override val v3: C, override val v4: D, override val v5: E, override val v6: F) : Record6<A, B, C, D, E, F>
data class Rec7<A, B, C, D, E, F, G>(override val v1: A, override val v2: B, override val v3: C, override val v4: D, override val v5: E, override val v6: F, override val v7: G) : Record7<A, B, C, D, E, F, G>
data class Rec8<A, B, C, D, E, F, G, H>(override val v1: A, override val v2: B, override val v3: C, override val v4: D, override val v5: E, override val v6: F, override val v7: G, override val v8: H) : Record8<A, B, C, D, E, F, G, H>
