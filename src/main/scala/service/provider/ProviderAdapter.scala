package service.provider

/** Pluggable adapter for different LLM providers.
  *
  * Each implementation handles the provider-specific HTTP request/response
  * format, while `TranslationService` handles batching and response parsing
  * on top.
  */
trait ProviderAdapter:

  /** Human-readable provider name (e.g. "OpenAI", "Gemini", "Claude"). */
  def name: String

  /** Send a chat completion request and return the response text.
    *
    * @param apiKey   the secret API key
    * @param apiUrl   the API endpoint URL (provider may override if unused)
    * @param model    the model identifier
    * @param systemPrompt  system-level instruction
    * @param userMessage   the user message text
    * @return the raw response text, or an error message
    */
  def sendRequest(
      apiKey: String,
      apiUrl: String,
      model: String,
      systemPrompt: String,
      userMessage: String
  ): Either[String, String]
