/**
 * `com.netonstream:neton` 的占位源文件。
 *
 * 这个模块本身没有代码：它只是把最小可运行应用所需的四个模块（core、logging、http、routing）
 * 聚合成一个坐标，并导出 BOM 约束。Kotlin/Native 的空模块无法产出 klib，因此保留这一个文件。
 */
@file:Suppress("unused")

package neton
