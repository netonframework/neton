package neton.core

import neton.core.component.NetonContext

/** 委托：private val repo by inject<UserRepository>() */
inline fun <reified T : Any> inject(): kotlin.Lazy<T> =
    kotlin.lazy { NetonContext.current().get(T::class) }

/** 函数式：val repo = get<UserRepository>() */
inline fun <reified T : Any> get(): T = NetonContext.current().get(T::class)
