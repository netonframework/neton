package neton.ai

@neton.ai.ExperimentalNetonAiApi
sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Named(val name: String) : ToolChoice
}
