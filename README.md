# pptx-translator

Translate Microsoft PowerPoint (`.pptx`) files using the DeepSeek API. Only text is translated — images, charts, and other media are preserved as-is.

The default language direction is **Ukrainian → Russian**, but any language pair is supported (configured in `application.conf`). Source language can be auto-detected by the model.

## Features

- **Configurable language direction** — set any source/target pair, or let the model auto-detect the source
- **Batch translation** — multiple text segments are sent in a single API call for efficiency (configurable batch size)
- **Formatting preservation** — font style, size, color, bold/italic, and other formatting are retained
- **Table support** — text inside table cells is translated
- **Group shape support** — text inside nested grouped shapes is handled
- **Incremental processing** — already-translated files are skipped on re-run
- **Idempotent output** — translated files get `_ru` suffix, originals are untouched

## Requirements

- **Java 11+**
- **sbt 1.10+**
- **DeepSeek API key** — get one at [platform.deepseek.com](https://platform.deepseek.com)

## Project Structure

```
├── build.sbt
├── README.md
├── .gitignore
├── src/
│   └── main/
│       ├── resources/
│       │   ├── application.conf        ← configuration (key, paths, language, model)
│       │   ├── logback.xml
│       │   ├── source/                  ← put .pptx files here
│       │   │   └── .gitkeep
│       │   └── output/                  ← translated files appear here
│       │       └── .gitkeep
│       └── scala/
│           ├── Main.scala               ← entry point
│           ├── config/Config.scala      ← reads application.conf + env vars
│           ├── model/TextElement.scala  ← data model for text positions
│           └── service/
│               ├── PptxService.scala    ← Apache POI read/write
│               └── TranslationService.scala ← DeepSeek API client
└── project/
    ├── build.properties
    └── plugins.sbt
```

## Quick Start

### 1. Set the API key

**Option A — environment variable** (recommended):

```bash
export DEEPSEEK_API_KEY="sk-your-key-here"
```

**Option B — `application.conf`**:

Edit `src/main/resources/application.conf` and uncomment the key line:

```hocon
deepseek {
  api-key = "sk-your-key-here"
  ...
}
```

> If you put a real key in `application.conf`, uncomment `application.conf` in `.gitignore` first to prevent accidental commits.

### 2. Place source files

```bash
cp /path/to/your/file.pptx src/main/resources/source/
```

### 3. Run

```bash
sbt run
```

### 4. Find results

Translated files appear in `src/main/resources/output/` with `_ru` suffix, e.g.:

```
для Кирг Актуальні зміни в МСА.pptx → для Кирг Актуальні зміни в МСА_ru.pptx
```

## Configuration

All settings can be defined in `src/main/resources/application.conf` or overridden via environment variables:

| Setting | `application.conf` path | Env var | Default |
|---|---|---|---|
| API key | `deepseek.api-key` | `DEEPSEEK_API_KEY` | *(required)* |
| API URL | `deepseek.api-url` | `DEEPSEEK_API_URL` | `https://api.deepseek.com/v1/chat/completions` |
| Model | `deepseek.model` | `DEEPSEEK_MODEL` | `deepseek-chat` |
| Source directory | `app.source-dir` | `SOURCE_DIR` | `src/main/resources/source` |
| Output directory | `app.output-dir` | `OUTPUT_DIR` | `src/main/resources/output` |
| Batch size | `app.max-batch-size` | `MAX_BATCH_SIZE` | `10` |
| Source language | `app.source-lang` | `SOURCE_LANG` | `auto` |
| Target language | `app.target-lang` | `TARGET_LANG` | `Russian` |

Resolution order: **`application.conf` → environment variable → hardcoded default**.

### Language direction

The `source-lang` and `target-lang` control translation direction:

| `source-lang` | `target-lang` | Behaviour |
|---|---|---|
| `auto` (or empty) | `Russian` | Auto-detect source → Russian |
| `Ukrainian` | `Russian` | Ukrainian → Russian |
| `English` | `Ukrainian` | English → Ukrainian |
| `German` | `English` | German → English |

Language names are passed to the model in the system prompt. Use names the model understands (e.g. `Ukrainian`, `Russian`, `English`, `German`, `French`, `Chinese`).

### Batch size

The translation API processes multiple text segments in one call. Default is 10 texts per batch. If you encounter token limit errors, reduce this value. For longer texts, consider reducing to 3–5.

## Building a fat JAR

```bash
sbt assembly
java -jar target/scala-3.6.4/pptx-translator-assembly-1.0.0.jar
```

When running from a JAR, ensure the source/output directories exist on the filesystem and are configured via environment variables or `application.conf` next to the JAR.

## How It Works

1. **Extract** — `PptxService` opens the `.pptx` with Apache POI and walks every slide → shape → paragraph → text run, recording each text's position
2. **Batch** — texts are grouped into batches (default 10)
3. **Translate** — each batch is sent to DeepSeek as a numbered list with a system prompt for the configured language direction. The response is parsed back into individual translations
4. **Write** — `PptxService` opens the original file again and replaces each text run using its position as a lookup key, then saves to the output folder with `_ru` suffix

## Error Handling

- **Batch failures** — if a batch returns a mismatched count of translations, each text in that batch is retried individually
- **Unreachable API** — the app prints the error and continues with the next batch/file
- **Missing API key** — the app exits immediately with a clear message
- **Corrupt pptx** — the file is skipped and an error is printed

## Environment variables reference

```bash
export DEEPSEEK_API_KEY="sk-..."     # required
export DEEPSEEK_MODEL="deepseek-chat"
export DEEPSEEK_API_URL="https://..."
export SOURCE_DIR="/absolute/path"
export OUTPUT_DIR="/absolute/path"
export MAX_BATCH_SIZE="5"
export SOURCE_LANG="Ukrainian"       # "auto" or empty = auto-detect
export TARGET_LANG="Russian"
```
