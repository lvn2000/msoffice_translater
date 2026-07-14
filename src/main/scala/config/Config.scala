package config

import com.typesafe.config.{ConfigFactory, Config => TsConfig}

/** Application configuration.
  *
  * Reads from `application.conf` on the classpath, with environment variable
  * overrides. The API key must be set via the `DEEPSEEK_API_KEY` environment
  * variable, otherwise loading fails.
  */
case class Config(
    apiKey: String,
    apiUrl: String,
    modelName: String,
    sourceDir: String,
    outputDir: String,
    maxBatchSize: Int,
    sourceLang: String,
    targetLang: String
)

object Config:
  private val DefaultApiUrl   = "https://api.deepseek.com/v1/chat/completions"
  private val DefaultModel    = "deepseek-chat"
  private val DefaultMaxBatch = 10

  /** Load configuration: `application.conf` + env overrides. */
  def load(): Config =
    val tsConfig: TsConfig = ConfigFactory.load().resolve()

    // Name of the env var that holds the API key — from config, default DEEPSEEK_API_KEY
    val apiKeyEnvVar =
      if tsConfig.hasPath("deepseek.api-key-env") then tsConfig.getString("deepseek.api-key-env")
      else "DEEPSEEK_API_KEY"

    // API key — only from the environment variable named above
    val apiKey = sys.env.getOrElse(
      apiKeyEnvVar,
      sys.error(
        s"$apiKeyEnvVar is not set.\n" +
        "Export it in your shell:\n" +
        s"  export $apiKeyEnvVar=\"sk-...\""
      )
    )

    val apiUrl = resolveStr(
      tsConfig, "deepseek.api-url",
      "DEEPSEEK_API_URL",
      DefaultApiUrl
    )

    val sourceDir = resolveStr(
      tsConfig, "app.source-dir",
      "SOURCE_DIR",
      "src/main/resources/source"
    )

    val outputDir = resolveStr(
      tsConfig, "app.output-dir",
      "OUTPUT_DIR",
      "src/main/resources/output"
    )

    val modelName = resolveStr(
      tsConfig, "deepseek.model",
      "DEEPSEEK_MODEL",
      DefaultModel
    )

    val maxBatchSize = resolveInt(
      tsConfig, "app.max-batch-size",
      "MAX_BATCH_SIZE",
      DefaultMaxBatch
    )

    val sourceLang = resolveStr(
      tsConfig, "app.source-lang",
      "SOURCE_LANG",
      "auto"
    )

    val targetLang = resolveStr(
      tsConfig, "app.target-lang",
      "TARGET_LANG",
      "Russian"
    )

    Config(
      apiKey       = apiKey,
      apiUrl       = apiUrl,
      modelName    = modelName,
      sourceDir    = sourceDir,
      outputDir    = outputDir,
      maxBatchSize = maxBatchSize,
      sourceLang   = sourceLang,
      targetLang   = targetLang
    )

  // --------------------------------------------------------------------------
  // Private resolution helpers
  // --------------------------------------------------------------------------

  /** Read a string: application.conf > env var > fallback. */
  private def resolveStr(
      cfg: TsConfig,
      path: String,
      env: String,
      fallback: String
  ): String =
    if cfg.hasPath(path) then cfg.getString(path)
    else sys.env.getOrElse(env, fallback)

  /** Read an integer: application.conf > env var > fallback. */
  private def resolveInt(
      cfg: TsConfig,
      path: String,
      env: String,
      fallback: Int
  ): Int =
    if cfg.hasPath(path) then cfg.getInt(path)
    else sys.env.get(env).flatMap(_.toIntOption).getOrElse(fallback)
