package config

import com.typesafe.config.{ConfigFactory, Config => TsConfig}

import java.io.File

/** Application configuration.
  *
  * Resolution order (each level overrides the previous):
  *  1. Hardcoded defaults
  *  2. `application.conf` on the classpath
  *  3. Environment variables
  *
  * The API key must be set either in `application.conf` or via the
  * `DEEPSEEK_API_KEY` environment variable, otherwise loading fails.
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

    // --- resolve each value: application.conf first, then env var, then hardcoded default ---
    val apiKey = resolveSecret(
      tsConfig, "deepseek.api-key",
      "DEEPSEEK_API_KEY",
      None
    ).getOrElse(
      sys.error(
        "DEEPSEEK_API_KEY is not set. Provide it in application.conf:\n" +
        "  deepseek.api-key = \"sk-...\"\n" +
        "or via the DEEPSEEK_API_KEY environment variable."
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

  /** Read a secret (e.g. API key): application.conf > env var.
    * Returns `None` if neither is set. */
  private def resolveSecret(
      cfg: TsConfig,
      path: String,
      env: String,
      fallback: Option[String]
  ): Option[String] =
    if cfg.hasPath(path) then
      val v = cfg.getString(path).trim
      if v.nonEmpty then Some(v) else fallback
    else
      sys.env.get(env).orElse(fallback)

  /** Read an integer: application.conf > env var > fallback. */
  private def resolveInt(
      cfg: TsConfig,
      path: String,
      env: String,
      fallback: Int
  ): Int =
    if cfg.hasPath(path) then cfg.getInt(path)
    else sys.env.get(env).flatMap(_.toIntOption).getOrElse(fallback)
