package neton.ai

/** AI contracts remain experimental during the framework 1.x stable lifecycle. */
@RequiresOptIn(level = RequiresOptIn.Level.ERROR, message = "Neton AI is experimental; explicitly opt in and pin the framework version.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class ExperimentalNetonAiApi
